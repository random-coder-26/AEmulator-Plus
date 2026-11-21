/*
 * aemu-keymaster-answerer - the "emulator host" side of android.hardware.keymaster@4.1-service.remote.
 *
 * Added for AEmulator Plus (2026-11-14).
 *
 * WHAT THE FIRMWARE EXPECTS (read from the disassembly of the shipped binaries):
 *
 *   android.hardware.keymaster@4.1-service.remote (device/google/kiwi/guest/hals/keymaster/remote) is a CLIENT. It never
 *   computes a key itself. At start it opens a byte stream to a keymaster that lives outside the guest:
 *
 *     - ro.boot.vsock_keymaster_port != 0  ->  cuttlefish::SharedFD::VsockClient(CID_HOST = 2, port, SOCK_STREAM)
 *     - ro.boot.vsock_keymaster_port == 0  ->  SharedFD::Open("/dev/hvc1", O_RDWR) + tcgetattr/cfmakeraw (a tty)
 *     - the stream can't be opened         ->  LOG(FATAL) "Could not connect to keymaster: No such file or directory", exit 134
 *
 *   Over that stream it speaks cuttlefish::KeymasterChannel (libkiwicuttlefish_security.so). This is NOT the stock Cuttlefish
 *   framing (8-byte bit-field header): kiwi's header is 12 bytes, little endian,
 *
 *       struct { uint32_t cmd; uint8_t is_response; uint8_t pad[3]; uint32_t payload_size; uint8_t payload[]; }
 *
 *   and the payload is the serialization of the keymaster::*Request / *Response of AOSP's system/keymaster
 *   (android_keymaster_messages.h), exactly what AndroidKeymaster consumes. The client sends CONFIGURE (18, message version 3,
 *   the OS version and patch level) right after it connects, then forwards every IKeymasterDevice 4.0/4.1 call as one request
 *   and waits for exactly one reply frame (it does not look at the reply's cmd). Commands it uses: 0-6, 8, 15-23, 25-27.
 *
 * WHAT THIS DOES: the other end of that stream. It listens on a unix socket in the guest's dev/socket (which the emulator already
 * keeps as real host sockets, like /dev/socket/rild), and answers every frame with the AOSP software keymaster
 * (keymaster::AndroidKeymaster + PureSoftKeymasterContext) taken from the keymaster libraries the firmware itself ships
 * (libkeymaster_portable / libkeymaster_messages / libpuresoftkeymasterdevice): the same code the AOSP software HAL runs, and
 * what Cuttlefish's host-side secure_env does, minus the TPM. It keeps no state of its own: PureSoftKeymasterContext wraps key
 * blobs with a fixed software master key, so blobs stay valid across restarts of this program and of the guest.
 *
 *   aemu-keymaster-answerer [--tty LINK] [--level tee|software] [-v]
 *       LINK  default /dev/hvc1: the path the firmware opens when ro.boot.vsock_keymaster_port is 0. A pty is made and LINK
 *             is a symlink to its slave: no patch to any firmware file, no property. (--socket PATH: unix socket, for tests)
 *       level the security level of the context (default tee: what the HAL in front of it reports)
 *
 * Header-light on purpose: only AOSP's keymaster message/engine headers are used; the context is built through its exported
 * constructor so no BoringSSL headers are needed. Build: build-keymaster-answerer.sh. Host test: tests/run-host-test.sh.
 */
#include <errno.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/un.h>
#include <termios.h>
#include <time.h>
#include <unistd.h>

#include <vector>

#include <keymaster/android_keymaster.h>
#include <keymaster/android_keymaster_messages.h>

#ifndef AEMU_KM_HOSTTEST
/* PureSoftKeymasterContext(KmVersion, keymaster_security_level_t), exported by libpuresoftkeymasterdevice.so. Built by hand:
 * its header would pull in BoringSSL's. The object is allocated far larger than it is (a few hundred bytes) and never freed. */
extern "C" void aemu_psc_ctor(void* self, keymaster::KmVersion v, keymaster_security_level_t level)
    __asm__("_ZN9keymaster24PureSoftKeymasterContextC1ENS_9KmVersionE26keymaster_security_level_t");
#else
extern "C" void aemu_psc_ctor(void* self, keymaster::KmVersion v, keymaster_security_level_t level);
#endif

using namespace keymaster;

static int g_verbose = 0;
static int g_logged = 0;

