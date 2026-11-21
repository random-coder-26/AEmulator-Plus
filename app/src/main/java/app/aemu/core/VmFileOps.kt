/* AEmulator Plus addition, 2026-11-15: file operations behind "Browse VM files". GPL-3.0; see NOTICE.md. */
package app.aemu.core

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.system.Os
import android.webkit.MimeTypeMap
import app.aemu.R
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Locale

/** One row of a folder listing. [isDir] and [isRegular] follow links, [isLink] says the entry itself is one. */
internal class VmNode(val file: File, val isDir: Boolean, val isLink: Boolean, val isRegular: Boolean, val size: Long, val modified: Long) {
    val name: String get() = file.name
    val path: String get() = file.path
}

internal class OpResult(val done: Int, val skipped: Int)

internal class TextPreview(val lines: List<String>, val truncated: Boolean, val total: Long)

/**
 * Listing, previewing, copying, renaming and deleting inside a VM's own directory (the tree the export reads from).
 *
 * Rules every write follows:
 *  - the same lease and idle check as export/import/reset: nothing is changed while any VM is running;
 *  - only items that really live in the VM's directory may change (a link to somewhere else is the link, never its target);
 *  - the VM's own bookkeeping (image.json, the lock files, root/dhd.owners, root itself) is never touched;
 *  - links are never followed when deleting, and the guest ownership table (root/dhd.owners) is kept in step: rows of
 *    deleted inodes are dropped, copies get the owner of the original.
 * Reading and copying out only need read access and work while the VM runs.
 */
internal object VmFileOps {
    enum class Access { READONLY, PROTECTED, CORE, NORMAL }

    const val PREVIEW_MAX = 256 * 1024

    private val TEXT_EXT = setOf(
        "txt", "prop", "log", "py", "md", "json", "xml", "sh", "rc", "cfg", "conf", "ini", "csv", "tsv", "yaml", "yml", "toml",
        "java", "kt", "kts", "c", "h", "cc", "cpp", "hpp", "js", "html", "htm", "css", "mk", "bp", "te", "gradle", "properties",
        "list", "policy", "rules", "sql", "pl", "lua", "smali", "pro", "svc", "mf", "bat", "cmd", "lst", "tab", "env",
    )

    // ------------------------------------------------------------------------------------------------------------
    // Paths
    // ------------------------------------------------------------------------------------------------------------

    fun vmDir(ctx: Context, id: String): File = VmPaths(ctx, id).dir.canonicalFile

    /** Real location of [f] with its last component left alone: a link is the item, not what it points to. */
    private fun slot(f: File): Path? = f.absoluteFile.parentFile?.canonicalFile?.toPath()?.resolve(f.name)

    /** True when the folder's real location (links resolved) is in the VM's directory. */
    fun dirInside(vm: File, dir: File): Boolean = try { dir.canonicalFile.toPath().startsWith(vm.toPath()) } catch (e: IOException) { false }

    /** May the browser open this folder? The VM's directory, or what it links to inside the app's private files (shared system). */
    fun canEnter(allowed: File, dir: File): Boolean =
        try { dir.isDirectory && dir.canonicalFile.toPath().startsWith(allowed.toPath()) } catch (e: IOException) { false }

    private fun isProtected(rel: String) =
        rel.isEmpty() || rel == "root" || rel == "image.json" || rel == "image.json.tmp" || rel == "root/dhd.owners" ||
            Regex("vm-storage(-\\d+)?\\.lock").matches(rel)

    fun access(vm: File, f: File): Access {
        val p = slot(f)
        if (p == null || !p.startsWith(vm.toPath())) return Access.READONLY
        val rel = vm.toPath().relativize(p).toString()
        return when {
            isProtected(rel) -> Access.PROTECTED
            rel.count { it == '/' } <= 1 -> Access.CORE   // root/system, root/data, boot.img, ...: the VM's partitions and images
            else -> Access.NORMAL
        }
    }

