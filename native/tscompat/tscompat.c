/*
 * Added for AEmulator Plus (2026-11-18). aemts.so: the three libcutils / libutils functions that the engine's old HIDL stack
 * (libhwbinder.so of VNDK 28, copied into /vendor/lib64 for the passthrough graphics mapper) imports and that the VNDK 34+
 * libcutils / libutils of an Android 15+ firmware no longer export:
 *   thread_store_get / thread_store_set   (cutils/threads.h, removed)
 *   androidSetThreadName                  (hedge: the next symbol the old libhwbinder names, absent from some newer libutils)
 * Without them dlopen of android.hardware.graphics.mapper@2.0-impl.so fails in SurfaceFlinger's sphal namespace
 * ("cannot locate symbol thread_store_get referenced by /vendor/lib64/libhwbinder.so"), libui logs "gralloc-mapper is missing"
 * and SurfaceFlinger aborts (signal 6, exit code 134) before its first frame.
 *
 * TreeFixer.bringSwiftShaderDepsWithoutVndk() installs this file as /vendor/lib64/aemts.so and points the (otherwise unused)
 * "libdl.so" DT_NEEDED entry of /vendor/lib64/libhwbinder.so at it, so the symbols are found next to the library that wants them
 * and nothing else in the vendor namespace is changed.
 *
 * Semantics are those of AOSP 9 libcutils/threads.c: a store is { pthread_key_t tls; int has_tls; }, the key is created on the
 * first set. A spin lock replaces the mutex so the only imports are pthread_key_create / pthread_getspecific /
 * pthread_setspecific / prctl (bionic's libc, listed as NEEDED).
 */
typedef struct { int tls; int has_tls; } thread_store_t;

extern int pthread_key_create(int *key, void (*destructor)(void *));
extern void *pthread_getspecific(int key);
extern int pthread_setspecific(int key, const void *value);
extern int prctl(int option, ...);

static int g_lock __attribute__((section(".data"), used));

__attribute__((visibility("default"), used))
void *thread_store_get(thread_store_t *store) {
    if (!__atomic_load_n(&store->has_tls, __ATOMIC_ACQUIRE)) return 0;
    return pthread_getspecific(store->tls);
}

__attribute__((visibility("default"), used))
void thread_store_set(thread_store_t *store, void *value, void (*destroy)(void *)) {
    if (!__atomic_load_n(&store->has_tls, __ATOMIC_ACQUIRE)) {
        while (__atomic_exchange_n(&g_lock, 1, __ATOMIC_ACQUIRE)) { }
        if (!store->has_tls) {
            int key;
            if (pthread_key_create(&key, destroy) == 0) {
                store->tls = key;
                __atomic_store_n(&store->has_tls, 1, __ATOMIC_RELEASE);
            }
        }
        __atomic_store_n(&g_lock, 0, __ATOMIC_RELEASE);
    }
    if (__atomic_load_n(&store->has_tls, __ATOMIC_ACQUIRE)) pthread_setspecific(store->tls, value);
}

__attribute__((visibility("default"), used))
void androidSetThreadName(const char *name) {
    prctl(15 /* PR_SET_NAME */, name);
}
