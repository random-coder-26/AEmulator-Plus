package app.aemu.archive;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.util.ArrayList;
import java.util.List;

/** Opens 7z archives: natively (streamed, any size) when libaemuarc takes them, else with Commons Compress. */
public final class SevenZReaders {
    private SevenZReaders() {}

    /** The channel is only read from, never closed or repositioned by the native reader. */
    public static SevenZReader open(FileChannel ch) throws IOException {
        if (NativeArc.AVAILABLE) {
            try {
                return new Native(ch);
            } catch (UnsupportedOperationException | UnsatisfiedLinkError e) { /* e.g. AES, exotic coders: Java reader */
            } catch (IOException e) { /* damaged or not a 7z: the Java reader gives the usual diagnosis */ }
        }
        return new Commons(ch);
    }

    /** True when the native reader is in use for this archive. */
    public static boolean isNative(SevenZReader r) { return r instanceof Native; }

    private static final class Native implements SevenZReader {
        private final FileChannel ch;
        private long h;
        private final List<Entry> entries = new ArrayList<>();
        private int idx = -1;

        Native(FileChannel ch) throws IOException {
            this.ch = ch;
            Object src = new Object() {
                @SuppressWarnings("unused") // called from native code
                public int readAt(long pos, ByteBuffer dst) throws IOException { return ch.read(dst, pos); }
            };
            h = NativeArc.szOpen(src, ch.size(), 512L << 20);
            try {
                int n = NativeArc.szCount(h);
                long[] info = new long[2];
                for (int i = 0; i < n; i++) {
                    NativeArc.szInfo(h, i, info);
                    entries.add(new Entry(NativeArc.szName(h, i), info[0], info[1] != 0));
                }
            } catch (RuntimeException | Error e) {
                NativeArc.szClose(h); h = 0;
                throw e;
            }
        }

        @Override public List<Entry> entries() { return entries; }

        @Override public Entry nextEntry() throws IOException {
            if (h == 0) throw new IOException("Stream closed");
            if (++idx >= entries.size()) { idx = entries.size(); return null; }
            NativeArc.szSelect(h, idx);
            return entries.get(idx);
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (h == 0) throw new IOException("Stream closed");
            if (idx < 0 || idx >= entries.size()) return -1;
            if (len == 0) return 0;
            return NativeArc.szRead(h, b, off, len);
        }

        @Override public void close() { long x = h; h = 0; if (x != 0) NativeArc.szClose(x); }
    }

    private static final class Commons implements SevenZReader {
        private final SevenZFile z;
        private final List<Entry> entries = new ArrayList<>();

        Commons(FileChannel ch) throws IOException {
            ch.position(0);
            z = SevenZFile.builder().setSeekableByteChannel(new NonClosing(ch)).get();
            for (SevenZArchiveEntry e : z.getEntries()) entries.add(conv(e));
        }

        private static Entry conv(SevenZArchiveEntry e) { return new Entry(e.getName(), e.getSize(), e.isDirectory()); }

        @Override public List<Entry> entries() { return entries; }
        @Override public Entry nextEntry() throws IOException {
            SevenZArchiveEntry e = z.getNextEntry();
            return e == null ? null : conv(e);
        }
        @Override public int read(byte[] b, int off, int len) throws IOException { return z.read(b, off, len); }
        @Override public void close() throws IOException { z.close(); }
    }

    private static final class NonClosing implements SeekableByteChannel {
        private final SeekableByteChannel c;
        NonClosing(SeekableByteChannel c) { this.c = c; }
        @Override public int read(ByteBuffer dst) throws IOException { return c.read(dst); }
        @Override public int write(ByteBuffer src) throws IOException { return c.write(src); }
        @Override public long position() throws IOException { return c.position(); }
        @Override public SeekableByteChannel position(long p) throws IOException { c.position(p); return this; }
        @Override public long size() throws IOException { return c.size(); }
        @Override public SeekableByteChannel truncate(long s) throws IOException { c.truncate(s); return this; }
        @Override public boolean isOpen() { return c.isOpen(); }
        @Override public void close() {}
    }
}
