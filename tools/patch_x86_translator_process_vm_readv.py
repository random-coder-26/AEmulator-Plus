"""Patches libqemu_x86_64.so and libqemu_i386.so (stock QEMU 8.2.7, see tools/qemu-zig) so that process_vm_readv is forwarded to the host.

Why: x86 / x86-64 Android needs process_vm_readv to boot (syscall 310 on x86-64, 347 on i386). User-mode QEMU has no case for it in
do_syscall1, so the guest gets -ENOSYS ("Function not implemented") and never gets past it. ARM Android does not make the call, and
the ARM translators (libqemu_arm.so, libqemu_kk*.so) are not touched. Neither is libqemu_kk_i386.so, the patched x86-32 translator of
the Android 1-7 engine (kk). Only the two translators of the native engine are changed here.

What: do_syscall1 dispatches through a jump table (ldrsw/adr/add/br) indexed by the guest syscall number (by number - 1 on i386); the
entries of the numbers QEMU does not implement all point at the "unsupported syscall" block. The entry of process_vm_readv now points
at a small stub that makes the host call itself (host nr 270, aarch64) and leaves through the function's own epilogue (stack-protector
check, register restore, ret) with the host result, which is already -errno on failure, in x0:
  * x86-64: struct iovec is the host's own, so the six arguments (x2..x7 of do_syscall1) are handed to the kernel as they are. Guest
    and host addresses are the same under the runner's `-B 0`, and the kernel does the EFAULT/EINVAL/ESRCH checks.
  * i386: struct iovec is {u32 base, u32 len}, 8 bytes, not the host's 16. The stub rejects flags != 0 and counts above UIO_MAXIOV
    (1024) with -EINVAL like the kernel, copies each guest vector into a stack buffer with a process_vm_readv on its own process (so an
    unreadable vector gives -EFAULT, not a SIGSEGV in the translator), widens the entries in place, from the last to the first, to the
    host layout and makes the real call. It uses 32 + 16 * (liovcnt + riovcnt) bytes of stack at most 32 KiB; the epilogue restores sp.
The stub only needs ~40 / ~270 bytes and the text segment is packed against the data segment in the file, so the file is grown by
0x10000 bytes (keeps the 64 KiB offset/vaddr congruence of the data segments): the stub goes at the end of the text segment (behind PT_TLS's virtual range), which
is made that much longer (p_filesz / p_memsz, the .text section size) and still ends far below the data segment's virtual address;
the program headers and section headers of everything after it get the new file offsets. No address of the program moves, so the other
tools/patch_*translator*.py scripts (all addresses are virtual) stay valid, in any order.
Run from the checkout root; every site is checked against the expected original bytes first. Idempotent.
"""
import struct
D = "app/src/main/jniLibs/arm64-v8a/"
GROW = 0x10000
NR_HOST = 270                      # aarch64 process_vm_readv

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

def stub_bytes(stub, at, epilogue):
    return b"".join(struct.pack("<I", B(at + 4 * i, epilogue) if w is None else w) for i, (w, _) in enumerate(stub))

# (word, assembly). `b <epilogue>` is the only instruction that depends on the address, the others are position independent.
# x86-64: do_syscall1(env=x0, num=w1, arg1..arg6 = x2..x7); host syscall: x8 = nr, x0..x5 = args
STUB_X86_64 = [
    (0xd28021c8, 'mov x8, #0x10e'),
    (0xaa0203e0, 'mov x0, x2'),
    (0xaa0303e1, 'mov x1, x3'),
    (0xaa0403e2, 'mov x2, x4'),
    (0xaa0503e3, 'mov x3, x5'),
    (0xaa0603e4, 'mov x4, x6'),
    (0xaa0703e5, 'mov x5, x7'),
    (0xd4000001, 'svc #0'),
    (None, 'b <epilogue>'),
]



