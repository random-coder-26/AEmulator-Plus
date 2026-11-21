#!/bin/sh
# The i386 build of the GLES bridge (assets/engines/common-x86_64/system#lib#egl#libGLES_aemu.so): see build-x86-zig.sh
exec sh "$(dirname "$0")/build-x86-zig.sh" i386 "$@"
