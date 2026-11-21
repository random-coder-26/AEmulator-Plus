"""Runs the patched libqemu_x86_64.so / libqemu_i386.so (tools/patch_x86_translator_process_vm_readv.py) in Unicorn: the program is
mapped from its program headers the way the kernel does, do_syscall1 is entered at its first instruction with the guest syscall number
and arguments in the registers QEMU passes them in, the jump table, the stub and the function's epilogue run for real, and the
host system calls (svc) are answered by a small model of the kernel's getpid / process_vm_readv.

    pip install unicorn
    python3 tools/test_x86_translator_process_vm_readv.py        (from the checkout root)

Checks per translator: the data of the image is byte for byte the one of the unpatched file (nothing but the one jump table entry and
the headers changed), process_vm_readv gets the host call with the right arguments and result, errors come back as -errno, i386 vectors
are widened, bad guest vectors give -EFAULT, a neighbouring syscall and the other unimplemented one (process_vm_writev) still behave as
before, and sp / x19..x30 are balanced.
"""
import struct, sys
from unicorn import *
from unicorn.arm64_const import *

D = "app/src/main/jniLibs/arm64-v8a/"
ORIG = sys.argv[1] if len(sys.argv) > 1 else None       # optional directory with the unpatched files (extra image comparison)
ENOSYS, EINVAL, EFAULT, ESRCH = 38, 22, 14, 3
SELF, OTHER = 1234, 4321
SENT = 0x2000                                           # return address of do_syscall1
STACK_TOP = 0x7f100000

def segments(d):
    phoff = struct.unpack_from("<Q", d, 32)[0]; phsz = struct.unpack_from("<H", d, 54)[0]; phn = struct.unpack_from("<H", d, 56)[0]
    return [p for p in (struct.unpack_from("<IIQQQQQQ", d, phoff + i * phsz) for i in range(phn)) if p[0] == 1]   # type, flags, off, va, pa, fsz, msz, al

def image(d):
    """{vaddr: byte} would be big; return the mapped regions as (va, bytes) after zero filling memsz"""
    return [(va, bytes(d[off:off + fsz]) + bytes(msz - fsz)) for _, _, off, va, _, fsz, msz, _ in segments(d)]

class Emu:
    def __init__(self, path, remote):
        self.d = open(path, "rb").read()
        self.uc = Uc(UC_ARCH_ARM64, UC_MODE_ARM)
        for va, data in image(self.d):
            lo = va & ~0xfff; hi = (va + len(data) + 0xfff) & ~0xfff
            try: self.uc.mem_map(lo, hi - lo)
            except UcError: pass                                 # page shared with the previous segment
        for va, data in image(self.d): self.uc.mem_write(va, data)
        self.uc.mem_map(SENT & ~0xfff, 0x1000)
        self.uc.mem_map(STACK_TOP - 0x100000, 0x100000)
        self.uc.mem_map(0x10000000, 0x400000)                    # "guest" memory: vectors, buffers
        self.remote = remote                                     # {pid: {addr: bytes}} memory of the other processes
        self.calls = []
        self.uc.hook_add(UC_HOOK_INTR, self.svc)

    def rd(self, r): return self.uc.reg_read(r)
    def svc(self, uc, intno, user):
        nr = self.rd(UC_ARM64_REG_X8); a = [self.rd(UC_ARM64_REG_X0 + i) for i in range(6)]
        if nr == 172: r = SELF
        elif nr == 270: r = self.pvr(*a); self.calls.append((nr, a, r))
        else: raise AssertionError("unexpected host syscall %d" % nr)
        uc.reg_write(UC_ARM64_REG_X0, r & 0xffffffffffffffff)

    def iovs(self, p, n):
        return [struct.unpack("<QQ", self.uc.mem_read(p + 16 * i, 16)) for i in range(n)]

    def pvr(self, pid, lv, lc, rv, rc, flags):            # the kernel's process_vm_readv (read side), simplified
        pid &= 0xffffffff
        if flags != 0: return -EINVAL
        if lc > 1024 or rc > 1024: return -EINVAL
        try: L = self.iovs(lv, lc)
        except UcError: return -EFAULT
        if sum(l for _, l in L) == 0: return 0
        try: R = self.iovs(rv, rc)
        except UcError: return -EFAULT
        if pid != SELF and pid not in self.remote: return -ESRCH
        src = bytearray(); fault = False
        for base, ln in R:
            for i in range(ln):
                if pid == SELF:
                    try: src += self.uc.mem_read(base + i, 1)
                    except UcError: fault = True; break
                else:
                    for a, b in self.remote[pid].items():
                        if a <= base + i < a + len(b): src.append(b[base + i - a]); break
                    else: fault = True; break
            if fault: break
        n = 0
        for base, ln in L:
            chunk = bytes(src[n:n + ln])
            if not chunk: break
            try: self.uc.mem_write(base, chunk)
            except UcError: break
            n += len(chunk)
        return n if n else (-EFAULT if fault else 0)

    def syscall(self, nr, args):
        """do_syscall1(env, num, arg1..arg6): returns (x0 as signed 64 bit, balanced?)"""
        uc = self.uc
        sp = STACK_TOP
        init = {r: 0x1111000 + i for i, r in enumerate(range(UC_ARM64_REG_X19, UC_ARM64_REG_X29))}
        for r, v in init.items(): uc.reg_write(r, v)
        uc.reg_write(UC_ARM64_REG_X29, 0x2222222); uc.reg_write(UC_ARM64_REG_SP, sp); uc.reg_write(UC_ARM64_REG_X30, SENT)
        uc.reg_write(UC_ARM64_REG_X0, 0x7e000000)                              # env: not touched by the paths tested
        uc.reg_write(UC_ARM64_REG_X1, nr)
        for i, a in enumerate(args): uc.reg_write(UC_ARM64_REG_X2 + i, a)
        self.calls = []
        uc.emu_start(self.entry, SENT, count=2000000)
        x0 = self.rd(UC_ARM64_REG_X0)
        ok = (self.rd(UC_ARM64_REG_SP) == sp and self.rd(UC_ARM64_REG_X29) == 0x2222222 and
              all(self.rd(r) == v for r, v in init.items()))
        return struct.unpack("<q", struct.pack("<Q", x0))[0], ok

