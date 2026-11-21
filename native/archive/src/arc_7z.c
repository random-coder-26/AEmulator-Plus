/*
 * Streaming 7z reader on the 7-Zip LZMA SDK (public domain): the SDK parses the headers, this file decodes folders as a
 * stream instead of into one buffer, so a multi-gigabyte solid system.img is never held in memory.
 * Streamed: Copy, LZMA, LZMA2, PPMd, BZip2, Deflate, optionally followed by a BCJ / ARM / ARM64 / ARMT / PPC / SPARC /
 * IA64 / RISC-V / Delta filter. Other folders (BCJ2) are unpacked into memory when they are not bigger than mem_cap.
 */
#include <stdlib.h>
#include <string.h>
#include "arc_internal.h"
#include "7z.h"
#include "7zCrc.h"
#include "Alloc.h"
#include "Bra.h"
#include "CpuArch.h"
#include "Delta.h"
#include "Lzma2Dec.h"
#include "LzmaDec.h"
#include "Ppmd7.h"

#define IBCAP (1u << 20)       /* packed bytes read from the archive at a time */
#define FBCAP (1u << 18)       /* filter window */
#define SKIPCAP (1u << 16)

enum { ID_COPY = 0, ID_LZMA2 = 0x21, ID_LZMA = 0x30101, ID_PPMD = 0x30401, ID_DEFLATE = 0x40108, ID_BZIP2 = 0x40202, ID_BCJ2 = 0x303011B };
enum { M_COPY = 1, M_LZMA, M_LZMA2, M_PPMD, M_DEFLATE, M_BZIP2 };
enum { F_NONE = 0, F_DELTA, F_X86, F_PPC, F_IA64, F_ARM, F_ARMT, F_SPARC, F_ARM64, F_RISCV };

static int main_kind(uint32_t id) {
    switch (id) {
    case ID_COPY: return M_COPY; case ID_LZMA: return M_LZMA; case ID_LZMA2: return M_LZMA2; case ID_PPMD: return M_PPMD;
    case ID_DEFLATE: return M_DEFLATE; case ID_BZIP2: return M_BZIP2; default: return 0;
    }
}
static int filter_kind(uint32_t id) {
    switch (id) {
    case 3: return F_DELTA; case 0x3030103: return F_X86; case 0x3030205: return F_PPC; case 0x3030401: return F_IA64;
    case 0x3030501: return F_ARM; case 0x3030701: return F_ARMT; case 0x3030805: return F_SPARC;
    case 0xa: return F_ARM64; case 0xb: return F_RISCV; default: return 0;
    }
}

typedef struct fdec fdec;
typedef struct { IByteIn vt; fdec *f; } ppin;

struct arc7z {
    arc_src src;
    uint64_t mem_cap;
    struct { ISeekInStream vt; struct arc7z *a; uint64_t pos; } ss;
    CLookToRead2 look;
    CSzArEx db;
    int db_open;
    uint64_t *foff;        /* offset of every file inside its folder */
    uint8_t *scratch;
    /* current entry */
    int64_t cur;
    uint64_t ent_left;
    uint32_t crc;
    int crc_checked;
    /* current folder */
    fdec *f;
    uint32_t fold;
    uint64_t f_pos;
};

struct fdec {
    arc7z *a;
    int memory;
    uint8_t *mem; uint64_t mem_len, mem_pos;
    uint64_t pk_pos, pk_left;
    uint8_t *ib; size_t ib_pos, ib_len;
    int mk;
    CLzmaDec lz; CLzma2Dec lz2; int lz_alloc;
    CPpmd7 pp; ppin ppi; int pp_alloc, pp_init, pp_eof;
    const arc_ops *ex; void *exst;
    uint64_t main_left; int main_done;
    int filt; uint32_t pc, xstate; unsigned delta; uint8_t dstate[DELTA_STATE_SIZE];
    uint8_t *fb; size_t fb_pos, fb_ready, fb_len; int fb_end;
    uint64_t out_left;
};

/* ---- the archive as an SDK stream ---------------------------------------------------------------------------- */

