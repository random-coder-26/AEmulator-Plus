/* Real ARM syscall test under qemu-arm of the /dev/adss stand-in (adss.h), through the production open() and ioctl() of
 * aemushim.c: the open succeeds, register-server hands back a usable fd, wait-for-client blocks, connect is refused. */
#include "../aemushim.c"
char **environ;
static int test_errno;
int *__errno(void) { return &test_errno; }
AemuFile *fdopen(int fd, const char *mode) { (void)fd; (void)mode; return 0; }
#define CHECK(c) do { if (!(c)) return __LINE__; } while (0)
static int test_main(void) {
    CHECK(adss_open("/dev/null", 0) == -4096);                        /* not ours */
    int bus = open("/dev/adss", 02000000);                            /* O_CLOEXEC, like the JNI's plain open() does not, both work */
    int twin = open("/dev/AdssBus", 0);
    CHECK(bus > 0 && twin > 0 && adss_kind(bus) == ADSS_KIND_BUS && adss_kind(twin) == ADSS_KIND_BUS);
    int nul = open("/dev/null", 2);
    CHECK(nul > 0 && adss_kind(nul) == 0);
    CHECK(ioctl(nul, (int)ADSS_IOC_REGISTER, (void *)&nul) == -1 && test_errno == 25);   /* ENOTTY from the kernel for anything else */

    /* register server: arg[0] = name pointer in, server fd out */
    unsigned arg[3] = { (unsigned)(long)"adss_server_1", (unsigned)-1, 0 };
    CHECK(ioctl(bus, (int)ADSS_IOC_REGISTER, arg) == 0);
    int srv = (int)arg[0];
    CHECK(srv > 2 && srv != bus && adss_kind(srv) == ADSS_KIND_SERVER);
    CHECK(ioctl(bus, 0x12345, arg) == -1 && test_errno == 25);

    /* Dalvik's fork path: open("/dev/adss", O_RDWR), ioctl(fd, 0x22003f, &args) must succeed or the fork fails */
    int dv = open("/dev/adss", 2);
    unsigned fargs[1] = { 0x1000 };
    CHECK(dv > 0 && ioctl(dv, (int)ADSS_IOC_FORK, fargs) == 0);
    CHECK(close(dv) == 0);

    /* connect: nobody serves */
    unsigned carg[3] = { (unsigned)(long)"adss_server_1", (unsigned)-1, 0 };
    CHECK(ioctl(bus, (int)ADSS_IOC_CONNECT, carg) == -1 && test_errno == 111);
    CHECK(ioctl(srv, (int)ADSS_IOC_CONNECT, carg) == -1 && test_errno == 25);

    /* wait for a client blocks for good */
    long pid = sys3(2 /* fork */, 0, 0, 0);
    CHECK(pid >= 0);
    if (pid == 0) {
        int w = -1;
        ioctl(srv, (int)ADSS_IOC_WAIT, &w);                          /* must not return */
        sys3(1, 77, 0, 0);
    }
    struct timespec_s ts = { 0, 300 * 1000 * 1000 };
    sys3(162, (long)&ts, 0, 0);
    int st = 0;
    CHECK(sys4(114 /* wait4 */, pid, (long)&st, 1 /* WNOHANG */, 0) == 0);   /* still blocked after 300 ms */
    sys3(37 /* kill */, pid, 9, 0);
    CHECK(sys4(114, pid, (long)&st, 0, 0) == pid && (st & 0x7f) == 9);

    /* host side: registering "RatControl" starts the Windows-host stand-in; the system proxy connects at once */
    unsigned char b[600];
    unsigned rarg[3] = { (unsigned)(long)"RatControl", (unsigned)-1, 0 };
    CHECK(ioctl(bus, (int)ADSS_IOC_REGISTER, rarg) == 0);
    int rat = (int)rarg[0];
    unsigned warg[3] = { (unsigned)-1, 0, 0 };
    CHECK(ioctl(rat, (int)ADSS_IOC_WAIT, warg) == 0);                 /* returns the connection */
    int px = (int)warg[0];
    CHECK(px > 2);
    unsigned neg[3] = { 12, (unsigned)-1, 0 };
    CHECK(sys3(4, px, (long)neg, 12) == 12);
    CHECK(sys3(3, px, (long)b, sizeof b) == 12 && adss_le32(b) == 12 && adss_le32(b + 4) == (unsigned)-1 && adss_le32(b + 8) == 2);
    unsigned ident[3] = { 12, 0, 0 };
    CHECK(sys3(4, px, (long)ident, 12) == 12);
    long il = sys3(3, px, (long)b, sizeof b);
    CHECK(il >= 24 && adss_le32(b) == (unsigned)il && adss_le32(b + 4) == 0 && adss_le32(b + 8) == 0 /* system proxy */);

    /* create channel "ProxyControlChannel" on a server the guest registered */
    unsigned carg2[3] = { (unsigned)(long)"ProxyCtl", (unsigned)-1, 0 };
    CHECK(ioctl(bus, (int)ADSS_IOC_REGISTER, carg2) == 0);
    int psrv = (int)carg2[0];
    {
        static const char sname[] = "ProxyCtl", cname[] = "ProxyControlChannel", third[] = "1";
        int o = 0;
        o += adss_put32(b + o, 0); o += adss_put32(b + o, 2); o += adss_put32(b + o, 77);
        o += adss_put32(b + o, sizeof sname - 1); o += adss_put32(b + o, sizeof cname - 1); o += adss_put32(b + o, sizeof third - 1);
        for (unsigned i = 0; i < sizeof sname - 1; i++) b[o++] = sname[i];
        for (unsigned i = 0; i < sizeof cname - 1; i++) b[o++] = cname[i];
        b[o++] = third[0];
        adss_put32(b, (unsigned)o);
        CHECK(sys3(4, px, (long)b, o) == o);
        CHECK(sys3(3, px, (long)b, sizeof b) == 12 && adss_le32(b + 4) == 77 && adss_le32(b + 8) == 0);
    }
    unsigned warg2[3] = { (unsigned)-1, 0, 0 };
    CHECK(ioctl(psrv, (int)ADSS_IOC_WAIT, warg2) == 0);
    int ch = (int)warg2[0];
    CHECK(ch > 2);
    /* a plain request is answered with an empty reply carrying the same call id */
    static const unsigned char req5[] = { 0x08, 0x05 };
    CHECK(sys3(4, ch, (long)req5, 2) == 2);
    CHECK(sys3(3, ch, (long)b, sizeof b) == 4 && b[0] == 0x08 && b[1] == 0x05 && b[2] == 0x10 && b[3] == 0x01);
    /* LaunchProxy for "pkg": a second connection shows up on RatControl, the reply carries LaunchProxyReply{true} */
    static const unsigned char launch[] = { 0x08, 0x07, 0x22, 0x09, 0x08, 0x00, 0x12, 0x05, 0x0a, 0x03, 'p', 'k', 'g' };
    CHECK(sys3(4, ch, (long)launch, sizeof launch) == (long)sizeof launch);
    CHECK(sys3(3, ch, (long)b, sizeof b) == 12 && b[0] == 0x08 && b[1] == 0x07 && b[2] == 0x10 && b[3] == 0x01 && b[4] == 0x22 && b[5] == 0x06 && b[6] == 0x08 && b[7] == 0x02);
    unsigned warg3[3] = { (unsigned)-1, 0, 0 };
    CHECK(ioctl(rat, (int)ADSS_IOC_WAIT, warg3) == 0);
    int app = (int)warg3[0];
    CHECK(sys3(4, app, (long)ident, 12) == 12);
    il = sys3(3, app, (long)b, sizeof b);
    CHECK(il >= 24 && adss_le32(b + 8) == 1 /* app proxy */ && adss_le32(b + 12) == 3 /* "pkg" */);

    /* close forgets the descriptor; a reused number is no longer ours */
    CHECK(close(bus) == 0 && adss_kind(bus) == 0);
    CHECK(adss_ioctl(bus, ADSS_IOC_CONNECT, carg) == -4096);                  /* passes through to the kernel again */
    return 0;
}
void _start(void) {
    int r = test_main();
    static const char ok[] = "adss smoke OK\n", bad[] = "adss smoke FAILED (line ";
    if (!r) { sys3(4, 1, (long)ok, sizeof ok - 1); sys3(248 /* exit_group: the host thread must not keep the process alive */, 0, 0, 0); }
    char dig[12]; int k = 0, x = r;
    while (x) { dig[k++] = (char)('0' + x % 10); x /= 10; }
    sys3(4, 1, (long)bad, sizeof bad - 1);
    while (k) sys3(4, 1, (long)&dig[--k], 1);
    sys3(4, 1, (long)")\n", 2);
    sys3(248 /* exit_group: the host thread must not keep the process alive */, 1, 0, 0);
}
