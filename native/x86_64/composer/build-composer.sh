#!/bin/sh
# The HIDL graphics composer 2.1 HAL for an x86-64 Android 10/11+ firmware without a composer of its own (a system-only image:
# /vendor is empty, SurfaceFlinger aborts with "failed to get hwcomposer service"), built with zig against AOSP android11-release:
#   sh build-composer.sh [aosp dir] [out dir]      (aosp dir: ../audio/fetch-aosp.sh, run first; out: assets/engines/n64-30/x86_64)
#   vendor#bin#hw#android.hardware.graphics.composer@2.1-service       ../../composer32/composer_service.cpp (no AOSP headers, own _start)
#   vendor#lib64#hw#android.hardware.graphics.composer@2.1-impl.so     composer_impl.cpp (AOSP's passthrough without the adapters)
#   vendor#lib64#android.hardware.graphics.composer@2.1-resources.so   hardware/interfaces graphics/composer/2.1/utils/resources
# The HWC2 device it drives is ../build-hwcomposer.sh's hwcomposer.default.so (common-x86_64). Same method as ../audio/build-audio.sh.
set -eu
H=$(cd "$(dirname "$0")" && pwd)
A=${1:-$H/../audio/aosp11}; A=$(cd "$A" && pwd)
OUT=${2:-$H/../../../app/src/main/assets/engines/n64-30/x86_64}
mkdir -p "$OUT"; OUT=$(cd "$OUT" && pwd)
ZIG=${ZIG:-zig}; command -v "$ZIG" >/dev/null 2>&1 || ZIG="python3 -m ziglang"
[ -f "$A/gen/android/hardware/graphics/mapper/4.0/IMapper.h" ] || { echo "run ../audio/fetch-aosp.sh first" >&2; exit 1; }
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
TG="-target x86_64-linux-android"
HI=$A/hardware_interfaces/graphics/composer/2.1
ZL=$($ZIG env | sed -n 's/.*\.lib_dir = "\(.*\)".*/\1/p; s/.*"lib_dir": *"\([^"]*\)".*/\1/p')
mkdir -p "$T/clang-inc" "$T/cfg"; cp -r "$ZL"/include/* "$T/clang-inc/"; rm -f "$T/clang-inc/stdint.h" "$T/clang-inc/inttypes.h" "$T/clang-inc/stdatomic.h"
printf '#ifndef _LIBCPP_CONFIG_SITE\n#define _LIBCPP_CONFIG_SITE\n#define _LIBCPP_HAS_THREAD_API_PTHREAD\n#endif\n' > "$T/cfg/__config_site"
LB=$A/system_libbase/include; [ -d "$LB" ] || LB=$A/system_core/base/include
INC="-nostdinc -nostdinc++ -isystem $T/cfg -isystem $A/libcxx/include -isystem $A/libcxxabi/include -isystem $T/clang-inc
 -I$A/gen -I$HI/utils/hal/include -I$HI/utils/passthrough/include -I$HI/utils/resources/include -I$HI/utils/command-buffer/include
 -I$A/system_libhidl/base/include -I$A/system_libhidl/transport/include
 -I$A/system_core/libutils/include -I$A/system_core/libcutils/include -I$A/system_core/libsync/include -I$A/system_core/libsystem/include
 -I$A/system_core/include -I$LB -I$A/system_logging/liblog/include -I$A/hardware_libhardware/include
 -I$A/system_libhwbinder/include -I$A/system_libfmq/base -I$A/system_libfmq/include
 -isystem $A/bionic/libc/include -isystem $A/bionic/libc/kernel/uapi -isystem $A/bionic/libc/kernel/uapi/asm-x86 -isystem $A/bionic/libc/kernel/android/uapi"
CXX="$ZIG c++ $TG -std=c++17 -fno-sized-deallocation -fPIC -fno-exceptions -fno-rtti -fno-stack-protector -Os -w -D__ANDROID_API__=30 -D_LIBCPP_HAS_THREAD_API_PTHREAD"
CC="$ZIG cc $TG -Os -fPIC -w -fno-stack-protector -fno-unwind-tables -fno-asynchronous-unwind-tables"
LD="$ZIG c++ $TG -nostdlib"
LDF="-Wl,--build-id=none -Wl,--hash-style=both -Wl,-z,norelro -Wl,-z,max-page-size=4096 -Wl,--no-as-needed -Wl,-s"

$CXX $INC -DLOG_TAG='"ComposerResources"' -c $HI/utils/resources/ComposerResources.cpp -o $T/res.o
$CXX $INC -DLOG_TAG='"composer@2.1-impl"' -c $H/composer_impl.cpp -o $T/impl.o
$CC -c "$H/../gralloc2/dso_handle.c" -o $T/dso_handle.o

# ---- stand-in libraries: the soname and nothing else (DT_NEEDED entries of the AOSP build, the guest's libraries serve them) ----
mkdir -p $T/so; : > $T/e.c
for L in libdl.so libm.so liblog.so libcutils.so libhardware.so libutils.so libbase.so libhidlbase.so libc++.so libfmq.so libsync.so libc.so \
         android.hardware.graphics.composer@2.1.so android.hardware.graphics.mapper@2.0.so android.hardware.graphics.mapper@3.0.so \
         android.hardware.graphics.mapper@4.0.so android.hardware.graphics.composer@2.1-resources.so; do
    $CC -nostdlib -shared -Wl,-soname,$L -Wl,--build-id=none -o $T/so/$L $T/e.c
done
S=$T/so
BASE="$S/libc++.so $S/libm.so $S/libdl.so $S/libc.so"
$LD -shared -Wl,-soname,android.hardware.graphics.composer@2.1-resources.so $LDF $T/res.o $T/dso_handle.o \
    $S/android.hardware.graphics.composer@2.1.so $S/android.hardware.graphics.mapper@2.0.so $S/android.hardware.graphics.mapper@3.0.so $S/android.hardware.graphics.mapper@4.0.so \
    $S/libcutils.so $S/libhardware.so $S/libhidlbase.so $S/liblog.so $S/libutils.so $BASE \
    -o "$OUT/vendor#lib64#android.hardware.graphics.composer@2.1-resources.so"
# libc++'s condition_variable::wait_for (ComposerImpl::waitForClientDestroyedLocked) does chrono arithmetic on a long double, which the target
# compiles to __multf3 / __lttf2 / __subtf3 / __floatditf. AOSP links compiler-rt's builtins into every library; with -nostdlib nothing does,
# the symbols stay undefined and the guest's linker refuses the library ("cannot locate symbol __multf3"). Zig's compiler-rt is linked in
# statically, and a version script keeps all of its symbols private to the impl (memcpy & co. included: libc's stay the ones other libs see).
$ZIG build-obj "$ZL/compiler_rt.zig" -target x86_64-linux-android -OReleaseSmall -fPIC -femit-bin="$T/crt.o"
{ echo '{ local:'; nm --defined-only -g "$T/crt.o" | awk '{print $3 ";"}' | sort -u; echo '  __*; };'; } > "$T/crt.map"
$LD -shared -Wl,-soname,android.hardware.graphics.composer@2.1-impl.so $LDF -Wl,--gc-sections -Wl,--version-script="$T/crt.map" $T/impl.o $T/dso_handle.o $T/crt.o \
    $S/android.hardware.graphics.composer@2.1.so $S/android.hardware.graphics.composer@2.1-resources.so $S/libfmq.so \
    $S/libhardware.so $S/libhidlbase.so $S/liblog.so $S/libcutils.so $S/libsync.so $S/libutils.so $BASE \
    -o "$OUT/vendor#lib64#hw#android.hardware.graphics.composer@2.1-impl.so"

# ---- the service: composer32/composer_service.cpp, built as native/composer32/build-zig.sh does it (-nostdlib, own _start) ----
for n in c dl log; do
    case $n in
      c)   printf 'void __libc_init(void) {} void abort(void) { for (;;) {} } void _exit(void) { for (;;) {} }\n' > $T/${n}_stub.c ;;
      dl)  printf 'void *dlopen(void) { return 0; } void *dlsym(void) { return 0; } char *dlerror(void) { return 0; }\n' > $T/${n}_stub.c ;;
      log) printf 'int __android_log_print(void) { return 0; }\n' > $T/${n}_stub.c ;;
    esac
    $CC -nostdlib -shared -Wl,-soname,lib$n.so -Wl,--build-id=none -o $T/stub_lib$n.so $T/${n}_stub.c
done
$ZIG c++ $TG -DAEMU_NO_BIONIC_HEADERS -Os -fPIE -ffreestanding -nostdinc++ -nostdlib -fno-exceptions -fno-rtti -fno-stack-protector \
    -fno-unwind-tables -fno-asynchronous-unwind-tables -Wall -pie -Wl,--dynamic-linker=/system/bin/linker64 -Wl,--build-id=none \
    -Wl,--hash-style=both -Wl,-z,now -Wl,-z,relro -rdynamic -fvisibility=hidden -Wl,--gc-sections -Wl,-e,_start -Wl,-z,max-page-size=4096 \
    -o "$OUT/vendor#bin#hw#android.hardware.graphics.composer@2.1-service" "$H/../../composer32/composer_service.cpp" -x none \
    $T/stub_libdl.so $T/stub_liblog.so $T/stub_libc.so

# a service with no PT_INTERP starts with no dynamic linker (nothing relocated, DT_NEEDED not loaded) and dies with SIGSEGV in its first
# PLT call, exit 139, restarted for ever (rip 0x2486 / 0x1936 in hwcomposer-2-1.log / gralloc-2-0.log); tools/patch_x86_service_interp.py adds it
for f in "$OUT/vendor#bin#hw#android.hardware.graphics.composer@2.1-service"; do
    readelf -lW "$f" | grep -q ' INTERP ' || { echo "error: $f has no PT_INTERP" >&2; exit 1; }
done
# the guest linker resolves an import only from the DT_NEEDED libraries: "cannot locate symbol sync_wait referenced by composer@2.1-impl.so"
# (composer-2-1 never registered, SurfaceFlinger waited for ever) came from an impl that imported sync_wait without NEEDED libsync.so
if nm -D "$OUT/vendor#lib64#hw#android.hardware.graphics.composer@2.1-impl.so" | grep -q " U sync_wait$"; then
    readelf -dW "$OUT/vendor#lib64#hw#android.hardware.graphics.composer@2.1-impl.so" | grep -q "NEEDED.*\[libsync.so\]" || { echo "error: composer impl imports sync_wait without NEEDED libsync.so" >&2; exit 1; }
fi
# no compiler-rt import may stay open either (same failure mode as sync_wait)
if nm -D "$OUT"/vendor#lib64#hw#android.hardware.graphics.composer@2.1-impl.so | grep -Eq ' U __[a-z]*(tf[0-9]|ditf|tf[a-z]*)$'; then echo "error: composer impl imports a compiler-rt builtin" >&2; exit 1; fi
for f in "$OUT"/vendor#lib64#hw#android.hardware.graphics.composer@2.1-impl.so "$OUT"/vendor#lib64#android.hardware.graphics.composer@2.1-resources.so "$OUT"/vendor#bin#hw#android.hardware.graphics.composer@2.1-service; do
    if nm -D "$f" | grep -Eq ' U (__stack_chk_fail|__errno_location|__memcpy_chk|__sprintf_chk|__snprintf_chk|__fprintf_chk)$'; then echo "error: $f imports a glibc/musl-only symbol" >&2; exit 1; fi
done
ls -l "$OUT"/vendor#*composer*
