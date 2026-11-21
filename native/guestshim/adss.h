/*
 * adss.h - stand-in for the Microsoft "ADSS" bus driver of Windows 10 Mobile "Project A" (ArcadiaArm, Android 4.4),
 * including the HOST side of it: the "system proxy" that on a phone is a Windows process on the other end of a
 * Hyper-V channel.
 *
 * Added for AEmulator Plus (2026-11-15). Included by aemushim.c after sys3()/fail(); hooks are called from its
 * open()/openat()/close()/ioctl(). Nothing in the firmware is patched.
 *
 * Users of the driver in the firmware (all reverse-engineered from services.odex / framework2.odex / libandroid_runtime):
 *  - libdvm.so (Dalvik's forkAndSpecialize/forkSystemServer, so EVERY zygote fork, system_server included) does
 *    open("/dev/adss", O_RDWR) + ioctl(fd, 0x22003f, &{args}) just before fork() and gives up with "ioctl failed for
 *    /dev/adss: ..." (the fork then fails) when the ioctl is negative.
 *  - ZygoteInit.startSystemServer() calls com.microsoft.internal.adss.AdssBus.getInstance(); libandroid_runtime.so
 *    (AdssBusJNI) opens /dev/adss and then, through ioctl(), registers a server by name, connects to one, or waits for a
 *    client. All three take a 3-word argument whose word 0 is the name going in and the NEW fd coming out.
 *  - system_server's RunningAppTable (com.microsoft.rat) registers the server "RatControl" and, in
 *    SystemServer.initAndLoop(), com.microsoft.locale.LocaleManager asks it for a channel. That call blocks on a
 *    ConditionVariable until a *system proxy* has connected to RatControl and answered the handshake, so without a
 *    host the boot stops there for ever: zygote has forked system_server, the daemons idle, fb0 stays black.
 *
 * What is emulated here (one process: system_server holds both ends, a helper thread plays the Windows host):
 *
 *   open("/dev/adss") / open("/dev/AdssBus")   a descriptor on /dev/null, remembered as the bus
 *   ioctl(bus, 0x22002b, {name*})              register server: word 0 comes back as the server fd (read end of a
 *                                              private pipe; the write end is used to wake WAIT up)
 *   ioctl(server, 0x220033, {-1})              wait for a client: blocks until the host connects, returns a fresh
 *                                              AF_UNIX end in word 0 (stream for RatControl, SEQPACKET for channels)
 *   ioctl(bus, 0x22002f, {name*, -1, 0})       connect to a server: nobody on the host side serves anything, ECONNREFUSED
 *   ioctl(bus, 0x22003f, {args*})              Dalvik's "about to fork" notice: accepted
 *
 * The host helper thread (raw clone(), no libc, no TLS) speaks, over the connection that RatControl hands out:
 *
 *   RAT control protocol v2 (little endian; the guest sends commands, the proxy answers):
 *     command  [len][cmd][transaction] body      cmd -1 negotiate (body: 2), 0 get identity, 2 create channel
 *     reply    [len][transaction] body           len counts the 8 header bytes; the transaction field carries -1 / 0
 *                                                for the first two replies and the guest's transaction id otherwise
 *     negotiate reply  body = int version (2)
 *     identity  reply  body = int clientType (0 = the system proxy), int pkgLen, int extraLen, int nReasons,
 *                              pkg, extra, nReasons x int len, nReasons x string   (the reasons are channel names)
 *     create    cmd    body = int len0, len1, len2, then server name, channel name, third string (reason / pid)
 *     create    reply  body = int result (0 = ok)
 *   The proxy "connects" to the server named in a create command by delivering a new SEQPACKET end to the guest's
 *   waitForConnection() and keeping the other end as the CHANNEL.
 *
 *   Channel = one protobuf Common.ApiMessageHeader {1 uint64 callId, 2 bool isReply, 3 uint32 returnCode,
 *   4 bytes payload, 6 string exceptionMessage} per datagram. Every request (isReply false, callId != 0) is answered
 *   with an empty successful reply so that no redirector blocks for ever in send(); nothing here knows what Windows
 *   would say. ProxyControlChannel is understood: LaunchProxy for package P makes a second, per-app proxy connect to
 *   RatControl (clientType 1, package P) which is what Service.getAppProxy() waits for (30 s timeout otherwise).
 */
#ifndef ADSS_H
#define ADSS_H

#define ADSS_IOC_REGISTER 0x22002bu
#define ADSS_IOC_CONNECT 0x22002fu
#define ADSS_IOC_WAIT 0x220033u
#define ADSS_IOC_FORK 0x22003fu   /* libdvm.so, before every zygote fork */
#define ADSS_IOC_PKGLIST 0x22008bu /* netd (AoW build), after it parsed /data/system/packages.list: {count, entries*} for the host */

