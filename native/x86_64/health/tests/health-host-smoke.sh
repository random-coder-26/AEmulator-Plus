#!/bin/sh
# Host check of sysfs.h (the stand-in health service's power_supply reader): builds tests/health-host-smoke.cpp with the host
# compiler and runs it against fake /sys/class/power_supply trees. Not run inside a guest.
set -eu
H=$(cd "$(dirname "$0")/.." && pwd)
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
${CXX:-g++} -std=c++17 -Wall -I"$H" "$H/tests/health-host-smoke.cpp" -o "$T/smoke"
"$T/smoke"