static void logf_(const char* fmt, ...) __attribute__((format(printf, 1, 2)));
static void logf_(const char* fmt, ...) {
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    struct tm tm;
    time_t t = ts.tv_sec;
    localtime_r(&t, &tm);
    char buf[512];
    int n = snprintf(buf, sizeof buf, "%02d:%02d:%02d aemu-keymaster-answerer: ", tm.tm_hour, tm.tm_min, tm.tm_sec);
    va_list ap;
    va_start(ap, fmt);
    n += vsnprintf(buf + n, sizeof buf - n - 2, fmt, ap);
    va_end(ap);
    if (n > (int)sizeof buf - 2) n = sizeof buf - 2;
    buf[n++] = '\n';
    ssize_t w = write(2, buf, n);
    (void)w;
}

/* ---- framing (12-byte kiwi header) ---------------------------------------------------------------------------------- */

struct FrameHeader {
    uint32_t cmd;
    uint8_t is_response;
    uint8_t pad[3];
    uint32_t payload_size;
};
static_assert(sizeof(FrameHeader) == 12, "kiwi KeymasterChannel header is 12 bytes");

static const uint32_t kMaxPayload = 16u << 20;  // a wrapped key / certificate chain is far below this

static bool read_exact(int fd, void* p, size_t n) {
    uint8_t* b = static_cast<uint8_t*>(p);
    while (n) {
        ssize_t r = read(fd, b, n);
        if (r > 0) { b += r; n -= r; continue; }
        if (r < 0 && errno == EINTR) continue;
        return false;  // EOF or error
    }
    return true;
}

static bool write_all(int fd, const void* p, size_t n) {
    const uint8_t* b = static_cast<const uint8_t*>(p);
    while (n) {
        ssize_t r = send(fd, b, n, MSG_NOSIGNAL);
        if (r < 0 && errno == ENOTSOCK) r = write(fd, b, n);   // the pty master
        if (r > 0) { b += r; n -= r; continue; }
        if (r < 0 && errno == EINTR) continue;
        return false;
    }
    return true;
}

static const char* cmd_name(uint32_t c) {
    static const char* const names[] = {
        "GENERATE_KEY", "BEGIN_OPERATION", "UPDATE_OPERATION", "FINISH_OPERATION", "ABORT_OPERATION", "IMPORT_KEY",
        "EXPORT_KEY", "GET_VERSION", "ADD_RNG_ENTROPY", "GET_SUPPORTED_ALGORITHMS", "GET_SUPPORTED_BLOCK_MODES",
        "GET_SUPPORTED_PADDING_MODES", "GET_SUPPORTED_DIGESTS", "GET_SUPPORTED_IMPORT_FORMATS",
        "GET_SUPPORTED_EXPORT_FORMATS", "GET_KEY_CHARACTERISTICS", "ATTEST_KEY", "UPGRADE_KEY", "CONFIGURE",
        "GET_HMAC_SHARING_PARAMETERS", "COMPUTE_SHARED_HMAC", "VERIFY_AUTHORIZATION", "DELETE_KEY", "DELETE_ALL_KEYS",
        "DESTROY_ATTESTATION_IDS", "IMPORT_WRAPPED_KEY", "EARLY_BOOT_ENDED", "DEVICE_LOCKED", "GET_VERSION_2",
        "GENERATE_RKP_KEY", "GENERATE_CSR", "GENERATE_TIMESTAMP_TOKEN", "CONFIGURE_VENDOR_PATCHLEVEL",
        "CONFIGURE_BOOT_PATCHLEVEL"};
    return c < sizeof names / sizeof names[0] ? names[c] : "?";
}

/* ---- dispatch ------------------------------------------------------------------------------------------------------- */

struct Reply {
    std::vector<uint8_t> payload;
    keymaster_error_t error = KM_ERROR_OK;
};

static void put(const KeymasterResponse& r, Reply& out) {
    size_t n = r.SerializedSize();
    out.payload.assign(n, 0);
    uint8_t* end = r.Serialize(out.payload.data(), out.payload.data() + n);
    if (end != out.payload.data() + n) out.payload.resize(end > out.payload.data() ? end - out.payload.data() : 0);
    out.error = r.error;
}

/* The client's requests carry no version field: the version is the one negotiated on this side (the default, 3, until a
 * GetVersion/GetVersion2 says otherwise; the firmware never sends one), and responses must be built with the same. */
#define NEW_REQ(Req) Req req(km.message_version())

template <class Req, class Rsp>
static void call_void(AndroidKeymaster& km, void (AndroidKeymaster::*m)(const Req&, Rsp*), const uint8_t* p, size_t n, Reply& out) {
    Req req(km.message_version());
    Rsp rsp(km.message_version());
    const uint8_t* q = p;
    if (!req.Deserialize(&q, p + n)) rsp.error = KM_ERROR_INVALID_ARGUMENT;
    else (km.*m)(req, &rsp);
    put(rsp, out);
}

