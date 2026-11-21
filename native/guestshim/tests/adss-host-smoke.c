/* Real ARM syscalls under qemu-arm: the guest side of RunningAppTable talking to the host stand-in in adss.h
 * through the production open()/ioctl() of aemushim.c: RatControl handshake, identity, create channel, channel
 * request/reply, ProxyControl LaunchProxy -> second (app) proxy. */
#include "../aemushim.c"
char **environ;
static int test_errno;
int *__errno(void) { return &test_errno; }
AemuFile *fdopen(int fd, const char *mode) { (void)fd; (void)mode; return 0; }
#define CHECK(c) do { if (!(c)) return __LINE__; } while (0)

static int reg(int bus, const char *name) { unsigned a[3] = { (unsigned)(long)name, (unsigned)-1, 0 }; return ioctl(bus, (int)ADSS_IOC_REGISTER, a) == 0 ? (int)a[0] : -1; }
static int wait_client(int srv) { int a[3] = { -1, 0, 0 }; return ioctl(srv, (int)ADSS_IOC_WAIT, a) == 0 ? a[0] : -1; }
static int cmd(int fd, unsigned char *b, int n) { return sys3(4, fd, (long)b, n) == n; }
/* one reply [len][tid][body], stitched from datagrams like ReplyBuilder does */
static int reply(int fd, unsigned char *b, int cap) {
    int got = 0, want = 8;
    while (got < want) {
        long r = sys3(3, fd, (long)(b + got), cap - got);
        if (r <= 0) return -1;
        got += (int)r;
        if (got >= 4) want = (int)adss_le32(b);
    }
    return got;
}
static int create(int rat, const char *srv, const char *chan, unsigned tid) {
    unsigned char b[128]; int l0 = adss_len(srv), l1 = adss_len(chan), o = 0;
    o += adss_put32(b + o, 24 + l0 + l1 + 1); o += adss_put32(b + o, 2); o += adss_put32(b + o, tid);
    o += adss_put32(b + o, l0); o += adss_put32(b + o, l1); o += adss_put32(b + o, 1);
    for (int i = 0; i < l0; i++) b[o++] = srv[i];
    for (int i = 0; i < l1; i++) b[o++] = chan[i];
    b[o++] = 'x';
    return cmd(rat, b, o);
}
static int identity(int fd, unsigned char *b, int cap) {
    unsigned char c[12] = { 12, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 };
    return cmd(fd, c, 12) ? reply(fd, b, cap) : -1;
}
static int test_main(void) {
    static unsigned char b[4096];
    int bus = open("/dev/adss", 2);
    CHECK(bus > 0);
    int rcs = reg(bus, "RatControl");
    CHECK(rcs > 2);
    int rat = wait_client(rcs);                         /* the system proxy is waiting already: no blocking */
    CHECK(rat > 2);

    unsigned char neg[16] = { 16, 0, 0, 0, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 2, 0, 0, 0 };
    CHECK(cmd(rat, neg, 16) && reply(rat, b, sizeof b) == 12);
    CHECK(adss_le32(b) == 12 && adss_le32(b + 4) == 0xffffffffu && adss_le32(b + 8) == 2);

    int n = identity(rat, b, sizeof b);
    CHECK(n > 100 && (int)adss_le32(b) == n && adss_le32(b + 4) == 0);
    CHECK(adss_le32(b + 8) == 0 && adss_le32(b + 12) == 0 && adss_le32(b + 16) == 0);     /* system proxy, no package, no extra */
    unsigned nr = adss_le32(b + 20), off = 24 + 4 * nr, found = 0;
    CHECK(nr > 60);
    for (unsigned i = 0; i < nr; i++) {
        unsigned l = adss_le32(b + 24 + 4 * i);
        if (l == 23 && !__builtin_memcmp(b + off, "LocaleManagementChannel", 23)) found = 1;
        off += l;
    }
    CHECK(found && off == (unsigned)n);

    /* LocaleManager: guest registers adss_server_1, asks the proxy for channel LocaleManagementChannel */
    int s1 = reg(bus, "adss_server_1");
    CHECK(s1 > 2 && create(rat, "adss_server_1", "LocaleManagementChannel", 1));
    CHECK(reply(rat, b, sizeof b) == 12 && adss_le32(b + 4) == 1 && adss_le32(b + 8) == 0);
    int ch = wait_client(s1);
    CHECK(ch > 2);
    unsigned char rq[] = { 0x08, 0x05, 0x22, 0x02, 0x08, 0x01 };                /* callId 5, a request with a payload */
    CHECK(cmd(ch, rq, sizeof rq) && sys3(3, ch, (long)b, sizeof b) == 4);
    CHECK(b[0] == 0x08 && b[1] == 0x05 && b[2] == 0x10 && b[3] == 0x01);        /* empty successful reply, same callId */
    unsigned char oneway[] = { 0x08, 0x00 };                                    /* callId 0: no reply, next request still works */
    unsigned char rq2[] = { 0x08, 0x07 };
    CHECK(cmd(ch, oneway, 2) && cmd(ch, rq2, 2) && sys3(3, ch, (long)b, sizeof b) == 4 && b[1] == 0x07);

    /* a server nobody registered is refused */
    CHECK(create(rat, "adss_server_99", "X", 2) && reply(rat, b, sizeof b) == 12 && adss_le32(b + 4) == 2 && adss_le32(b + 8) != 0);

    /* ProxyControlChannel: LaunchProxy for com.example.app brings up an app proxy on RatControl */
    int s2 = reg(bus, "adss_server_2");
    CHECK(create(rat, "adss_server_2", "ProxyControlChannel", 3) && reply(rat, b, sizeof b) == 12);
    int pc = wait_client(s2);
    CHECK(pc > 2);
    /* ProxyControlMessage{type 0, launchProxyMessage{packageName "com.exap"}} payload = 08 00 12 0a 0a 08 <8 chars> */
    unsigned char lp2[] = { 0x08, 0x09, 0x22, 0x0e, 0x08, 0x00, 0x12, 0x0a, 0x0a, 0x08, 'c','o','m','.','e','x','a','p' };
    CHECK(cmd(pc, lp2, sizeof lp2) && sys3(3, pc, (long)b, sizeof b) == 12);
    CHECK(b[0] == 0x08 && b[1] == 0x09 && b[2] == 0x10 && b[3] == 0x01 && b[4] == 0x22 && b[5] == 0x06 && b[6] == 0x08 && b[7] == 0x02 && b[8] == 0x22 && b[9] == 0x02 && b[10] == 0x08 && b[11] == 0x01);
    int app = wait_client(rcs);                         /* the app proxy is handed to RatControl's next waitForConnection */
    CHECK(app > 2 && app != rat);
    CHECK(cmd(app, neg, 16) && reply(app, b, sizeof b) == 12);
    n = identity(app, b, sizeof b);
    CHECK(n > 100 && adss_le32(b + 8) == 1 && adss_le32(b + 12) == 8 && !__builtin_memcmp(b + 24 + 4 * adss_le32(b + 20), "", 0));
    CHECK(!__builtin_memcmp(b + 24, "com.exap", 8));
    return 0;
}
void _start(void) {
    sys3(27 /* alarm */, 15, 0, 0);                    /* a hung host must fail the test, not the CI */
    int r = test_main();
    static const char ok[] = "adss host smoke OK\n", bad[] = "adss host smoke FAILED (line ";
    if (!r) { sys3(4, 1, (long)ok, sizeof ok - 1); sys3(248, 0, 0, 0); }
    char dig[12]; int k = 0, x = r;
    while (x) { dig[k++] = (char)('0' + x % 10); x /= 10; }
    sys3(4, 1, (long)bad, sizeof bad - 1);
    while (k) sys3(4, 1, (long)&dig[--k], 1);
    sys3(4, 1, (long)")\n", 2);
    sys3(248, 1, 0, 0);
}