static SRes ss_read(ISeekInStreamPtr p, void *buf, size_t *size) {
    typeof(((arc7z *)0)->ss) *s = (void *)p;
    if (*size == 0) return SZ_OK;
    int64_t n = s->a->src.read_at(s->a->src.ctx, s->pos, buf, *size);
    if (n < 0) return SZ_ERROR_READ;
    s->pos += (uint64_t)n;
    *size = (size_t)n;
    return SZ_OK;
}
static SRes ss_seek(ISeekInStreamPtr p, Int64 *pos, ESzSeek origin) {
    typeof(((arc7z *)0)->ss) *s = (void *)p;
    int64_t np;
    switch (origin) {
    case SZ_SEEK_SET: np = *pos; break;
    case SZ_SEEK_CUR: np = (int64_t)s->pos + *pos; break;
    case SZ_SEEK_END: np = (int64_t)s->a->src.size + *pos; break;
    default: return SZ_ERROR_PARAM;
    }
    if (np < 0) return SZ_ERROR_PARAM;
    s->pos = (uint64_t)np;
    *pos = np;
    return SZ_OK;
}

static int map_sres(SRes r) {
    switch (r) {
    case SZ_OK: return ARC_OK;
    case SZ_ERROR_MEM: return ARC_E_MEM;
    case SZ_ERROR_UNSUPPORTED: return ARC_E_UNSUPPORTED;
    case SZ_ERROR_READ: return ARC_E_IO;
    case SZ_ERROR_INPUT_EOF: return ARC_E_TRUNCATED;
    default: return ARC_E_DATA;
    }
}

/* ---- folder description ---------------------------------------------------------------------------------------- */

typedef struct {
    CSzFolder fo;
    const Byte *data;           /* coder properties are relative to this */
    const UInt64 *us;           /* unpack size of every coder */
    const UInt64 *pp;           /* pack stream positions */
    int streamable, mk, filt;
} fplan;

static int plan_folder(const arc7z *a, uint32_t fi, fplan *p) {
    const CSzAr *ar = &a->db.db;
    CSzData sd;
    memset(p, 0, sizeof *p);
    p->data = ar->CodersData + ar->FoCodersOffsets[fi];
    sd.Data = p->data;
    sd.Size = ar->FoCodersOffsets[(size_t)fi + 1] - ar->FoCodersOffsets[fi];
    if (SzGetNextFolderItem(&p->fo, &sd) != SZ_OK || sd.Size != 0) return ARC_E_DATA;
    p->us = ar->CoderUnpackSizes + ar->FoToCoderUnpackSizes[fi];
    p->pp = ar->PackPositions + ar->FoStartPackStreamIndex[fi];
    const CSzFolder *f = &p->fo;
    if (f->NumCoders == 1 && f->NumPackStreams == 1 && f->PackStreams[0] == 0 && f->NumBonds == 0)
        p->mk = main_kind((uint32_t)f->Coders[0].MethodID);
    else if (f->NumCoders == 2 && f->NumPackStreams == 1 && f->PackStreams[0] == 0 && f->NumBonds == 1 &&
             f->Bonds[0].InIndex == 1 && f->Bonds[0].OutIndex == 0 && f->Coders[1].NumStreams == 1) {
        p->mk = main_kind((uint32_t)f->Coders[0].MethodID);
        p->filt = filter_kind((uint32_t)f->Coders[1].MethodID);
        if (!p->filt) p->mk = 0;
    }
    p->streamable = p->mk != 0;
    return ARC_OK;
}

/* the folders the SDK can unpack in one piece: LZMA / LZMA2 / PPMd / Copy, a filter after one, or BCJ2 with three of them */
static int memory_capable(const CSzFolder *f) {
    UInt32 i;
    if (f->NumCoders < 1 || f->NumCoders > 4) return 0;
    for (i = 0; i < (f->NumCoders == 4 ? 3u : 1u); i++) {
        uint32_t id = (uint32_t)f->Coders[i].MethodID;
        if (id != ID_COPY && id != ID_LZMA && id != ID_LZMA2 && id != ID_PPMD) return 0;
    }
    if (f->NumCoders == 4) return (uint32_t)f->Coders[3].MethodID == ID_BCJ2;
    if (f->NumCoders == 2) return filter_kind((uint32_t)f->Coders[1].MethodID) != 0;
    return 1;
}