#define ADSS_KIND_BUS 1
#define ADSS_KIND_SERVER 2
#define ADSS_SLOTS 32

#define ADSS_MAX_SRV 64
#define ADSS_MAX_CONN 48
#define ADSS_PEND 8
#define ADSS_NAME 64
#define ADSS_CONN_RAT 1
#define ADSS_CONN_CHAN 2

static long sys4(long n, long a, long b, long c, long d);   /* defined further down in aemushim.c */
static long sys5(long n, long a, long b, long c, long d, long e);

static struct { volatile int fd[ADSS_SLOTS]; volatile unsigned char kind[ADSS_SLOTS]; } g_adss;

struct adss_srv  { volatile int used, rfd, wfd, pcnt; int pend[ADSS_PEND]; char name[ADSS_NAME]; };
struct adss_conn { volatile int used, hfd, kind, sys, rxn; char name[ADSS_NAME]; unsigned char rx[1024]; };
struct adss_pollfd { int fd; short events, revents; };

static struct adss_srv g_srv[ADSS_MAX_SRV];
static struct adss_conn g_conn[ADSS_MAX_CONN];
static volatile int g_adss_lock, g_adss_started, g_adss_nsrv;
static int g_adss_wake_r = -1, g_adss_wake_w = -1;
static char g_adss_stack[32768] __attribute__((aligned(16)));

/* channel names the host proxy claims to serve (ChannelNames.java of framework2 + ExceptionReporterChannel) */
static const char *const g_adss_reasons[] = {
    "AccountsSyncChannel",
    "ActivityRecognitionChannel",
    "AdInfoChannel",
    "AlarmManagementChannel",
    "ApkManagementChannel",
    "AppLifecycleChannel",
    "ApplicationCrashReportChannel",
    "AudioChannel",
    "AudioControlChannel",
    "AudioNotificationChannel",
    "BackgroundIntentChannel",
    "BackgroundNetworkServicesChannel",
    "BackgroundNotificationChannel",
    "BackgroundOAuthTokenChannel",
    "BackgroundUINotificationChannel",
    "BackgroundVoiceCommandChannel",
    "BluetoothCommand",
    "BluetoothData",
    "BluetoothEvent",
    "CameraModuleChannel",
    "ClipboardChannel",
    "ContactsProviderTransactionChannel",
    "ContactsSyncChannel",
    "ContentSyncChannel",
    "DeviceSettings",
    "DisplayChange",
    "ExceptionReporterChannel",
    "ForegroundPLM",
    "ForegroundPowerManagementChannel",
    "FullScreenUIChannel",
    "GamesServiceChannel",
    "GeofenceServiceChannel",
    "GeolocationChannel",
    "InAppPurchaseChannel",
    "IntentChannel",
    "KeyboardChannel",
    "LocaleManagementChannel",
    "MediaComponentChannel",
    "MediaContentChannel",
    "NetworkServicesChannel",
    "NotificationChannel",
    "OAuthTokenChannel",
    "PointerChannel",
    "PowerNotificationChannel",
    "ProxyControlChannel",
    "PushNotificationChannel",
    "ScreenRotationChannel",
    "SensorControlChannel",
    "SensorDataChannel",
    "SoftInputChannel",
    "SpeechRecognitionChannel",
    "StorageChannel",
    "SynchronousGeolocationChannel",
    "SystemAudioEffectChannel",
    "SystemMediaControlsChannel",
    "SystemPowerManagementChannel",
    "SystemWakeLockChannel",
    "TelemetryChannel",
    "TestAppChannel",
    "TextToSpeechChannel",
    "TimeZone",
    "UINotificationChannel",
    "UiAutomationChannel",
    "UserInput",
    "VibrationChannel",
    "VoiceCommandChannel",
    "WindowsStateChannel",
    0
};

static int adss_streq(const char *a, const char *b) {
    if (!a || !b) return 0;
    while (*a && *a == *b) { a++; b++; }
    return *a == *b;
}

