#!/bin/sh
# The HIDL audio core HAL (android.hardware.audio@2.0-impl.so) for an x86-64 Android 10/11 firmware without an audio HAL of its
# own (a system-only image: /vendor is empty), built with zig against AOSP android11-release (run fetch-aosp.sh first):
#   sh build-audio.sh [aosp dir] [out dir]       (out: assets/engines/n64-30/x86_64)
# Same method as ../gralloc2/build-gralloc2.sh: bionic's own headers (-nostdinc), stand-in libraries that carry only the
# soname, so the file gets the DT_NEEDED entries of the AOSP build and the guest's real libraries serve it.
set -eu
H=$(cd "$(dirname "$0")" && pwd)
A=${1:-$H/aosp11}; A=$(cd "$A" && pwd)
OUT=${2:-$H/../../../app/src/main/assets/engines/n64-30/x86_64}
mkdir -p "$OUT"; OUT=$(cd "$OUT" && pwd)
ZIG=${ZIG:-zig}; command -v "$ZIG" >/dev/null 2>&1 || ZIG="python3 -m ziglang"
[ -f "$A/gen/android/hardware/audio/2.0/IDevicesFactory.h" ] || { echo "run fetch-aosp.sh first" >&2; exit 1; }
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
TG="-target x86_64-linux-android"
AU=$A/hardware_interfaces/audio
ZL=$($ZIG env | sed -n 's/.*\.lib_dir = "\(.*\)".*/\1/p; s/.*"lib_dir": *"\([^"]*\)".*/\1/p')
mkdir -p "$T/clang-inc" "$T/cfg"; cp -r "$ZL"/include/* "$T/clang-inc/"; rm -f "$T/clang-inc/stdint.h" "$T/clang-inc/inttypes.h" "$T/clang-inc/stdatomic.h"
printf '#ifndef _LIBCPP_CONFIG_SITE\n#define _LIBCPP_CONFIG_SITE\n#define _LIBCPP_HAS_THREAD_API_PTHREAD\n#endif\n' > "$T/cfg/__config_site"
INC="-nostdinc -nostdinc++ -isystem $T/cfg -isystem $A/libcxx/include -isystem $A/libcxxabi/include -isystem $T/clang-inc
 -I$A/gen -I$AU/common/all-versions/util/include -I$AU/common/all-versions/default -I$AU/common/all-versions/default/include
 -I$AU/core/all-versions/default/include -I$AU/effect/all-versions/default
 -I$A/frameworks_av/media/libmedia/include -I$A/frameworks_av/media/libaudiofoundation/include -I$A/frameworks_av/media/libaudioclient/include
 -I$A/frameworks_av/media/libmediahelper/include -I$A/frameworks_av/media/libeffects/factory -I$A/frameworks_av/include
 -I$A/system_media/audio/include -I$A/system_media/audio_utils/include -I$A/system_media/audio_effects/include
 -I$A/system_libhidl/base/include -I$A/system_libhidl/transport/include -I$A/system_libhidl/transport/memory/include -I$A/system_libhidl/libhidlmemory/include
 -I$A/system_core/libutils/include -I$A/system_core/libcutils/include -I$A/system_core/libsync/include -I$A/system_core/libsystem/include
 -I$A/system_core/include -I$A/system_core/base/include -I$A/system_logging/liblog/include -I$A/hardware_libhardware/include
 -I$A/system_libhwbinder/include -I$A/system_libfmq/base -I$A/system_libfmq/include
 -I$A/frameworks_native/libs/binder/include -I$A/frameworks_native/libs/nativebase/include -I$A/frameworks_native/libs/arect/include
 -isystem $A/bionic/libc/include -isystem $A/bionic/libc/kernel/uapi -isystem $A/bionic/libc/kernel/uapi/asm-x86 -isystem $A/bionic/libc/kernel/android/uapi"
CXX="$ZIG c++ $TG -std=c++17 -fPIC -fno-exceptions -fno-rtti -fno-stack-protector -Os -w -D__ANDROID_API__=30 -D_LIBCPP_HAS_THREAD_API_PTHREAD"
CXX="$CXX -DANDROID_VNDK -D__ANDROID_VNDK__ -DMAJOR_VERSION=2 -DMINOR_VERSION=0 -include common/all-versions/VersionMacro.h"
CC="$ZIG cc $TG -Os -fPIC -w -fno-stack-protector -fno-unwind-tables -fno-asynchronous-unwind-tables"
LD="$ZIG c++ $TG -nostdlib"
LDF="-Wl,--build-id=none -Wl,--hash-style=both -Wl,-z,norelro -Wl,-z,max-page-size=4096 -Wl,--no-as-needed -Wl,-s"
mkdir -p "$T/o"
i=0
for f in Conversions Device DevicesFactory ParametersUtil PrimaryDevice Stream StreamIn StreamOut; do
    i=$((i+1))
    $CXX $INC -DLOG_TAG='"AudioHAL"' -c $AU/core/all-versions/default/$f.cpp -o $T/o/$f.o &
    [ $((i % 4)) -eq 0 ] && wait
done; wait
for f in "$T"/o/*.o; do :; done
$CXX $INC -DLOG_TAG='"AudioHAL"' -c $A/frameworks_av/media/libaudiofoundation/AudioContainers.cpp -o $T/o/AudioContainers.o
[ "$(ls $T/o | wc -l)" -eq 9 ] || { echo "compile failed" >&2; exit 1; }
$CC -c "$H/../gralloc2/dso_handle.c" -o $T/dso_handle.o
mkdir -p $T/so; : > $T/e.c
for L in libdl.so libm.so liblog.so libcutils.so libhardware.so libutils.so libbase.so libhidlbase.so libc++.so libfmq.so libmedia_helper.so \
         android.hardware.audio@2.0.so android.hardware.audio.common@2.0.so android.hardware.audio.common@2.0-util.so android.hardware.audio.common-util.so libc.so; do
    $CC -nostdlib -shared -Wl,-soname,$L -Wl,--build-id=none -o $T/so/$L $T/e.c
done
S=$T/so

# ---- vendor copies of what AOSP builds with vendor_available: the firmware's /system copies are not visible to /vendor code ----
# (EffectMap is one singleton shared by the core and the effect impl, hence a library of its own, not static in each)
$CXX $INC -DLOG_TAG='"AudioHAL"' -c $AU/common/all-versions/default/EffectMap.cpp -o $T/cm_effectmap.o
$CXX $INC -DLOG_TAG='"AudioHAL"' -c $AU/common/all-versions/default/HidlUtils.cpp -o $T/cm_hidlutils.o
$LD -shared -Wl,-soname,android.hardware.audio.common-util.so $LDF $T/cm_effectmap.o $T/dso_handle.o $S/libhidlbase.so $S/liblog.so $S/libutils.so $S/libc++.so $S/libm.so $S/libdl.so $S/libc.so \
    -o "$OUT/vendor#lib64#android.hardware.audio.common-util.so"
$LD -shared -Wl,-soname,android.hardware.audio.common@2.0-util.so $LDF $T/cm_hidlutils.o $T/dso_handle.o $S/android.hardware.audio.common@2.0.so $S/android.hardware.audio.common-util.so \
    $S/libhidlbase.so $S/liblog.so $S/libutils.so $S/libc++.so $S/libm.so $S/libdl.so $S/libc.so \
    -o "$OUT/vendor#lib64#android.hardware.audio.common@2.0-util.so"
$LD -shared -Wl,-soname,android.hardware.audio@2.0-impl.so $LDF $T/o/*.o $T/dso_handle.o \
    $S/android.hardware.audio@2.0.so $S/android.hardware.audio.common@2.0.so $S/android.hardware.audio.common@2.0-util.so $S/android.hardware.audio.common-util.so \
    $S/libbase.so $S/libcutils.so $S/libfmq.so $S/libhardware.so $S/libhidlbase.so $S/liblog.so $S/libmedia_helper.so $S/libutils.so \
    $S/libc++.so $S/libm.so $S/libdl.so $S/libc.so \
    -o "$OUT/vendor#lib64#hw#android.hardware.audio@2.0-impl.so"

# ---- effects: libeffects (frameworks/av factory) and android.hardware.audio.effect@2.0-impl ----
FX=$A/frameworks_av/media/libeffects/factory
EFXINC="-I$FX -I$FX/include -I$A/frameworks_av/media/libeffects/config/include -I$A/frameworks_av/media/libeffects/factory/include/media"
for f in EffectsFactory EffectsConfigLoader EffectsFactoryState; do
    $CC $(echo "$INC" | sed 's/-nostdinc++ //; s#-isystem [^ ]*libcxx[^ ]*##g; s#-isystem [^ ]*cfg##') $EFXINC -std=gnu11 -include linux/limits.h -fvisibility=hidden -DLOG_TAG='"EffectsFactory"' -c $FX/$f.c -o $T/o2_$f.o &
done; wait
$CXX $INC $EFXINC -fvisibility=hidden -DLOG_TAG='"EffectsFactory"' -c $FX/EffectsXmlConfigLoader.cpp -o $T/o2_xml.o
# (libeffectsconfig and tinyxml2 are not VNDK: static here)
$CXX $INC $EFXINC -I$A/tinyxml2 -DLOG_TAG='"EffectsConfig"' -c $A/frameworks_av/media/libeffects/config/src/EffectsConfig.cpp -o $T/o2_cfg.o
$CXX $INC -I$A/tinyxml2 -w -c $A/tinyxml2/tinyxml2.cpp -o $T/o2_tx.o
for L in libeffects.so libhidlmemory.so android.hidl.memory@1.0.so android.hardware.audio.effect@2.0.so; do
    $CC -nostdlib -shared -Wl,-soname,$L -Wl,--build-id=none -o $T/so/$L $T/e.c
done
$LD -shared -Wl,-soname,libeffects.so $LDF $T/o2_*.o $T/dso_handle.o $S/libcutils.so $S/liblog.so $S/libmedia_helper.so $S/libc++.so $S/libm.so $S/libdl.so $S/libc.so \
    -o "$OUT/vendor#lib64#libeffects.so"
EF=$AU/effect/all-versions/default
mkdir -p $T/fx
i=0
for f in AcousticEchoCancelerEffect AudioBufferManager AutomaticGainControlEffect BassBoostEffect Conversions DownmixEffect Effect EffectsFactory EnvironmentalReverbEffect \
         EqualizerEffect LoudnessEnhancerEffect NoiseSuppressionEffect PresetReverbEffect VirtualizerEffect VisualizerEffect; do
    i=$((i+1))
    $CXX $INC $EFXINC -DLOG_TAG='"EffectsHAL"' -c $EF/$f.cpp -o $T/fx/$f.o &
    [ $((i % 4)) -eq 0 ] && wait
done; wait
[ "$(ls $T/fx | wc -l)" -eq 15 ] || { echo "effect compile failed" >&2; exit 1; }
$LD -shared -Wl,-soname,android.hardware.audio.effect@2.0-impl.so $LDF $T/fx/*.o $T/dso_handle.o \
    $S/android.hardware.audio.effect@2.0.so $S/android.hardware.audio.common@2.0.so $S/android.hardware.audio.common@2.0-util.so $S/android.hardware.audio.common-util.so \
    $S/android.hidl.memory@1.0.so $S/libbase.so $S/libcutils.so $S/libeffects.so $S/libfmq.so $S/libhidlbase.so $S/libhidlmemory.so $S/liblog.so $S/libutils.so \
    $S/libc++.so $S/libm.so $S/libdl.so $S/libc.so \
    -o "$OUT/vendor#lib64#hw#android.hardware.audio.effect@2.0-impl.so"

# ---- the service ----
$CXX $INC -DLOG_TAG='"audiohalservice"' -c $H/service.cpp -o $T/svc.o
$CC -c $H/../gralloc2/crt_begin.c -o $T/crt_begin.o; $CC -c $H/../gralloc2/crt_end.c -o $T/crt_end.o
nm -u $T/svc.o $T/crt_begin.o | awk '$1=="U"{print $2}' | sort -u | grep -v '^main$' > $T/svc.undef
while read -r s; do printf 'void %s(void){}\n' "$s"; done < $T/svc.undef > $T/c2.c
$CC -nostdlib -shared -Wl,-soname,libc.so -Wl,--build-id=none -o $T/so/libc_svc.so $T/c2.c
$LD -pie -Wl,-dynamic-linker=/system/bin/linker64 $LDF $T/crt_begin.o $T/svc.o $T/crt_end.o \
    $S/android.hardware.audio@2.0.so $S/android.hardware.audio.effect@2.0.so $S/libhidlbase.so $S/liblog.so $S/libutils.so $S/libc++.so $S/libm.so $S/libdl.so $S/libc_svc.so \
    -o "$OUT/vendor#bin#hw#android.hardware.audio@2.0-service"

# ---- the HAL module (the engine's /dev/eac PCM channel, native/audiohal/audio_hal.c) ----
ZB_BY_PATH=1 sh "$H/../zig-bionic.sh" "$OUT/vendor#lib64#hw#audio.primary.default.so" audio.primary.default.so "$H/../../audiohal/audio_hal_v2.c" -I"$H/../../audiohal" -DAEMU_BIONIC

# a service with no PT_INTERP starts with no dynamic linker (nothing relocated, DT_NEEDED not loaded) and dies with SIGSEGV in its first
# PLT call, exit 139, restarted for ever (rip 0x2486 / 0x1936 in hwcomposer-2-1.log / gralloc-2-0.log); tools/patch_x86_service_interp.py adds it
for f in "$OUT/vendor#bin#hw#android.hardware.audio@2.0-service"; do
    readelf -lW "$f" | grep -q ' INTERP ' || { echo "error: $f has no PT_INTERP" >&2; exit 1; }
done
for f in "$OUT"/vendor#lib64#hw#android.hardware.audio*-impl.so "$OUT/vendor#lib64#android.hardware.audio.common-util.so" "$OUT/vendor#lib64#android.hardware.audio.common@2.0-util.so" "$OUT/vendor#lib64#libeffects.so" "$OUT/vendor#bin#hw#android.hardware.audio@2.0-service" "$OUT/vendor#lib64#hw#audio.primary.default.so"; do
    if nm -D "$f" | grep -Eq ' U (__stack_chk_fail|__errno_location|__memcpy_chk|__sprintf_chk|__snprintf_chk|__fprintf_chk)$'; then echo "error: $f imports a glibc/musl-only symbol" >&2; exit 1; fi
done
ls -l "$OUT"/vendor#*audio* "$OUT/vendor#lib64#libeffects.so"
