#!/bin/sh
# Builds and runs solo-smoke.c on this machine (gcc, glibc): the standalone EGL of guest.c, no guest and no GPU needed.
set -eu
cd "$(dirname "$0")"
gcc -O1 -g -Wall -Wno-unused-function -o /tmp/solo-smoke solo-smoke.c -ldl -lpthread
/tmp/solo-smoke
