/*
 * Host test of the "solo" mode of guest.c (no software renderer behind the bridge: x86 / x86-64 firmware).
 *   sh run-solo.sh
 * guest.c is included whole so that the statics can be reached; bionic's two imports are stood in for. There is no
 * host renderer socket here, so contexts cannot be made: that has to fail cleanly (EGL_BAD_ALLOC), not crash.
 */
#include <stdarg.h>
#include <stdio.h>
#include "../guest.c"

int __android_log_print(int prio, const char *tag, const char *fmt, ...) {
    (void)prio; va_list ap; va_start(ap, fmt);
    fprintf(stderr, "  [%s] ", tag); vfprintf(stderr, fmt, ap); fputc('\n', stderr);
    va_end(ap); return 0;
}
int __system_property_get(const char *name, char *value) { (void)name; value[0] = 0; return 0; }

static int bad;
#define CHECK(c) do { if (!(c)) { printf("FAIL line %d: %s\n", __LINE__, #c); bad++; } } while (0)

static int refs;
static void inc(struct native_base *b) { (void)b; refs++; }
static void dec(struct native_base *b) { (void)b; refs--; }

int main(void) {
    CHECK(solo());
    EGLDisplay d = eglGetDisplay(0);
    CHECK(d != 0);
    EGLint maj = 0, min = 0;
    CHECK(eglInitialize(d, &maj, &min) && maj == 1 && min == 4);
    CHECK(eglInitialize((void *)0x1234, 0, 0) == 0 && eglGetError() == 0x3008);
    CHECK(!strcmp(eglQueryString(d, 0x3054), "1.4 AEmulator GL bridge"));
    const char *ext = eglQueryString(d, 0x3055);
    CHECK(ext && strstr(ext, "EGL_ANDROID_image_native_buffer") && strstr(ext, "EGL_KHR_surfaceless_context"));
    CHECK(strstr(ext, "EGL_KHR_fence_sync") && strstr(ext, "EGL_EXT_create_context_robustness"));
    CHECK(!strcmp(eglQueryString(d, 0x308D), "OpenGL_ES"));
    CHECK(eglBindAPI(0x30A0) == 1 && eglQueryAPI() == 0x30A0 && eglBindAPI(0x30A1) == 0 && eglGetError() == 0x300C);

    EGLint n = 0;
    CHECK(eglGetConfigs(d, 0, 0, &n) && n == 9);
    EGLConfig all[16];
    CHECK(eglGetConfigs(d, all, 4, &n) && n == 4);

    /* SurfaceFlinger's RenderEngine of 10/11: RGBA8888, ES2 (+ES3 try), window and pbuffer, recordable */
    const EGLint sf[] = { 0x3040, 0x4, 0x3033, 0x4 | 0x1, 0x3024, 8, 0x3023, 8, 0x3022, 8, 0x3021, 8, 0x3142, 1, EGL_NONE };
    EGLConfig got[16];
    CHECK(eglChooseConfig(d, sf, got, 16, &n) && n >= 1);
    EGLint v = 0;
    CHECK(eglGetConfigAttrib(d, got[0], 0x302E, &v) && v == 1);          /* first one is a RGBA_8888 config */
    for (int i = 0; i < n; i++) {
        CHECK(eglGetConfigAttrib(d, got[i], 0x3021, &v) && v == 8);
        CHECK(eglGetConfigAttrib(d, got[i], 0x3040, &v) && (v & 4));
    }
    CHECK(n == 3);                                                           /* the three RGBA 8888 ones */
    /* a window of the framebuffer target (hwc): RGBX is allowed, a 565 one is not asked for */
    const EGLint fb[] = { 0x3147, 1, 0x3040, 4, 0x3024, 8, 0x3023, 8, 0x3022, 8, EGL_NONE };
    CHECK(eglChooseConfig(d, fb, got, 16, &n) && n == 6);
    /* 565, no alpha, no depth: the lowest buffer size that fits comes after the larger colour-bit total (spec order) */
    const EGLint c565[] = { 0x3024, 5, 0x3023, 6, 0x3022, 5, 0x3025, 0, 0x3033, 4, EGL_NONE };
    CHECK(eglChooseConfig(d, c565, got, 16, &n) && n == 9);
    CHECK(eglGetConfigAttrib(d, got[0], 0x3024, &v) && v == 8);
    /* exact match on the id */
    const EGLint id7[] = { 0x3028, 7, EGL_NONE };
    CHECK(eglChooseConfig(d, id7, got, 16, &n) && n == 1 && got[0] == (EGLConfig)(uintptr_t)7);
    CHECK(eglGetConfigAttrib(d, got[0], 0x302E, &v) && v == 4);
    /* don't-care and no attributes at all */
    const EGLint dc[] = { 0x3025, -1, 0x3027, -1, EGL_NONE };
    CHECK(eglChooseConfig(d, dc, got, 16, &n) && n == 9);
    CHECK(eglChooseConfig(d, 0, got, 16, &n) && n == 9);
    /* nothing has 16-bit alpha */
    const EGLint nope[] = { 0x3021, 16, EGL_NONE };
    CHECK(eglChooseConfig(d, nope, got, 16, &n) && n == 0);
    CHECK(eglGetConfigAttrib(d, (void *)0x99, 0x3020, &v) == 0 && eglGetError() == 0x3005);
    CHECK(eglGetConfigAttrib(d, all[0], 0x9999, &v) == 0 && eglGetError() == 0x3004);

    /* no host renderer: a context fails with EGL_BAD_ALLOC and nothing else happens */
    const EGLint c2[] = { 0x3098, 2, EGL_NONE };
    CHECK(eglCreateContext(d, all[0], 0, c2) == 0 && eglGetError() == EGL_BAD_ALLOC);

    /* RenderEngine's images are made with EGL_NO_CONTEXT */
    struct ANativeWindowBuffer b = { .common = { .incRef = inc, .decRef = dec }, .width = 64, .height = 64, .stride = 64, .format = 1 };
    EGLImageKHR img = eglCreateImageKHR(d, 0, 0x3140, &b, 0);
    CHECK(img != 0 && refs == 1);
    CHECK(eglDestroyImageKHR(d, img) == 1 && refs == 0);
    CHECK(eglCreateImageKHR(d, 0, 0x30B1, (void *)1, 0) == 0);              /* a texture image needs a context */

    /* sync objects without a context: signalled */
    EGLSyncKHR sy = eglCreateSyncKHR(d, 0x30F9, 0);
    CHECK(sy != 0 && eglClientWaitSyncKHR(d, sy, 0, 0) == 0x30F6 && eglDestroySyncKHR(d, sy) == 1);

    /* surfaces do not need anybody */
    EGLSurface pb = eglCreatePbufferSurface(d, all[0], (const EGLint[]){ EGL_WIDTH, 16, EGL_HEIGHT, 8, EGL_NONE });
    CHECK(pb != 0 && eglQuerySurface(d, pb, EGL_WIDTH, &v) && v == 16 && eglQuerySurface(d, pb, EGL_HEIGHT, &v) && v == 8);
    CHECK(eglQuerySurface(d, pb, 0x3028, &v) && v == 1);
    CHECK(eglDestroySurface(d, pb) == 1);
    CHECK(eglTerminate(d) == 1);

    printf(bad ? "%d check(s) FAILED\n" : "solo smoke: all checks passed\n", bad);
    return bad != 0;
}