/* debug: one line into the guest's logcat (tag AdssHost), raw syscalls only, safe from any thread */
static void adss_log(const char *m, long a, long b, const unsigned char *hex, int hn) {
    char l[200]; int n = 0; const char *t = "AdssHost";
    l[n++] = 3;                                          /* ANDROID_LOG_DEBUG */
    while (*t) l[n++] = *t++;
    l[n++] = 0;
    while (*m && n < 100) l[n++] = *m++;
    for (int k = 0; k < 2; k++) {
        long v = k ? b : a; char d[12]; int i = 0;
        l[n++] = ' ';
        if (v < 0) { l[n++] = '-'; v = -v; }
        if (!v) d[i++] = '0';
        while (v && i < 11) { d[i++] = (char)('0' + v % 10); v /= 10; }
        while (i) l[n++] = d[--i];
    }
    if (hex) {
        l[n++] = ' ';
        for (int i = 0; i < hn && i < 24 && n < 190; i++) { l[n++] = "0123456789abcdef"[hex[i] >> 4]; l[n++] = "0123456789abcdef"[hex[i] & 15]; }
    }
    l[n++] = 0;
    long fd = sys3(5 /* open */, (long)"/dev/log/main", 1 /* O_WRONLY */, 0);
    if (fd >= 0) { sys3(4, fd, (long)l, n); sys3(6, fd, 0, 0); }
}
static int adss_len(const char *s) { int n = 0; while (s[n]) n++; return n; }
static void adss_copy(char *d, const char *s, int max) {          /* bounded, always terminated, tolerates NULL */
    int i = 0;
    if (s) for (; s[i] && i < max - 1; i++) d[i] = s[i];
    d[i] = 0;
}
static unsigned adss_le32(const unsigned char *p) { return p[0] | (unsigned)p[1] << 8 | (unsigned)p[2] << 16 | (unsigned)p[3] << 24; }
static int adss_put32(unsigned char *p, unsigned v) { p[0] = (unsigned char)v; p[1] = (unsigned char)(v >> 8); p[2] = (unsigned char)(v >> 16); p[3] = (unsigned char)(v >> 24); return 4; }

static void adss_lock(void) { while (!__sync_bool_compare_and_swap(&g_adss_lock, 0, 1)) sys3(158 /* sched_yield */, 0, 0, 0); }
static void adss_unlock(void) { __sync_synchronize(); g_adss_lock = 0; }

/* a slot is claimed with fd = -1 first, so two threads never take the same one; the real fd goes in last */
static void adss_remember(int fd, int kind) {
    for (int i = 0; i < ADSS_SLOTS; i++) {
        if (g_adss.fd[i] == fd) { g_adss.kind[i] = (unsigned char)kind; return; }
    }
    for (int i = 0; i < ADSS_SLOTS; i++) {
        if (__sync_bool_compare_and_swap(&g_adss.fd[i], 0, -1)) {
            g_adss.kind[i] = (unsigned char)kind;
            __sync_synchronize();
            g_adss.fd[i] = fd;
            return;
        }
    }
}
static int adss_kind(int fd) {
    if (fd <= 0) return 0;
    for (int i = 0; i < ADSS_SLOTS; i++) if (g_adss.fd[i] == fd) return g_adss.kind[i];
    return 0;
}

/* ---------------------------------------------------------------------------------------------- registry */

static struct adss_srv *adss_srv_by_name(const char *name) {
    for (int i = 0; i < ADSS_MAX_SRV; i++) if (g_srv[i].used && adss_streq(g_srv[i].name, name)) return &g_srv[i];
    return 0;
}
static struct adss_srv *adss_srv_by_fd(int fd) {
    for (int i = 0; i < ADSS_MAX_SRV; i++) if (g_srv[i].used && g_srv[i].rfd == fd) return &g_srv[i];
    return 0;
}
static void adss_srv_add(const char *name, int rfd, int wfd) {
    adss_lock();
    for (int i = 0; i < ADSS_MAX_SRV; i++) {
        if (g_srv[i].used) continue;
        adss_copy(g_srv[i].name, name, ADSS_NAME);
        g_srv[i].rfd = rfd; g_srv[i].wfd = wfd; g_srv[i].pcnt = 0;
        __sync_synchronize();
        g_srv[i].used = 1; g_adss_nsrv++;
        break;
    }
    adss_unlock();
}
/* hand a connected end to whoever waits on this server (under g_adss_lock: no) */
static int adss_srv_deliver(struct adss_srv *s, int fd) {
    int ok = 0;
    adss_lock();
    if (s->used && s->pcnt < ADSS_PEND) { s->pend[s->pcnt++] = fd; ok = 1; }
    adss_unlock();
    if (ok) { char c = 1; sys3(4 /* write */, s->wfd, (long)&c, 1); }
    return ok;
}
static int adss_srv_take(struct adss_srv *s) {
    int fd = -1;
    adss_lock();
    if (s->pcnt > 0) { fd = s->pend[0]; for (int i = 1; i < s->pcnt; i++) s->pend[i - 1] = s->pend[i]; s->pcnt--; }
    adss_unlock();
    return fd;
}
static void adss_closed(int fd) {
    if (fd <= 0) return;
    for (int i = 0; i < ADSS_SLOTS; i++) if (g_adss.fd[i] == fd) g_adss.fd[i] = 0;
    if (!g_adss_nsrv) return;
    adss_lock();
    for (int i = 0; i < ADSS_MAX_SRV; i++) {
        if (g_srv[i].used && g_srv[i].rfd == fd) {
            g_srv[i].used = 0; g_adss_nsrv--;
            sys3(6, g_srv[i].wfd, 0, 0);
            for (int k = 0; k < g_srv[i].pcnt; k++) sys3(6, g_srv[i].pend[k], 0, 0);
            g_srv[i].pcnt = 0;
        }
    }
    adss_unlock();
}

