"""Makes the guest-path translator of the libqemu_kk.so family treat EVERY top-level directory of the guest tree as guest-rooted.

Usage: python3 tools/patch_qemu_guest_roots.py [lib.so ...]   (default: the kk-family translators in app/src/main/jniLibs/arm64-v8a;
       patches in place; idempotent; every byte is checked first; a lib of another build is refused, not guessed at)

Why: path() (0x1ad9f0) turns a guest path P into <root>/P by g_build_filename(root, P) and then
    1. faccessat(<root>/P) == 0            -> rooted (this is why files of an imported /cust or /oem already open),
    2. P starts with one of 20 hardcoded prefixes (/data /cache /mnt /tmp /storage /system /vendor /odm /product /system_ext /apex
       /etc /bin /sbin /dbdata /firmware /oem ... the pointer table at 0x3aaec8)  -> rooted even though P does not exist yet,
    3. anything else                       -> the PHONE's path P.
Step 3 is the bug for every partition that is not in the table: creating, renaming or opening a not-yet-existing file below /cust,
/preload, /carrier, /hw_product, /persist, /mi_ext ... (an init.rc "mkdir /cust/x", a service writing /cust/log, a mount point) hits the
phone's real filesystem (EACCES / EROFS / ENOENT) instead of the guest tree, although the partition is imported and its directory exists.

What: step 4 is added. When the table gives up (0x1adc4c: 'mov x0, x27', x21 = P, x27 = <root>/P, x23 = 0x469000), 304 bytes of new code at
0x388200 (the unused tail of the page the R E segment already maps; its p_filesz / p_memsz grow to 0x388400) take the first component C of P and
ask faccessat(<root>/C). If it exists, the path is guest-rooted exactly as if C were in the table (jump to 0x1adea8); if not, nothing changes
(back to 0x1adc50 with x0 = x27). C = proc, dev, sys (the kernel's own trees: /dev/socket, /sys/fs/selinux stay rooted through the table
as before), ".", ".." and anything longer than 128 bytes never qualify. The partition list is therefore read from the guest tree at run time:
whatever top-level directory the importer created is accepted, no list to keep in step.

Registers: x19 / x20 / x25 / x26 / x28 are saved in the frame of path() and restored before it returns; the cave keeps sp balanced (0x400-byte buffer).
Checked by emulating the cave (unicorn) against a stubbed faccessat: routing, sp balance, x0 / x27 on the fall-through, over-long root and component.
"""
import struct, sys, os

HOOK, CAVE = 0x1adc4c, 0x388200
CAVE_END = 0x388400
HOOK_OLD = 0xaa1b03e0                       # mov x0, x27
LDR_TABLE_NEXT = 0xf8408754                 # ldr x20, [x26], #8   (0x1adc44, just before the cbnz that leaves the loop)
CAVE_HEX = "fc0315aa811740383fbc0071e1080054f4031caa81034039a10000343fbc0071600000549c070091fbffff17990314cbd90700b43f0302f1880700543f0f00f1c101005481024079820a403983ac8c523f00036b610000545fd8017160060054632e8f523f00036b010300545fcc0171c0050054150000143f1300f1e1000054810240b9034e8e52e36dac723f00036bc00400540d0000143f0b00f1c100005481024079c3c585523f00036be0030054060000143f0700f181000054810240393fb8007120030054ff0310d1e0da40f9e1030091e2030c9103144038a30000343f0002eb0202005423140038fbffff17e3058052231400388316403823140038390700f1a1ffff543f000039e00300910100805222d4fb97ff03109160000035e296f817ff031091e0031baa4996f817"
DEFAULT = ["libqemu_kk.so", "libqemu_kk_xperiaplay.so", "libqemu_arm_1.0.so", "libqemu_arm_pre1.0.so"]


def patch(b: bytearray) -> str:
    code = bytes.fromhex(CAVE_HEX)
    jump = 0x14000000 | (((CAVE - HOOK) >> 2) & 0x3ffffff)
    cur = struct.unpack_from("<I", b, HOOK)[0]
    if cur == jump and bytes(b[CAVE:CAVE + len(code)]) == code:
        return "already patched"
    if cur != HOOK_OLD or struct.unpack_from("<I", b, HOOK - 8)[0] != LDR_TABLE_NEXT:
        raise SystemExit("not the expected build: bytes at 0x%x are %08x" % (HOOK, cur))
    if any(b[CAVE:CAVE_END]):
        raise SystemExit("0x%x..0x%x is not unused" % (CAVE, CAVE_END))
    # the first PT_LOAD (R E) must cover the cave
    phoff, phentsize, phnum = struct.unpack_from("<QHH", b, 0x20)[0], *struct.unpack_from("<HH", b, 0x36)
    p = phoff
    typ, flags, off, va, pa, fsz, msz, al = struct.unpack_from("<IIQQQQQQ", b, p)
    if typ != 1 or off != 0 or va != 0 or flags != 5 or fsz > CAVE:
        raise SystemExit("unexpected first program header")
    struct.pack_into("<QQ", b, p + 32, CAVE_END, CAVE_END)
    b[CAVE:CAVE + len(code)] = code
    struct.pack_into("<I", b, HOOK, jump)
    return "patched"


if __name__ == "__main__":
    here = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "jniLibs", "arm64-v8a")
    paths = sys.argv[1:] or [os.path.join(here, n) for n in DEFAULT]
    for p in paths:
        d = bytearray(open(p, "rb").read())
        r = patch(d)
        if r == "patched":
            open(p, "wb").write(d)
        print(p + ": " + r)
