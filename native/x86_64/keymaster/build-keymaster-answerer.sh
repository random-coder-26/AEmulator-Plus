#!/bin/sh
# Builds aemu-keymaster-answerer (x86-64 bionic, for the guest) with zig, the same method as ../health/build-health.sh.
#   sh build-keymaster-answerer.sh <fw lib dir> [aosp dir] [out dir]
#   <fw lib dir>  holds the firmware's own libkeymaster_portable.so, libkeymaster_messages.so, libpuresoftkeymasterdevice.so
#   [aosp dir]    needs libcxx/ libcxxabi/ bionic/ (../audio/fetch-aosp.sh) plus, fetched here if missing:
#                 km/ (system/keymaster) and libhardware/ at android12L-release, the firmware's release.
set -eu
H=$(cd "$(dirname "$0")" && pwd)
FW=$(cd "${1:?firmware lib dir}" && pwd)
A=${2:-$H/../audio/aosp11}; A=$(cd "$A" && pwd)
OUT=${3:-$H/../../../app/src/main/assets/engines/n64-30/x86_64}; mkdir -p "$OUT"; OUT=$(cd "$OUT" && pwd)
ZIG=${ZIG:-zig}; command -v "$ZIG" >/dev/null 2>&1 || ZIG="python3 -m ziglang"
[ -d "$A/km/include/keymaster" ] || GIT_TERMINAL_PROMPT=0 git clone -q --depth 1 -b android12L-release https://github.com/aosp-mirror-neo/platform_system_keymaster "$A/km"
[ -d "$A/libhardware/include" ] || GIT_TERMINAL_PROMPT=0 git clone -q --depth 1 -b android12L-release https://github.com/aosp-mirror/platform_hardware_libhardware "$A/libhardware"
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
TG="-target x86_64-linux-android"
ZL=$($ZIG env | sed -n 's/.*\.lib_dir = "\(.*\)".*/\1/p; s/.*"lib_dir": *"\([^"]*\)".*/\1/p')
mkdir -p "$T/clang-inc" "$T/cfg"; cp -r "$ZL"/include/* "$T/clang-inc/"; rm -f "$T/clang-inc/stdint.h" "$T/clang-inc/inttypes.h" "$T/clang-inc/stdatomic.h"
printf '#ifndef _LIBCPP_CONFIG_SITE\n#define _LIBCPP_CONFIG_SITE\n#define _LIBCPP_HAS_THREAD_API_PTHREAD\n#endif\n' > "$T/cfg/__config_site"
INC="-nostdinc -nostdinc++ -isystem $T/cfg -isystem $A/libcxx/include -isystem $A/libcxxabi/include -isystem $T/clang-inc
 -I$A/km/include -I$A/libhardware/include
 -isystem $A/bionic/libc/include -isystem $A/bionic/libc/kernel/uapi -isystem $A/bionic/libc/kernel/uapi/asm-x86 -isystem $A/bionic/libc/kernel/android/uapi"
CXX="$ZIG c++ $TG -std=c++17 -fPIC -fno-exceptions -fno-rtti -fno-stack-protector -Os -w -D__ANDROID_API__=30 -D_LIBCPP_HAS_THREAD_API_PTHREAD -DKEYMASTER_NAME_TAGS"
CC="$ZIG cc $TG -Os -fPIC -w -fno-stack-protector -fno-unwind-tables -fno-asynchronous-unwind-tables"
LD="$ZIG c++ $TG -nostdlib"
LDF="-Wl,--build-id=none -Wl,--hash-style=both -Wl,-z,norelro -Wl,-z,max-page-size=4096 -Wl,--no-as-needed -Wl,-s -Wl,--allow-shlib-undefined"
$CXX $INC -c "$H/aemu-keymaster-answerer.cpp" -o $T/ans.o
$CC -c "$H/../gralloc2/crt_begin.c" -o $T/crt_begin.o; $CC -c "$H/../gralloc2/crt_end.c" -o $T/crt_end.o
mkdir -p $T/so; : > $T/e.c
for L in libdl.so libm.so liblog.so libc++.so; do $CC -nostdlib -shared -Wl,-soname,$L -Wl,--build-id=none -o $T/so/$L $T/e.c; done
S=$T/so
# imports that are neither the firmware's keymaster libs' nor the stand-ins' go into a stand-in libc.so (soname only matters)
for L in libkeymaster_portable.so libkeymaster_messages.so libpuresoftkeymasterdevice.so; do nm -D --defined-only "$FW/$L" | awk '{print $3}'; done | sort -u > $T/have
nm -u $T/ans.o $T/crt_begin.o | awk '$1=="U"{print $2}' | sort -u | grep -v '^main$' | grep -vxFf $T/have > $T/ans.undef
while read -r s; do printf 'void %s(void){}\n' "$s"; done < $T/ans.undef > $T/c2.c
$CC -nostdlib -shared -Wl,-soname,libc.so -Wl,--build-id=none -o $T/so/libc_svc.so $T/c2.c
F="$OUT/vendor#bin#hw#aemu-keymaster-answerer"
$LD -pie -Wl,-dynamic-linker=/system/bin/linker64 $LDF $T/crt_begin.o $T/ans.o $T/crt_end.o \
    "$FW/libkeymaster_portable.so" "$FW/libkeymaster_messages.so" "$FW/libpuresoftkeymasterdevice.so" \
    $S/liblog.so $S/libc++.so $S/libm.so $S/libdl.so $S/libc_svc.so -o "$F"
readelf -lW "$F" | grep -q ' INTERP ' || { echo "error: $F has no PT_INTERP" >&2; exit 1; }
# every keymaster symbol it imports must be one the firmware's libraries export (a header/library release mismatch shows here)
nm -D --undefined-only "$F" | awk '{print $2}' | grep '^_ZN9keymaster' | grep -vxFf $T/have && { echo "error: unresolved keymaster symbols above" >&2; exit 1; } || true
ls -l "$F"