/* ---- pulling packed bytes ---------------------------------------------------------------------------------------- */

static int refill(fdec *f) {
    size_t rem = f->ib_len - f->ib_pos;
    if (f->ib_pos && rem) memmove(f->ib, f->ib + f->ib_pos, rem);
    f->ib_pos = 0; f->ib_len = rem;
    if (f->pk_left && f->ib_len < IBCAP) {
        size_t want = IBCAP - f->ib_len;
        if (want > f->pk_left) want = (size_t)f->pk_left;
        int64_t n = f->a->src.read_at(f->a->src.ctx, f->pk_pos, f->ib + f->ib_len, want);
        if (n < 0) return ARC_E_IO;
        if (n == 0) return ARC_E_TRUNCATED;
        f->ib_len += (size_t)n; f->pk_pos += (uint64_t)n; f->pk_left -= (uint64_t)n;
    }
    return ARC_OK;
}

static Byte pp_read(IByteInPtr p) {
    fdec *f = ((ppin *)p)->f;
    if (f->ib_pos == f->ib_len && refill(f) != ARC_OK) f->pp_eof = 1;
    if (f->ib_pos == f->ib_len) { f->pp_eof = 1; return 0; }
    return f->ib[f->ib_pos++];
}

/* ---- main decoder: up to cap bytes, 0 at the end, <0 on error --------------------------------------------------------- */

static int64_t main_read(fdec *f, uint8_t *dst, size_t cap) {
    if (f->main_left == 0 || f->main_done) return 0;
    if ((uint64_t)cap > f->main_left) cap = (size_t)f->main_left;
    if (cap == 0) return 0;
    switch (f->mk) {
    case M_COPY: {
        if (f->ib_pos == f->ib_len) {
            int r = refill(f);
            if (r < 0) return r;
            if (f->ib_pos == f->ib_len) return ARC_E_TRUNCATED;
        }
        size_t n = f->ib_len - f->ib_pos;
        if (n > cap) n = cap;
        memcpy(dst, f->ib + f->ib_pos, n);
        f->ib_pos += n; f->main_left -= n;
        return (int64_t)n;
    }
    case M_LZMA: case M_LZMA2:
        for (;;) {
            if (f->ib_pos == f->ib_len && f->pk_left) { int r = refill(f); if (r < 0) return r; }
            SizeT sl = f->ib_len - f->ib_pos, dl = cap;
            ELzmaStatus st;
            SRes r = f->mk == M_LZMA
                ? LzmaDec_DecodeToBuf(&f->lz, dst, &dl, f->ib + f->ib_pos, &sl, LZMA_FINISH_ANY, &st)
                : Lzma2Dec_DecodeToBuf(&f->lz2, dst, &dl, f->ib + f->ib_pos, &sl, LZMA_FINISH_ANY, &st);
            f->ib_pos += sl;
            if (r != SZ_OK) return ARC_E_DATA;
            if (dl > 0) { f->main_left -= dl; return (int64_t)dl; }
            if (st == LZMA_STATUS_FINISHED_WITH_MARK) return ARC_E_DATA; /* ended before the announced size */
            if (sl == 0 && f->ib_pos == f->ib_len && f->pk_left == 0) return ARC_E_TRUNCATED;
        }
    case M_PPMD: {
        size_t n = 0;
        if (!f->pp_init) {
            f->pp.rc.dec.Stream = &f->ppi.vt;
            if (!Ppmd7z_RangeDec_Init(&f->pp.rc.dec)) return f->pp_eof ? ARC_E_TRUNCATED : ARC_E_DATA;
            f->pp_init = 1;
        }
        while (n < cap) {
            int sym = Ppmd7z_DecodeSymbol(&f->pp);
            if (f->pp_eof || sym < 0) { if (n) { f->pp_eof = 1; break; } return f->pp_eof ? ARC_E_TRUNCATED : ARC_E_DATA; }
            dst[n++] = (uint8_t)sym;
        }
        f->main_left -= n;
        return (int64_t)n;
    }
    case M_DEFLATE: case M_BZIP2:
        for (;;) {
            if (f->ib_pos == f->ib_len && f->pk_left) { int r = refill(f); if (r < 0) return r; }
            size_t avail = f->ib_len - f->ib_pos, used = 0, made = 0;
            int eof = f->pk_left == 0;
            int r = f->ex->step(f->exst, f->ib + f->ib_pos, avail, &used, dst, cap, &made, eof);
            f->ib_pos += used;
            if (made) { f->main_left -= made; return (int64_t)made; }
            if (r == ARC_END) return ARC_E_DATA; /* ended before the announced size */
            if (r < 0) return r;
            if (used == 0) {
                if (eof) return ARC_E_TRUNCATED;
                if (avail == IBCAP) return ARC_E_DATA;
                { int rr = refill(f); if (rr < 0) return rr; }
            }
        }
    }
    return ARC_E_UNSUPPORTED;
}

