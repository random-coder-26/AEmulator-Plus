/* Added for AEmulator Plus (2026-11-18). GPL-3.0.
 * Kernel uevents for the guest's UEventObserver (power_supply: battery level / charger changes on Android 2.3 - 4.3).
 *
 * A real device wakes BatteryService with a NETLINK_KOBJECT_UEVENT message whenever a power_supply node changes. The host
 * denies that socket to an app, and the qemu stand answers socket(AF_NETLINK, ., 15) with an AF_UNIX pair nobody writes to,
 * so BatteryService only read the sysfs nodes once, at start: the VM menu's battery level never reached the guest.
 *
 * Here socket(AF_NETLINK, SOCK_DGRAM, NETLINK_KOBJECT_UEVENT) hands out a connection to the socket the app serves at
 * /dev/aemu_uevent (qemu rewrites the guest-root path, as for /dev/aemu_touchpad) and bind() on it succeeds. The app writes the
 * kernel's own message format ("change@/path\0ACTION=change\0...\0SUBSYSTEM=power_supply\0..."), recv() returns it.
 * When the app does not serve the socket (connect fails), socket() carries on to the stand's own answer as before.
 * Self-contained: needs only sys3() from the including shim. A peer name identifies the descriptor, so dup/fork/close are safe. */
#ifndef UEVENT_H
#define UEVENT_H

#ifndef UEV_SOCKET
#define UEV_SOCKET "/dev/aemu_uevent"
#endif
struct uev_sockaddr { unsigned short family; char path[108]; };

/* The descriptor, or -1 when the app does not serve uevents. */
static long uev_socket(int type) {
    struct uev_sockaddr addr;
    for (unsigned i = 0; i < sizeof(addr); i++) ((unsigned char *)&addr)[i] = 0;
    addr.family = 1;
    for (unsigned i = 0; UEV_SOCKET[i]; i++) addr.path[i] = UEV_SOCKET[i];
    long fd = sys3(281 /* socket */, 1 /* AF_UNIX */, 1 /* SOCK_STREAM */ | (type & (04000 | 02000000)), 0);
    if (fd < 0) return -1;
    /* the whole sun_path capacity is passed: QEMU rewrites the guest-root pathname in place */
    if (sys3(283 /* connect */, fd, (long)&addr, sizeof(addr)) < 0) { sys3(6 /* close */, fd, 0, 0); return -1; }
    return fd;
}

/* 1 when fd is the connection made by uev_socket (by its peer, so dup/fork keep working). */
static int uev_fd(int fd) {
    struct uev_sockaddr addr;
    for (unsigned i = 0; i < sizeof(addr); i++) ((unsigned char *)&addr)[i] = 0;
    unsigned len = sizeof(addr);
    if (sys3(287 /* getpeername */, fd, (long)&addr, (long)&len) < 0 || addr.family != 1) return 0;
    unsigned n = 0;
    while (n < sizeof(addr.path) && addr.path[n]) n++;
    const char *suffix = "aemu_uevent";
    unsigned sl = 0;
    while (suffix[sl]) sl++;
    if (n < sl) return 0;
    for (unsigned i = 0; i < sl; i++) if (addr.path[n - sl + i] != suffix[i]) return 0;
    return 1;
}
#endif