/* stream = 1 for the RAT control link: the guest's native reader (PacketIOAdssBus) reads it in pieces - header first - and a
 * short read() on a datagram/seqpacket socket drops the rest of the message, so the reply body never arrives (seen on a
 * real boot). The Java ReplyBuilder reassembles a byte stream. Channels keep message boundaries. */
static int adss_pair(int sv[2], int stream) {
    static const int types[3] = { 5 /* SEQPACKET */, 2 /* DGRAM */, 1 /* STREAM */ };
    for (int i = stream ? 2 : 0; i < 3; i++)
        if (sys4(288 /* socketpair */, 1 /* AF_UNIX */, types[i] | 02000000 /* SOCK_CLOEXEC */, 0, (long)sv) == 0) { adss_log("pair type fds", types[i], (long)sv[0] * 1000 + sv[1], 0, 0); return 0; }
    return -1;
}
static void adss_wake(void) { char c = 1; if (g_adss_wake_w >= 0) sys3(4, g_adss_wake_w, (long)&c, 1); }

static int adss_conn_add(int kind, int sys, const char *name, int hfd) {
    int ok = 0;
    adss_lock();
    for (int i = 0; i < ADSS_MAX_CONN; i++) {
        if (g_conn[i].used) continue;
        g_conn[i].hfd = hfd; g_conn[i].kind = kind; g_conn[i].sys = sys; g_conn[i].rxn = 0;
        adss_copy(g_conn[i].name, name, ADSS_NAME);
        __sync_synchronize();
        g_conn[i].used = 1; ok = 1;
        break;
    }
    adss_unlock();
    adss_log("conn add kind hfd", kind, hfd, 0, 0);
    if (ok) adss_wake();
    return ok;
}

/* ------------------------------------------------------------------------------------------ host: sending */

static void adss_send(int fd, const unsigned char *p, int n) {     /* <= 512 bytes per datagram: the guest stitches them */
    while (n > 0) {
        int k = n > 512 ? 512 : n;
        long r = sys3(4, fd, (long)p, k);
        if (r == -4) continue;
        adss_log("host sent fd rc", fd, r, p, k);
        if (r < 0) return;
        p += k; n -= k;
    }
}
static int adss_varint(unsigned char *p, unsigned long long v) {
    int n = 0;
    while (v >= 0x80) { p[n++] = (unsigned char)(v | 0x80); v >>= 7; }
    p[n++] = (unsigned char)v;
    return n;
}

/* ---------------------------------------------------------------------------------- host: RAT protocol v2 */

