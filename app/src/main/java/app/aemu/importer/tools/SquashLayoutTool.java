/* AEmulator Plus addition: SquashFS system layout (the counterpart of SystemLayout for ext4). GPL-3.0; see LICENSE. */
package app.aemu.importer.tools;

import com.github.luben.zstd.Zstd;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * What is needed to rebuild a SquashFS 4 image bit for bit from the files extracted out of it, recorded while the
 * image is imported so that the image itself does not have to be kept (a block-based OTA works on the image).
 * <p>
 * A SquashFS image is: superblock, data blocks (file data and fragment blocks, each compressed on its own), then the
 * metadata tables (inodes, folders, fragments, ids, xattrs...). The metadata is small and is stored as it was. A
 * data block is NOT stored if it can be reproduced: it is recompressed from the file's bytes with the same compressor
 * and the same options, and the result is compared with the block in the image while the layout is made. Only the
 * blocks that do not come out identical (a compressor that is not available here, or another version of it) are
 * stored, in compressed form, as they were. Whatever the compressor, the result is therefore always exact.
 * <p>
 * Also recorded: every file (path, size, mode, SHA-1, where its blocks are), every link and every folder, to compare
 * the tree with the image an OTA produces.
 */
public final class SquashLayoutTool {
    private SquashLayoutTool() {}

    static final String MAGIC = "AQS1";
    private static final int SB = 96;
    private static final int FLAG_COMP_OPTIONS = 0x400;
    private static final int UNCOMP = 1 << 24;
    private static final int NO_FRAG = 0xffffffff;
    private static final int KIND_FILE = 0, KIND_FRAG = 1;
    private static final int COMP_ZLIB = 1, COMP_ZSTD = 6;

    // ------------------------------------------------------------------ public types

    /** Gives the stock content of a file of the image, or null if the file in the VM is not the stock one. */
    public interface Content { byte[] read(String path) throws IOException; }

    public interface Progress { void step(int done, int total); }

    /** A file (or one more name of it) of the image. [ranges] = flat (offset, length) pairs: where its stored blocks lie. */
    public static final class Info {
        public final String path; public final long size; public final int mode; public final String sha1; public final long[] ranges;
        Info(String path, long size, int mode, String sha1, long[] ranges) { this.path = path; this.size = size; this.mode = mode; this.sha1 = sha1; this.ranges = ranges; }
    }

    public static final class Tables {
        public final long imageLength; public final int blockSize; public final int compression;
        public final Map<String, Info> files; public final Map<String, String> links; public final Map<String, Integer> dirs;
        Tables(long len, int bs, int comp, Map<String, Info> f, Map<String, String> l, Map<String, Integer> d) {
            imageLength = len; blockSize = bs; compression = comp; files = f; links = l; dirs = d;
        }
    }

    public static final class Stats {
        /** data blocks that are rebuilt from the files, data blocks that are stored as they were, bytes stored in the layout, size of the image */
        public final int rebuilt, stored; public final long storedBytes, imageBytes;
        Stats(int rebuilt, int stored, long storedBytes, long imageBytes) { this.rebuilt = rebuilt; this.stored = stored; this.storedBytes = storedBytes; this.imageBytes = imageBytes; }
    }

    /** True if [f] is a layout written by [capture]. */
    public static boolean isLayout(File f) {
        try (DataInputStream i = new DataInputStream(new InflaterInputStream(new FileInputStream(f)))) {
            return MAGIC.equals(i.readUTF());
        } catch (IOException e) { return false; }
    }

    // ------------------------------------------------------------------ compressors

    /** compress() returns null if the block is not made smaller (it is then stored as it is). */
    interface Comp { byte[] compress(byte[] data) throws IOException; }

