#!/bin/sh
# aegr.so: the libc stand-in of engines/glibc-x86/gralloc.default.so (see aegr_shim.c): zig cc (LLVM), x86-linux-musl target but
# freestanding (no libc is linked in), same recipe as the other Revue stand-ins. The glibc imports (__errno_location,
# __cxa_atexit, malloc, free) are resolved against a throw-away stub named libc.so.6 so that the library carries DT_NEEDED libc.so.6 (always
# present on a glibc firmware); the stub is not shipped. DT_HASH + DT_GNU_HASH (glibc 2.7 reads either).
#   sh build-aegr.sh [output]     (default: ../../app/src/main/assets/engines/glibc-x86/aegr.so)
set -eu
ZIG=${ZIG:-python3 -m ziglang}
HERE=$(dirname "$0")
OUT=${1:-$HERE/../../app/src/main/assets/engines/glibc-x86/aegr.so}
STUB=$(mktemp -d); trap 'rm -rf "$STUB"' EXIT
cat > "$STUB/libc.c" <<'EOT'
int *__errno_location(void) { static int e; return &e; }
int __cxa_atexit(void (*f)(void *), void *a, void *d) { (void)f; (void)a; (void)d; return 0; }
void *malloc(unsigned int n) { (void)n; return 0; }
void free(void *p) { (void)p; }
EOT
T="--target=x86-linux-musl -fPIC -shared -nostdlib -ffreestanding -fno-builtin"
$ZIG cc $T -Os -Wl,-soname,libc.so.6 -o "$STUB/libc.so.6" "$STUB/libc.c"
mkdir -p "$(dirname "$OUT")"
$ZIG cc $T -Os -fno-stack-protector -fno-asynchronous-unwind-tables -fno-unwind-tables \
  -Wl,-z,norelro -Wl,-z,noexecstack -Wl,--build-id=none -Wl,--hash-style=both -Wl,--no-undefined -Wl,-s -Wl,-soname,aegr.so \
  -L"$STUB" -o "$OUT" "$HERE/aegr_shim.c" -l:libc.so.6
if command -v readelf >/dev/null 2>&1; then
  BAD=$(readelf --dyn-syms "$OUT" | awk '$7 == "UND" && $8 != "" {print $8}' | grep -v -x -E '__errno_location|__cxa_atexit|malloc|free' || true)
  [ -z "$BAD" ] || { echo "error: $OUT imports $BAD, which glibc 2.7 may not have" >&2; exit 1; }
fi
ls -l "$OUT"