static void adss_reply_negotiate(int fd) {
    unsigned char b[12]; int o = 0;
    o += adss_put32(b + o, 12); o += adss_put32(b + o, (unsigned)-1); o += adss_put32(b + o, 2);
    adss_send(fd, b, o);
}
static void adss_reply_identity(int fd, int sys, const char *pkg) {
    static unsigned char b[4096];            /* only the host thread builds replies */
    int o = 8, pl = sys ? 0 : adss_len(pkg), nr = 0;
    while (g_adss_reasons[nr]) nr++;
    o += adss_put32(b + o, sys ? 0 : 1);     /* clientType: 0 is the system proxy, anything else an app proxy */
    o += adss_put32(b + o, (unsigned)pl);
    o += adss_put32(b + o, 0);               /* extra string, skipped by the guest */
    o += adss_put32(b + o, (unsigned)nr);
    for (int i = 0; i < pl; i++) b[o++] = (unsigned char)pkg[i];
    for (int i = 0; i < nr; i++) o += adss_put32(b + o, (unsigned)adss_len(g_adss_reasons[i]));
    for (int i = 0; i < nr; i++) for (const char *s = g_adss_reasons[i]; *s; s++) b[o++] = (unsigned char)*s;
    adss_put32(b, (unsigned)o); adss_put32(b + 4, 0);
    adss_send(fd, b, o);
}
static void adss_reply_result(int fd, unsigned tid, int result) {
    unsigned char b[12]; int o = 0;
    o += adss_put32(b + o, 12); o += adss_put32(b + o, tid); o += adss_put32(b + o, (unsigned)result);
    adss_send(fd, b, o);
}
/* the proxy "connects" to a server the guest registered: a fresh pair, one end to the waiter, one kept as channel */
static int adss_connect_server(const char *server, const char *channel) {
    int sv[2];
    struct adss_srv *s = adss_srv_by_name(server);
    if (!s || adss_pair(sv, 0) < 0) return -1;
    if (!adss_conn_add(ADSS_CONN_CHAN, 0, channel, sv[1])) { sys3(6, sv[0], 0, 0); sys3(6, sv[1], 0, 0); return -1; }
    if (!adss_srv_deliver(s, sv[0])) { sys3(6, sv[0], 0, 0); return -1; }   /* the channel end stays, it is reaped on EOF */
    return 0;
}
static void adss_rat_msg(struct adss_conn *c, const unsigned char *m, int n) {
    if (n < 12) return;
    int cmd = (int)adss_le32(m + 4);
    unsigned tid = adss_le32(m + 8);
    if (cmd == -1) adss_reply_negotiate(c->hfd);
    else if (cmd == 0) adss_reply_identity(c->hfd, c->sys, c->name);
    else if (cmd == 2 && n >= 24) {
        unsigned l0 = adss_le32(m + 12), l1 = adss_le32(m + 16), l2 = adss_le32(m + 20);
        char server[ADSS_NAME], chan[ADSS_NAME];
        int res = -1;
        if (l0 < ADSS_NAME && l1 < ADSS_NAME && l2 < 4096 && 24 + l0 + l1 + l2 <= (unsigned)n) {
            for (unsigned i = 0; i < l0; i++) server[i] = (char)m[24 + i];
            server[l0] = 0;
            for (unsigned i = 0; i < l1; i++) chan[i] = (char)m[24 + l0 + i];
            chan[l1] = 0;
            res = adss_connect_server(server, chan);
        }
        adss_reply_result(c->hfd, tid, res);
    }
}

/* app proxy for package pkg: one more connection that RatControl's waitForConnection() hands out */
static void adss_launch_proxy(const char *pkg) {
    struct adss_srv *s = adss_srv_by_name("RatControl");
    int sv[2], have = 0;
    if (!s || !*pkg) return;
    adss_lock();
    for (int i = 0; i < ADSS_MAX_CONN; i++) if (g_conn[i].used && g_conn[i].kind == ADSS_CONN_RAT && adss_streq(g_conn[i].name, pkg)) have = 1;
    adss_unlock();
    if (have || adss_pair(sv, 1) < 0) return;
    if (!adss_conn_add(ADSS_CONN_RAT, 0, pkg, sv[1])) { sys3(6, sv[0], 0, 0); sys3(6, sv[1], 0, 0); return; }
    if (!adss_srv_deliver(s, sv[0])) sys3(6, sv[0], 0, 0);
}

/* ------------------------------------------------------------------------------------- host: channel traffic */

