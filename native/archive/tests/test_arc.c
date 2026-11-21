/* C-level driver for the native library: tests/run.sh feeds it files made by the reference tools. */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <sys/stat.h>
#include "aemuarc.h"

static int64_t file_read_at(void *ctx, uint64_t pos, void *buf, size_t len) {
    ssize_t n = pread(*(int *)ctx, buf, len, (off_t)pos);
    return n;
}

static int cmd_dec(int codec, const char *in, const char *out, size_t ic, size_t oc) {
    FILE *fi = fopen(in, "rb"), *fo = fopen(out, "wb");
    if (!fi || !fo) { perror("open"); return 2; }
    arc_dec *d = arc_dec_new(codec);
    uint8_t *ib = malloc(ic + 16), *ob = malloc(oc);
    size_t len = 0, pos = 0;
    int eof = 0, rc = ARC_OK, stalled = 0;
    for (;;) {
        if (!eof && (len == pos || stalled)) {             /* top the input up: keeps the unconsumed bytes in front */
            memmove(ib, ib + pos, len - pos); len -= pos; pos = 0;
            size_t n = fread(ib + len, 1, ic, fi);
            len += n;
            if (n == 0) eof = 1;
        }
        size_t used, made;
        rc = arc_dec_step(d, ib + pos, len - pos, &used, ob, oc, &made, eof);
        pos += used;
        stalled = (used == 0 && made == 0);
        fwrite(ob, 1, made, fo);
        if (rc == ARC_END) { rc = 0; break; }
        if (rc < 0) break;
        if (used == 0 && made == 0) {
            if (eof) { rc = ARC_E_TRUNCATED; break; }
            if (!eof && len - pos >= ic + 16) { rc = ARC_E_DATA; break; }
        }
    }
    fclose(fo); fclose(fi);
    free(ib); free(ob);
    arc_dec_free(d);
    if (rc) fprintf(stderr, "error: %s\n", arc_strerror(rc));
    return rc ? 1 : 0;
}

static void u16_to_utf8(const uint16_t *s, size_t n, char *o) {
    size_t i; for (i = 0; i < n; i++) { uint16_t c = s[i]; *o++ = c < 128 ? (char)c : '?'; } *o = 0;
}

/* 7z <archive> <outdir> <order> : extracts every file (flat: index.bin), prints "index size dir name" */
static int cmd_7z(const char *path, const char *outdir, const char *order, uint64_t cap, size_t chunk) {
    int fd = open(path, O_RDONLY);
    struct stat st;
    if (fd < 0 || fstat(fd, &st)) { perror("open"); return 2; }
    arc_src s = { file_read_at, &fd, (uint64_t)st.st_size };
    int err = 0;
    arc7z *a = arc7z_open(&s, cap, &err);
    if (!a) { fprintf(stderr, "open: %s\n", arc_strerror(err)); return err == ARC_E_UNSUPPORTED ? 3 : 1; }
    uint32_t n = arc7z_count(a), i;
    uint8_t *buf = malloc(chunk);
    int rc = 0;
    for (i = 0; i < n; i++) {
        uint32_t k = !strcmp(order, "reverse") ? n - 1 - i : i;
        uint64_t size; int dir;
        arc7z_info(a, k, &size, &dir);
        size_t nl = arc7z_name16(a, k, NULL);
        uint16_t *nm = malloc((nl + 1) * 2); arc7z_name16(a, k, nm);
        char *u = malloc(nl + 1); u16_to_utf8(nm, nl, u);
        printf("%u %llu %d %s\n", k, (unsigned long long)size, dir, u);
        free(nm); free(u);
        if (dir) continue;
        char fn[1024]; snprintf(fn, sizeof fn, "%s/%u.bin", outdir, k);
        FILE *fo = fopen(fn, "wb");
        arc7z_select(a, k);
        int64_t r;
        uint64_t total = 0;
        /* "partial" order: read half of every entry first, then (once) all of the next one, to exercise skipping */
        while ((r = arc7z_read(a, buf, chunk)) > 0) { fwrite(buf, 1, (size_t)r, fo); total += (uint64_t)r; if (!strcmp(order, "partial") && total >= size / 2 && (k & 1)) break; }
        fclose(fo);
        if (r < 0) { fprintf(stderr, "read %u: %s\n", k, arc_strerror((int)r)); rc = 1; break; }
    }
    free(buf);
    arc7z_close(a); close(fd);
    return rc;
}

static int cmd_lz4block(const char *in, const char *out, size_t outsize) {
    FILE *f = fopen(in, "rb"); uint8_t *b = malloc(1 << 24); size_t n = fread(b, 1, 1 << 24, f); fclose(f);
    uint8_t *o = malloc(outsize);
    int64_t r = arc_lz4_block(b, n, o, outsize);
    free(b);
    if (r < 0) return 1;
    f = fopen(out, "wb"); fwrite(o, 1, (size_t)r, f); fclose(f); free(o);
    return 0;
}

int main(int argc, char **argv) {
    if (argc >= 6 && !strcmp(argv[1], "dec")) return cmd_dec(atoi(argv[2]), argv[3], argv[4], (size_t)atol(argv[5]), (size_t)atol(argv[6]));
    if (argc >= 6 && !strcmp(argv[1], "7z")) return cmd_7z(argv[2], argv[3], argv[4], (uint64_t)atoll(argv[5]), (size_t)atol(argv[6]));
    if (argc >= 5 && !strcmp(argv[1], "lz4block")) return cmd_lz4block(argv[2], argv[3], (size_t)atol(argv[4]));
    fprintf(stderr, "usage\n");
    return 2;
}
