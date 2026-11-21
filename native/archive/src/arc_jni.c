/* JNI glue for app.aemu.archive.NativeArc. Natives are registered in JNI_OnLoad, so nothing but JNI_OnLoad is exported. */
#include <jni.h>
#include <limits.h>
#include <stdlib.h>
#include <string.h>
#include "aemuarc.h"

#define CLS "app/aemu/archive/NativeArc"

static void throw_new(JNIEnv *env, const char *cls, const char *msg) {
    if ((*env)->ExceptionCheck(env)) return;       /* keep the exception that is already on its way */
    jclass c = (*env)->FindClass(env, cls);
    if (c) { (*env)->ThrowNew(env, c, msg); (*env)->DeleteLocalRef(env, c); }
}
static void throw_arc(JNIEnv *env, int rc) {
    if (rc == ARC_E_MEM) throw_new(env, "java/lang/OutOfMemoryError", "native archive library: out of memory");
    else if (rc == ARC_E_UNSUPPORTED) throw_new(env, "java/lang/UnsupportedOperationException", arc_strerror(rc));
    else throw_new(env, "java/io/IOException", arc_strerror(rc));
}

static jstring n_version(JNIEnv *env, jclass k) { (void)k; return (*env)->NewStringUTF(env, arc_version()); }

/* ---- stream decoders ------------------------------------------------------------------------------------------ */

static jlong n_dec_open(JNIEnv *env, jclass k, jint codec) {
    (void)k;
    arc_dec *d = arc_dec_new(codec);
    if (!d) throw_new(env, "java/io/IOException", "cannot create the native decoder");
    return (jlong)(intptr_t)d;
}
static void n_dec_close(JNIEnv *env, jclass k, jlong h) { (void)env; (void)k; arc_dec_free((arc_dec *)(intptr_t)h); }

/* res = { status, in_used, out_made }; no JNI call happens between Get and Release of the critical arrays */
static void n_dec_step(JNIEnv *env, jclass k, jlong h, jbyteArray in, jint in_off, jint in_len, jbyteArray out, jint out_off,
                       jint out_cap, jboolean eof, jintArray res) {
    static const uint8_t none[1] = { 0 };
    (void)k;
    size_t used = 0, made = 0;
    int rc;
    jbyte *ip = in_len > 0 ? (jbyte *)(*env)->GetPrimitiveArrayCritical(env, in, NULL) : NULL;
    jbyte *op = (jbyte *)(*env)->GetPrimitiveArrayCritical(env, out, NULL);
    if (!op || (in_len > 0 && !ip)) {
        if (ip) (*env)->ReleasePrimitiveArrayCritical(env, in, ip, JNI_ABORT);
        if (op) (*env)->ReleasePrimitiveArrayCritical(env, out, op, JNI_ABORT);
        throw_arc(env, ARC_E_MEM);
        return;
    }
    rc = arc_dec_step((arc_dec *)(intptr_t)h, ip ? (const uint8_t *)ip + in_off : none, (size_t)in_len, &used,
                      (uint8_t *)op + out_off, (size_t)out_cap, &made, eof ? 1 : 0);
    (*env)->ReleasePrimitiveArrayCritical(env, out, op, 0);
    if (ip) (*env)->ReleasePrimitiveArrayCritical(env, in, ip, JNI_ABORT);
    {
        jint r[3] = { rc, (jint)used, (jint)made };
        (*env)->SetIntArrayRegion(env, res, 0, 3, r);
    }
}

static jbyteArray to_java(JNIEnv *env, uint8_t *buf, size_t len) {
    jbyteArray r;
    if (len > INT_MAX) { arc_free(buf); throw_new(env, "java/lang/OutOfMemoryError", "decoded data is larger than 2 GB"); return NULL; }
    r = (*env)->NewByteArray(env, (jsize)len);
    if (r) (*env)->SetByteArrayRegion(env, r, 0, (jsize)len, (const jbyte *)buf);
    arc_free(buf);
    return r;
}