/* minimal protobuf reader: next field of buf[*i..n); returns 0 at the end or on garbage */
static int adss_pb_next(const unsigned char *b, int n, int *i, int *field, unsigned long long *val, int *vlen) {
    unsigned long long key = 0, v = 0; int sh = 0;
    if (*i >= n) return 0;
    while (*i < n) { unsigned char c = b[(*i)++]; key |= (unsigned long long)(c & 0x7f) << sh; sh += 7; if (!(c & 0x80)) break; if (sh > 63) return 0; }
    *field = (int)(key >> 3); *vlen = -1; *val = 0;
    switch ((int)(key & 7)) {
    case 0: sh = 0; while (*i < n) { unsigned char c = b[(*i)++]; v |= (unsigned long long)(c & 0x7f) << sh; sh += 7; if (!(c & 0x80)) break; if (sh > 63) return 0; } *val = v; return 1;
    case 2: sh = 0; while (*i < n) { unsigned char c = b[(*i)++]; v |= (unsigned long long)(c & 0x7f) << sh; sh += 7; if (!(c & 0x80)) break; if (sh > 31) return 0; }
            if (v > (unsigned long long)(n - *i)) return 0;
            *val = (unsigned long long)*i; *vlen = (int)v; *i += (int)v; return 1;
    case 1: if (n - *i < 8) return 0; *i += 8; return 1;
    case 5: if (n - *i < 4) return 0; *i += 4; return 1;
    }
    return 0;
}
static void adss_chan_reply(int fd, unsigned long long call, const unsigned char *pl, int pn) {
    unsigned char b[64]; int o = 0;
    b[o++] = 0x08; o += adss_varint(b + o, call);
    b[o++] = 0x10; b[o++] = 0x01;                                  /* isReply */
    /* returnCode = 0 must be present, not just defaulted: netd's prepareReceive() only builds the reply message of a reply
     * that carries a payload when the header has_return_code bit is set (and the code is 0); without it the call completes
     * with a null reply and netd dereferences it (SIGSEGV in the aow_get*response helpers). */
    if (pl && pn > 0 && pn < 40) { b[o++] = 0x18; b[o++] = 0x00; }
    if (pl && pn > 0 && pn < 40) { b[o++] = 0x22; b[o++] = (unsigned char)pn; for (int i = 0; i < pn; i++) b[o++] = pl[i]; }
    adss_send(fd, b, o);
}
static void adss_chan_msg(struct adss_conn *c, const unsigned char *m, int n) {
    int i = 0, f, vl, isreply = 0, ppos = -1, plen = 0;
    unsigned long long call = 0, v;
    static const unsigned char proxy_ok[] = { 0x08, 0x02, 0x22, 0x02, 0x08, 0x01 };   /* ProxyControlMessage{LaunchProxyReply{true}} */
    int launched = 0;
    while (adss_pb_next(m, n, &i, &f, &v, &vl)) {
        if (f == 1 && vl < 0) call = v;
        else if (f == 2 && vl < 0) isreply = v != 0;
        else if (f == 4 && vl >= 0) { ppos = (int)v; plen = vl; }
    }
    /* NetworkServicesChannel is netd's line to the Windows network proxy (libapipipe: ArcadiaApi.Network.NetworkServicesMessage).
     * netd numbers its calls from 0 and blocks 20 s on every one, so here call id 0 is a request too. Message{1: type=1
     * (response), 3: NetworkServicesResponse{1: type=0 (generic), 2: result=0, 3: GenericResponse{} (no text, no integers)}}:
     * netd's aow_get*response helpers accept that as "done, nothing to report" (empty interface list, empty config, ...).
     * Read off NetworkServicesMessage/Response/GenericResponse::SerializeWithCachedSizes of the firmware's libapipipe.so. */
    static const unsigned char net_ok[] = { 0x08, 0x01, 0x1a, 0x06, 0x08, 0x00, 0x10, 0x00, 0x1a, 0x00 };
    if (!isreply && ppos >= 0 && adss_streq(c->name, "NetworkServicesChannel")) {
        adss_chan_reply(c->hfd, call, net_ok, sizeof net_ok);
        return;
    }
    if (isreply || !call) return;          /* we never send requests; one-way messages need no answer */
    if (ppos >= 0 && adss_streq(c->name, "ProxyControlChannel")) {
        int j = 0, type = 0, spos = -1, slen = 0;
        const unsigned char *p = m + ppos;
        while (adss_pb_next(p, plen, &j, &f, &v, &vl)) {
            if (f == 1 && vl < 0) type = (int)v;
            else if ((f == 2 || f == 3) && vl >= 0) { spos = (int)v; slen = vl; }
        }
        if (spos >= 0 && type < 2) {                                /* LaunchProxy / LaunchProxyForDeveloper */
            int k = 0; const unsigned char *q = p + spos;
            while (adss_pb_next(q, slen, &k, &f, &v, &vl)) {
                if (f == 1 && vl >= 0 && vl < ADSS_NAME) {
                    char pkg[ADSS_NAME];
                    for (int t = 0; t < vl; t++) pkg[t] = (char)q[(int)v + t];
                    pkg[vl] = 0;
                    adss_launch_proxy(pkg);
                    launched = 1;
                }
            }
        }
    }
    if (launched) adss_chan_reply(c->hfd, call, proxy_ok, sizeof proxy_ok);
    else adss_chan_reply(c->hfd, call, 0, 0);
}

/* ---------------------------------------------------------------------------------------- host: the thread */

