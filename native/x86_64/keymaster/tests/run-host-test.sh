#!/bin/sh
# Host test: AOSP's real message classes + a stand-in engine, a pty, and a client doing what the firmware service does.
#   sh run-host-test.sh <aosp dir with km/ and libhardware/ (android12L-release)>
set -eu
H=$(cd "$(dirname "$0")" && pwd); A=$(cd "$1" && pwd); T=$(mktemp -d); trap 'kill $P 2>/dev/null; rm -rf $T' EXIT
INC="-I$A/km/include -I$A/libhardware/include"; F="-std=c++17 -O1 -w -fno-rtti -DKEYMASTER_NAME_TAGS -DAEMU_KM_HOSTTEST"
for f in android_keymaster_messages android_keymaster_utils authorization_set keymaster_tags logger serializable; do set -- "$@"; g++ $F $INC -c $A/km/android_keymaster/$f.cpp -o $T/$f.o; done
g++ $F $INC $H/../aemu-keymaster-answerer.cpp $H/fake_km.cpp $T/*.o -o $T/ans
$T/ans --tty $T/hvc1 2>$T/log & P=$!; sleep 1
python3 $H/hvc1_client_test.py $T/hvc1
