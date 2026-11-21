#!/bin/sh
# Fetches the AOSP sources the x86-64 gralloc 2.0 stack is built from and prepares what the compile needs:
#   sh fetch-aosp.sh [dir]        (default: native/x86_64/gralloc2/aosp12l, git-ignored)
# Branch android12L-release (API 32): the guest's libhidlbase/libutils/libc++ and the allocator@2.0 / mapper@2.0 interface
# libraries it loads against are of that release, so the headers have to be too. Repos come from github.com/aosp-mirror-neo,
# system_core / bionic / frameworks from github.com/aosp-mirror (the -neo mirror has no system_core).
# Then it builds hidl-gen on this machine (g++, bison, flex, libssl-dev, libjsoncpp-dev) and generates the C++ headers of
# android.hardware.graphics.{allocator,mapper}@2.0, graphics.common@1.0-1.2 android.hidl.base@1.0 and android.hidl.manager@1.0 into <dir>/gen.
set -eu
H=$(cd "$(dirname "$0")" && pwd)
A=${1:-$H/aosp12l}
BR=${AOSP_BRANCH:-android12L-release}
mkdir -p "$A"; A=$(cd "$A" && pwd); cd "$A"
for t in git g++ bison flex; do command -v $t >/dev/null || { echo "need $t (apt install git g++ bison flex libssl-dev libjsoncpp-dev)" >&2; exit 1; }; done
cl() {      # name org repo
    [ -d "$1/.git" ] || git clone -q --depth 1 -b "$BR" "https://github.com/$2/$3" "$1"
}
cl hardware_interfaces  aosp-mirror-neo platform_hardware_interfaces &
cl hardware_libhardware aosp-mirror-neo platform_hardware_libhardware &
cl system_libhidl       aosp-mirror-neo platform_system_libhidl &
cl system_tools_hidl    aosp-mirror-neo platform_system_tools_hidl &
cl system_libbase       aosp-mirror-neo platform_system_libbase &
cl system_logging       aosp-mirror-neo platform_system_logging &
cl system_libhwbinder   aosp-mirror-neo platform_system_libhwbinder &
cl system_libfmq        aosp-mirror-neo platform_system_libfmq &
cl libcxx               aosp-mirror-neo platform_external_libcxx &
cl libcxxabi            aosp-mirror-neo platform_external_libcxxabi &
wait
cl system_core aosp-mirror platform_system_core &
cl bionic      aosp-mirror platform_bionic &
wait
for d in hardware_interfaces hardware_libhardware system_libhidl system_tools_hidl system_libbase system_logging system_libhwbinder system_libfmq libcxx libcxxabi system_core bionic; do
    [ -d "$d/.git" ] || { echo "clone of $d failed (branch $BR)" >&2; exit 1; }
done

# ---- hidl-gen (host) ----
if [ ! -x hb/hidl-gen ]; then
    mkdir -p hb/o; T=$A/system_tools_hidl; B=$A/system_libbase
    # bison 3.8 names the semantic type value_type; the header it writes has to see the AST types (%code requires)
    sed 's/semantic_type/value_type/g' "$T/hidl-gen_y.yy" > hb/y.yy
    sed -i '/^%skeleton "glr.cc"/a %code requires {\n#include "AST.h"\n#include "Annotation.h"\n#include "ArrayType.h"\n#include "CompoundType.h"\n#include "ConstantExpression.h"\n#include "DocComment.h"\n#include "EnumType.h"\n#include "Interface.h"\n#include "Location.h"\n#include "Method.h"\n#include "Scope.h"\n#include "TypeDef.h"\n#include "VectorType.h"\n#include "Reference.h"\n#include <hidl-util/FQName.h>\n}' hb/y.yy
    (cd hb && bison -d -o hidl-gen_y.cpp y.yy 2>/dev/null; cp hidl-gen_y.hpp hidl-gen_y.h; flex -o hidl-gen_l.cpp "$T/hidl-gen_l.ll")
    sed -i '1i #include "hidl-gen_y-helpers.h"\n#include "hidl-gen_y.h"\ntypedef yy::parser::value_type YYSTYPE; typedef yy::parser::location_type YYLTYPE;' hb/hidl-gen_l.cpp
    INC="-Ihb -I$T -I$T/hashing/include -I$T/hashing/include/hidl-hash -I$T/host_utils/include -I$T/host_utils/include/hidl-util -I$T/utils/include -I$T/utils/include/hidl-util -I$B/include -I$A/system_logging/liblog/include -I$A/system_core/include -I$A/system_libhwbinder/include -I/usr/include/jsoncpp"
    SRCS="$(ls $T/*.cpp | grep -v -E 'hidl-gen_|/main.cpp') $T/main.cpp $T/hashing/Hash.cpp $T/host_utils/Formatter.cpp $T/host_utils/StringHelper.cpp $T/utils/FQName.cpp $T/utils/FqInstance.cpp $B/file.cpp $B/logging.cpp $B/stringprintf.cpp $B/strings.cpp $B/errors_unix.cpp $B/chrono_utils.cpp $B/threads.cpp $B/test_utils.cpp $H/hidlgen_logstub.cpp hb/hidl-gen_y.cpp hb/hidl-gen_l.cpp"
    i=0; for f in $SRCS; do
        i=$((i+1))
        g++ -std=gnu++17 -w -O0 -fpermissive -include cstdint -include cstring -include cstdlib -include algorithm -D_GNU_SOURCE '-D__builtin_available(...)=1' $INC -c "$f" -o hb/o/$i.o &
        [ $((i % 4)) -eq 0 ] && wait
    done; wait
    g++ hb/o/*.o -lssl -lcrypto -ljsoncpp -lpthread -o hb/hidl-gen
fi

# ---- generated headers ----
if [ ! -f gen/android/hardware/graphics/allocator/2.0/IAllocator.h ]; then
    R="-r android.hardware:hardware_interfaces -r android.hidl:system_libhidl/transport"
    mkdir -p gen
    for p in android.hardware.graphics.allocator@2.0 android.hardware.graphics.mapper@2.0 android.hardware.graphics.common@1.0 \
             android.hardware.graphics.common@1.1 android.hardware.graphics.common@1.2 android.hidl.base@1.0 android.hidl.manager@1.0; do
        hb/hidl-gen -o gen -L c++-headers $R $p
    done
fi
echo "AOSP sources and generated headers ready in $A"