static void adss_host_main(void) {
    static unsigned char buf[4096];
    unsigned long long all = ~0ULL;
    struct adss_pollfd pf[ADSS_MAX_CONN + 1];
    int idx[ADSS_MAX_CONN + 1];
    sys4(175 /* rt_sigprocmask */, 0 /* SIG_BLOCK */, (long)&all, 0, 8);
    adss_log("host thread up wake fd", g_adss_wake_r, 0, 0, 0);
    for (;;) {
        int n = 1;
        pf[0].fd = g_adss_wake_r; pf[0].events = 1; pf[0].revents = 0; idx[0] = -1;
        adss_lock();
        for (int i = 0; i < ADSS_MAX_CONN; i++)
            if (g_conn[i].used) { pf[n].fd = g_conn[i].hfd; pf[n].events = 1; pf[n].revents = 0; idx[n] = i; n++; }
        adss_unlock();
        { long pr = sys3(168 /* poll */, (long)pf, n, -1); adss_log("host poll nfds ret", n, pr, 0, 0); if (pr < 0) continue; }
        if (pf[0].revents) sys3(3, g_adss_wake_r, (long)buf, 64);
        for (int k = 1; k < n; k++) {
            struct adss_conn *c = &g_conn[idx[k]];
            long len;
            if (!pf[k].revents) continue;
            len = sys3(3, c->hfd, (long)buf, sizeof buf);
            adss_log("host read fd len", c->hfd, len, buf, len > 0 ? (int)len : 0);
            if (len == -4 || len == -11) continue;
            if (len <= 0) { adss_lock(); sys3(6, c->hfd, 0, 0); c->used = 0; adss_unlock(); continue; }
            if (c->kind != ADSS_CONN_RAT) { adss_chan_msg(c, buf, (int)len); continue; }
            /* RAT link is a byte stream: reassemble [len][cmd][tid]... commands */
            for (int i = 0; i < len && c->rxn < (int)sizeof c->rx; i++) c->rx[c->rxn++] = buf[i];
            while (c->rxn >= 4) {
                int want = (int)adss_le32(c->rx);
                if (want < 12 || want > (int)sizeof c->rx) { c->rxn = 0; break; }   /* garbage: resync by dropping */
                if (c->rxn < want) break;
                adss_rat_msg(c, c->rx, want);
                for (int i = want; i < c->rxn; i++) c->rx[i - want] = c->rx[i];
                c->rxn -= want;
            }
        }
    }
}

/* thread without libc: the function pointer rides on the child's stack, TLS is inherited and never touched */
__attribute__((naked, noinline)) static long adss_clone(long flags, void *stack_top, void (*fn)(void)) {
    __asm__ volatile(
        "push {r4, r7, lr}\n"
        "str r2, [r1, #-4]!\n"
        "mov r2, #0\n"
        "mov r3, #0\n"
        "mov r4, #0\n"
        "mov r7, #120\n"                 /* __NR_clone */
        "svc #0\n"
        "cmp r0, #0\n"
        "bne 1f\n"
        "pop {r0}\n"
        "blx r0\n"
        "mov r0, #0\n"
        "mov r7, #1\n"                   /* __NR_exit: this thread only */
        "svc #0\n"
        "1: pop {r4, r7, pc}\n");
}

static int adss_host_start(void) {
    adss_log("host start", 0, 0, 0, 0);
    int w[2], ok = 0;
    adss_lock();
    if (g_adss_started) { adss_unlock(); return 0; }
    if (sys3(359 /* pipe2 */, (long)w, 02000000 | 04000 /* O_CLOEXEC|O_NONBLOCK */, 0) == 0) {
        g_adss_wake_r = w[0]; g_adss_wake_w = w[1];
        long ct = adss_clone(0x50f00 /* VM|FS|FILES|SIGHAND|THREAD|SYSVSEM */, g_adss_stack + sizeof g_adss_stack, adss_host_main);
        adss_log("clone ret", ct, 0, 0, 0);
        if (ct > 0) { g_adss_started = 1; ok = 1; }
        else { sys3(6, w[0], 0, 0); sys3(6, w[1], 0, 0); g_adss_wake_r = g_adss_wake_w = -1; }
    }
    adss_unlock();
    return ok ? 0 : -1;
}
/* RatControl was registered: start the host and let the system proxy connect, as Windows does at phone boot */
static void adss_rat_boot(void) {
    int sv[2];
    struct adss_srv *s = adss_srv_by_name("RatControl");
    if (!s || adss_host_start() < 0 || adss_pair(sv, 1) < 0) return;
    if (!adss_conn_add(ADSS_CONN_RAT, 1, "", sv[1])) { sys3(6, sv[0], 0, 0); sys3(6, sv[1], 0, 0); return; }
    if (!adss_srv_deliver(s, sv[0])) sys3(6, sv[0], 0, 0);
}

/* ------------------------------------------------------------------------------------------ guest-facing hooks */

/* raw result; -4096 means "not one of ours" */
static long adss_open(const char *path, int flags) {
    if (!adss_streq(path, "/dev/adss") && !adss_streq(path, "/dev/AdssBus")) return -4096;
    long fd = sys3(5 /* open */, (long)"/dev/null", 2 /* O_RDWR */ | 0400000, 0);
    if (fd < 0) return fd;
    if (flags & 02000000) sys3(55 /* fcntl */, fd, 2 /* F_SETFD */, 1 /* FD_CLOEXEC */);
    adss_remember((int)fd, ADSS_KIND_BUS);
    return fd;
}