    /** Compressor [id] with the options [p] (see [Options]), or null if blocks of this kind cannot be reproduced here. */
    static Comp compressor(int id, int[] p) {
        if (id == COMP_ZLIB) {
            final int level = p[0], window = p[1], strategy = p[2];
            if (level < 1 || level > 9 || window != 15) return null;
            // mksquashfs tries every strategy of the mask and keeps the smallest result; Java has three of them
            final List<Integer> tries = new ArrayList<>();
            if (strategy == 0 || (strategy & 1) != 0) tries.add(Deflater.DEFAULT_STRATEGY);
            if ((strategy & 2) != 0) tries.add(Deflater.FILTERED);
            if ((strategy & 4) != 0) tries.add(Deflater.HUFFMAN_ONLY);
            return data -> {
                byte[] best = null;
                for (int s : tries) {
                    byte[] c = deflate(data, level, s);
                    if (best == null || c.length < best.length) best = c;
                }
                return best != null && best.length < data.length ? best : null;
            };
        }
        if (id == COMP_ZSTD) {
            final int level = p[0];
            return data -> {
                byte[] c = Zstd.compress(data, level);
                return c.length < data.length ? c : null;
            };
        }
        return null;
    }

    private static byte[] deflate(byte[] data, int level, int strategy) {
        Deflater d = new Deflater(level);
        try {
            d.setStrategy(strategy);
            d.setInput(data);
            d.finish();
            ByteArrayOutputStream o = new ByteArrayOutputStream(Math.max(64, data.length / 2));
            byte[] b = new byte[16384];
            while (!d.finished()) { int n = d.deflate(b); o.write(b, 0, n); }
            return o.toByteArray();
        } finally { d.end(); }
    }

    /** The compressor options of the image: zlib (level, window, strategy mask) or zstd (level); the defaults of mksquashfs if none are recorded. */
    private static int[] options(byte[] sb, int comp, boolean be, RandomAccessFile r) throws IOException {
        int flags = g16(sb, 24, be);
        int[] p = comp == COMP_ZSTD ? new int[]{15, 0, 0} : new int[]{9, 15, 0};
        if ((flags & FLAG_COMP_OPTIONS) == 0) return p;
        byte[] h = new byte[2];
        r.seek(SB); r.readFully(h);
        int v = g16(h, 0, be), n = v & 0x7fff;
        if ((v & 0x8000) == 0 || n < 4 || n > 64) return p; // options are never compressed; anything else is not understood
        byte[] o = new byte[n];
        r.readFully(o);
        if (comp == COMP_ZLIB && n >= 8) { p[0] = g32(o, 0, be); p[1] = g16(o, 4, be); p[2] = g16(o, 6, be); }
        else if (comp == COMP_ZSTD) p[0] = g32(o, 0, be);
        return p;
    }

    // ------------------------------------------------------------------ capture

    private static final class FRec {
        final String path; final long size; final int mode; final byte[] sha1; final List<long[]> ext = new ArrayList<>();
        FRec(String path, long size, int mode, byte[] sha1) { this.path = path; this.size = size; this.mode = mode; this.sha1 = sha1; }
    }

    private static final class BRec {
        final int kind; final long off; final int len; final int file; final int index; final int[] tails; final int crc;
        BRec(int kind, long off, int len, int file, int index, int[] tails, int crc) { this.kind = kind; this.off = off; this.len = len; this.file = file; this.index = index; this.tails = tails; this.crc = crc; }
    }

    private static final class Tail {
        final int file; final int off; final int len;
        Tail(int file, int off, int len) { this.file = file; this.off = off; this.len = len; }
    }