static jbyteArray n_decode_all(JNIEnv *env, jclass k, jint codec, jbyteArray in, jint off, jint len, jlong hint) {
    (void)k;
    uint8_t *src = (uint8_t *)malloc(len > 0 ? (size_t)len : 1), *out = NULL;
    size_t out_len = 0;
    int rc;
    if (!src) { throw_arc(env, ARC_E_MEM); return NULL; }
    (*env)->GetByteArrayRegion(env, in, off, len, (jbyte *)src);
    rc = arc_decode_all(codec, src, (size_t)len, hint > 0 ? (uint64_t)hint : 0, &out, &out_len);
    free(src);
    if (rc != ARC_OK) { throw_arc(env, rc); return NULL; }
    return to_java(env, out, out_len);
}

static jbyteArray n_lz4_block(JNIEnv *env, jclass k, jbyteArray in, jint off, jint len, jint out_size) {
    (void)k;
    uint8_t *src = (uint8_t *)malloc(len > 0 ? (size_t)len : 1), *out = (uint8_t *)malloc(out_size > 0 ? (size_t)out_size : 1);
    int64_t n;
    if (!src || !out) { free(src); free(out); throw_arc(env, ARC_E_MEM); return NULL; }
    (*env)->GetByteArrayRegion(env, in, off, len, (jbyte *)src);
    n = arc_lz4_block(src, (size_t)len, out, (size_t)out_size);
    free(src);
    if (n < 0) { free(out); throw_arc(env, (int)n); return NULL; }
    return to_java(env, out, (size_t)n);
}

/* ---- 7z ---------------------------------------------------------------------------------------------------------------- */

typedef struct {
    JNIEnv *env;       /* of the call in progress: the read callback runs inside one */
    jobject src;
    jmethodID mid;
    arc7z *a;
    uint8_t *tmp; size_t tmp_cap;
} szh;

static int64_t sz_read_at(void *ctx, uint64_t pos, void *buf, size_t len) {
    szh *h = (szh *)ctx;
    JNIEnv *env = h->env;
    size_t got = 0;
    while (got < len) {
        size_t chunk = len - got > (size_t)INT_MAX ? (size_t)INT_MAX : len - got;
        jobject bb = (*env)->NewDirectByteBuffer(env, (char *)buf + got, (jlong)chunk);
        jint n;
        if (!bb) return -1;
        n = (*env)->CallIntMethod(env, h->src, h->mid, (jlong)(pos + got), bb);
        (*env)->DeleteLocalRef(env, bb);
        if ((*env)->ExceptionCheck(env)) return -1;
        if (n <= 0) break;
        got += (size_t)n;
    }
    return (int64_t)got;
}

static jlong n_sz_open(JNIEnv *env, jclass k, jobject src, jlong size, jlong mem_cap) {
    (void)k;
    szh *h = (szh *)calloc(1, sizeof *h);
    arc_src as;
    int rc = ARC_OK;
    jclass sc;
    if (!h) { throw_arc(env, ARC_E_MEM); return 0; }
    h->env = env;
    h->src = (*env)->NewGlobalRef(env, src);
    sc = (*env)->GetObjectClass(env, src);
    h->mid = (*env)->GetMethodID(env, sc, "readAt", "(JLjava/nio/ByteBuffer;)I");
    (*env)->DeleteLocalRef(env, sc);
    if (!h->mid || !h->src) { (*env)->DeleteGlobalRef(env, h->src); free(h); return 0; }
    as.read_at = sz_read_at; as.ctx = h; as.size = (uint64_t)size;
    h->a = arc7z_open(&as, mem_cap > 0 ? (uint64_t)mem_cap : 0, &rc);
    if (!h->a) {
        (*env)->DeleteGlobalRef(env, h->src);
        free(h);
        throw_arc(env, rc);
        return 0;
    }
    return (jlong)(intptr_t)h;
}
static void n_sz_close(JNIEnv *env, jclass k, jlong hh) {
    szh *h = (szh *)(intptr_t)hh;
    (void)k;
    if (!h) return;
    arc7z_close(h->a);
    (*env)->DeleteGlobalRef(env, h->src);
    free(h->tmp); free(h);
}
static jint n_sz_count(JNIEnv *env, jclass k, jlong hh) { (void)env; (void)k; return (jint)arc7z_count(((szh *)(intptr_t)hh)->a); }