/* raw result; -4096 means "not one of ours" */
static long adss_ioctl(int fd, unsigned req, void *arg) {
    int kind = adss_kind(fd);
    if (!kind) return -4096;
    if (kind == ADSS_KIND_BUS) {
        if (req == ADSS_IOC_REGISTER && arg) {
            int p[2];
            char name[ADSS_NAME];
            long r = sys3(42 /* pipe */, (long)p, 0, 0);
            if (r < 0) return r;
            adss_copy(name, ((const char **)arg)[0], ADSS_NAME);
            sys3(55, p[0], 2, 1); sys3(55, p[1], 2, 1);          /* FD_CLOEXEC on both: only forks (system_server) inherit them */
            adss_remember(p[0], ADSS_KIND_SERVER);
            adss_srv_add(name, p[0], p[1]);
            ((int *)arg)[0] = p[0];                               /* the write end stays open: a read on p[0] blocks until a client is delivered */
            if (adss_streq(name, "RatControl")) adss_rat_boot();
            return 0;
        }
        if (req == ADSS_IOC_CONNECT) return -111;                 /* ECONNREFUSED: the host serves nothing the guest could connect to */
        if (req == ADSS_IOC_FORK) return 0;                       /* Dalvik tells the driver it is about to fork */
        /* netd's read_package_list(): hands the parsed package table (uid, flags, name; 12 bytes each) to the host proxy and
         * only checks the return value. Nothing on this side consumes it, so accept it; without this netd logs "failed to
         * send adss ioctl: Not a typewriter", never answers system_server's listInterfaces and the boot stalls in
         * ConnectivityService (black fb0). */
        if (req == ADSS_IOC_PKGLIST) return 0;
        return -25;                                               /* ENOTTY */
    }
    /* ADSS_KIND_SERVER */
    if (req == ADSS_IOC_WAIT) {
        struct adss_srv *s = adss_srv_by_fd(fd);
        for (;;) {
            char c;
            long r;
            int got = s ? adss_srv_take(s) : -1;
            if (got >= 0) { adss_log("WAIT returns fd", got, fd, 0, 0); if (arg) ((int *)arg)[0] = got; return 0; }
            r = sys3(3 /* read */, fd, (long)&c, 1);
            if (r == -4 /* EINTR */ || r > 0) continue;       /* a wake-up byte: look at the queue again */
            if (r < 0) return r;                              /* the server was closed under us */
            { struct timespec_s ts = { 1, 0 }; sys3(162 /* nanosleep */, (long)&ts, 0, 0); }   /* EOF (cannot happen): sleep instead of spinning */
        }
    }
    return -25;
}

/* ---------------------------------------------------------------------------------- guest: read() of a channel fd */

/* The firmware's receive side (libintersystemscommunications.so, PacketIOAdssBus::receive(), used by netd's AowNetProxy)
 * does not read a channel message in one go. It first calls read(fd, buf, 4) and relies on the ADSS driver's answer
 * for a message that is longer than that: read() fails with EOVERFLOW (75) and the driver leaves the message's size in
 * the first four bytes of buf, the message itself stays queued. Only then does it read the whole message with
 * read(fd, buf, size) and insists on getting exactly `size` bytes back.
 * A SEQPACKET end of the stand-in has no such rule: it simply truncates the datagram to the 4 bytes asked for and
 * drops the rest. netd then took the first four bytes of the host's reply (callId 0, isReply) for the whole message,
 * built a reply with no payload, and aow_get*response() dereferenced the null result (SIGSEGV, fault address 0x1c,
 * netd+0x9c40) on the first command that reached the proxy.
 * This returns -EOVERFLOW + size for exactly that probe (4-byte read of a SEQPACKET socket with a longer datagram
 * pending) and -4096 for everything else, which the caller then reads the normal way. */
static long adss_read(int fd, void *buf, unsigned long n) {
    int type = 0; unsigned tl = sizeof type;
    unsigned size;
    long m;
    if (n != 4 || !buf) return -4096;
    if (sys5(295 /* getsockopt */, fd, 1 /* SOL_SOCKET */, 3 /* SO_TYPE */, (long)&type, (long)&tl) < 0 || type != 5 /* SOCK_SEQPACKET */) return -4096;
    m = sys4(291 /* recv */, fd, (long)buf, 4, 0x2 | 0x20 | 0x40 /* MSG_PEEK | MSG_TRUNC | MSG_DONTWAIT: look, don't take */);
    if (m <= 4) return -4096;                 /* nothing pending (EAGAIN: the real read blocks), or it fits: plain read */
    size = (unsigned)m;
    *(unsigned *)buf = size;
    return -75;                               /* EOVERFLOW */
}

#endif /* ADSS_H */