/* ---- filter stage ------------------------------------------------------------------------------------------------- */

static size_t apply_filter(fdec *f, uint8_t *p, size_t n) {
    Byte *e = p + n;
    switch (f->filt) {
    case F_DELTA: Delta_Decode(f->dstate, f->delta, p, n); return n;
    case F_X86: e = z7_BranchConvSt_X86_Dec(p, n, f->pc, &f->xstate); break;
    case F_PPC: e = z7_BranchConv_PPC_Dec(p, n, f->pc); break;
    case F_IA64: e = z7_BranchConv_IA64_Dec(p, n, f->pc); break;
    case F_ARM: e = z7_BranchConv_ARM_Dec(p, n, f->pc); break;
    case F_ARMT: e = z7_BranchConv_ARMT_Dec(p, n, f->pc); break;
    case F_SPARC: e = z7_BranchConv_SPARC_Dec(p, n, f->pc); break;
    case F_ARM64: e = z7_BranchConv_ARM64_Dec(p, n, f->pc); break;
    case F_RISCV: e = z7_BranchConv_RISCV_Dec(p, n, f->pc); break;
    default: return n;
    }
    size_t done = (size_t)(e - p);
    f->pc += (uint32_t)done;
    return done;
}

static int64_t fdec_read(fdec *f, uint8_t *dst, size_t cap) {
    int64_t n;
    if (f->out_left == 0) return 0;
    if ((uint64_t)cap > f->out_left) cap = (size_t)f->out_left;
    if (f->memory) {
        uint64_t left = f->mem_len - f->mem_pos;
        size_t k = left < cap ? (size_t)left : cap;
        memcpy(dst, f->mem + f->mem_pos, k);
        f->mem_pos += k; f->out_left -= k;
        return (int64_t)k;
    }
    if (f->filt == F_NONE) n = main_read(f, dst, cap);
    else {
        while (f->fb_ready == f->fb_pos) {
            size_t tail = f->fb_len - f->fb_ready;
            if (tail && f->fb_ready) memmove(f->fb, f->fb + f->fb_ready, tail);
            f->fb_pos = f->fb_ready = 0; f->fb_len = tail;
            if (!f->fb_end) {
                int64_t m = main_read(f, f->fb + f->fb_len, FBCAP - f->fb_len);
                if (m < 0) return m;
                if (m == 0) f->fb_end = 1;
                else {
                    f->fb_len += (size_t)m;
                    f->fb_ready += apply_filter(f, f->fb + f->fb_ready, f->fb_len - f->fb_ready);
                    continue;
                }
            }
            f->fb_ready = f->fb_len;       /* the stream is over: what is left is passed on as it is */
            if (f->fb_len == 0) return 0;
        }
        n = (int64_t)(f->fb_ready - f->fb_pos);
        if ((uint64_t)n > cap) n = (int64_t)cap;
        memcpy(dst, f->fb + f->fb_pos, (size_t)n);
        f->fb_pos += (size_t)n;
    }
    if (n > 0) f->out_left -= (uint64_t)n;
    return n;
}

