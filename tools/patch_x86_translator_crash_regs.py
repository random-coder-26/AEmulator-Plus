"""Patches libqemu_x86_64.so and libqemu_i386.so (stock QEMU 8.2.7, see tools/qemu-zig) so that a guest that dies on a fatal signal
leaves its CPU state in the service log.

Why: a guest that ends with "qemu: uncaught target signal 11 (Segmentation fault)" leaves no account of where. QEMU_STRACE is no way out on
x86 / x86-64 (it slows the translator so much that the firmware does not boot), and the guest core file QEMU announces is not written. The
host-side crash report of the runner (aemu64: fatal signal ...) only shows QEMU's own end: dump_core_and_abort() raises the signal on the
process with kill(getpid()) and waits in sigsuspend(), so the pc/lr of that report are always the same ones, in every process.

What: dump_core_and_abort(CPUArchState *env, int sig) is where QEMU gives a fatal guest signal up. Its first instruction (`sub sp, sp, #0x40`,
at 0x11da6e8 in libqemu_x86_64.so, 0x11d937c in libqemu_i386.so) is replaced by a `b` to a stub that
  * does what the replaced instruction did,
  * writes one line to the process's stderr (host write(2), nr 64: the runner redirects it into run/<service>.log, where QEMU's own
    "uncaught target signal" message goes), without calling anything: it needs 0x190 bytes of the stack below the frame, builds the line in them
    and gives them back,
  * hands x0 / x1 back unchanged and jumps to the second instruction of the function. Nothing but x2..x10 (scratch) is touched.
The line is the signal number and then the guest registers in the order of CPUX86State (CPUX86State.regs[], then eip and eflags: env + 0 .. 0x88 on x86-64, env + 0 .. 0x24 on i386), every
value in hex with a fixed width and a space before it:
  x86-64:  aemu-guest: <sig> rax rcx rdx rbx rsp rbp rsi rdi r8 r9 r10 r11 r12 r13 r14 r15 rip rflags      (16 digits each)
  i386:    aemu-guest: <sig> eax ecx edx ebx esp ebp esi edi eip eflags                                       (8 digits each)
With rip at hand, resolve it against the maps of the process (the guest runs under `-B 0`, so guest and host addresses are the same).
The stub runs for every signal QEMU ends the process with (an abort() of the guest, SIGABRT, gives its line too, with rip inside the guest's libc),
not only for SIGSEGV; the first value says which.

Where: the stub needs 180 bytes behind the text segment. patch_x86_translator_process_vm_readv.py leaves 64 KiB of zero padding there, after its own
stub, and this script uses it, so run that one first (it is idempotent and the order of the others does not matter). The text segment (p_filesz /
p_memsz, the .text section size) is made as much longer as the stub is; no address of the program moves.
Run from the checkout root; every site is checked against the expected original bytes first. Idempotent.
"""
import struct
D = "app/src/main/jniLibs/arm64-v8a/"

ORIG_ENTRY = 0xd10103ff            # sub sp, sp, #0x40
ORIG_NEXT = 0xa9017bfd             # stp x29, x30, [sp, #0x10]

