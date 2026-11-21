/* Added for AEmulator Plus, 2026-11-15: the HTTP debugger's hook in the native engine's runner. GPL-3.0; see LICENSE and NOTICE.md.
 *
 * Every outgoing TCP connection of the guest is sent to the debugger's door on the phone's loopback (AEMU64_NETDBG=<port>),
 * and the destination it really wanted goes ahead of the data, 28 bytes:
 *     "AEHD" | 1 | family (4 or 6) | port (2, big endian) | address (16 bytes; IPv4 in the first four) | 4 reserved
 * UDP to port 53 goes to the debugger's DNS answerer (AEMU64_NETDNS=<port>). Loopback destinations, unix sockets and every
 * other kind of socket are left alone. The connect is made blocking for a moment, so the header is out before the guest can
 * write; a non-blocking socket gets an immediate 0 (a loopback connect finishes at once).
 * Freestanding, aarch64, raw system calls: it is compiled into run64.c, so the seccomp filter passes its calls.
 */
#ifndef AEMU_NETHOOK_H
#define AEMU_NETHOOK_H
#ifndef __aarch64__
#error "aemu_nethook.h is for the arm64 runner"
#endif

#define NH_PASS 0x7fffffffL

static int nh_port, nh_dns;

static inline long nh_sc(long n, long a, long b, long c, long d, long e, long f) {
    register long x8 __asm__("x8") = n; register long x0 __asm__("x0") = a; register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c; register long x3 __asm__("x3") = d; register long x4 __asm__("x4") = e; register long x5 __asm__("x5") = f;
    __asm__ volatile("svc 0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x5) : "memory", "cc");
    return x0;
}
enum { NH_fcntl = 25, NH_connect = 203, NH_sendto = 206, NH_getsockopt = 209, NH_ppoll = 73 };

static int nh_atoi(const char *s) { int v = 0; while (*s >= '0' && *s <= '9') v = v * 10 + (*s++ - '0'); return v; }
static void nh_set(int port, int dns) { nh_port = port; nh_dns = dns; }
static void nh_set_env(const char *netdbg, const char *netdns) { if (netdbg) nh_port = nh_atoi(netdbg); if (netdns) nh_dns = nh_atoi(netdns); }

static void nh_zero(void *p, unsigned long n) { unsigned char *c = p; while (n--) *c++ = 0; }
static void nh_copy(void *d, const void *s, unsigned long n) { unsigned char *a = d; const unsigned char *b = s; while (n--) *a++ = *b++; }

/* the loopback address [127.0.0.1]:port in the form the socket's own family takes; returns its length */
static unsigned long nh_loopback(unsigned char *sa, int domain, int port) {
    nh_zero(sa, 28);
    if (domain == 10) {                                   /* sockaddr_in6, ::ffff:127.0.0.1 */
        sa[0] = 10; sa[2] = (unsigned char)(port >> 8); sa[3] = (unsigned char)port;
        sa[8 + 10] = 0xff; sa[8 + 11] = 0xff; sa[8 + 12] = 127; sa[8 + 15] = 1;
        return 28;
    }
    sa[0] = 2; sa[2] = (unsigned char)(port >> 8); sa[3] = (unsigned char)port; sa[4] = 127; sa[7] = 1;
    return 16;
}

static int nh_sockopt(long fd, int opt) {
    int v = 0; unsigned l = 4;
    if (nh_sc(NH_getsockopt, fd, 1 /*SOL_SOCKET*/, opt, (long)&v, (long)&l, 0) < 0) return -1;
    return v;
}

/* 1 = not for us (loopback, unspecified): the address and its port are filled in when it is */
static int nh_target(const unsigned char *sa, unsigned long len, unsigned char *addr16, int *port, int *fam) {
    unsigned f = sa[0] | (sa[1] << 8);
    if (f == 2 && len >= 16) {
        *fam = 4; *port = (sa[2] << 8) | sa[3];
        nh_zero(addr16, 16); nh_copy(addr16, sa + 4, 4);
        if (sa[4] == 127 || (!sa[4] && !sa[5] && !sa[6] && !sa[7])) return 1;
        return 0;
    }
    if (f == 10 && len >= 28) {
        *port = (sa[2] << 8) | sa[3]; nh_copy(addr16, sa + 8, 16);
        int mapped = 1, zero = 1, loop6 = 1;
        for (int i = 0; i < 10; i++) if (addr16[i]) mapped = 0;
        if (addr16[10] != 0xff || addr16[11] != 0xff) mapped = 0;
        for (int i = 0; i < 16; i++) if (addr16[i]) zero = 0;
        for (int i = 0; i < 15; i++) if (addr16[i]) loop6 = 0;
        if (addr16[15] != 1) loop6 = 0;
        if (zero || loop6) return 1;
        if (mapped) {                                     /* v4-mapped: the debugger gets it as an IPv4 destination */
            if (addr16[12] == 127 || !(addr16[12] | addr16[13] | addr16[14] | addr16[15])) return 1;
            unsigned char t[16]; nh_zero(t, 16); nh_copy(t, addr16 + 12, 4); nh_copy(addr16, t, 16);
            *fam = 4; return 0;
        }
        *fam = 6; return 0;
    }
    return 1;
}

/* connect(2) of the guest. NH_PASS: make the real call. */
static long nh_connect(long fd, const void *sa, unsigned long len) {
    if ((!nh_port && !nh_dns) || !sa || len < 16) return NH_PASS;
    const unsigned char *s = sa;
    unsigned fam16 = s[0] | (s[1] << 8);
    if (fam16 != 2 && fam16 != 10) return NH_PASS;
    int type = nh_sockopt(fd, 3 /*SO_TYPE*/);
    if (type < 0) return NH_PASS;
    type &= 0xf;
    unsigned char addr[16]; int port = 0, fam = 0;
    int skip = nh_target(s, len, addr, &port, &fam);
    if (skip) return NH_PASS;
    int domain = nh_sockopt(fd, 39 /*SO_DOMAIN*/);
    if (domain != 2 && domain != 10) return NH_PASS;
    unsigned char lo[28];

    if (type == 2 /*SOCK_DGRAM*/) {
        if (port != 53 || !nh_dns) return NH_PASS;
        unsigned long n = nh_loopback(lo, domain, nh_dns);
        return nh_sc(NH_connect, fd, (long)lo, (long)n, 0, 0, 0);
    }
    if (type != 1 /*SOCK_STREAM*/ || !nh_port) return NH_PASS;

    long fl = nh_sc(NH_fcntl, fd, 3 /*F_GETFL*/, 0, 0, 0, 0);
    int nonblock = fl >= 0 && (fl & 04000);
    if (nonblock) nh_sc(NH_fcntl, fd, 4 /*F_SETFL*/, fl & ~04000L, 0, 0, 0);
    unsigned long n = nh_loopback(lo, domain, nh_port);
    long r = nh_sc(NH_connect, fd, (long)lo, (long)n, 0, 0, 0);
    if (r == -4 /*EINTR*/ || r == -115 /*EINPROGRESS*/) {
        struct { int fd; short events, revents; } p = { (int)fd, 4 /*POLLOUT*/, 0 };
        struct { long s, ns; } ts = { 5, 0 };
        if (nh_sc(NH_ppoll, (long)&p, 1, (long)&ts, 0, 8, 0) > 0) { int e = nh_sockopt(fd, 4 /*SO_ERROR*/); r = e > 0 ? -e : 0; }
        else r = -111;                                     /* the debugger is not there: refused, like any closed port */
    }
    if (r == 0) {
        unsigned char h[28];
        nh_zero(h, 28);
        h[0] = 'A'; h[1] = 'E'; h[2] = 'H'; h[3] = 'D'; h[4] = 1; h[5] = (unsigned char)fam;
        h[6] = (unsigned char)(port >> 8); h[7] = (unsigned char)port;
        nh_copy(h + 8, addr, 16);
        unsigned long off = 0;
        while (off < 28) {
            long w = nh_sc(NH_sendto, fd, (long)(h + off), 28 - off, 0x4000 /*MSG_NOSIGNAL*/, 0, 0);
            if (w == -4) continue;
            if (w <= 0) { r = w < 0 ? w : -32; break; }
            off += (unsigned long)w;
        }
    }
    if (nonblock) nh_sc(NH_fcntl, fd, 4, fl, 0, 0, 0);
    return r;
}

/* sendto(2) with an address on an unconnected UDP socket: DNS goes to the debugger's answerer. a = the call's six arguments. */
static long nh_sendto(const long *a) {
    if (!nh_dns || !a[4] || (unsigned long)a[5] < 16) return NH_PASS;
    const unsigned char *s = (const unsigned char *)a[4];
    unsigned f = s[0] | (s[1] << 8);
    if (f != 2 && f != 10) return NH_PASS;
    int type = nh_sockopt(a[0], 3);
    if (type < 0 || (type & 0xf) != 2) return NH_PASS;
    unsigned char addr[16]; int port = 0, fam = 0;
    if (nh_target(s, (unsigned long)a[5], addr, &port, &fam) || port != 53) return NH_PASS;
    int domain = nh_sockopt(a[0], 39);
    if (domain != 2 && domain != 10) return NH_PASS;
    unsigned char lo[28];
    unsigned long n = nh_loopback(lo, domain, nh_dns);
    return nh_sc(NH_sendto, a[0], a[1], a[2], a[3], (long)lo, (long)n);
}
#endif
