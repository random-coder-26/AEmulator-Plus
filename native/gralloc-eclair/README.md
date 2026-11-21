# Eclair gralloc for x86 (engines/kk-x86/gralloc.default.so)

`gralloc-single-page-ashmem.patch` is the change to AOSP Eclair's `modules/gralloc/gralloc.cpp` (`gralloc_alloc_framebuffer_locked`)
that the shipped `app/src/main/assets/engines/kk-x86/gralloc.default.so` (i386, bionic) was built with.

When fb0 offers a single page, gralloc does not page-flip: it hands out a plain buffer that is copied to the screen on `post`.
Stock Eclair asks for `GRALLOC_USAGE_HW_2D` for that buffer, which makes `gralloc_alloc_buffer()` take the pmem path;
`init_pmem_area()` locks the module mutex that the caller already holds, and the mutex is not recursive: SurfaceFlinger blocks
for good (FUTEX_WAIT right after the mmap of fb0). The patch clears `HW_FB`, `HW_2D` and `HW_TEXTURE`, so the buffer is plain ashmem.

The glibc build (`engines/glibc-x86/gralloc.default.so`) is made from the file above by `tools/patch_gralloc_glibc.py`, with
`engines/glibc-x86/aegr.so` (`native/revue-shims`). Apply with `patch -p1` in an Eclair tree (the paths are `a/` and `b/`).
The build script and toolchain of the binary are not in this checkout (see docs/license-audit.md).