# i386: do_syscall1(env=x0, num=w1, arg1..arg6 = w2..w7, abi_long); pid = w2, lvec = w3, lcnt = w4, rvec = w5, rcnt = w6, flags = w7.
# x19..x28 are saved by the prologue and restored by the epilogue; x9..x17 are scratch; sp is reset by the epilogue (mov sp, x29).
# Layout of the frame the stub makes: [sp, sp+32) two iovecs for the self-read, then the local array (16 * lcnt), then the remote one.
# `rd` (x9 = buffer, x10 = guest address, x11 = count; x27 = our pid): buffer <- guest array (8 * count bytes), then widened in place
# from the last entry to the first (the entry i of the result starts at 16 * i, the unread ones end at 8 * i: they never overlap).
STUB_I386 = [
    (0x35000527, 'cbnz w7, #+164'),
    (0x52808009, 'mov w9, #0x400'),
    (0x6b09009f, 'cmp w4, w9'),
    (0x540004c8, 'b.hi #+152'),
    (0x6b0900df, 'cmp w6, w9'),
    (0x54000488, 'b.hi #+144'),
    (0x2a0203f3, 'mov w19, w2'),
    (0x2a0303f4, 'mov w20, w3'),
    (0x2a0403f5, 'mov w21, w4'),
    (0x2a0503f6, 'mov w22, w5'),
    (0x2a0603f7, 'mov w23, w6'),
    (0xd37ceeb8, 'lsl x24, x21, #4'),
    (0xd37ceef9, 'lsl x25, x23, #4'),
    (0x8b19031a, 'add x26, x24, x25'),
    (0x9100835a, 'add x26, x26, #0x20'),
    (0xcb3a63ff, 'sub sp, sp, x26'),
    (0x91008318, 'add x24, x24, #0x20'),
    (0x8b3863f9, 'add x25, sp, x24'),
    (0x910083f8, 'add x24, sp, #0x20'),
    (0xd2801588, 'mov x8, #0xac'),
    (0xd4000001, 'svc #0'),
    (0x2a0003fb, 'mov w27, w0'),
    (0xaa1803e9, 'mov x9, x24'),
    (0x2a1403ea, 'mov w10, w20'),
    (0xaa1503eb, 'mov x11, x21'),
    (0x94000012, 'bl #+72'),
    (0xb50001c0, 'cbnz x0, #+56'),
    (0xaa1903e9, 'mov x9, x25'),
    (0x2a1603ea, 'mov w10, w22'),
    (0xaa1703eb, 'mov x11, x23'),
    (0x9400000d, 'bl #+52'),
    (0xb5000120, 'cbnz x0, #+36'),
    (0x2a1303e0, 'mov w0, w19'),
    (0xaa1803e1, 'mov x1, x24'),
    (0xaa1503e2, 'mov x2, x21'),
    (0xaa1903e3, 'mov x3, x25'),
    (0xaa1703e4, 'mov x4, x23'),
    (0xaa1f03e5, 'mov x5, xzr'),
    (0xd28021c8, 'mov x8, #0x10e'),
    (0xd4000001, 'svc #0'),
    (None, 'b <epilogue>'),
    (0x928002a0, 'mov x0, #-0x16'),
    (None, 'b <epilogue>'),
    (0xd37df16c, 'lsl x12, x11, #3'),
    (0xa90033e9, 'stp x9, x12, [sp]'),
    (0xa90133ea, 'stp x10, x12, [sp, #0x10]'),
    (0xaa1b03e0, 'mov x0, x27'),
    (0x910003e1, 'mov x1, sp'),
    (0x52800022, 'mov w2, #1'),
    (0x910043e3, 'add x3, sp, #0x10'),
    (0x52800024, 'mov w4, #1'),
    (0xaa1f03e5, 'mov x5, xzr'),
    (0xd28021c8, 'mov x8, #0x10e'),
    (0xd4000001, 'svc #0'),
    (0xeb0c001f, 'cmp x0, x12'),
    (0x54000141, 'b.ne #+40'),
    (0xb40000eb, 'cbz x11, #+28'),
    (0xd100056b, 'sub x11, x11, #1'),
    (0x8b0b0d2f, 'add x15, x9, x11, lsl #3'),
    (0x294045f0, 'ldp w16, w17, [x15]'),
    (0x8b0b112f, 'add x15, x9, x11, lsl #4'),
    (0xa90045f0, 'stp x16, x17, [x15]'),
    (0x17fffffa, 'b #-24'),
    (0xaa1f03e0, 'mov x0, xzr'),
    (0xd65f03c0, 'ret '),
    (0x928001a0, 'mov x0, #-0xe'),
    (0xd65f03c0, 'ret '),
]

SITES = {
    # name: (guest nr, table index = nr - bias, jump table, its base (the adr of the dispatch), bound of `cmp` (address, imm),
    #        default block (adrp x8 ..), epilogue, stub)
    "libqemu_x86_64.so": (310, 0, 0x10453a0, 0x11e5a8c, 0x11e5a6c, 0x1b7, 0x11e94fc, 0x11eaa70, STUB_X86_64),
    "libqemu_i386.so":   (347, 1, 0x1045a20, 0x11e6b68, 0x11e6b50, 0x1b6, 0x11ec088, 0x11ed534, STUB_I386),
}

