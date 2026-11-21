#!/bin/sh
# libaemuarc.so: native decompression + 7z for the importer.
#   sh build.sh android [out.so]   arm64-v8a with zig cc (LLVM), no NDK needed; default output
#                                  ../../app/src/main/jniLibs/arm64-v8a/libaemuarc.so
#   sh build.sh host [out.so]      this machine's gcc/clang, for tests/run.sh (needs a JDK for jni.h)
# Same approach as native/hwchal/build-zig.sh: the libc imports are resolved against a stand-in libc.so that is not
# shipped, the library ends up importing nothing but the plain libc functions listed at the end of the build.
set -eu
ARC_DIR=$(cd "$(dirname "$0")" && pwd)
. "$ARC_DIR/sources.sh"
MODE=${1:-android}
case $MODE in
  android) OUT=${2:-$ARC_DIR/../../app/src/main/jniLibs/arm64-v8a/libaemuarc.so} ;;
  host)    OUT=${2:-$ARC_DIR/build/host/libaemuarc.so} ;;
  *) echo "usage: $0 android|host [out.so]" >&2; exit 2 ;;
esac
WORK=$(mktemp -d); trap 'rm -rf "$WORK"' EXIT
OPT="-O3 -DNDEBUG -fPIC -fvisibility=hidden -fno-stack-protector -D_FORTIFY_SOURCE=0 -DBZ_NO_STDIO -w ${EXTRA_CFLAGS:-}"
if [ "$MODE" = android ]; then
  ZIG=${ZIG:-python3 -m ziglang}
  CC="$ZIG cc -target aarch64-linux-musl -fno-unwind-tables -fno-asynchronous-unwind-tables"
  JNI_INC=$ARC_DIR/jni
  CF="$OPT"
else
  CC=${CC:-cc}

  JNI_INC=$ARC_DIR/jni
  CF="$OPT"
fi
cc_group() { # name incdirs defines files...
  name=$1; inc=$2; def=$3; shift 3
  for f in "$@"; do
    o=$WORK/$name-$(echo "$f" | tr / _).o
    $CC $CF $def $inc -c "$f" -o "$o" || exit 1
    echo "$o" >> "$WORK/objs"
  done
}
: > "$WORK/objs"
cd "$ARC_DIR"
SDKI="-I$TP/lzma-sdk"
cc_group zlib "-I$TP/zlib" "-DHAVE_UNISTD_H -DHAVE_STDARG_H" $(for f in $SRC_ZLIB; do echo $TP/zlib/$f; done)
cc_group bz2  "-I$TP/bzip2" "-D_FILE_OFFSET_BITS=64" $(for f in $SRC_BZIP2; do echo $TP/bzip2/$f; done)
cc_group lz4  "-I$TP/lz4" "" $(for f in $SRC_LZ4; do echo $TP/lz4/$f; done)
cc_group zstd "-I$TP/zstd -I$TP/zstd/common -I$TP/zstd/decompress" "-DZSTD_DISABLE_ASM -DZSTD_LEGACY_SUPPORT=0 -DXXH_NAMESPACE=ZSTD_ -DZSTD_STRIP_ERROR_STRINGS" $(for f in $SRC_ZSTD; do echo $TP/zstd/$f; done)
cc_group brotli "-I$TP/brotli/include -I$TP/brotli" "" $(for f in $SRC_BROTLI; do echo $TP/brotli/$f; done)
cc_group sdk  "$SDKI" "-DZ7_ST" $(for f in $SRC_SDK; do echo $TP/lzma-sdk/$f; done)
cc_group arc  "-Iinclude -Isrc -I$TP/zlib -I$TP/bzip2 -I$TP/lz4 -I$TP/zstd -I$TP/brotli/include $SDKI" "-DZ7_ST" $ARC_CORE
if [ -f "$JNI_INC/jni.h" ] || [ "$MODE" = host ]; then
  cc_group jni "-Iinclude -Isrc -I$JNI_INC" "" $ARC_JNI
fi
mkdir -p "$(dirname "$OUT")"
if [ "$MODE" = android ]; then
  # undefined imports -> stand-in libc.so (soname libc.so) so that the link resolves; bionic provides the real ones
  UND=$(for o in $(cat "$WORK/objs"); do $ZIG cc -target aarch64-linux-musl --version >/dev/null 2>&1; llvm_nm=1; done; \
        python3 - "$WORK" <<'PY'
import subprocess, sys, glob
work = sys.argv[1]
defs, und = set(), set()
for o in open(work + "/objs").read().split():
    out = subprocess.run(["python3", "-m", "ziglang", "objdump", "-t", o], capture_output=True, text=True).stdout
    for line in out.splitlines():
        p = line.split()
        if len(p) >= 2 and "*UND*" in line:
            und.add(p[-1])
        elif len(p) >= 5 and p[1] in ("g", "l") and ".text" in line or ".data" in line or ".bss" in line or ".rodata" in line:
            defs.add(p[-1])
for s in sorted(und - defs):
    if s and not s.startswith("."):
        print(s)
PY
  )
  echo "$UND" > "$WORK/und.txt"
  { for s in $UND; do echo "void $s(void) {}"; done; } > "$WORK/stub.c"
  $CC -shared -nostdlib -fPIC -Wl,-soname,libc.so -o "$WORK/libc.so" "$WORK/stub.c" -w
  $CC -shared -nostdlib -Wl,--no-undefined -Wl,--version-script=$ARC_DIR/exports.map -Wl,-z,max-page-size=16384 -Wl,--build-id=none -Wl,-s -Wl,-soname,libaemuarc.so \
      -o "$OUT" $(cat "$WORK/objs") -L"$WORK" -lc
  echo "imports: $(echo $UND | tr '\n' ' ')"
else
  $CC -shared -Wl,--no-undefined ${EXTRA_CFLAGS:-} -o "$OUT" $(cat "$WORK/objs") -lm
fi
ls -l "$OUT"
