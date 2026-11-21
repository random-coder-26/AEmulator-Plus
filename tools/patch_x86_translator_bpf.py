#!/usr/bin/env python3
"""Patches libqemu_x86_64.so (stock QEMU 8.2.7, see tools/qemu-zig) so that the bpf() system call is forwarded to the host.

Added for AEmulator Plus (2026-11-15).

Why: netd of Android 12+ opens its pinned eBPF maps with bpf(BPF_OBJ_GET) and exits when it cannot:
    netd: Failed to start trafficcontroller: Pinned map not accessible or does not exist: (/sys/fs/bpf/map_netd_cookie_tag_map): Function not implemented
    netd: CRITICAL: sleeping 60 seconds, netd exiting with failure, crash loop likely!       (ClatdController: getClatEgress4MapFd() failure: ...)
User-mode QEMU has no case for bpf in do_syscall1: the number (321 on x86-64) is in the "unsupported syscall" block and the guest gets -ENOSYS
without any host call, so the eBPF stand-in of the runner (native/guest64/<api>/run64.c, `case 280`, which catches the host call in its
seccomp filter) never sees it. An arm64 guest does not have the problem (its calls go to the runner directly); a translated x86-64 guest does.

What: the jump-table entry of syscall 321 in do_syscall1 now points at a stub that makes the host call itself (aarch64 bpf = 280; cmd, attr and
size are the guest's three arguments as they are: struct bpf_attr has the same layout on both, and guest and host addresses are the same under the
runner's -B 0) and leaves through the function's own epilogue with the result (-errno on failure) in x0:
        mov x8, #280 ; mov x0, x2 ; mov x1, x3 ; mov x2, x4 ; svc #0 ; b <epilogue>
Same method and the same sites as tools/patch_x86_translator_process_vm_readv.py (run that first: it leaves 64 KiB of padding behind the text
segment; the stub goes after the stubs of the other scripts and the text segment grows by its size, no address of the program moves).
libqemu_i386.so is not touched (a 32-bit guest passes 32-bit pointers inside struct bpf_attr's aligned u64 fields: not the same call).
Every site is checked against the expected original words first; a file that is neither original nor completely patched is refused. Idempotent.

Usage (from the checkout root):  python3 tools/patch_x86_translator_bpf.py          or: python3 patch_x86_translator_bpf.py libqemu_x86_64.so
Check with:  python3 tools/test_x86_translator_bpf.py
"""
import os, struct, sys
D = "app/src/main/jniLibs/arm64-v8a/"
NR_GUEST, NR_HOST = 321, 280
TABLE, BASE, CMP_AT, BOUND, DEFAULT, EPILOGUE = 0x10453a0, 0x11e5a8c, 0x11e5a6c, 0x1b7, 0x11e94fc, 0x11eaa70   # as in the process_vm_readv script

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

STUB = [(0xd2800008 | (NR_HOST << 5), "mov x8, #%d" % NR_HOST), (0xaa0203e0, "mov x0, x2"), (0xaa0303e1, "mov x1, x3"),
        (0xaa0403e2, "mov x2, x4"), (0xd4000001, "svc #0"), (None, "b <epilogue>")]
def stub_bytes(at): return b"".join(struct.pack("<I", B(at + 4 * i, EPILOGUE) if w is None else w) for i, (w, _) in enumerate(STUB))

