#!/bin/sh
# Builds the library for this machine (plain and with AddressSanitizer) and runs tests/run_tests.py against both.
# Needs: cc, python3 with lz4 zstandard brotli, xz, and optionally 7zz (7-Zip 25) to make the 7z fixtures.
set -eu
HERE=$(cd "$(dirname "$0")/.." && pwd)
Z7=${Z7:-$(command -v 7zz || command -v 7z || true)}
for v in plain asan; do
  D=$HERE/build/$v; mkdir -p "$D"
  X=""; [ $v = asan ] && X="-fsanitize=address -fno-omit-frame-pointer -g"
  EXTRA_CFLAGS="$X" sh "$HERE/build.sh" host "$D/libaemuarc.so" >/dev/null
  ${CC:-cc} -O1 -g -Wall -Wextra $X -I"$HERE/include" -o "$D/test_arc" "$HERE/tests/test_arc.c" -L"$D" -laemuarc -Wl,-rpath,"$D"
  echo "== $v"; python3 "$HERE/tests/run_tests.py" "$D" "$Z7"
done