/* GenerateTimestampToken takes its request non-const */
template <class Req, class Rsp>
static void call_void_nc(AndroidKeymaster& km, void (AndroidKeymaster::*m)(Req&, Rsp*), const uint8_t* p, size_t n, Reply& out) {
    Req req(km.message_version());
    Rsp rsp(km.message_version());
    const uint8_t* q = p;
    if (!req.Deserialize(&q, p + n)) rsp.error = KM_ERROR_INVALID_ARGUMENT;
    else (km.*m)(req, &rsp);
    put(rsp, out);
}

template <class Req, class Rsp>
static void call_ret(AndroidKeymaster& km, Rsp (AndroidKeymaster::*m)(const Req&), const uint8_t* p, size_t n, Reply& out) {
    Req req(km.message_version());
    const uint8_t* q = p;
    if (!req.Deserialize(&q, p + n)) {
        Rsp bad(km.message_version());
        bad.error = KM_ERROR_INVALID_ARGUMENT;
        put(bad, out);
        return;
    }
    Rsp rsp = (km.*m)(req);
    put(rsp, out);
}

template <class Rsp>
static void call_ret0(AndroidKeymaster& km, Rsp (AndroidKeymaster::*m)(), Reply& out) {
    Rsp rsp = (km.*m)();
    put(rsp, out);
}

static void unimplemented(AndroidKeymaster& km, Reply& out) {
    EmptyKeymasterResponse rsp(km.message_version());
    rsp.error = KM_ERROR_UNIMPLEMENTED;
    put(rsp, out);
}

static void dispatch(AndroidKeymaster& km, uint32_t cmd, const uint8_t* p, size_t n, Reply& out) {
    switch (cmd) {
        case GENERATE_KEY:                call_void(km, &AndroidKeymaster::GenerateKey, p, n, out); break;
        case BEGIN_OPERATION:             call_void(km, &AndroidKeymaster::BeginOperation, p, n, out); break;
        case UPDATE_OPERATION:            call_void(km, &AndroidKeymaster::UpdateOperation, p, n, out); break;
        case FINISH_OPERATION:            call_void(km, &AndroidKeymaster::FinishOperation, p, n, out); break;
        case ABORT_OPERATION:             call_void(km, &AndroidKeymaster::AbortOperation, p, n, out); break;
        case IMPORT_KEY:                  call_void(km, &AndroidKeymaster::ImportKey, p, n, out); break;
        case EXPORT_KEY:                  call_void(km, &AndroidKeymaster::ExportKey, p, n, out); break;
        case GET_VERSION:                 call_void(km, &AndroidKeymaster::GetVersion, p, n, out); break;
        case ADD_RNG_ENTROPY:             call_void(km, &AndroidKeymaster::AddRngEntropy, p, n, out); break;
        case GET_SUPPORTED_ALGORITHMS:    call_void(km, &AndroidKeymaster::SupportedAlgorithms, p, n, out); break;
        case GET_SUPPORTED_BLOCK_MODES:   call_void(km, &AndroidKeymaster::SupportedBlockModes, p, n, out); break;
        case GET_SUPPORTED_PADDING_MODES: call_void(km, &AndroidKeymaster::SupportedPaddingModes, p, n, out); break;
        case GET_SUPPORTED_DIGESTS:       call_void(km, &AndroidKeymaster::SupportedDigests, p, n, out); break;
        case GET_SUPPORTED_IMPORT_FORMATS: call_void(km, &AndroidKeymaster::SupportedImportFormats, p, n, out); break;
        case GET_SUPPORTED_EXPORT_FORMATS: call_void(km, &AndroidKeymaster::SupportedExportFormats, p, n, out); break;
        case GET_KEY_CHARACTERISTICS:     call_void(km, &AndroidKeymaster::GetKeyCharacteristics, p, n, out); break;
        case ATTEST_KEY:                  call_void(km, &AndroidKeymaster::AttestKey, p, n, out); break;
        case UPGRADE_KEY:                 call_void(km, &AndroidKeymaster::UpgradeKey, p, n, out); break;
        case CONFIGURE:                   call_void(km, &AndroidKeymaster::Configure, p, n, out); break;
        case GET_HMAC_SHARING_PARAMETERS: call_ret0(km, &AndroidKeymaster::GetHmacSharingParameters, out); break;
        case COMPUTE_SHARED_HMAC:         call_ret(km, &AndroidKeymaster::ComputeSharedHmac, p, n, out); break;
        case VERIFY_AUTHORIZATION:        call_ret(km, &AndroidKeymaster::VerifyAuthorization, p, n, out); break;
        case DELETE_KEY:                  call_void(km, &AndroidKeymaster::DeleteKey, p, n, out); break;
        case DELETE_ALL_KEYS:             call_void(km, &AndroidKeymaster::DeleteAllKeys, p, n, out); break;
        case IMPORT_WRAPPED_KEY:          call_void(km, &AndroidKeymaster::ImportWrappedKey, p, n, out); break;
        case EARLY_BOOT_ENDED:            call_ret0(km, &AndroidKeymaster::EarlyBootEnded, out); break;
        case DEVICE_LOCKED:               call_ret(km, &AndroidKeymaster::DeviceLocked, p, n, out); break;
        case GET_VERSION_2:               call_ret(km, &AndroidKeymaster::GetVersion2, p, n, out); break;
        case GENERATE_RKP_KEY:            call_void(km, &AndroidKeymaster::GenerateRkpKey, p, n, out); break;
        case GENERATE_CSR:                call_void(km, &AndroidKeymaster::GenerateCsr, p, n, out); break;
        case GENERATE_TIMESTAMP_TOKEN:    call_void_nc(km, &AndroidKeymaster::GenerateTimestampToken, p, n, out); break;
        case CONFIGURE_VENDOR_PATCHLEVEL: call_ret(km, &AndroidKeymaster::ConfigureVendorPatchlevel, p, n, out); break;
        case CONFIGURE_BOOT_PATCHLEVEL:   call_ret(km, &AndroidKeymaster::ConfigureBootPatchlevel, p, n, out); break;
        default:                          unimplemented(km, out); break;  // DESTROY_ATTESTATION_IDS and anything newer
    }
}