    private fun requireMutable(vm: File, f: File) {
        when (access(vm, f)) {
            Access.READONLY -> localizedError("Not part of the VM's own files", R.string.vmf_readonly)
            Access.PROTECTED -> localizedError("Protected VM file", R.string.vmf_protected)
            else -> {}
        }
    }

    fun validName(name: String): Boolean =
        name.isNotEmpty() && name != "." && name != ".." && '/' !in name && '\u0000' !in name && name.toByteArray(Charsets.UTF_8).size <= 255

    private fun absent(f: File) = !Files.exists(f.toPath(), LinkOption.NOFOLLOW_LINKS)

    /** [name] in [dir], or "name (2).ext", "name (3).ext" ... when it is taken. */
    fun uniqueIn(dir: File, name: String): File {
        val first = File(dir, name)
        if (absent(first)) return first
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var i = 2
        while (true) {
            val f = File(dir, "$base ($i)$ext")
            if (absent(f)) return f
            i++
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Listing and previews
    // ------------------------------------------------------------------------------------------------------------

    fun list(dir: File): List<VmNode>? {
        val names = dir.list() ?: return null
        return names.map { n ->
            val f = File(dir, n)
            val reg = f.isFile
            VmNode(f, f.isDirectory, Files.isSymbolicLink(f.toPath()), reg, if (reg) f.length() else 0L, f.lastModified())
        }
    }

    fun isTextName(name: String): Boolean = name.substringAfterLast('.', "").lowercase(Locale.ROOT) in TEXT_EXT

    private fun readUpTo(input: InputStream, buf: ByteArray): Int {
        var n = 0
        while (n < buf.size) {
            val r = input.read(buf, n, buf.size - n)
            if (r < 0) break
            n += r
        }
        return n
    }

    /** For files whose name says nothing: no NUL bytes, valid UTF-8 and hardly any control characters in the first 4 KB. */
    fun looksLikeText(f: File): Boolean {
        if (!f.isFile) return false
        return try {
            val buf = ByteArray(4096)
            val n = f.inputStream().use { readUpTo(it, buf) }
            if (n == 0) return true
            var control = 0
            for (i in 0 until n) {
                val b = buf[i].toInt() and 0xff
                if (b == 0) return false
                if (b < 32 && b != 9 && b != 10 && b != 12 && b != 13 && b != 27) control++
            }
            if (control * 50 > n) return false
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            // a character cut off at the end of the sample is not an error unless the whole file fits in it
            !decoder.decode(ByteBuffer.wrap(buf, 0, n), CharBuffer.allocate(n + 1), n < buf.size).isError
        } catch (e: IOException) { false }
    }

    fun readPreview(f: File): TextPreview {
        if (!f.isFile) throw IOException("Not a regular file")
        val total = f.length()
        val buf = ByteArray(PREVIEW_MAX)
        val n = f.inputStream().use { readUpTo(it, buf) }
        val truncated = total > PREVIEW_MAX
        var text = String(buf, 0, n, Charsets.UTF_8)
        if (truncated) text = text.trimEnd('\uFFFD')
        return TextPreview(text.replace("\r\n", "\n").replace('\r', '\n').split('\n'), truncated, total)
    }

    // ------------------------------------------------------------------------------------------------------------
    // Guest ownership table
    // ------------------------------------------------------------------------------------------------------------

    private fun key(f: File): String {
        val st = Os.lstat(f.path)
        return String.format(Locale.ROOT, "%016x %016x", st.st_dev, st.st_ino)
    }

    /** "dev ino" to (uid, gid) as the hex strings the table holds. */
    private fun loadOwners(paths: VmPaths): HashMap<String, Pair<String, String>> {
        val out = HashMap<String, Pair<String, String>>()
        if (paths.owners.isFile) paths.owners.forEachLine { line ->
            val f = line.trim().split(Regex("\\s+"))
            if (f.size == 4) out[f[0] + " " + f[1]] = f[2] to f[3]
        }
        return out
    }

    private fun pruneOwners(paths: VmPaths, removed: Set<String>) {
        val owners = paths.owners
        if (removed.isEmpty() || !owners.isFile) return
        require(!Files.isSymbolicLink(owners.toPath())) { "Unsafe ownership table" }
        val temp = Files.createTempFile(owners.parentFile.toPath(), "owners-edit-", ".tmp")
        try {
            Files.newBufferedWriter(temp).use { out ->
                owners.forEachLine { line ->
                    val k = line.trim().split(Regex("\\s+")).take(2).joinToString(" ")
                    if (k !in removed) { out.write(line); out.newLine() }
                }
            }
            Files.move(temp, owners.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally { Files.deleteIfExists(temp) }
    }

    private inline fun <T> exclusive(ctx: Context, block: () -> T): T =
        VmStorageLease(ctx.filesDir).use { lease ->
            lease.acquire()
            GuestStorageWriters.requireIdle(ctx)
            block()
        }

    // ------------------------------------------------------------------------------------------------------------
    // Delete, rename, new folder
    // ------------------------------------------------------------------------------------------------------------

    /** Never follows links; the owner table is pruned before anything is unlinked so a recycled inode cannot inherit a stale owner. */
    fun delete(ctx: Context, id: String, targets: List<File>, progress: (String) -> Unit): Int = exclusive(ctx) {
        val paths = VmPaths(ctx, id)
        val vm = paths.dir.canonicalFile
        for (t in targets) requireMutable(vm, t)
        val keys = HashSet<String>()
        for (t in targets) if (!absent(t)) Files.walkFileTree(t.toPath(), object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(p: Path, a: BasicFileAttributes): FileVisitResult {
                openUp(p.toFile()); keys += runCatching { key(p.toFile()) }.getOrDefault(""); return FileVisitResult.CONTINUE
            }
            override fun visitFile(p: Path, a: BasicFileAttributes): FileVisitResult {
                keys += runCatching { key(p.toFile()) }.getOrDefault(""); return FileVisitResult.CONTINUE
            }
        })
        pruneOwners(paths, keys)
        for (t in targets) if (!absent(t)) deleteTree(t, progress)
        targets.size
    }

    /** The guest may have stripped the owner's rights on a folder; the host side needs them to look inside and to unlink. */
    private fun openUp(dir: File) {
        dir.setReadable(true, true); dir.setWritable(true, true); dir.setExecutable(true, true)
    }

    private fun deleteTree(root: File, progress: (String) -> Unit) {
        Files.walkFileTree(root.toPath(), object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(p: Path, a: BasicFileAttributes): FileVisitResult {
                openUp(p.toFile()); return FileVisitResult.CONTINUE
            }
            override fun visitFile(p: Path, a: BasicFileAttributes): FileVisitResult {
                progress(p.fileName.toString()); Files.delete(p); return FileVisitResult.CONTINUE
            }
            override fun postVisitDirectory(p: Path, e: IOException?): FileVisitResult {
                if (e != null) throw e
                Files.delete(p); return FileVisitResult.CONTINUE
            }
        })
    }

    fun rename(ctx: Context, id: String, file: File, newName: String): Unit = exclusive(ctx) {
        val vm = vmDir(ctx, id)
        requireMutable(vm, file)
        val name = newName.trim()
        if (!validName(name)) localizedError("That name is not allowed", R.string.vmf_bad_name)
        val target = File(file.parentFile ?: localizedError("No parent folder", R.string.vmf_bad_name), name)
        if (!absent(target)) localizedError("An item with that name exists", R.string.vmf_exists)
        // same folder, same inode: the guest ownership table needs no change
        Files.move(file.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    fun makeDir(ctx: Context, id: String, parent: File, name: String): Unit = exclusive(ctx) {
        val vm = vmDir(ctx, id)
        if (!dirInside(vm, parent)) localizedError("Not part of the VM's own files", R.string.vmf_readonly)
        val n = name.trim()
        if (!validName(n)) localizedError("That name is not allowed", R.string.vmf_bad_name)
        val target = File(parent, n)
        if (!absent(target)) localizedError("An item with that name exists", R.string.vmf_exists)
        Files.createDirectory(target.toPath())
    }

    fun makeOutsideDir(parent: File, name: String) {
        val n = name.trim()
        if (!validName(n)) localizedError("That name is not allowed", R.string.vmf_bad_name)
        val target = File(parent, n)
        if (!absent(target)) localizedError("An item with that name exists", R.string.vmf_exists)
        Files.createDirectory(target.toPath())
    }

    // ------------------------------------------------------------------------------------------------------------
    // Copy: inside the VM
    // ------------------------------------------------------------------------------------------------------------

    private fun sizeOf(f: File): Long {
        var sum = 0L
        Files.walkFileTree(f.toPath(), object : SimpleFileVisitor<Path>() {
            override fun visitFile(p: Path, a: BasicFileAttributes): FileVisitResult {
                if (a.isRegularFile) sum += a.size(); return FileVisitResult.CONTINUE
            }
            override fun visitFileFailed(p: Path, e: IOException): FileVisitResult = FileVisitResult.CONTINUE
        })
        return sum
    }

    private class InsideCopier(val owners: Map<String, Pair<String, String>>, val rows: StringBuilder, val progress: (String) -> Unit) {
        var skipped = 0

        fun copy(src: File, dest: File): Boolean {
            val attrs = Files.readAttributes(src.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
            val srcKey = runCatching { key(src) }.getOrNull()
            val mode = runCatching { Os.lstat(src.path).st_mode and 0xfff }.getOrDefault(0x1a4)
            when {
                attrs.isSymbolicLink -> {
                    val target = Files.readSymbolicLink(src.toPath())
                    if (target.isAbsolute) { skipped++; return false }   // host-engine link, rebuilt by TreeFixer
                    Files.createSymbolicLink(dest.toPath(), target)
                }
                attrs.isDirectory -> {
                    progress(src.name)
                    Files.createDirectory(dest.toPath())
                    val kids = src.listFiles() ?: throw IOException("Cannot read ${src.name}")
                    for (c in kids.sortedBy { it.name }) copy(c, File(dest, c.name))
                    Os.chmod(dest.path, mode)   // after the children: the original may not allow writing
                }
                attrs.isRegularFile -> {
                    progress(src.name)
                    src.inputStream().use { i -> dest.outputStream().use { o -> i.copyTo(o, 65536) } }
                    Os.chmod(dest.path, mode)
                }
                else -> { skipped++; return false }
            }
            if (srcKey != null) owners[srcKey]?.let { (uid, gid) ->
                rows.append(key(dest)).append(' ').append(uid).append(' ').append(gid).append('\n')
            }
            return true
        }
    }

    /** Copies [sources] into [destDir], another place in the same VM. Owner and mode are kept; a failed item leaves nothing behind. */
    fun copyInside(ctx: Context, id: String, sources: List<File>, destDir: File, progress: (String) -> Unit): OpResult = exclusive(ctx) {
        val paths = VmPaths(ctx, id)
        val vm = paths.dir.canonicalFile
        if (!dirInside(vm, destDir)) localizedError("Not part of the VM's own files", R.string.vmf_readonly)
        if (sources.sumOf { sizeOf(it) } > destDir.usableSpace) localizedError("Not enough space", R.string.vmf_no_space)
        val rows = StringBuilder()
        val copier = InsideCopier(loadOwners(paths), rows, progress)
        val destReal = destDir.canonicalFile.toPath()
        var done = 0
        try {
            for (src in sources) {
                val real = slot(src)
                // a folder cannot be copied into itself
                if (src.isDirectory && !Files.isSymbolicLink(src.toPath()) && real != null && destReal.startsWith(real)) { copier.skipped++; continue }
                val dest = uniqueIn(destDir, src.name)
                val mark = rows.length
                try {
                    if (copier.copy(src, dest)) done++
                } catch (t: Throwable) {
                    rows.setLength(mark)
                    if (!absent(dest)) runCatching { deleteTree(dest) {} }
                    throw t
                }
            }
        } finally {
            if (rows.isNotEmpty()) paths.owners.appendText(rows.toString())
        }
        OpResult(done, copier.skipped)
    }

    // ------------------------------------------------------------------------------------------------------------
    // Copy: out of the VM
    // ------------------------------------------------------------------------------------------------------------

    private interface Sink {
        fun dir(name: String): Sink
        fun file(name: String, src: File)
    }

    private class FileSink(val dir: File) : Sink {
        override fun dir(name: String): Sink {
            val d = uniqueIn(dir, name)
            Files.createDirectory(d.toPath())
            return FileSink(d)
        }
        override fun file(name: String, src: File) {
            val d = uniqueIn(dir, name)
            src.inputStream().use { i -> d.outputStream().use { o -> i.copyTo(o, 65536) } }
            d.setLastModified(src.lastModified())
        }
    }

    private class TreeSink(val ctx: Context, val parent: Uri) : Sink {
        override fun dir(name: String): Sink {
            val u = DocumentsContract.createDocument(ctx.contentResolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name)
                ?: throw IOException("Cannot create folder $name")
            return TreeSink(ctx, u)
        }
        override fun file(name: String, src: File) {
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase(Locale.ROOT))
                ?: "application/octet-stream"
            val u = DocumentsContract.createDocument(ctx.contentResolver, parent, mime, name) ?: throw IOException("Cannot create $name")
            val out = ctx.contentResolver.openOutputStream(u) ?: throw IOException("Cannot write $name")
            out.use { o -> src.inputStream().use { it.copyTo(o, 65536) } }
        }
    }

    private class OutCopier(val allowed: Path, val progress: (String) -> Unit) {
        var skipped = 0

        /** Links are followed only to what stays inside the app's private files; loops are cut. */
        fun copy(src: File, sink: Sink, chain: Set<String> = emptySet()): Boolean {
            val real = try { src.canonicalFile } catch (e: IOException) { skipped++; return false }
            if (Files.isSymbolicLink(src.toPath()) && !real.toPath().startsWith(allowed)) { skipped++; return false }
            when {
                real.isDirectory -> {
                    if (real.path in chain) { skipped++; return false }
                    progress(src.name)
                    val child = sink.dir(src.name)
                    val kids = src.listFiles() ?: throw IOException("Cannot read ${src.name}")
                    for (k in kids.sortedBy { it.name }) copy(k, child, chain + real.path)
                }
                real.isFile -> { progress(src.name); sink.file(src.name, src) }
                else -> { skipped++; return false }
            }
            return true
        }
    }

    private fun copyOut(ctx: Context, sources: List<File>, sink: Sink, progress: (String) -> Unit): OpResult {
        val copier = OutCopier(ctx.filesDir.canonicalFile.toPath(), progress)
        var done = 0
        for (src in sources) if (copier.copy(src, sink)) done++
        return OpResult(done, copier.skipped)
    }

    /** Copy to a folder on shared storage (plain file access). Only reads the VM, so it also works while the VM runs. */
    fun copyOutToDir(ctx: Context, sources: List<File>, destDir: File, progress: (String) -> Unit): OpResult {
        if (!destDir.isDirectory || !destDir.canWrite()) throw IOException("Cannot write to ${destDir.path}")
        if (sources.sumOf { sizeOf(it) } > destDir.usableSpace) localizedError("Not enough space", R.string.vmf_no_space)
        return copyOut(ctx, sources, FileSink(destDir), progress)
    }

    /** Copy to a folder chosen with the system's folder picker. */
    fun copyOutToTree(ctx: Context, sources: List<File>, tree: Uri, progress: (String) -> Unit): OpResult {
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return copyOut(ctx, sources, TreeSink(ctx, root), progress)
    }
}
