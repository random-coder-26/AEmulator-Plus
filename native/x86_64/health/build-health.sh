#!/bin/sh
# The stand-in android.hardware.health@2.0 service (service.cpp) for an x86-64 Android 10/11 firmware that has no health HAL of its
# own (a system-only image: /vendor is empty), built with zig against AOSP android11-release (run ../audio/fetch-aosp.sh first;
# this script generates the health headers into the same tree with the hidl-gen that script built):
#   sh build-health.sh [aosp dir] [out dir]       (out: assets/engines/n64-30/x86_64)
# Same method as ../audio/build-audio.sh: bionic's own headers (-nostdinc), stand-in libraries that carry only the soname, so the
# file gets the DT_NEEDED entries of the AOSP build and the guest's real libraries serve it.
set -eu
H=$(cd "$(dirname "$0")" && pwd)
A=${1:-$H/../audio/aosp11}; A=$(cd "$A" && pwd)
OUT=${2:-$H/../../../app/src/main/assets/engines/n64-30/x86_64}
mkdir -p "$OUT"; OUT=$(cd "$OUT" && pwd)
ZIG=${ZIG:-zig}; command -v "$ZIG" >/dev/null 2>&1 || ZIG="python3 -m ziglang"
[ -x "$A/hb/hidl-gen" ] || { echo "run ../audio/fetch-aosp.sh first (no hidl-gen in $A/hb)" >&2; exit 1; }
if [ ! -f "$A/gen/android/hardware/health/2.0/IHealth.h" ]; then
    R="-r android.hardware:$A/hardware_interfaces -r android.hidl:$A/system_libhidl/transport"
    for p in android.hardware.health@1.0 android.hardware.health@2.0; do "$A/hb/hidl-gen" -o "$A/gen" -L c++-headers $R $p; done
fi
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
TG="-target x86_64-linux-android"
ZL=$($ZIG env | sed -n 's/.*\.lib_dir = "\(.*\)".*/\1/p; s/.*"lib_dir": *"\([^"]*\)".*/\1/p')
mkdir -p "$T/clang-inc" "$T/cfg"; cp -r "$ZL"/include/* "$T/clang-inc/"; rm -f "$T/clang-inc/stdint.h" "$T/clang-inc/inttypes.h" "$T/clang-inc/stdatomic.h"
printf '#ifndef _LIBCPP_CONFIG_SITE\n#define _LIBCPP_CONFIG_SITE\n#define _LIBCPP_HAS_THREAD_API_PTHREAD\n#endif\n' > "$T/cfg/__config_site"
INC="-I$H -nostdinc -nostdinc++ -isystem $T/cfg -isystem $A/libcxx/include -isystem $A/libcxxabi/include -isystem $T/clang-inc
 -I$A/gen -I$A/system_libhidl/base/include -I$A/system_libhidl/transport/include -I$A/system_libhidl/transport/memory/include
 -I$A/system_core/libutils/include -I$A/system_core/libcutils/include -I$A/system_core/libsystem/include -I$A/system_core/include
 -I$A/system_core/base/include -I$A/system_logging/liblog/include -I$A/system_libhwbinder/include
 -isystem $A/bionic/libc/include -isystem $A/bionic/libc/kernel/uapi -isystem $A/bionic/libc/kernel/uapi/asm-x86 -isystem $A/bionic/libc/kernel/android/uapi"
CXX="$ZIG c++ $TG -std=c++17 -fPIC -fno-exceptions -fno-rtti -fno-stack-protector -Os -w -D__ANDROID_API__=30 -D_LIBCPP_HAS_THREAD_API_PTHREAD -DANDROID_VNDK -D__ANDROID_VNDK__"
CC="$ZIG cc $TG -Os -fPIC -w -fno-stack-protector -fno-unwind-tables -fno-asynchronous-unwind-tables"
LD="$ZIG c++ $TG -nostdlib"
LDF="-Wl,--build-id=none -Wl,--hash-style=both -Wl,-z,norelro -Wl,-z,max-page-size=4096 -Wl,--no-as-needed -Wl,-s"
$CXX $INC -c "$H/service.cpp" -o $T/svc.o
$CC -c "$H/../gralloc2/crt_begin.c" -o $T/crt_begin.o; $CC -c "$H/../gralloc2/crt_end.c" -o $T/crt_end.o
mkdir -p $T/so; : > $T/e.c
for L in libdl.so libm.so liblog.so libutils.so libc++.so libhidlbase.so android.hardware.health@1.0.so android.hardware.health@2.0.so; do
    $CC -nostdlib -shared -Wl,-soname,$L -Wl,--build-id=none -o $T/so/$L $T/e.c
done
S=$T/so
# every symbol the object imports and no stand-in above is meant to carry goes into a stand-in libc.so (soname only matters)
nm -u $T/svc.o $T/crt_begin.o | awk '$1=="U"{print $2}' | sort -u | grep -v '^main$' > $T/svc.undef
while read -r s; do printf 'void %s(void){}\n' "$s"; done < $T/svc.undef > $T/c2.c
$CC -nostdlib -shared -Wl,-soname,libc.so -Wl,--build-id=none -o $T/so/libc_svc.so $T/c2.c
$LD -pie -Wl,-dynamic-linker=/system/bin/linker64 $LDF $T/crt_begin.o $T/svc.o $T/crt_end.o \
    $S/android.hardware.health@2.0.so $S/android.hardware.health@1.0.so $S/libhidlbase.so $S/liblog.so $S/libutils.so $S/libc++.so $S/libm.so $S/libdl.so $S/libc_svc.so \
    -o "$OUT/vendor#bin#hw#android.hardware.health@2.0-service"
# a service with no PT_INTERP starts with no dynamic linker and dies in its first PLT call (see ../audio/build-audio.sh)
f="$OUT/vendor#bin#hw#android.hardware.health@2.0-service"
readelf -lW "$f" | grep -q ' INTERP ' || { echo "error: $f has no PT_INTERP" >&2; exit 1; }
if nm -D "$f" | grep -Eq ' U (__stack_chk_fail|__errno_location|__memcpy_chk|__sprintf_chk|__snprintf_chk|__fprintf_chk)$'; then echo "error: $f imports a glibc/musl-only symbol" >&2; exit 1; fi
ls -l "$f"