def patch(path):
    name = os.path.basename(path)
    if name != "libqemu_x86_64.so":
        sys.exit("%s: only libqemu_x86_64.so (see the note on libqemu_i386.so above)" % path)
    d = bytearray(open(path, "rb").read())
    if d[:4] != b"\x7fELF" or struct.unpack_from("<H", d, 18)[0] != 183:
        sys.exit("%s: not an aarch64 ELF" % path)
    c = word(d, CMP_AT)
    assert (c & 0xffc0001f) == 0x7100001f and ((c >> 10) & 0xfff) == BOUND, "%s: not the jump table bound of do_syscall1 at %#x" % (name, CMP_AT)
    assert word(d, BASE) == 0x1000000a and word(d, BASE + 4) == 0xb8a8792b and word(d, BASE + 8) == 0x8b0b014a, "%s: no jump table dispatch at %#x" % (name, BASE)
    w = [word(d, DEFAULT + 4 * i) for i in range(3)]
    assert (w[0] & 0x9f00001f) == 0x90000008 and (w[1] & 0xffc003ff) == 0x39400108 and (w[2] & 0xfff8001f) == 0x37100008, "%s: %#x is not the unimplemented-syscall block" % (name, DEFAULT)
    e = [word(d, EPILOGUE + 4 * i) for i in range(6)]
    assert (e[0] & 0x9f00001f) == 0x90000008 and e[1] == 0xf85f83a9 and e[3] == 0xeb09011f and e[5] == 0x910003bf, "%s: %#x is not the do_syscall1 epilogue" % (name, EPILOGUE)
    assert NR_GUEST <= BOUND

    eo = vaddr_to_off(d, TABLE) + 4 * NR_GUEST
    cur = BASE + struct.unpack_from("<i", d, eo)[0]
    if cur != DEFAULT:
        n = 4 * len(STUB)
        assert bytes(d[vaddr_to_off(d, cur):vaddr_to_off(d, cur) + n]) == stub_bytes(cur), "%s: entry %d points to %#x, neither the default nor our stub" % (name, NR_GUEST, cur)
        print("%s: already patched (bpf %d -> stub at %#x)" % (name, NR_GUEST, cur)); return

    ph = phdrs(d)
    loads = [p for p in ph if p[1] == 1]
    text = [p for p in loads if p[2] & 1]
    assert len(text) == 1, "%s: expected one executable PT_LOAD" % name
    tpos, _, _, toff, tva, _, tfsz, tmsz, _ = text[0]
    assert tfsz == tmsz
    nxt = min((p for p in loads if p[4] > tva), key=lambda p: p[4])
    ins = nxt[3]
    n = 4 * len(STUB)
    stub_va = (tva + tfsz + 15) & ~15
    stub_off = toff + (stub_va - tva)
    tls_end = max([p[4] + p[7] for p in ph if p[1] == 7 and p[4] >= tva] + [0])
    assert stub_va >= tls_end, "%s: the text ends inside the PT_TLS range (run patch_x86_translator_process_vm_readv.py first)" % name
    assert stub_off + n <= ins, "%s: no room behind the text segment (run patch_x86_translator_process_vm_readv.py first, it leaves 64 KiB)" % name
    assert stub_va + n <= (nxt[4] & ~0xffff), "%s: no room for the stub below the next segment in memory" % name
    assert not any(d[toff + tfsz:stub_off + n]), "%s: the bytes behind the text segment are not padding" % name
    new_end = stub_va + n

    out = bytearray(d)
    out[stub_off:stub_off + n] = stub_bytes(stub_va)
    struct.pack_into("<i", out, vaddr_to_off(out, TABLE) + 4 * NR_GUEST, stub_va - BASE)
    struct.pack_into("<Q", out, tpos + 32, new_end - tva)                       # text: p_filesz
    struct.pack_into("<Q", out, tpos + 40, new_end - tva)                       #       p_memsz
    shoff = struct.unpack_from("<Q", out, 40)[0]; shsz = struct.unpack_from("<H", out, 58)[0]; shn = struct.unpack_from("<H", out, 60)[0]
    for i in range(shn if shoff else 0):
        sp = shoff + i * shsz
        sty, sfl, sad, sof, ssz = struct.unpack_from("<IQQQQ", out, sp + 4)
        if sad == tva and (sfl & 4):                                             # .text covers the stub too
            struct.pack_into("<Q", out, sp + 32, new_end - tva)
    tmp = path + ".tmp"
    open(tmp, "wb").write(out)
    os.chmod(tmp, os.stat(path).st_mode & 0o7777)
    os.replace(tmp, path)
    print("%s: patched: bpf (%d) -> stub at %#x (%d bytes, host syscall %d), text segment now %#x..%#x" % (name, NR_GUEST, stub_va, n, NR_HOST, tva, new_end))

if __name__ == "__main__":
    for a in sys.argv[1:] or [D + "libqemu_x86_64.so"]:
        patch(a)
