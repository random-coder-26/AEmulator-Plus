#!/usr/bin/env python3
"""Regenerate native/guestshim/adss_channels.inc from a text file with one channel name per line
(the ChannelNames of the firmware's framework2 + ExceptionReporterChannel).

usage: gen-adss-channels.py channels.txt [out.inc]

Names that the host proxy must NOT claim (they are served by the guest itself) and the RemoteStreamsChannel- prefix
stub are dropped, as in the original generator."""
import sys
SKIP = {'BluetoothApiRedirector', 'BluetoothManagerService', 'BluetoothSocketRedirector',
        'SettingsServiceChannelHandler', 'RemoteStreamsChannel-'}
src = sys.argv[1]
out = sys.argv[2] if len(sys.argv) > 2 else __file__.rsplit('/tools/', 1)[0] + '/native/guestshim/adss_channels.inc'
names = [n for n in open(src).read().split('\n') if n and n not in SKIP]
open(out, 'w').write(',\n'.join('    "%s"' % n for n in names) + ',\n')
print(len(names), 'reasons ->', out)