fails = 0
def check(name, cond, extra=""):
    global fails
    print("  %-62s %s %s" % (name, "ok" if cond else "FAIL", extra))
    if not cond: fails += 1

G = 0x10000000
def put(e, addr, data): e.uc.mem_write(addr, data)
def get(e, addr, n): return bytes(e.uc.mem_read(addr, n))

def test_x86_64():
    print("libqemu_x86_64.so (syscall 310)")
    A, B = b"hello-from-other-process", b"second chunk"
    e = Emu(D + "libqemu_x86_64.so", {OTHER: {0x500000: A + bytes(8), 0x600000: B}})
    e.entry = 0x11e5a48
    lv, rv = G, G + 0x100
    put(e, lv, struct.pack("<QQQQ", G + 0x1000, len(A), G + 0x2000, len(B)))
    put(e, rv, struct.pack("<QQQQ", 0x500000, len(A), 0x600000, len(B)))
    r, ok = e.syscall(310, [OTHER, lv, 2, rv, 2, 0])
    check("read of two vectors returns the byte count", r == len(A) + len(B), "ret=%d" % r)
    check("data arrived", get(e, G + 0x1000, len(A)) == A and get(e, G + 0x2000, len(B)) == B)
    check("host call: nr 270, pid/vectors/counts/flags as given", e.calls == [(270, [OTHER, lv, 2, rv, 2, 0], len(A) + len(B))], str(e.calls))
    check("sp, x19..x29 restored", ok)
    r, ok = e.syscall(310, [99999, lv, 2, rv, 2, 0]); check("unknown pid -> -ESRCH", r == -ESRCH, "ret=%d" % r)
    r, ok = e.syscall(310, [OTHER, lv, 2, rv, 2, 1]);  check("flags != 0 -> -EINVAL", r == -EINVAL, "ret=%d" % r)
    r, ok = e.syscall(310, [OTHER, lv, 2, 0x10, 2, 0]); check("bad remote vector -> -EFAULT", r == -EFAULT, "ret=%d" % r)
    r, ok = e.syscall(311, [OTHER, lv, 2, rv, 2, 0]);  check("process_vm_writev (311) is still unimplemented -> -ENOSYS", r == -ENOSYS and not e.calls, "ret=%d" % r)
    r, ok = e.syscall(39, []);  check("neighbouring syscall (getpid, 39) unchanged", r == SELF, "ret=%d" % r)
    return e

