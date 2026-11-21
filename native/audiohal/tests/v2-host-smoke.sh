#!/bin/sh
# glibc build of audio_hal_v2.c + a driver that plays through FIFOs in a temp dir. needs gcc.
set -eu
H=$(cd "$(dirname "$0")/.." && pwd); T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
mkfifo "$T/eac" "$T/mic" "$T/mic_ctl"
gcc -shared -fPIC -O1 -w -I"$H" -DAEMU_PCM_PATH="\"$T/eac\"" -DAEMU_MIC_PATH="\"$T/mic\"" -DAEMU_MIC_CTL_PATH="\"$T/mic_ctl\"" \
    "$H/audio_hal_v2.c" -o "$T/hal.so"
gcc -w -I"$H" "$H/tests/v2-host-smoke.c" -ldl -o "$T/drv"
AEMU_PCM="$T/eac" AEMU_MIC_CTL="$T/mic_ctl" "$T/drv" "$T/hal.so"