static jstring n_sz_name(JNIEnv *env, jclass k, jlong hh, jint i) {
    szh *h = (szh *)(intptr_t)hh;
    size_t n = arc7z_name16(h->a, (uint32_t)i, NULL);
    uint16_t *b = (uint16_t *)malloc((n + 1) * sizeof(uint16_t));
    jstring s;
    (void)k;
    if (!b) { throw_arc(env, ARC_E_MEM); return NULL; }
    arc7z_name16(h->a, (uint32_t)i, b);
    s = (*env)->NewString(env, (const jchar *)b, (jsize)n);
    free(b);
    return s;
}
static void n_sz_info(JNIEnv *env, jclass k, jlong hh, jint i, jlongArray out) {
    szh *h = (szh *)(intptr_t)hh;
    uint64_t size = 0;
    int dir = 0;
    jlong v[2];
    (void)k;
    if (arc7z_info(h->a, (uint32_t)i, &size, &dir) != ARC_OK) { throw_arc(env, ARC_E_ARG); return; }
    v[0] = (jlong)size; v[1] = dir;
    (*env)->SetLongArrayRegion(env, out, 0, 2, v);
}
static void n_sz_select(JNIEnv *env, jclass k, jlong hh, jint i) {
    int rc = arc7z_select(((szh *)(intptr_t)hh)->a, (uint32_t)i);
    (void)k;
    if (rc != ARC_OK) throw_arc(env, rc);
}
static jint n_sz_read(JNIEnv *env, jclass k, jlong hh, jbyteArray b, jint off, jint len) {
    szh *h = (szh *)(intptr_t)hh;
    size_t want = (size_t)len > (1u << 20) ? (1u << 20) : (size_t)len;
    int64_t n;
    (void)k;
    h->env = env;
    if (h->tmp_cap < want) {
        uint8_t *t = (uint8_t *)realloc(h->tmp, want);
        if (!t) { throw_arc(env, ARC_E_MEM); return -1; }
        h->tmp = t; h->tmp_cap = want;
    }
    n = arc7z_read(h->a, h->tmp, want);
    if (n < 0) { throw_arc(env, (int)n); return -1; }
    if (n == 0) return -1;
    (*env)->SetByteArrayRegion(env, b, off, (jsize)n, (const jbyte *)h->tmp);
    return (jint)n;
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    static const JNINativeMethod m[] = {
        { "nativeVersion", "()Ljava/lang/String;", (void *)n_version },
        { "decOpen", "(I)J", (void *)n_dec_open },
        { "decClose", "(J)V", (void *)n_dec_close },
        { "decStep", "(J[BII[BIIZ[I)V", (void *)n_dec_step },
        { "decodeAll", "(I[BIIJ)[B", (void *)n_decode_all },
        { "lz4Block", "([BIII)[B", (void *)n_lz4_block },
        { "szOpen", "(Ljava/lang/Object;JJ)J", (void *)n_sz_open },
        { "szClose", "(J)V", (void *)n_sz_close },
        { "szCount", "(J)I", (void *)n_sz_count },
        { "szName", "(JI)Ljava/lang/String;", (void *)n_sz_name },
        { "szInfo", "(JI[J)V", (void *)n_sz_info },
        { "szSelect", "(JI)V", (void *)n_sz_select },
        { "szRead", "(J[BII)I", (void *)n_sz_read },
    };
    JNIEnv *env;
    jclass c;
    (void)reserved;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    c = (*env)->FindClass(env, CLS);
    if (!c) { (*env)->ExceptionClear(env); return JNI_VERSION_1_6; } /* loaded by something that is not the app (C tests) */
    if ((*env)->RegisterNatives(env, c, m, (jint)(sizeof m / sizeof m[0])) != JNI_OK) return JNI_ERR;
    return JNI_VERSION_1_6;
}
