/* AEmulator Plus 2026-11-17: aegr.so, the "libc" of engines/glibc-x86/gralloc.default.so on a glibc Google TV firmware
 * (Logitech Revue, Intel CE4100, glibc 2.7).
 *
 * engines/kk-x86/gralloc.default.so (the AOSP Eclair gralloc, i386) was linked for a bionic userland: DT_NEEDED libcutils.so,
 * liblog.so and libc.so. tools/patch_gralloc_glibc.py rewrites two things in its dynamic string table, and this file is
 * what both rewrites point at:
 *
 *  1. DT_NEEDED libc.so -> aegr.so.  A glibc firmware has libc.so.6 and no libc.so, so the dependency could never be
 *     satisfied.  glibc itself provides everything else the module imports (malloc, mmap, ioctl, snprintf, ...); aegr.so
 *     only defines the three names bionic has and glibc 2.7 does not export:
 *       __errno   bionic's errno accessor; glibc's is __errno_location().
 *       gettid    not in glibc before 2.30 (a bare syscall here).
 *       atexit    glibc keeps it in libc_nonshared.a, i.e. it is linked into programs but NOT exported by libc.so.6, so a
 *                 module that imports it fails dlopen(RTLD_NOW) with "undefined symbol: atexit".  The module is built
 *                 with -fno-use-cxa-atexit, so its static destructors arrive here.
 *     android_atomic_cmpxchg / android_atomic_or are normally exported by the firmware's libcutils (which stays in the
 *     DT_NEEDED list, together with liblog.so and ashmem_create_region / __android_log_print).  They are defined here as a
 *     fallback only: libcutils comes first in the module's lookup scope, so its own copy wins when it has one.
 *
 *  2. pthread_mutex_{init,lock,unlock,destroy} -> aegr_mx_{init,lock,unlock,destroy}.  The imports are renamed, not just
 *     provided: a symbol is looked up in the process-wide scope first, where glibc's own pthread_mutex_* would win over
 *     anything defined here.  The module's mutexes are plain statics (PTHREAD_MUTEX_INITIALIZER, all zero) and Locker objects
 *     inside gralloc's own data; they never need recursion, timeouts or priority protocols.  So the lock is one int, the
 *     FIRST word of whatever pthread_mutex_t the module was compiled with (4 bytes in bionic, 24 in glibc and in the musl
 *     i386 headers the shipped build used): it fits every layout, never touches the other bytes, and does not depend on
 *     libpthread being mapped into the process (SurfaceFlinger and system_server load this module).
 *     The lock spins with sched_yield(): the critical sections are a few loads and stores.
 *
 *  3. The build of 2026-11-17 (single-page fb0 handled with a plain ashmem buffer) also imports three things from bionic's
 *     libstdc++.so / libc.so that a glibc firmware does not have under those names:
 *       _Znwj, _ZdlPv   operator new / delete (bionic's libstdc++ is only these, over malloc / free).  libstdc++.so.6 may or
 *                       may not be mapped into the process, so they are defined here; if it is, its own are found first.
 *       __page_size     bionic's page size variable (read through the GOT).  glibc has no such symbol; i386 pages are 4096.
 *     The module's DT_NEEDED libstdc++.so / libm.so / libdl.so (names glibc does not have; it calls nothing of libm / libdl)
 *     are pointed at this file as well by tools/patch_gralloc_glibc.py.
 *
 * Freestanding i386 (no libc is linked; __errno_location, __cxa_atexit, malloc and free are resolved from libc.so.6 at load
 * time).  Build:
 * native/revue-shims/build-aegr.sh  ->  app/src/main/assets/engines/glibc-x86/aegr.so
 */
typedef unsigned int u32;

extern int *__errno_location(void);
extern int __cxa_atexit(void (*fn)(void *), void *arg, void *dso);
extern void *malloc(u32 n);
extern void free(void *p);

static inline int sys0(int nr) {
    int r;
    __asm__ __volatile__("int $0x80" : "=a"(r) : "0"(nr) : "memory");
    return r;
}

int *__errno(void) { return __errno_location(); }

/* bionic's page size variable; the module only reads it */
unsigned int __page_size = 4096;

/* operator new / delete as bionic's libstdc++ has them: plain malloc / free (no exceptions in this userland) */
void *_Znwj(u32 n) { return malloc(n); }
void _ZdlPv(void *p) { free(p); }

int gettid(void) { return sys0(224 /* __NR_gettid, i386 */); }

static void atexit_call(void *fn) { ((void (*)(void))fn)(); }
/* no dso handle: the handler runs at process exit, never at dlclose(); a gralloc module is never unloaded */
int atexit(void (*fn)(void)) { return __cxa_atexit(atexit_call, (void *)fn, 0); }

int aegr_mx_init(volatile int *m, const void *attr) { (void)attr; *m = 0; return 0; }
int aegr_mx_destroy(volatile int *m) { (void)m; return 0; }
int aegr_mx_lock(volatile int *m) {
    while (__sync_lock_test_and_set(m, 1)) sys0(158 /* __NR_sched_yield, i386 */);
    return 0;
}
int aegr_mx_unlock(volatile int *m) { __sync_lock_release(m); return 0; }

/* fallbacks, see above; the semantics are the ones of the Eclair <cutils/atomic.h> */
int android_atomic_cmpxchg(int old_value, int new_value, volatile int *ptr) {
    return !__sync_bool_compare_and_swap(ptr, old_value, new_value); /* 0 = written */
}
int android_atomic_or(int value, volatile int *ptr) { return __sync_fetch_and_or(ptr, value); /* previous value */ }