static void fdec_free(fdec *f) {
    if (!f) return;
    if (f->lz_alloc == M_LZMA) LzmaDec_Free(&f->lz, &g_Alloc);
    else if (f->lz_alloc == M_LZMA2) Lzma2Dec_Free(&f->lz2, &g_Alloc);
    if (f->pp_alloc) Ppmd7_Free(&f->pp, &g_Alloc);
    if (f->ex && f->exst) f->ex->destroy(f->exst);
    free(f->ib); free(f->fb); free(f->mem);
    free(f);
}

static int fdec_open(arc7z *a, uint32_t fi, fdec **out) {
    fplan p;
    int rc = plan_folder(a, fi, &p);
    fdec *f;
    *out = NULL;
    if (rc != ARC_OK) return rc;
    f = (fdec *)calloc(1, sizeof *f);
    if (!f) return ARC_E_MEM;
    f->a = a;
    f->out_left = SzAr_GetFolderUnpackSize(&a->db.db, fi);
    if (!p.streamable) {
        /* in memory */
        SRes r;
        if (!memory_capable(&p.fo) || f->out_left > a->mem_cap || (size_t)f->out_left != f->out_left) { fdec_free(f); return ARC_E_UNSUPPORTED; }
        f->mem = (uint8_t *)malloc(f->out_left ? (size_t)f->out_left : 1);
        if (!f->mem) { fdec_free(f); return ARC_E_MEM; }
        r = SzAr_DecodeFolder(&a->db.db, fi, &a->look.vt, a->db.dataPos, f->mem, (size_t)f->out_left, &g_Alloc);
        if (r != SZ_OK) { fdec_free(f); return map_sres(r); }
        f->memory = 1; f->mem_len = f->out_left;
        *out = f;
        return ARC_OK;
    }
    f->mk = p.mk; f->filt = p.filt;
    f->main_left = p.us[0];
    f->pk_pos = a->db.dataPos + p.pp[0];
    f->pk_left = p.pp[1] - p.pp[0];
    f->ib = (uint8_t *)malloc(IBCAP);
    if (!f->ib) { fdec_free(f); return ARC_E_MEM; }
    {
        const CSzCoderInfo *c = &p.fo.Coders[0];
        const Byte *props = p.data + c->PropsOffset;
        SRes r = SZ_OK;
        switch (p.mk) {
        case M_COPY: break;
        case M_LZMA: {
            Byte pr[5];
            UInt32 dict;
            if (c->PropsSize != 5) { rc = ARC_E_UNSUPPORTED; break; }
            memcpy(pr, props, 5);
            dict = GetUi32(pr + 1);
            if (f->main_left < dict) { dict = f->main_left < 4096 ? 4096 : (UInt32)f->main_left; SetUi32(pr + 1, dict); } /* window no bigger than the data */
            f->lz_alloc = M_LZMA;
            LzmaDec_CONSTRUCT(&f->lz);
            r = LzmaDec_Allocate(&f->lz, pr, 5, &g_Alloc);
            if (r == SZ_OK) LzmaDec_Init(&f->lz);
            break;
        }
        case M_LZMA2: {
            unsigned q, pb;
            if (c->PropsSize != 1 || props[0] > 40) { rc = ARC_E_UNSUPPORTED; break; }
            pb = props[0];
            for (q = 0; q < pb; q++) if ((((uint64_t)2 | (q & 1)) << (q / 2 + 11)) >= f->main_left) break;
            f->lz_alloc = M_LZMA2;
            Lzma2Dec_CONSTRUCT(&f->lz2);
            r = Lzma2Dec_Allocate(&f->lz2, (Byte)q, &g_Alloc);
            if (r == SZ_OK) Lzma2Dec_Init(&f->lz2);
            break;
        }
        case M_PPMD: {
            unsigned order;
            UInt32 mem;
            if (c->PropsSize != 5) { rc = ARC_E_UNSUPPORTED; break; }
            order = props[0]; mem = GetUi32(props + 1);
            if (order < PPMD7_MIN_ORDER || order > PPMD7_MAX_ORDER || mem < PPMD7_MIN_MEM_SIZE || mem > PPMD7_MAX_MEM_SIZE) { rc = ARC_E_UNSUPPORTED; break; }
            Ppmd7_Construct(&f->pp);
            if (!Ppmd7_Alloc(&f->pp, mem, &g_Alloc)) { r = SZ_ERROR_MEM; break; }
            f->pp_alloc = 1;
            Ppmd7_Init(&f->pp, order);
            f->ppi.vt.Read = pp_read; f->ppi.f = f;
            break;
        }
        case M_DEFLATE: f->ex = &arc_ops_zlib; f->exst = f->ex->create(ARC_DEFLATE); if (!f->exst) r = SZ_ERROR_MEM; break;
        case M_BZIP2: f->ex = &arc_ops_bz2; f->exst = f->ex->create(ARC_BZIP2); if (!f->exst) r = SZ_ERROR_MEM; break;
        }
        if (rc == ARC_OK && r != SZ_OK) rc = map_sres(r);
        if (rc != ARC_OK) { fdec_free(f); return rc; }
    }
    if (p.filt) {
        const CSzCoderInfo *c = &p.fo.Coders[1];
        const Byte *props = p.data + c->PropsOffset;
        if (p.filt == F_DELTA) {
            if (c->PropsSize != 1) { fdec_free(f); return ARC_E_UNSUPPORTED; }
            f->delta = (unsigned)props[0] + 1;
            Delta_Init(f->dstate);
        } else if (p.filt == F_ARM64 || p.filt == F_RISCV) {
            if (c->PropsSize == 4) f->pc = GetUi32(props);
            else if (c->PropsSize != 0) { fdec_free(f); return ARC_E_UNSUPPORTED; }
        } else if (c->PropsSize != 0) { fdec_free(f); return ARC_E_UNSUPPORTED; }
        f->xstate = Z7_BRANCH_CONV_ST_X86_STATE_INIT_VAL;
        f->fb = (uint8_t *)malloc(FBCAP);
        if (!f->fb) { fdec_free(f); return ARC_E_MEM; }
        f->main_left = p.us[0];
    }
    *out = f;
    return ARC_OK;
}