    /**
     * Walks the SquashFS image [image], verifies for every data block whether it can be rebuilt from the files and
     * writes the layout to [out]. [visitor] (optional) also receives the content of the image, exactly as
     * FirmwareToolset.walkSquashFs would give it, so that one pass over the image does both.
     */
    public static Stats capture(File image, File out, Consumer<String> log, BooleanSupplier cancelled,
                                FirmwareToolset.SquashVisitor visitor) throws Exception {
        try (FirmwareToolset.SquashReader r = new FirmwareToolset.SquashReader(image, log)) {
            if (r.base != 0) throw new IOException("a SquashFS image that starts inside another file is not supported");
            long imageLen = image.length();
            byte[] sb = new byte[SB];
            r.r.seek(0); r.r.readFully(sb);
            int[] opt = options(sb, r.compression, r.be, r.r);
            Comp comp = compressor(r.compression, opt);
            log.accept("SquashFS layout: compression " + r.compression + (comp == null ? " (blocks cannot be rebuilt here, they are stored)" : " (blocks are rebuilt from the files)"));

            Capture c = new Capture(r, comp, visitor, cancelled);
            c.walk(r.rootRef, "", new HashSet<Long>());
            c.fragments();

            // data blocks sorted by position; everything between them is stored as it is
            List<BRec> bl = new ArrayList<>(c.blocks);
            bl.sort((a, b) -> Long.compare(a.off, b.off));
            for (int i = 1; i < bl.size(); i++)
                if (bl.get(i - 1).off + bl.get(i - 1).len > bl.get(i).off) throw new IOException("overlapping data blocks");

            File tmp = new File(out.getPath() + ".tmp");
            long stored = 0;
            try {
                Deflater def = new Deflater(Deflater.BEST_SPEED);
                try (DataOutputStream o = new DataOutputStream(new DeflaterOutputStream(new BufferedOutputStream(new FileOutputStream(tmp), 1 << 16), def))) {
                    o.writeUTF(MAGIC);
                    o.writeLong(imageLen);
                    o.writeInt(r.block);
                    o.writeInt(r.compression);
                    for (int i = 0; i < 3; i++) o.writeInt(opt[i]);
                    o.writeInt(c.files.size());
                    for (FRec f : c.files) {
                        o.writeUTF(f.path); o.writeLong(f.size); o.writeInt(f.mode); o.write(f.sha1);
                        o.writeInt(f.ext.size());
                        for (long[] e : f.ext) { o.writeLong(e[0]); o.writeLong(e[1]); }
                    }
                    o.writeInt(c.links.size());
                    for (String[] l : c.links) { o.writeUTF(l[0]); o.writeUTF(l[1]); }
                    o.writeInt(c.dirs.size());
                    for (Object[] d : c.dirs) { o.writeUTF((String) d[0]); o.writeInt((Integer) d[1]); }
                    o.writeInt(bl.size());
                    for (BRec b : bl) {
                        o.writeByte(b.kind); o.writeLong(b.off); o.writeInt(b.len); o.writeInt(b.crc);
                        if (b.kind == KIND_FILE) { o.writeInt(b.file); o.writeInt(b.index); }
                        else { o.writeInt(b.tails.length); for (int t : b.tails) o.writeInt(t); }
                    }
                    // the stored bytes: the gaps between the rebuilt blocks, without the zeros around them
                    long pos = 0;
                    byte[] buf = new byte[1 << 20];
                    for (int i = 0; i <= bl.size(); i++) {
                        long end = i < bl.size() ? bl.get(i).off : imageLen;
                        for (long p = pos; p < end; ) {
                            int n = (int) Math.min(buf.length, end - p);
                            r.r.seek(p); r.r.readFully(buf, 0, n);
                            int a = 0, z = n - 1;
                            while (a < n && buf[a] == 0) a++;
                            while (z >= a && buf[z] == 0) z--;
                            if (a < n) {
                                o.writeLong(p + a); o.writeInt(z - a + 1); o.write(buf, a, z - a + 1);
                                stored += z - a + 1;
                            }
                            p += n;
                        }
                        if (i < bl.size()) pos = bl.get(i).off + bl.get(i).len;
                    }
                    o.writeLong(-1L);
                } finally { def.end(); }
                out.delete();
                if (!tmp.renameTo(out)) throw new IOException("cannot save the SquashFS layout");
            } finally { tmp.delete(); }
            return new Stats(bl.size(), c.rawBlocks, stored, imageLen);
        }
    }