def B(f, t): return 0x14000000 | (((t - f) // 4) & 0x3ffffff)

def phdrs(d):
    phoff = struct.unpack_from("<Q", d, 32)[0]; phsz = struct.unpack_from("<H", d, 54)[0]; phn = struct.unpack_from("<H", d, 56)[0]
    return [(phoff + i * phsz,) + struct.unpack_from("<IIQQQQQQ", d, phoff + i * phsz) for i in range(phn)]   # (pos, type, flags, off, va, pa, fsz, msz, al)

def vaddr_to_off(d, va):
    for pos, t, fl, off, vad, _, fsz, msz, al in phdrs(d):
        if t == 1 and vad <= va < vad + fsz:
            return off + va - vad
    raise SystemExit("address not in a PT_LOAD segment: %#x" % va)

def word(d, va): return struct.unpack_from("<I", d, vaddr_to_off(d, va))[0]

def stub_bytes(stub, at, back):
    return b"".join(struct.pack("<I", B(at + 4 * i, back) if w is None else w) for i, (w, _) in enumerate(stub))

# (word, assembly). `b <entry + 4>` is the only instruction that depends on the address, the others are position independent.
# x0 = env (guest CPU state), w1 = sig (the upper half of x1 is not looked at). The line is built at sp.. (0x180 bytes), x0 / x1 are kept at sp+0x180.
STUB_X86_64 = [
    (0xd10103ff, 'sub sp, sp, #0x40'),
    (0xd10643ff, 'sub sp, sp, #0x190'),
    (0xa91807e0, 'stp x0, x1, [sp, #0x180]'),
    (0x910003e9, 'mov x9, sp'),
    (0xaa0903e2, 'mov x2, x9'),
    (0x100004a3, 'adr x3, #0x94'),
    (0x38401464, 'ldrb w4, [x3], #1'),
    (0x34000064, 'cbz w4, #0xc'),
    (0x38001444, 'strb w4, [x2], #1'),
    (0x17fffffd, 'b #0xfffffffffffffff4'),
    (0xd2800005, 'mov x5, #0'),
    (0x52800406, 'mov w6, #0x20'),
    (0x38001446, 'strb w6, [x2], #1'),
    (0xb5000065, 'cbnz x5, #0xc'),
    (0x2a0103e4, 'mov w4, w1'),
    (0x14000003, 'b #0xc'),
    (0xd10004a6, 'sub x6, x5, #1'),
    (0xf8667804, 'ldr x4, [x0, x6, lsl #3]'),
    (0x52800786, 'mov w6, #0x3c'),
    (0x9ac62487, 'lsr x7, x4, x6'),
    (0x12000ce7, 'and w7, w7, #0xf'),
    (0x710028ff, 'cmp w7, #0xa'),
    (0x52800608, 'mov w8, #0x30'),
    (0x52800aea, 'mov w10, #0x57'),
    (0x1a882148, 'csel w8, w10, w8, hs'),
    (0x0b0800e7, 'add w7, w7, w8'),
    (0x38001447, 'strb w7, [x2], #1'),
    (0x710010c6, 'subs w6, w6, #4'),
    (0x54fffeea, 'b.ge #0xffffffffffffffdc'),
    (0x910004a5, 'add x5, x5, #1'),
    (0xf1004cbf, 'cmp x5, #0x13'),
    (0x54fffd83, 'b.lo #0xffffffffffffffb0'),
    (0x52800146, 'mov w6, #0xa'),
    (0x38001446, 'strb w6, [x2], #1'),
    (0xcb090042, 'sub x2, x2, x9'),
    (0xaa0903e1, 'mov x1, x9'),
    (0xd2800040, 'mov x0, #2'),
    (0xd2800808, 'mov x8, #0x40'),
    (0xd4000001, 'svc #0'),
    (0xa95807e0, 'ldp x0, x1, [sp, #0x180]'),
    (0x910643ff, 'add sp, sp, #0x190'),
    (None, 'b <entry + 4>'),
    (0x756d6561, "data 'aemu'"),
    (0x6575672d, "data '-gue'"),
    (0x003a7473, "data 'st:'"),
]

# The same with 4-byte registers: 10 values (eax .. edi, eip, eflags) of 8 digits.
STUB_I386 = [
    (0xd10103ff, 'sub sp, sp, #0x40'),
    (0xd10643ff, 'sub sp, sp, #0x190'),
    (0xa91807e0, 'stp x0, x1, [sp, #0x180]'),
    (0x910003e9, 'mov x9, sp'),
    (0xaa0903e2, 'mov x2, x9'),
    (0x100004a3, 'adr x3, #0x94'),
    (0x38401464, 'ldrb w4, [x3], #1'),
    (0x34000064, 'cbz w4, #0xc'),
    (0x38001444, 'strb w4, [x2], #1'),
    (0x17fffffd, 'b #0xfffffffffffffff4'),
    (0xd2800005, 'mov x5, #0'),
    (0x52800406, 'mov w6, #0x20'),
    (0x38001446, 'strb w6, [x2], #1'),
    (0xb5000065, 'cbnz x5, #0xc'),
    (0x2a0103e4, 'mov w4, w1'),
    (0x14000003, 'b #0xc'),
    (0xd10004a6, 'sub x6, x5, #1'),
    (0xb8667804, 'ldr w4, [x0, x6, lsl #2]'),
    (0x52800386, 'mov w6, #0x1c'),
    (0x9ac62487, 'lsr x7, x4, x6'),
    (0x12000ce7, 'and w7, w7, #0xf'),
    (0x710028ff, 'cmp w7, #0xa'),
    (0x52800608, 'mov w8, #0x30'),
    (0x52800aea, 'mov w10, #0x57'),
    (0x1a882148, 'csel w8, w10, w8, hs'),
    (0x0b0800e7, 'add w7, w7, w8'),
    (0x38001447, 'strb w7, [x2], #1'),
    (0x710010c6, 'subs w6, w6, #4'),
    (0x54fffeea, 'b.ge #0xffffffffffffffdc'),
    (0x910004a5, 'add x5, x5, #1'),
    (0xf1002cbf, 'cmp x5, #0xb'),
    (0x54fffd83, 'b.lo #0xffffffffffffffb0'),
    (0x52800146, 'mov w6, #0xa'),
    (0x38001446, 'strb w6, [x2], #1'),
    (0xcb090042, 'sub x2, x2, x9'),
    (0xaa0903e1, 'mov x1, x9'),
    (0xd2800040, 'mov x0, #2'),
    (0xd2800808, 'mov x8, #0x40'),
    (0xd4000001, 'svc #0'),
    (0xa95807e0, 'ldp x0, x1, [sp, #0x180]'),
    (0x910643ff, 'add sp, sp, #0x190'),
    (None, 'b <entry + 4>'),
    (0x756d6561, "data 'aemu'"),
    (0x6575672d, "data '-gue'"),
    (0x003a7473, "data 'st:'"),
]

SITES = {
    # name: (entry of dump_core_and_abort, stub)
    "libqemu_x86_64.so": (0x11da6e8, STUB_X86_64),
    "libqemu_i386.so":   (0x11d937c, STUB_I386),
}

def patch(name, entry, stub):
    path = D + name
    d = bytearray(open(path, "rb").read())
    n = 4 * len(stub)
    # the entry: `sub sp, sp, #0x40 / stp x29, x30, [sp, #0x10]` (the function takes env in x0, sig in w1: mov x19, x0 / mov w20, w1 follow)
    assert word(d, entry + 4) == ORIG_NEXT, "%s: %#x is not the start of dump_core_and_abort (another build of the translator?)" % (name, entry)
    w = word(d, entry)
    if w != ORIG_ENTRY:
        assert (w & 0xfc000000) == 0x14000000, "%s: unexpected first instruction %#08x at %#x" % (name, w, entry)
        off = (w & 0x3ffffff) | (-(w & 0x2000000) & ~0x3ffffff)           # sign extend imm26
        cur = entry + 4 * off
        assert bytes(d[vaddr_to_off(d, cur):vaddr_to_off(d, cur) + n]) == stub_bytes(stub, cur, entry + 4), \
            "%s: the entry branches to %#x, which is not our stub" % (name, cur)
        print(name, "already patched (entry %#x -> stub at %#x)" % (entry, cur)); return

    # --- the room for the stub: zero padding behind the text segment
    ph = phdrs(d)
    loads = [p for p in ph if p[1] == 1]
    text = [p for p in loads if p[2] & 1]
    assert len(text) == 1, "%s: expected one executable PT_LOAD" % name
    tpos, _, _, toff, tva, _, tfsz, tmsz, _ = text[0]
    assert tfsz == tmsz
    nxt = min((p for p in loads if p[4] > tva), key=lambda p: p[4])             # the segment after the text in memory
    ins = nxt[3]                                                                # its file offset
    stub_va = (tva + tfsz + 15) & ~15
    stub_off = toff + (stub_va - tva)
    tls_end = max([p[4] + p[7] for p in ph if p[1] == 7 and p[4] >= tva] + [0])
    assert stub_va >= tls_end, "%s: the text ends inside the PT_TLS range (run patch_x86_translator_process_vm_readv.py first)" % name
    assert stub_off + n <= ins, "%s: no room behind the text segment (run patch_x86_translator_process_vm_readv.py first, it leaves 64 KiB)" % name
    assert stub_va + n <= (nxt[4] & ~0xffff), "%s: no room for the stub below the next segment in memory" % name
    assert not any(d[toff + tfsz:stub_off + n]), "%s: the bytes behind the text segment are not padding" % name
    new_end = stub_va + n

    out = bytearray(d)
    out[stub_off:stub_off + n] = stub_bytes(stub, stub_va, entry + 4)
    struct.pack_into("<Q", out, tpos + 32, new_end - tva)                      # p_filesz
    struct.pack_into("<Q", out, tpos + 40, new_end - tva)                      # p_memsz
    shoff = struct.unpack_from("<Q", out, 40)[0]; shsz = struct.unpack_from("<H", out, 58)[0]; shn = struct.unpack_from("<H", out, 60)[0]
    if shoff:
        for i in range(shn):
            sp = shoff + i * shsz
            sty, sfl, sad, sof, ssz = struct.unpack_from("<IQQQQ", out, sp + 4)
            if sad == tva and (sfl & 4):                                         # .text covers the stub too
                struct.pack_into("<Q", out, sp + 32, new_end - tva)
    struct.pack_into("<I", out, vaddr_to_off(out, entry), B(entry, stub_va))
    open(path, "wb").write(out)
    print(name, "patched: dump_core_and_abort %#x -> stub at %#x (%d bytes), text segment %#x..%#x" % (entry, stub_va, n, tva, new_end))

for name, site in SITES.items():
    patch(name, *site)