/* ---- the archive ------------------------------------------------------------------------------------------------------- */

void arc7z_close(arc7z *a) {
    if (!a) return;
    fdec_free(a->f);
    if (a->db_open) SzArEx_Free(&a->db, &g_Alloc);
    free(a->look.buf); free(a->foff); free(a->scratch);
    free(a);
}

arc7z *arc7z_open(const arc_src *src, uint64_t mem_cap, int *err) {
    arc7z *a;
    uint32_t i;
    int e = ARC_OK;
    SRes r;
    uint64_t *running = NULL;
    arc_global_init();
    a = (arc7z *)calloc(1, sizeof *a);
    if (!a) { if (err) *err = ARC_E_MEM; return NULL; }
    a->src = *src; a->mem_cap = mem_cap; a->cur = -1;
    a->ss.vt.Read = ss_read; a->ss.vt.Seek = ss_seek; a->ss.a = a;
    a->look.bufSize = 1 << 16;
    a->look.buf = (Byte *)malloc(a->look.bufSize);
    a->scratch = (uint8_t *)malloc(SKIPCAP);
    if (!a->look.buf || !a->scratch) { e = ARC_E_MEM; goto fail; }
    LookToRead2_CreateVTable(&a->look, False);
    a->look.realStream = &a->ss.vt;
    LookToRead2_INIT(&a->look)
    SzArEx_Init(&a->db);
    a->db_open = 1;
    r = SzArEx_Open(&a->db, &a->look.vt, &g_Alloc, &g_Alloc);
    if (r != SZ_OK) { e = r == SZ_ERROR_NO_ARCHIVE ? ARC_E_DATA : map_sres(r); goto fail; }
    a->foff = (uint64_t *)calloc(a->db.NumFiles ? a->db.NumFiles : 1, sizeof(uint64_t));
    running = (uint64_t *)calloc(a->db.db.NumFolders ? a->db.db.NumFolders : 1, sizeof(uint64_t));
    if (!a->foff || !running) { e = ARC_E_MEM; goto fail; }
    for (i = 0; i < a->db.NumFiles; i++) {
        uint32_t fo = a->db.FileToFolder[i];
        if (fo == (uint32_t)-1) continue;
        a->foff[i] = running[fo];
        running[fo] += SzArEx_GetFileSize(&a->db, i);
    }
    for (i = 0; i < a->db.db.NumFolders; i++) {   /* every folder must be decodable here, or the caller picks another reader */
        fplan p;
        e = plan_folder(a, i, &p);
        if (e != ARC_OK) goto fail;
        if (running[i] > SzAr_GetFolderUnpackSize(&a->db.db, i)) { e = ARC_E_DATA; goto fail; }
        if (!p.streamable && (!memory_capable(&p.fo) || SzAr_GetFolderUnpackSize(&a->db.db, i) > mem_cap)) { e = ARC_E_UNSUPPORTED; goto fail; }
        if (p.streamable && p.mk == M_LZMA && p.fo.Coders[0].PropsSize != 5) { e = ARC_E_UNSUPPORTED; goto fail; }
    }
    free(running);
    if (err) *err = ARC_OK;
    return a;
fail:
    free(running);
    arc7z_close(a);
    if (err) *err = e;
    return NULL;
}