/* one request frame in, one reply frame out; false when the connection is over */
static bool serve_one(AndroidKeymaster& km, int fd) {
    FrameHeader h;
    if (!read_exact(fd, &h, sizeof h)) return false;
    if (h.payload_size > kMaxPayload) {
        logf_("frame of %u bytes (cmd %u): refused, closing", h.payload_size, h.cmd);
        return false;
    }
    std::vector<uint8_t> in(h.payload_size);
    if (h.payload_size && !read_exact(fd, in.data(), in.size())) return false;

    Reply out;
    dispatch(km, h.cmd, in.data(), in.size(), out);

    if (g_verbose || out.error != KM_ERROR_OK || g_logged < 60) {
        g_logged++;
        logf_("%s (%u): request %u bytes -> error %d, reply %zu bytes", cmd_name(h.cmd), h.cmd, h.payload_size,
              (int)out.error, out.payload.size());
    }

    FrameHeader r;
    memset(&r, 0, sizeof r);
    r.cmd = h.cmd;
    r.is_response = 1;
    r.payload_size = static_cast<uint32_t>(out.payload.size());
    std::vector<uint8_t> frame(sizeof r + out.payload.size());
    memcpy(frame.data(), &r, sizeof r);
    if (!out.payload.empty()) memcpy(frame.data() + sizeof r, out.payload.data(), out.payload.size());
    return write_all(fd, frame.data(), frame.size());
}

/* ---- socket --------------------------------------------------------------------------------------------------------- */

static void mkdirs_for(const char* path) {
    char tmp[sizeof(sockaddr_un::sun_path)];
    snprintf(tmp, sizeof tmp, "%s", path);
    for (char* c = tmp + 1; *c; c++) {
        if (*c != '/') continue;
        *c = 0;
        mkdir(tmp, 0771);
        *c = '/';
    }
}

static int listen_unix(const char* path) {
    sockaddr_un a;
    memset(&a, 0, sizeof a);
    a.sun_family = AF_UNIX;
    if (strlen(path) >= sizeof a.sun_path) {
        logf_("socket path too long: %s", path);
        return -1;
    }
    strcpy(a.sun_path, path);
    mkdirs_for(path);
    unlink(path);
    int s = socket(AF_UNIX, SOCK_STREAM, 0);
    if (s < 0) { logf_("socket: %s", strerror(errno)); return -1; }
    if (bind(s, reinterpret_cast<sockaddr*>(&a), sizeof a) < 0) { logf_("bind %s: %s", path, strerror(errno)); close(s); return -1; }
    chmod(path, 0666);  // the HAL runs as system, this as whoever the emulator starts it as
    if (listen(s, 4) < 0) { logf_("listen: %s", strerror(errno)); close(s); return -1; }
    return s;
}

