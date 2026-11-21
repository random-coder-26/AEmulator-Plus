#!/bin/sh
# sensors.default.so (no sensors, native/x86_64/sensors_none.c) for an x86-64 guest:
#   sh build-sensors.sh [out]    (default: engines/common-x86_64/vendor#lib64#hw#sensors.default.so)
set -eu
H=$(cd "$(dirname "$0")" && pwd)
OUT=${1:-$H/../../app/src/main/assets/engines/common-x86_64/vendor#lib64#hw#sensors.default.so}
sh "$H/zig-bionic.sh" "$OUT" sensors.default.so "$H/sensors_none.c"
