#!/bin/sh
# Builds assets/engines/common-x86_64/libc_scudo_stub.so: an empty x86-64 shared library with the soname libc_scudo.so.
#   sh build-scudo-stub.sh [out.so]
# core/ScudoStub.kt puts it in place of an Android 10 x86-64 guest's libc_scudo.so, so mediaextractor and media.swcodec keep
# bionic's malloc instead of Scudo (whose 4 TiB primary region does not fit the phone's 39-bit address space).
# The only symbol is a data marker; no malloc, no imports, no DT_NEEDED. Needs zig (as zig-bionic.sh) or an x86-64 gcc.
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
OUT=${1:-$HERE/../../app/src/main/assets/engines/common-x86_64/libc_scudo_stub.so}
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
cat > "$T/stub.c" <<'C'
/* Marker only: the library exports no allocator, so a process that has it in DT_NEEDED keeps bionic's own malloc. */
int __aemu_scudo_stub = 1;
C
LDF="-Wl,-soname,libc_scudo.so -Wl,-z,norelro -Wl,-z,noexecstack -Wl,--build-id=none -Wl,--hash-style=both -Wl,-z,max-page-size=4096 -Wl,-s"
CF="-Os -fPIC -fno-stack-protector -fno-asynchronous-unwind-tables -fno-unwind-tables"
mkdir -p "$(dirname "$OUT")"
if command -v x86_64-linux-gnu-gcc >/dev/null 2>&1 || { [ "$(uname -m)" = x86_64 ] && command -v gcc >/dev/null 2>&1; }; then
  CC=$(command -v x86_64-linux-gnu-gcc || command -v gcc)
  $CC $CF -shared -nostdlib $LDF -o "$OUT" "$T/stub.c"
else
  ZIG=${ZIG:-zig}; command -v "$ZIG" >/dev/null 2>&1 || ZIG="python3 -m ziglang"
  $ZIG cc --target=x86_64-linux-musl $CF -shared -nostdlib $LDF -o "$OUT" "$T/stub.c"
fi
ls -l "$OUT"