def patch(name, nr, bias, table, base, cmp_at, bound, default, epilogue, stub):
    path = D + name
    d = bytearray(open(path, "rb").read())
    idx = nr - bias
    # the dispatch: cmp (num - bias), #bound / b.hi default / ... / adr x10, base / ldrsw x11,[x9,x8,lsl #2] / add x10,x10,x11
    c = word(d, cmp_at)
    assert (c & 0xffc0001f) == 0x7100001f, "%s: not a cmp w, #imm at %#x" % (name, cmp_at)
    assert ((c >> 10) & 0xfff) == bound, "%s: unexpected jump table bound %#x" % (name, (c >> 10) & 0xfff)
    assert idx <= bound, "%s: syscall %d is beyond the jump table" % (name, nr)
    assert word(d, base) == 0x1000000a, "%s: no 'adr x10, .' at %#x (another build of the translator?)" % (name, base)
    assert word(d, base + 4) == 0xb8a8792b, "%s: no ldrsw x11,[x9,x8,lsl #2] at %#x" % (name, base + 4)
    assert word(d, base + 8) == 0x8b0b014a, "%s: no add x10,x10,x11 at %#x" % (name, base + 8)
    # the default: adrp x8 / ldrb w8,[x8,#imm] / tbnz w8,#2 (LOG_UNIMP) / mov x0|w0,#-ENOSYS / b epilogue-ish
    w = [word(d, default + 4 * i) for i in range(4)]
    assert (w[0] & 0x9f00001f) == 0x90000008 and (w[1] & 0xffc003ff) == 0x39400108 and (w[2] & 0xfff8001f) == 0x37100008, "%s: %#x is not the unimplemented-syscall block" % (name, default)
    # the epilogue: adrp x8 / ldur x9,[x29,#-8] / ldr x8,[x8,#imm] / cmp x8,x9 / b.ne / mov sp,x29
    e = [word(d, epilogue + 4 * i) for i in range(6)]
    assert (e[0] & 0x9f00001f) == 0x90000008 and e[1] == 0xf85f83a9 and e[3] == 0xeb09011f and e[5] == 0x910003bf, "%s: %#x is not the do_syscall1 epilogue" % (name, epilogue)

    eo = vaddr_to_off(d, table) + 4 * idx
    cur = base + struct.unpack_from("<i", d, eo)[0]
    if cur != default:
        # already patched: the entry must lead to our stub, byte for byte
        n = 4 * len(stub)
        assert bytes(d[vaddr_to_off(d, cur):vaddr_to_off(d, cur) + n]) == stub_bytes(stub, cur, epilogue), "%s: entry %d points to %#x, neither the default nor our stub" % (name, nr, cur)
        print(name, "already patched (syscall %d -> %#x)" % (nr, cur)); return

    # --- grow the text segment
    ph = phdrs(d)
    loads = [p for p in ph if p[1] == 1]
    text = [p for p in loads if p[2] & 1]
    assert len(text) == 1, "%s: expected one executable PT_LOAD" % name
    tpos, _, _, toff, tva, _, tfsz, tmsz, _ = text[0]
    assert tfsz == tmsz
    nxt = min((p for p in loads if p[4] > tva), key=lambda p: p[4])             # the segment after the text in memory
    ins = nxt[3]                                                                # its file offset: the new bytes go here
    assert toff + tfsz <= ins, "%s: the file does not continue with the next segment at the end of the text" % name
    # the stub goes behind the text and behind PT_TLS, whose (file-less) .tbss range follows the text in the address space
    lo = max([tva + tfsz] + [p[4] + p[7] for p in ph if p[1] == 7 and p[4] >= tva])
    stub_va = (lo + 15) & ~15
    stub_off = toff + (stub_va - tva)
    n = 4 * len(stub)
    assert ins <= stub_off and stub_off + n <= ins + GROW, "%s: stub does not fit the new bytes" % name
    assert stub_va + n <= (nxt[4] & ~0xffff), "%s: no room for the stub below the next segment in memory" % name
    assert not any(p[1] != 1 and toff < p[3] < ins for p in ph), "%s: a non-load segment inside the text" % name
    assert not any(d[toff + tfsz:ins]), "%s: the bytes between the text and the next segment are not padding" % name
    new_end = stub_va + n

    # file: [.. ins) + zeros + stub + zeros up to GROW + [ins ..); text vaddr -> file offset keeps the same linear map
    shoff = struct.unpack_from("<Q", d, 40)[0]; shsz = struct.unpack_from("<H", d, 58)[0]; shn = struct.unpack_from("<H", d, 60)[0]
    out = bytearray(d[:ins]) + bytes(stub_off - ins) + stub_bytes(stub, stub_va, epilogue) + bytes(ins + GROW - stub_off - n) + d[ins:]
    for pos, t, fl, off, vad, pa, fsz, msz, al in ph:
        if pos == tpos:
            struct.pack_into("<Q", out, pos + 32, new_end - tva)               # p_filesz
            struct.pack_into("<Q", out, pos + 40, new_end - tva)               # p_memsz
        elif off >= ins:
            struct.pack_into("<Q", out, pos + 8, off + GROW)
    if shoff:
        assert shoff >= ins, "%s: section headers before the grown point" % name
        struct.pack_into("<Q", out, 40, shoff + GROW)
        for i in range(shn):
            sp = shoff + GROW + i * shsz
            sty, sfl, sad, sof, ssz = struct.unpack_from("<IQQQQ", out, sp + 4)
            if sof >= ins:
                struct.pack_into("<Q", out, sp + 24, sof + GROW)
            if sad == tva and (sfl & 4):                                         # .text covers the stub too
                struct.pack_into("<Q", out, sp + 32, new_end - tva)
    # the jump table entry
    ro = vaddr_to_off(out, table) + 4 * idx
    struct.pack_into("<i", out, ro, stub_va - base)
    open(path, "wb").write(out)
    print(name, "patched: syscall %d -> stub at %#x (%d bytes), text segment %#x..%#x, file grown by %#x" % (nr, stub_va, n, tva, new_end, GROW))

for name, site in SITES.items():
    patch(name, *site)