    private static final class Capture {
        final FirmwareToolset.SquashReader r; final Comp comp; final FirmwareToolset.SquashVisitor v; final BooleanSupplier cancelled;
        final List<FRec> files = new ArrayList<>();
        final List<String[]> links = new ArrayList<>();
        final List<Object[]> dirs = new ArrayList<>();
        final List<BRec> blocks = new ArrayList<>();
        final Map<Long, Integer> owner = new HashMap<>();   // inode ref -> index of the file that owns the blocks
        final Set<Long> seenAt = new HashSet<>();           // positions of data blocks already looked at (duplicate files share them)
        final Map<Integer, List<Tail>> tails = new TreeMap<>(); // fragment index -> tails of the files in it
        final Map<Integer, byte[]> fragCache = new LinkedHashMap<Integer, byte[]>(32, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<Integer, byte[]> e) { return size() > 12; }
        };
        int rawBlocks;

        Capture(FirmwareToolset.SquashReader r, Comp comp, FirmwareToolset.SquashVisitor v, BooleanSupplier cancelled) {
            this.r = r; this.comp = comp; this.v = v; this.cancelled = cancelled;
        }

        void walk(long ref, String rel, Set<Long> seen) throws Exception {
            if (cancelled != null && cancelled.getAsBoolean()) throw new IOException("cancelled");
            FirmwareToolset.SquashReader.Inode in = r.inode(ref);
            if (in.type == 1 || in.type == 8) {
                if (!rel.isEmpty()) { dirs.add(new Object[]{rel, in.mode & 07777}); if (v != null) v.dir(rel, in.mode & 07777); }
                if (!seen.add(ref)) return;
                for (FirmwareToolset.SquashReader.Pair p : r.dir(in)) {
                    if (p.name.equals(".") || p.name.equals("..") || p.name.indexOf('/') >= 0) continue;
                    walk(p.ref, rel.isEmpty() ? p.name : rel + "/" + p.name, seen);
                }
            } else if (in.type == 2 || in.type == 9) {
                file(ref, in, rel);
            } else if (in.type == 3 || in.type == 10) {
                links.add(new String[]{rel, in.target});
                if (v != null) v.link(rel, in.target);
            }
        }

        void file(long ref, FirmwareToolset.SquashReader.Inode in, String path) throws Exception {
            int mode = in.mode & 07777;
            Integer own = owner.get(ref);
            if (own != null) {
                // one more name of a file that was seen: same data, no blocks of its own
                FRec o = files.get(own);
                files.add(new FRec(path, o.size, o.mode, o.sha1));
                if (v != null) { OutputStream s = v.file(path, mode, in.size); if (s != null) { try { r.streamFile(s, in); } finally { s.close(); } v.fileEnd(path); } }
                return;
            }
            int idx = files.size();
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            FRec f;
            OutputStream sink = v == null ? null : v.file(path, mode, in.size);
            List<long[]> ext = new ArrayList<>();
            boolean ok = false;
            try {
                long rem = in.size, pos = in.start;
                int bs = r.block;
                int k = 0;
                for (int sz : in.sizes) {
                    long n = Math.min(bs, rem);
                    int cb = sz & 0xffffff;
                    boolean unc = (sz & UNCOMP) != 0;
                    if (cb == 0) {
                        feed(md, sink, new byte[(int) n], (int) n);
                    } else {
                        byte[] raw = r.readAt(r.base + pos, cb);
                        byte[] data = unc ? raw : r.decomp(raw, bs);
                        int take = (int) Math.min(n, data.length);
                        feed(md, sink, data, take);
                        if (take < n) feed(md, sink, new byte[(int) (n - take)], (int) (n - take));
                        ext.add(new long[]{pos, cb});
                        if (seenAt.add(pos)) {
                            if (data.length == n && reproducible(raw, unc, data)) blocks.add(new BRec(KIND_FILE, pos, cb, idx, k, null, crc(raw)));
                            else rawBlocks++;
                        }
                        pos += cb;
                    }
                    rem -= n; k++;
                    if (rem <= 0) break;
                }
                if (rem > 0 && in.frag != NO_FRAG) {
                    byte[] fb = fragment(in.frag);
                    int off = Math.min(in.fragOff, fb.length);
                    int take = (int) Math.min(rem, fb.length - off);
                    feed(md, sink, Arrays.copyOfRange(fb, off, off + take), take);
                    if (take < rem) feed(md, sink, new byte[(int) (rem - take)], (int) (rem - take));
                    if (take == rem) tails.computeIfAbsent(in.frag, x -> new ArrayList<>()).add(new Tail(idx, in.fragOff, (int) rem));
                    else tails.computeIfAbsent(in.frag, x -> new ArrayList<>()).add(new Tail(-1, in.fragOff, 0)); // damaged: forces the block to be stored
                } else if (rem > 0) {
                    feed(md, sink, new byte[(int) rem], (int) rem);
                }
                ok = true;
            } finally {
                if (sink != null) sink.close();
            }
            if (ok && v != null && sink != null) v.fileEnd(path);
            f = new FRec(path, in.size, mode, md.digest());
            f.ext.addAll(ext);
            files.add(f);
            owner.put(ref, idx);
        }

