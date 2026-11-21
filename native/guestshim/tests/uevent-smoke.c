/* Kernel-uevent socket served by the app (uevent.h): socket(AF_NETLINK, ., 15) connects, bind succeeds, messages arrive. */
#define UEV_SOCKET "/tmp/aemu_uevent"
#include "../aemushim.c"
char **environ;
static int test_errno;
int *__errno(void) { return &test_errno; }
AemuFile *fdopen(int fd, const char *mode) { (void)fd; (void)mode; return 0; }
#define CHECK(c) do { if (!(c)) return __LINE__; } while (0)
struct nl_addr { unsigned short family, pad; unsigned pid, groups; };
static int streq(const char *a, const char *b, int n) { for (int i = 0; i < n; i++) if (a[i] != b[i]) return 0; return 1; }
static int test_main(void) {
    sys3(10 /* unlink */, (long)UEV_SOCKET, 0, 0);
    struct nl_addr nl = { 16, 0, 0, 0xffffffff };
    /* no server: nothing of ours is handed out */
    int none = socket(16, 2, 15);
    if (none >= 0) { CHECK(!uev_fd(none)); sys3(6, none, 0, 0); }
    /* the app's server */
    int srv = (int)sys3(281, 1, 1, 0);
    CHECK(srv >= 0);
    struct uev_sockaddr a;
    for (unsigned i = 0; i < sizeof(a); i++) ((unsigned char *)&a)[i] = 0;
    a.family = 1;
    for (unsigned i = 0; UEV_SOCKET[i]; i++) a.path[i] = UEV_SOCKET[i];
    CHECK(sys3(282, srv, (long)&a, sizeof(a)) == 0);
    CHECK(sys3(284, srv, 4, 0) == 0);
    /* BatteryService / uevent_init: socket, setsockopt (result ignored), bind */
    int fd = socket(16, 2 | 02000000, 15);
    CHECK(fd >= 0 && uev_fd(fd));
    int sz = 65536;
    sys4(366 /* setsockopt */, fd, 1, 33 /* SO_RCVBUFFORCE */, (long)&sz);
    CHECK(bind(fd, &nl, sizeof(nl)) == 0);
    int conn = (int)sys3(285 /* accept */, srv, 0, 0);
    CHECK(conn >= 0);
    static const char msg[] = "change@/class/power_supply/battery\0ACTION=change\0SUBSYSTEM=power_supply\0POWER_SUPPLY_NAME=battery\0";
    CHECK(sys3(4, conn, (long)msg, sizeof(msg)) == (long)sizeof(msg));
    char buf[256];
    long n = sys4(291 /* recvfrom */, fd, (long)buf, sizeof(buf), 0);   /* what uevent_next_event does */
    CHECK(n == (long)sizeof(msg) && streq(buf, msg, sizeof(msg)));
    /* dup and fork keep the identity; an ordinary unix socket is not ours and bind on it is not swallowed */
    int copy = (int)sys3(41, fd, 0, 0);
    CHECK(copy >= 0 && uev_fd(copy));
    int plain = (int)sys3(281, 1, 1, 0);
    CHECK(plain >= 0 && !uev_fd(plain));
    sys3(6, plain, 0, 0);
    /* other netlink protocols are untouched by this code path */
    CHECK(!(socket(16, 3, 123456) >= 0 && uev_fd(0)));
    sys3(6, copy, 0, 0); sys3(6, fd, 0, 0); sys3(6, conn, 0, 0); sys3(6, srv, 0, 0);
    sys3(10, (long)UEV_SOCKET, 0, 0);
    return 0;
}
void _start(void) {
    int result = test_main();
    const char *message = result ? "uevent smoke FAILED\n" : "uevent smoke OK\n";
    sys3(4, 1, (long)message, result ? 20 : 16);
    sys3(1, result, 0, 0);
    for (;;) {}
}
