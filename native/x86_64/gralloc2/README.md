# x86-64 graphics allocator / mapper 2.0

Android 12L emulator-style x86-64 images start `allocator@4.0-service.minigbm`, which needs a DRM render node and exits with
code 234 here. SurfaceFlinger then has no allocator and dies. This builds the Android 8-style gralloc stack for x86-64 so that
TreeFixer can declare allocator/mapper **2.0** instead of 4.0 (`useSoftwareGrallocOnX86`).

    pip install ziglang
    sh fetch-aosp.sh          # android12L-release sources (aosp-mirror-neo / aosp-mirror), hidl-gen built on the host, headers generated
                              # needs: git g++ bison flex libssl-dev libjsoncpp-dev
    sh build-gralloc2.sh      # writes into app/src/main/assets/engines/common-x86_64

Sources are from the same release as the guest (API 32), not from Android 8: the impls load against that release's libhidlbase,
libutils and libc++ and its allocator@2.0.so / mapper@2.0.so interface libraries.

| output | source |
|---|---|
| `android.hardware.graphics.allocator@2.0-impl.so` | hardware/interfaces graphics/allocator/2.0/default |
| `android.hardware.graphics.mapper@2.0-impl.so` | hardware/interfaces graphics/mapper/2.0/default |
| `android.hardware.graphics.allocator@2.0-service` | graphics/allocator/2.0/default/service.cpp + `crt_begin.c` / `crt_end.c` (bionic's `_start`) |
| `libgralloc_aosp.so` | hardware/libhardware/modules/gralloc (AOSP default gralloc) |
| `gralloc.default.so` | native/grallocwrap/gralloc_wrap.c (forwards to libgralloc_aosp.so) |

TreeFixer installs it only on x86-64 Treble firmware of API 31 or newer that has no allocator@2.0 service of its own
(`x86Gralloc2Wanted`): the binaries are built against Android 12L's libhidlbase, and an image such as the Android 10 SDK one has a
working allocator@2.0 stack that must stay. Firmware files the stack replaces are kept in `system/.aemu-gralloc2-orig`.

Not run in a guest yet: only compiled, linked and checked (DT_NEEDED, interpreter, imports).
