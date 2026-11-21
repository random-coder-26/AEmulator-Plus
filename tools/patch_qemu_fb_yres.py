"""Makes a libqemu_kk.so-derived translator report yres_virtual = 2 * yres in FBIOGET_VSCREENINFO / FBIOPUT_VSCREENINFO.

Usage: python3 tools/patch_qemu_fb_yres.py [libqemu_arm_1.0.so | libqemu_arm_pre1.0.so]   (patches in place; idempotent; every byte is checked first)

Why: Android 0.9 / 1.0 / 1.1 libagl (eglCreateDisplaySurfaceANDROID) asks for yres_virtual = 2 * yres and, when the answer is smaller,
logs "page flipping not supported", clears PAGE_FLIP and renders into a malloc'd back buffer that egl_linuxfb_surface_t::swapBuffers
never copies anywhere: fb0 stays all zero. The emulated fb0 file already holds the pages (smem_len = 4 pages of 16 bpp, TreeFixer.makeFb),
only the answer was wrong. The host view (GuestScreen, pages = 2) shows the page that changed last.

What: the filler of the fb_var_screeninfo (0x16bf70, file offset == vaddr) stored yres_virtual = yres. Five instructions at 0x16bfdc:
    before: str w3,[x19,#8] ; ldr w1,[x2,#0x20] ; str w1,[x19,#4] ; str w1,[x19,#0xc] ; str w6,[x19,#0x18]
    after : ldr w1,[x2,#0x20] ; stp w1,w3,[x19,#4] ; add w1,w1,w1 ; str w1,[x19,#0xc] ; str w6,[x19,#0x18]
(w3 = xres, w1 = yres; w1 is dead after the stores.) No code cave, no size change.
"""
import struct, sys
A = 0x16bfdc
OLD = [0xb9000a63, 0xb9402041, 0xb9000661, 0xb9000e61, 0xb9001a66]
NEW = [0xb9402041, 0x29008e61, 0x0b010021, 0xb9000e61, 0xb9001a66]


def patch(b: bytearray) -> str:
    cur = list(struct.unpack("<5I", b[A:A + 20]))
    if cur == NEW:
        return "already patched"
    if cur != OLD:
        raise SystemExit("not the expected build: bytes at 0x%x are %s" % (A, [hex(x) for x in cur]))
    b[A:A + 20] = struct.pack("<5I", *NEW)
    return "patched"


if __name__ == "__main__":
    p = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/jniLibs/arm64-v8a/libqemu_arm_1.0.so"
    d = bytearray(open(p, "rb").read())
    r = patch(d)
    open(p, "wb").write(d)
    print(p + ": " + r)
