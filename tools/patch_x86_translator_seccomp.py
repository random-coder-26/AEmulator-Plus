"""Patches libqemu_x86_64.so and libqemu_i386.so (stock QEMU 8.2.7, see tools/qemu-zig) so that prctl(PR_SET_SECCOMP, ...) succeeds.

Why: user-mode QEMU answers PR_SET_SECCOMP (and PR_GET_SECCOMP) with -EINVAL on purpose, so a guest cannot lock out the syscalls the
translator itself needs. Android 8+ zygote installs a seccomp filter in every forked app process, system_server included
(libandroid_runtime SpecializeCommon -> _set_seccomp_filter -> install_filter), and aborts when the call fails:
    F zygote64: seccomp_policy.cpp:214] Could not set seccomp filter of size 212: Invalid argument
    F DEBUG: Abort message: 'Could not set seccomp filter of size 212: Invalid argument'   (pid of system_server, app_process64)
zygote then loses its system_server child and restarts itself (exit 137), so the guest never gets past boot. mediaextractor and
mediacodec (minijail: "prctl(seccomp_filter) failed: Invalid argument") die the same way. The app already runs under the phone's own
seccomp filter, and the guest's filter is an x86 BPF program the host kernel could not evaluate anyway, so accepting and dropping it is
the same thing the x86-32 engine does in native/guestshim/aemushim_x86.c (prctl hook).

What: do_prctl dispatches options 1..64 through a byte table (index option-1, target = base + 4 * byte). x0 is preset to -EINVAL and the
unsupported options all land on the common epilogue. The entry of option 22 (PR_SET_SECCOMP) is pointed at the block that
tools/patch_translator_prctl_set_vma.py already made, `mov x0|w0, xzr ; b epilogue`, so the call returns 0. One byte per file.
PR_GET_SECCOMP (21) keeps returning -EINVAL.
Run from the checkout root, after patch_translator_prctl_set_vma.py (the block is made there); every site is checked first. Idempotent.
"""
import struct
D = "app/src/main/jniLibs/arm64-v8a/"

def vaddr_to_off(d, va):
    phoff = struct.unpack_from("<Q", d, 32)[0]; phsz = struct.unpack_from("<H", d, 54)[0]; phn = struct.unpack_from("<H", d, 56)[0]
    for i in range(phn):
        t, fl, off, vad, _, filesz = struct.unpack_from("<IIQQQQ", d, phoff + i * phsz)
        if t == 1 and vad <= va < vad + filesz:
            return off + va - vad
    raise SystemExit("address not in a PT_LOAD segment: %#x" % va)

# name: (byte table, branch base of the table = the adr in front of the br, common epilogue, `mov x0|w0, xzr` block of the PR_SET_VMA patch, wide)
SITES = {
    "libqemu_i386.so":   (0x10462bc, 0x11f4ad8, 0x11f4af4, 0x11f4c34, False),
    "libqemu_x86_64.so": (0x1045bf0, 0x11f0ef4, 0x11f0fe4, 0x11f1060, True),
}
PR_SET_SECCOMP = 22
MOV_ZR = {False: 0x2a1f03e0, True: 0xaa1f03e0}           # mov w0, wzr / mov x0, xzr

for name, (table, base, epilogue, zero, wide) in SITES.items():
    path = D + name
    d = bytearray(open(path, "rb").read())
    o = vaddr_to_off(d, table) + PR_SET_SECCOMP - 1
    want_old, want_new = (epilogue - base) // 4, (zero - base) // 4
    assert (epilogue - base) % 4 == 0 and (zero - base) % 4 == 0 and want_new < 256
    z = struct.unpack_from("<2I", d, vaddr_to_off(d, zero))
    assert z[0] == MOV_ZR[wide], "%s: no `mov x0, xzr` at %#x, run tools/patch_translator_prctl_set_vma.py first" % (name, zero)
    assert (z[1] & 0xfc000000) == 0x14000000, "%s: no branch at %#x" % (name, zero + 4)
    if d[o] == want_new:
        print(name, "already patched"); continue
    assert d[o] == want_old, "%s: table entry of option %d is %#x, expected %#x" % (name, PR_SET_SECCOMP, d[o], want_old)
    d[o] = want_new
    open(path, "wb").write(d)
    print(name, "patched: PR_SET_SECCOMP now returns 0 (table byte at file offset %#x: %#x -> %#x)" % (o, want_old, want_new))
