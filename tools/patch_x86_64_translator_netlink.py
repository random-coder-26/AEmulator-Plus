"""Patches libqemu_x86_64.so (stock QEMU 8.2.7) so socket(AF_NETLINK, ..., any protocol) is passed on to the host instead of refused.

Why: Android 10's netd needs netlink sockets of the families NETLINK_XFRM (6), NETLINK_NFLOG (5) and NETLINK_NETFILTER (12):
    E Netd: Unable to create netlink socket for family 5: Protocol not supported
    E Netd: Unable to create netlink socket for family 12: Protocol not supported
    E Netd: Unable to create NFLogListener: Status[code: 93, ...]            -> netd exits with code 1, 12 restarts, system_server's
    W NetdService: WARNING: returning null INetd instance                       watchdog kills it (SIGKILL), zygote exits (137)
do_socket() (0x11ee824) answers every protocol except NETLINK_ROUTE (0), NETLINK_AUDIT (9) and NETLINK_KOBJECT_UEVENT (15) with
-EPROTONOSUPPORT (93) before any host call. The phone's SELinux would refuse those families to an app anyway; the engine's runner
(native/guest64/<api>/run64.c, case NR_socket) already answers a refused non-routing netlink socket with a socketpair that it serves
itself (nl_register / nl_handle_send: bind, getsockname, setsockopt succeed, a request gets its acknowledgement, a dump ends at once).
That code only ever sees the call if the translator makes it.

What (two instructions):
  * 0x11ee864 `b.hi 0x11ee9c0` (protocol > 15 -> -EPROTONOSUPPORT) becomes `b 0x11ee894`: the 0x8201 bit test behind it is skipped, every
    protocol goes on to the host socket() call.
  * 0x11ee988 `cbnz w20, 0x11eea04` is the `default: g_assert_not_reached()` of the switch that registers the fd translators after a
    successful netlink socket() (route, audit; uevent needs none). It now goes to the common exit 0x11ee9bc: no translator, the messages
    pass unchanged (nlmsghdr and the payload layouts are the same for an x86-64 guest on an aarch64 host).
NETLINK_ROUTE keeps its translator and its existing handling. Only libqemu_x86_64.so (the 64-bit programs); libqemu_i386.so is not touched.
Run from the checkout root; every site is checked against the expected original instruction first. Idempotent.
"""
import struct
P = "app/src/main/jniLibs/arm64-v8a/libqemu_x86_64.so"

def vaddr_to_off(d, va):
    phoff = struct.unpack_from("<Q", d, 32)[0]; phsz = struct.unpack_from("<H", d, 54)[0]; phn = struct.unpack_from("<H", d, 56)[0]
    for i in range(phn):
        t, fl, off, vad, _, filesz = struct.unpack_from("<IIQQQQ", d, phoff + i * phsz)
        if t == 1 and (fl & 1) and vad <= va < vad + filesz:
            return off + va - vad
    raise SystemExit("address not in an executable PT_LOAD segment: %#x" % va)

def B(f, t):        return 0x14000000 | (((t - f) // 4) & 0x3ffffff)
def BHI(f, t):      return 0x54000008 | ((((t - f) // 4) & 0x7ffff) << 5)
def CBNZ_W(f, t, r): return 0x35000000 | ((((t - f) // 4) & 0x7ffff) << 5) | r

# (address, expected original, replacement)
EDITS = [
    (0x11ee864, BHI(0x11ee864, 0x11ee9c0),     B(0x11ee864, 0x11ee894)),
    (0x11ee988, CBNZ_W(0x11ee988, 0x11eea04, 20), CBNZ_W(0x11ee988, 0x11ee9bc, 20)),
]
d = bytearray(open(P, "rb").read())
done = 0
for va, old, new in EDITS:
    o = vaddr_to_off(d, va)
    cur = struct.unpack_from("<I", d, o)[0]
    if cur == new: done += 1; continue
    assert cur == old, "%#x: expected %#010x, found %#010x (not the translator this patch was made for)" % (va, old, cur)
for va, old, new in EDITS:
    o = vaddr_to_off(d, va)
    if struct.unpack_from("<I", d, o)[0] == old:
        struct.pack_into("<I", d, o, new)
if done == len(EDITS):
    print("already patched")
else:
    open(P, "wb").write(d)
    print("patched: socket(AF_NETLINK, *, any protocol) is passed to the host (2 instructions)")