        byte[] fragment(int index) throws Exception {
            byte[] d = fragCache.get(index);
            if (d != null) return d;
            if (index < 0 || index >= r.frags.size()) throw new IOException("fragment " + index + " is outside the table");
            FirmwareToolset.SquashReader.Frag fr = r.frags.get(index);
            byte[] raw = r.readAt(r.base + fr.start, fr.size & 0xffffff);
            d = (fr.size & UNCOMP) != 0 ? raw : r.decomp(raw, r.block);
            fragCache.put(index, d);
            return d;
        }

        /** The fragment blocks: rebuilt from the tails of their files if those fill the block exactly. */
        void fragments() throws Exception {
            for (Map.Entry<Integer, List<Tail>> e : tails.entrySet()) {
                int index = e.getKey();
                FirmwareToolset.SquashReader.Frag fr = r.frags.get(index);
                if (!seenAt.add(fr.start)) continue;
                List<Tail> ts = new ArrayList<>(e.getValue());
                ts.sort((a, b) -> a.off != b.off ? Integer.compare(a.off, b.off) : Integer.compare(a.file, b.file));
                byte[] data = fragment(index);
                boolean tiled = true;
                List<Integer> order = new ArrayList<>();
                int cursor = 0, lastOff = -1;
                for (Tail t : ts) {
                    if (t.file < 0) { tiled = false; break; }
                    if (t.off == lastOff) continue;          // identical files share their tail
                    if (t.off != cursor || t.len <= 0) { tiled = false; break; }
                    order.add(t.file);
                    cursor += t.len; lastOff = t.off;
                }
                boolean unc = (fr.size & UNCOMP) != 0;
                int cb = fr.size & 0xffffff;
                byte[] raw = r.readAt(r.base + fr.start, cb);
                if (tiled && cursor == data.length && reproducible(raw, unc, data)) {
                    int[] a = new int[order.size()];
                    for (int i = 0; i < a.length; i++) a[i] = order.get(i);
                    blocks.add(new BRec(KIND_FRAG, fr.start, cb, -1, index, a, crc(raw)));
                } else rawBlocks++;
            }
        }

        /** True if [data] compressed the way the image was made gives exactly [raw] (a block stored as it is: if it could not be made smaller). */
        boolean reproducible(byte[] raw, boolean unc, byte[] data) throws IOException {
            if (comp == null) return false;
            byte[] c = comp.compress(data);
            return unc ? c == null : (c != null && Arrays.equals(c, raw));
        }

