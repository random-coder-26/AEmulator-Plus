package app.aemu.archive;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;

/** A forward reader of a 7z archive: nextEntry(), then read() until -1 (what is left unread of an entry is skipped). */
public interface SevenZReader extends Closeable {
    final class Entry {
        public final String name;
        public final long size;
        public final boolean isDirectory;
        public Entry(String name, long size, boolean isDirectory) { this.name = name; this.size = size; this.isDirectory = isDirectory; }
        public String getName() { return name; }
        public long getSize() { return size; }
    }

    List<Entry> entries();
    /** The next entry, null after the last one. */
    Entry nextEntry() throws IOException;
    /** Bytes of the current entry; -1 at its end. */
    int read(byte[] b, int off, int len) throws IOException;
    @Override void close() throws IOException;
}