def test_i386():
    print("libqemu_i386.so (syscall 347)")
    A, B = b"hello-from-other-process", b"second chunk"
    e = Emu(D + "libqemu_i386.so", {OTHER: {0x500000: A + bytes(8), 0x600000: B}})
    e.entry = 0x11e6b20
    lv, rv = G + 0x40, G + 0x140
    put(e, lv, struct.pack("<IIII", G + 0x1000, len(A), G + 0x2000, len(B)))      # i386 iovec: {u32 base, u32 len}
    put(e, rv, struct.pack("<IIII", 0x500000, len(A), 0x600000, len(B)))
    # abi_long arguments arrive in 64 bit registers whose upper half QEMU leaves as it is: use junk there
    junk = lambda v: v | 0xdeadbeef00000000
    r, ok = e.syscall(347, [junk(OTHER), junk(lv), junk(2), junk(rv), junk(2), junk(0)])
    check("read of two vectors returns the byte count", r & 0xffffffff == len(A) + len(B), "ret=%#x" % r)
    check("data arrived", get(e, G + 0x1000, len(A)) == A and get(e, G + 0x2000, len(B)) == B)
    check("sp, x19..x29 restored", ok)
    nr, a, res = e.calls[-1]
    li = e.uc  # the final call: vectors are in the host layout (16 bytes per entry), counts/flags zero-extended
    check("two host calls to read the vectors, then the real one", len(e.calls) == 3, str([c[1][2:5:2] for c in e.calls]))
    check("real call: pid, counts 2/2, flags 0", a[0] == OTHER and a[2] == 2 and a[4] == 2 and a[5] == 0, str(a))
    r, ok = e.syscall(347, [OTHER, lv, 2, rv, 2, 1]);  check("flags != 0 -> -EINVAL, no host call", r == -EINVAL and not e.calls, "ret=%d" % r)
    r, ok = e.syscall(347, [OTHER, lv, 1025, rv, 2, 0]); check("liovcnt > 1024 -> -EINVAL, no host call", r == -EINVAL and not e.calls and ok)
    r, ok = e.syscall(347, [OTHER, lv, 2, rv, 1025, 0]); check("riovcnt > 1024 -> -EINVAL, no host call", r == -EINVAL and not e.calls and ok)
    r, ok = e.syscall(347, [OTHER, 0x10, 2, rv, 2, 0]);  check("unreadable local vector -> -EFAULT (no fault in the translator)", r == -EFAULT and ok, "ret=%d" % r)
    r, ok = e.syscall(347, [OTHER, lv, 2, 0x10, 2, 0]);  check("unreadable remote vector -> -EFAULT", r == -EFAULT and ok, "ret=%d" % r)
    r, ok = e.syscall(347, [OTHER, lv, 2, G + 0x400000 - 8, 2, 0])
    check("vector that runs out of the mapped memory -> -EFAULT", r == -EFAULT and ok, "ret=%d" % r)
    r, ok = e.syscall(347, [99999, lv, 2, rv, 2, 0]);  check("unknown pid -> -ESRCH", r == -ESRCH and ok, "ret=%d" % r)
    r, ok = e.syscall(347, [OTHER, lv, 0, rv, 0, 0]);  check("zero counts -> 0", r == 0 and ok, "ret=%d" % r)
    # a vector that is entirely remote-readable only in part (second remote range unknown): the kernel reports the short count
    put(e, rv, struct.pack("<IIII", 0x500000, len(A), 0x700000, len(B)))
    r, ok = e.syscall(347, [OTHER, lv, 2, rv, 2, 0]);  check("short read is passed through", r == len(A) and ok, "ret=%d" % r)
    # 1024 + 1024 entries: widest frame
    n = 1024; lv2, rv2 = G + 0x10000, G + 0x20000
    put(e, lv2, b"".join(struct.pack("<II", G + 0x100000 + i * 4, 4) for i in range(n)))
    put(e, rv2, b"".join(struct.pack("<II", 0x800000 + i * 4, 4) for i in range(n)))
    e.remote[OTHER][0x800000] = bytes(range(256)) * 16
    r, ok = e.syscall(347, [OTHER, lv2, n, rv2, n, 0])
    check("1024 entries each: 4096 bytes, widened in order", r == 4096 and get(e, G + 0x100000, 4096) == bytes(range(256)) * 16 and ok, "ret=%d" % r)
    r, ok = e.syscall(348, [OTHER, lv, 2, rv, 2, 0]);  check("process_vm_writev (348) is still unimplemented -> -ENOSYS", r & 0xffffffff == (-ENOSYS) & 0xffffffff and not e.calls, "ret=%d" % r)
    r, ok = e.syscall(20, []);  check("neighbouring syscall (getpid, 20) unchanged", r & 0xffffffff == SELF, "ret=%d" % r)
    return e

def same_image(name):
    if not ORIG: return
    a = image(open(ORIG + "/" + name, "rb").read()); b = image(open(D + name, "rb").read())
    diff = []
    for (va, da), (vb, db) in zip(a, b):
        assert va == vb
        db = db[:len(da)]                                             # text: only the original part is compared, the stub follows it
        diff += [va + i for i in range(len(da)) if da[i] != db[i] and va + i >= 0x1000240]    # (the ELF and program headers differ)
    check("%s: image of the unpatched file unchanged except %d byte(s) (the jump table entry)" % (name, len(diff)), 1 <= len(diff) <= 4, str([hex(x) for x in diff]))

e = test_x86_64(); same_image("libqemu_x86_64.so")
e = test_i386();   same_image("libqemu_i386.so")
print("FAILED: %d" % fails if fails else "all checks passed")
sys.exit(1 if fails else 0)
