#!/bin/sh
# Compiles tests/abi-check.c against AOSP's real audio headers and against aemu_audio_abi.h (host, LP64) and diffs the facts.
# needs: gcc; aosp11 dir with hardware_libhardware + system_media (android11-release), see native/x86_64/audio/fetch-aosp.sh
set -eu
H=$(cd "$(dirname "$0")/.." && pwd)
A=${1:-$H/../x86_64/audio/aosp11}
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
gcc -DREAL -w -I"$H/tests/stub" -I"$H/../x86_64/inc" -I"$A/hardware_libhardware/include" -I"$A/system_media/audio/include" \
    -I"$A/system_media/audio_effects/include" "$H/tests/abi-check.c" -o "$T/real"
gcc -w -I"$H" "$H/tests/abi-check.c" -o "$T/mine"
"$T/real" > "$T/real.txt"; "$T/mine" > "$T/mine.txt"
[ -s "$T/real.txt" ] || { echo "abi-check: no facts printed" >&2; exit 1; }
diff "$T/real.txt" "$T/mine.txt" && echo "abi-check: $(wc -l < "$T/real.txt") facts identical"
