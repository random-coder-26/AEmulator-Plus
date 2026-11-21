"""Patches libqemu_arm_pre1.0.so (run from the checkout root, after tools/make_pre1_translator.py). Idempotent; every byte is checked first.

Why: JamVM (runtime.vm=jam, pre-1.0 builds such as HTC 20645) stops threads for GC by sending SIGUSR1 (pthread_kill) and waiting for
the handler to run. The system process's main thread sits in the binder "wait for work" read (BINDER_WRITE_READ, linux-user/dhd_binder.c).
That wait is a poll() loop (helper at 0x1e8e64) that retries on EINTR without going back to the guest, so qemu never delivers the guest
signal. The GC thread then spins on that thread for ever ("spinning on suspend: thread id 1 (bl=0 st=2)"), the VM stalls and
ActivityManager never starts the launcher. A real kernel binder driver is interrupted by the signal. Dalvik suspends by polling and never hit this.

What: in the helper, EINTR during the 8 s work wait (caller 0x16aac4, timeout 0x1f40) now ends the wait with the helper's own timeout
result (-2). The caller already handles that: it puts BR_NOOP in the read buffer, re-arms binderd (request 0xc) and returns 0, so libbinder
loops, and qemu delivers the pending guest signal on the way back to the cpu loop. The 30 s send-confirmation wait (0x7530) still retries on EINTR.
Room: the debug log of the helper's poll-timeout branch (0x1e8f10..0x1e8f4c) is dropped; its loop logic is rewritten in 4 words and
the rest of the block holds the new EINTR code.
"""
from keystone import Ks, KS_ARCH_ARM64, KS_MODE_LITTLE_ENDIAN
import sys
P = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/jniLibs/arm64-v8a/libqemu_arm_pre1.0.so"
d = bytearray(open(P, "rb").read())
ks = Ks(KS_ARCH_ARM64, KS_MODE_LITTLE_ENDIAN)
def asm(addr, text):
    enc, _ = ks.asm(text, addr)
    return bytes(enc)

# ---- original code (as in libqemu_kk.so / libqemu_arm_pre1.0.so)
ORIG = {0x1e8ef0: asm(0x1e8ef0, "b.ne 0x1e8f58"),             # poll failed, not EINTR: return -1; EINTR falls into the poll retry
        0x1e8f10: asm(0x1e8f10, "sub w8, w19, w22")}          # start of the log argument setup
assert d[0x1e8f50:0x1e8f54] == bytes.fromhex("20008012"), "not the expected build (mov w0, #-2)"
assert d[0x1e8ee4:0x1e8ef0] == bytes.fromhex("8e070294000040b91f100071"), "not the expected build (EINTR test)"
assert d[0x1e8f0c:0x1e8f10] == asm(0x1e8f0c, "b.ne 0x1e8f98"), "not the expected build (poll result test)"

new_ef0 = asm(0x1e8ef0, "b 0x1e8f20")
new_block = (asm(0x1e8f10, "sub w22, w22, w19") +             # remaining -= slice
             asm(0x1e8f14, "cmp w22, #0") +
             asm(0x1e8f18, "b.gt 0x1e8ed0") +                 # more time: poll again
             asm(0x1e8f1c, "b 0x1e8f50") +                    # none left: -2 as before
             asm(0x1e8f20, "b.ne 0x1e8f58") +                 # (flags of 'cmp w0, #4') not EINTR: -1 as before
             asm(0x1e8f24, "lsr w0, w24, #12") +              # total timeout >> 12: 0x1f40 -> 1, 0x7530 -> 7
             asm(0x1e8f28, "cmp w0, #1") +
             asm(0x1e8f2c, "b.eq 0x1e8f50") +                 # work wait: give up now, the caller returns BR_NOOP to the guest
             asm(0x1e8f30, "b 0x1e8ef4"))                     # any other wait: retry the poll as before
new_block += bytes.fromhex("1f2003d5") * ((0x1e8f50 - 0x1e8f10 - len(new_block)) // 4)
assert len(new_block) == 0x1e8f50 - 0x1e8f10

if d[0x1e8ef0:0x1e8ef4] == new_ef0 and d[0x1e8f10:0x1e8f50] == new_block:
    print("already patched"); sys.exit(0)
assert d[0x1e8ef0:0x1e8ef4] == ORIG[0x1e8ef0], "EINTR branch is not the expected one"
assert d[0x1e8f10:0x1e8f14] == ORIG[0x1e8f10], "poll-timeout block is not the expected one"
assert d[0x1e8f4c:0x1e8f50] == asm(0x1e8f4c, "b.gt 0x1e8ed0"), "poll-timeout loop branch is not the expected one"

# nothing else may jump into the rewritten block
import capstone
cs = capstone.Cs(capstone.CS_ARCH_ARM64, capstone.CS_MODE_ARM)
off, end = 0x30e30, 0x288cf4
for i in cs.disasm(bytes(d[off:end]), off):
    if i.mnemonic in ("b", "bl") or i.mnemonic.startswith("b.") or i.mnemonic in ("cbz", "cbnz", "tbz", "tbnz"):
        t = int(i.op_str.split("#")[-1], 16)
        if 0x1e8f14 <= t < 0x1e8f50 and not (0x1e8f10 <= i.address < 0x1e8f50):
            sys.exit("unexpected jump into the block from %#x" % i.address)

d[0x1e8ef0:0x1e8ef4] = new_ef0
d[0x1e8f10:0x1e8f50] = new_block
open(P, "wb").write(d)
print("ok")