/* The firmware's own path: it opens /dev/hvc1 O_RDWR and makes it raw (tcgetattr must work: a tty). A pty slave is one.
 * The slave is kept open here so the master never sees a hang-up when the service closes or restarts. */
static int open_pty(const char* link, int* keep_slave) {
    int m = posix_openpt(O_RDWR | O_NOCTTY);
    if (m < 0 || grantpt(m) < 0 || unlockpt(m) < 0) { logf_("pty: %s", strerror(errno)); return -1; }
    char name[128];
    if (ptsname_r(m, name, sizeof name)) { logf_("ptsname: %s", strerror(errno)); return -1; }
    int s = open(name, O_RDWR | O_NOCTTY);
    if (s < 0) { logf_("open %s: %s", name, strerror(errno)); return -1; }
    struct termios t;
    if (tcgetattr(s, &t) == 0) { cfmakeraw(&t); tcsetattr(s, TCSANOW, &t); }
    chmod(name, 0666);
    mkdirs_for(link);
    unlink(link);
    if (symlink(name, link) < 0) { logf_("symlink %s -> %s: %s", link, name, strerror(errno)); return -1; }
    *keep_slave = s;
    logf_("%s -> %s", link, name);
    return m;
}

int main(int argc, char** argv) {
    const char* path = nullptr;            // --socket: unix-socket transport (tests); default is the tty below
    const char* tty = "/dev/hvc1";
    keymaster_security_level_t level = KM_SECURITY_LEVEL_TRUSTED_ENVIRONMENT;
    for (int i = 1; i < argc; i++) {
        if (!strcmp(argv[i], "--socket") && i + 1 < argc) path = argv[++i];
        else if (!strcmp(argv[i], "--tty") && i + 1 < argc) tty = argv[++i];
        else if (!strcmp(argv[i], "--level") && i + 1 < argc) {
            const char* v = argv[++i];
            level = !strcmp(v, "software") ? KM_SECURITY_LEVEL_SOFTWARE : KM_SECURITY_LEVEL_TRUSTED_ENVIRONMENT;
        } else if (!strcmp(argv[i], "-v")) g_verbose = 1;
        else { fprintf(stderr, "usage: %s [--tty LINK | --socket PATH] [--level tee|software] [-v]\n", argv[0]); return 2; }
    }
    signal(SIGPIPE, SIG_IGN);

    void* ctx = nullptr;
    if (posix_memalign(&ctx, 16, 16384)) { logf_("out of memory"); return 1; }
    memset(ctx, 0, 16384);
    aemu_psc_ctor(ctx, KmVersion::KEYMASTER_4_1, level);
    // (AndroidKeymaster owns the context; the first base of PureSoftKeymasterContext is KeymasterContext: same address)
    static AndroidKeymaster km(static_cast<KeymasterContext*>(ctx), 64);

    if (!path) {
        int keep = -1;
        int m = open_pty(tty, &keep);
        if (m < 0) return 1;
        logf_("software keymaster ready on %s (level %d, message version %u)", tty, (int)level, km.message_version());
        for (;;) {
            pollfd p = {m, POLLIN, 0};
            if (poll(&p, 1, -1) < 0 && errno != EINTR) return 1;
            if ((p.revents & POLLIN) && !serve_one(km, m)) { logf_("pty read failed (%s), retrying", strerror(errno)); usleep(200000); }
        }
    }
    int ls = listen_unix(path);
    if (ls < 0) return 1;
    logf_("software keymaster ready on %s (level %d, message version %u)", path, (int)level, km.message_version());

    std::vector<int> clients;
    for (;;) {
        std::vector<pollfd> pf;
        pf.push_back({ls, POLLIN, 0});
        for (int c : clients) pf.push_back({c, POLLIN, 0});
        int r = poll(pf.data(), pf.size(), -1);
        if (r < 0) { if (errno == EINTR) continue; logf_("poll: %s", strerror(errno)); return 1; }
        if (pf[0].revents & POLLIN) {
            int c = accept(ls, nullptr, nullptr);
            if (c >= 0) {
                fcntl(c, F_SETFD, FD_CLOEXEC);
                clients.push_back(c);
                logf_("client connected (%zu)", clients.size());
            }
        }
        for (size_t i = 1; i < pf.size(); i++) {
            if (!(pf[i].revents & (POLLIN | POLLHUP | POLLERR))) continue;
            int c = pf[i].fd;
            if (!serve_one(km, c)) {
                close(c);
                for (size_t k = 0; k < clients.size(); k++) if (clients[k] == c) { clients.erase(clients.begin() + k); break; }
                logf_("client gone (%zu left)", clients.size());
            }
        }
    }
}