uint32_t arc7z_count(const arc7z *a) { return a->db.NumFiles; }

size_t arc7z_name16(const arc7z *a, uint32_t i, uint16_t *buf) {
    size_t n = SzArEx_GetFileNameUtf16(&a->db, i, buf);
    return n ? n - 1 : 0;
}

int arc7z_info(const arc7z *a, uint32_t i, uint64_t *size, int *is_dir) {
    if (i >= a->db.NumFiles) return ARC_E_ARG;
    if (size) *size = SzArEx_GetFileSize(&a->db, i);
    if (is_dir) *is_dir = SzArEx_IsDir(&a->db, i) ? 1 : 0;
    return ARC_OK;
}

int arc7z_select(arc7z *a, uint32_t i) {
    if (i >= a->db.NumFiles) return ARC_E_ARG;
    a->cur = i;
    a->ent_left = SzArEx_GetFileSize(&a->db, i);
    a->crc = CRC_INIT_VAL;
    a->crc_checked = 0;
    return ARC_OK;
}

int64_t arc7z_read(arc7z *a, uint8_t *buf, size_t cap) {
    uint32_t fo;
    uint64_t want, size;
    int64_t n;
    if (a->cur < 0) return ARC_E_ARG;
    if (a->ent_left == 0 || cap == 0) {
        if (a->ent_left == 0 && !a->crc_checked) {
            a->crc_checked = 1;
            if (SzBitWithVals_Check(&a->db.CRCs, (uint32_t)a->cur) && SzArEx_GetFileSize(&a->db, (uint32_t)a->cur) &&
                CRC_GET_DIGEST(a->crc) != a->db.CRCs.Vals[a->cur]) return ARC_E_DATA;
        }
        return 0;
    }
    fo = a->db.FileToFolder[a->cur];
    if (fo == (uint32_t)-1) return ARC_E_DATA;
    size = SzArEx_GetFileSize(&a->db, (uint32_t)a->cur);
    want = a->foff[a->cur] + (size - a->ent_left);
    if (!a->f || a->fold != fo || a->f_pos > want) {
        int rc;
        fdec_free(a->f); a->f = NULL;
        rc = fdec_open(a, fo, &a->f);
        if (rc != ARC_OK) return rc;
        a->fold = fo; a->f_pos = 0;
    }
    while (a->f_pos < want) {                  /* an entry that was not read, or a part of one, in front of ours */
        uint64_t gap = want - a->f_pos;
        n = fdec_read(a->f, a->scratch, gap < SKIPCAP ? (size_t)gap : SKIPCAP);
        if (n < 0) return n;
        if (n == 0) return ARC_E_TRUNCATED;
        a->f_pos += (uint64_t)n;
    }
    n = fdec_read(a->f, buf, cap < a->ent_left ? cap : (size_t)a->ent_left);
    if (n < 0) return n;
    if (n == 0) return ARC_E_TRUNCATED;
    a->f_pos += (uint64_t)n; a->ent_left -= (uint64_t)n;
    a->crc = CrcUpdate(a->crc, buf, (size_t)n);
    return n;
}