        void feed(MessageDigest md, OutputStream s, byte[] b, int n) throws IOException {
            md.update(b, 0, n);
            if (s != null) s.write(b, 0, n);
        }
    }

    // ------------------------------------------------------------------ read

    private static DataInputStream open(File f) throws IOException {
        return new DataInputStream(new InflaterInputStream(new FileInputStream(f), new java.util.zip.Inflater(), 1 << 16));
    }

    /** Header and tables of a layout (the stored blocks are not read). */
    public static Tables load(File layout) throws IOException {
        try (DataInputStream i = open(layout)) { return readTables(i, null); }
    }

    private static final class Header { int comp; int[] opt = new int[3]; }

    private static Tables readTables(DataInputStream i, Header h) throws IOException {
        if (!MAGIC.equals(i.readUTF())) throw new IOException("not a SquashFS layout");
        long len = i.readLong();
        int bs = i.readInt(), comp = i.readInt();
        int[] opt = new int[3];
        for (int k = 0; k < 3; k++) opt[k] = i.readInt();
        if (bs < 4096 || bs > (1 << 20) || (bs & (bs - 1)) != 0 || len <= 0) throw new IOException("corrupt SquashFS layout");
        if (h != null) { h.comp = comp; h.opt = opt; }
        int nf = i.readInt();
        if (nf < 0 || nf > 10_000_000) throw new IOException("corrupt SquashFS layout");
        Map<String, Info> files = new LinkedHashMap<>(nf * 2);
        for (int k = 0; k < nf; k++) {
            String path = i.readUTF(); long size = i.readLong(); int mode = i.readInt();
            byte[] sha = new byte[20]; i.readFully(sha);
            int ne = i.readInt();
            if (ne < 0 || ne > 100_000_000) throw new IOException("corrupt SquashFS layout");
            long[] ranges = new long[ne * 2];
            for (int x = 0; x < ranges.length; x++) ranges[x] = i.readLong();
            files.put(path, new Info(path, size, mode, hex(sha), ranges));
        }
        int nl = i.readInt();
        Map<String, String> links = new LinkedHashMap<>();
        for (int k = 0; k < nl; k++) { String p = i.readUTF(); links.put(p, i.readUTF()); }
        int nd = i.readInt();
        Map<String, Integer> dirs = new LinkedHashMap<>();
        for (int k = 0; k < nd; k++) { String p = i.readUTF(); dirs.put(p, i.readInt()); }
        return new Tables(len, bs, comp, files, links, dirs);
    }

    // ------------------------------------------------------------------ rebuild

    /**
     * Rebuilds the image described by [layout] into [out]: the stored bytes first, then every data block that is
     * rebuilt, from the files [content] returns. Returns the paths [content] could not provide (their blocks are left
     * blank); the image is only complete if there are none. Fails if a rebuilt block is not what was recorded.
     */
    public static List<String> rebuild(File layout, File out, Content content, Progress progress) throws IOException {
        Set<String> bad = new java.util.LinkedHashSet<>();
        try (DataInputStream i = open(layout); RandomAccessFile raf = new RandomAccessFile(out, "rw")) {
            Header h = new Header();
            Tables t = readTables(i, h);
            List<String> paths = new ArrayList<>(t.files.keySet());
            List<Info> infos = new ArrayList<>(t.files.values());
            int nb = i.readInt();
            if (nb < 0 || nb > 100_000_000) throw new IOException("corrupt SquashFS layout");
            BRec[] bl = new BRec[nb];
            for (int k = 0; k < nb; k++) {
                int kind = i.readByte();
                long off = i.readLong(); int len = i.readInt(); int crc = i.readInt();
                int file = -1, index;
                int[] tails = null;
                if (kind == KIND_FILE) { file = i.readInt(); index = i.readInt(); }
                else { int n = i.readInt(); if (n < 0 || n > 10_000_000) throw new IOException("corrupt SquashFS layout"); tails = new int[n]; for (int x = 0; x < n; x++) tails[x] = i.readInt(); index = 0; }
                if (off < 0 || len <= 0 || off + len > t.imageLength) throw new IOException("corrupt SquashFS layout");
                bl[k] = new BRec(kind, off, len, file, index, tails, crc);
            }
            raf.setLength(t.imageLength);
            byte[] chunk;
            while (true) {
                long off = i.readLong();
                if (off < 0) break;
                int n = i.readInt();
                if (n <= 0 || off + n > t.imageLength) throw new IOException("corrupt SquashFS layout");
                chunk = new byte[n];
                i.readFully(chunk);
                raf.seek(off); raf.write(chunk);
            }

            Comp comp = nb == 0 ? null : compressor(h.comp, h.opt);
            if (nb > 0 && comp == null) throw new IOException("this version of the app cannot recompress the blocks of this SquashFS image; import the stock firmware again");
            FileCache cache = new FileCache(content, 64L << 20);
            int bs = t.blockSize;
            for (int k = 0; k < nb; k++) {
                BRec b = bl[k];
                if (progress != null && k % 64 == 0) progress.step(k, nb);
                byte[] data;
                if (b.kind == KIND_FILE) {
                    String p = paths.get(b.file);
                    byte[] all = cache.get(p, infos.get(b.file));
                    if (all == null) { bad.add(p); continue; }
                    long from = (long) b.index * bs;
                    int n = (int) Math.min(bs, all.length - from);
                    if (n <= 0) throw new IOException("a file is shorter than the layout says: " + p);
                    data = Arrays.copyOfRange(all, (int) from, (int) from + n);
                } else {
                    ByteArrayOutputStream cat = new ByteArrayOutputStream(bs);
                    boolean missing = false;
                    for (int fi : b.tails) {
                        String p = paths.get(fi);
                        byte[] all = cache.get(p, infos.get(fi));
                        if (all == null) { bad.add(p); missing = true; continue; }
                        int full = (int) (all.length / bs) * bs;
                        cat.write(all, full, all.length - full);
                    }
                    if (missing) continue;
                    data = cat.toByteArray();
                }
                byte[] c = comp.compress(data);
                byte[] stored = c == null ? data : c;
                if (stored.length != b.len || crc(stored) != b.crc)
                    throw new IOException("a data block does not come out as it was recorded (the compressor of this device is not the one that made the image); import the stock firmware again");
                raf.seek(b.off); raf.write(stored);
            }
            if (progress != null) progress.step(nb, nb);
        }
        return new ArrayList<>(bad);
    }

    /** The stock bytes of the last files asked for, so that a fragment block does not read a large file again; null results are remembered too. */
    private static final class FileCache {
        final Content content; final long cap; long used;
        final LinkedHashMap<String, byte[]> map = new LinkedHashMap<>(16, 0.75f, true);
        final Set<String> missing = new HashSet<>();
        FileCache(Content c, long cap) { content = c; this.cap = cap; }
        byte[] get(String path, Info info) throws IOException {
            if (missing.contains(path)) return null;
            byte[] b = map.get(path);
            if (b != null) return b;
            b = content.read(path);
            if (b == null || b.length != info.size) { missing.add(path); return null; }
            if (b.length <= cap) {
                map.put(path, b); used += b.length;
                while (used > cap && map.size() > 1) {
                    Map.Entry<String, byte[]> e = map.entrySet().iterator().next();
                    used -= e.getValue().length; map.remove(e.getKey());
                }
            }
            return b;
        }
    }

    // ------------------------------------------------------------------ small helpers

    private static int crc(byte[] b) { CRC32 c = new CRC32(); c.update(b, 0, b.length); return (int) c.getValue(); }

    private static int g16(byte[] b, int o, boolean be) { return be ? ((b[o] & 255) << 8) | (b[o + 1] & 255) : (b[o] & 255) | ((b[o + 1] & 255) << 8); }

    private static int g32(byte[] b, int o, boolean be) { return be ? (g16(b, o, true) << 16) | g16(b, o + 2, true) : g16(b, o, false) | (g16(b, o + 2, false) << 16); }

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder(b.length * 2);
        for (byte x : b) { s.append(HEX[(x >> 4) & 15]); s.append(HEX[x & 15]); }
        return s.toString();
    }

    static byte[] unhex(String s) {
        byte[] b = new byte[s.length() / 2];
        for (int i = 0; i < b.length; i++) b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
        return b;
    }
}
