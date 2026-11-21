#!/bin/sh
# Builds aemts.so (aarch64) with zig cc. It links against a stub libc.so so that DT_NEEDED libc.so is recorded (the real bionic
# libc is what resolves pthread_* at run time; the stub is thrown away). Output: the engine's common directory.
set -eu
ZIG=${ZIG:-python3 -m ziglang}
OUT=${1:-../../app/src/main/assets/engines/common/libaemu_tscompat.so}
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
cat > "$TMP/libc_stub.c" <<'STUB'
int pthread_key_create(int *k, void (*d)(void *)) { (void)k; (void)d; return 0; }
void *pthread_getspecific(int k) { (void)k; return 0; }
int pthread_setspecific(int k, const void *v) { (void)k; (void)v; return 0; }
int prctl(int o, ...) { (void)o; return 0; }
STUB
$ZIG cc -target aarch64-linux-musl -shared -nostdlib -fno-stack-protector -fno-builtin -O2 -Wl,-soname,libc.so \
    -Wl,--hash-style=gnu -o "$TMP/libc.so" "$TMP/libc_stub.c"
$ZIG cc -target aarch64-linux-musl -shared -nostdlib -fno-stack-protector -fno-builtin -O2 -Wl,-soname,aemts.so \
    -Wl,--hash-style=gnu -Wl,-z,max-page-size=4096 -Wl,--strip-all -Wl,--no-undefined \
    -o "$OUT" tscompat.c "$TMP/libc.so"
