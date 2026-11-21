#!/bin/sh
# The x86-64 build of the GLES bridge (assets/engines/common-x86_64/system#lib64#egl#libGLES_aemu.so): see build-x86-zig.sh
exec sh "$(dirname "$0")/build-x86-zig.sh" x86_64 "$@"
