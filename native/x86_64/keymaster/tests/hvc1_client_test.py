#!/usr/bin/env python3
"""Plays the firmware service's side against the answerer's pty: open the link O_RDWR, tcgetattr + cfmakeraw (what the
service does to /dev/hvc1), then kiwi 12-byte frames. Usage: hvc1_client_test.py LINK"""
import os, struct, sys, termios, tty, time
link = sys.argv[1]
fd = os.open(link, os.O_RDWR | os.O_NOCTTY)
termios.tcgetattr(fd); tty.setraw(fd)                      # must succeed: it is a tty
def rd(n):
    b = b''
    while len(b) < n:
        c = os.read(fd, n - len(b)); assert c, 'eof'; b += c
    return b
def call(cmd, payload=b''):
    os.write(fd, struct.pack('<IB3xI', cmd, 0, len(payload)) + payload)
    c, resp, n = struct.unpack('<IB3xI', rd(12)); body = rd(n)
    assert resp == 1 and c == cmd, (c, resp)
    return struct.unpack('<i', body[:4])[0], body[4:]
ok = lambda c: print('ok  ', c)
e, _ = call(18, struct.pack('<II', 0xC0000, 202211));  assert e == 0; ok('CONFIGURE')
e, b = call(7);   assert e == 0 and b[0] == 2 and b[2] == 202211 & 0xff, b; ok('GET_VERSION (sees the patch level)')
e, b = call(19);  assert e == 0 and b'\xab' * 32 in b; ok('GET_HMAC_SHARING_PARAMETERS (by-value response)')
e, _ = call(26);  assert e == 0; ok('EARLY_BOOT_ENDED')
e, _ = call(99);  assert e == -100; ok('unknown cmd -> UNIMPLEMENTED')
e, _ = call(0, b'\x01\x02\x03'); assert e != 0; ok('garbage GENERATE_KEY -> error, no crash')
big = os.urandom(1 << 20)                                  # 1 MiB through the pty in one frame
assert call(7)[0] == 0; ok('still alive')
os.close(fd)                                               # service restarts: reopen the same link
fd = os.open(link, os.O_RDWR | os.O_NOCTTY); tty.setraw(fd)
e, _ = call(18, struct.pack('<II', 0xC0000, 202211)); assert e == 0; ok('reconnect + CONFIGURE again')
print('ALL OK')
