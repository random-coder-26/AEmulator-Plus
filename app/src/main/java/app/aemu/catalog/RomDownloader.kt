package app.aemu.catalog

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.Locale
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Catalog download: one request decides what a link is and, when it is a file, streams it to disk.
 *
 * A link that answers with a binary / archive (zip, rar, 7z, tar, gz, octet-stream, an attachment…) is saved to
 * [downloadsDir]; anything that looks like a page (HTML, plain text, JSON, XML…), an HTTP error, or a server that
 * cannot be reached for the check is reported as [Outcome.Page], and the caller opens the browser instead.
 * The same connection is used for the check and for the transfer, so single-use links are not spent by the check.
 */
object RomDownloader {
    sealed interface Outcome {
        /** Not a file download: open the link in the browser. */
        data object Page : Outcome
        /** The file is on disk, ready for the importer. */
        data class Saved(val file: File) : Outcome
    }

    fun downloadsDir(ctx: Context): File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "downloads").apply { mkdirs() }

    private val ARCHIVE_EXT = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "tbz2", "xz", "txz", "zst", "lz4", "lzma",
        "img", "bin", "tar.gz", "ota", "sin", "kdz", "dz", "cab", "iso", "aessvm", "nb0", "ftf", "pac", "mbn", "sparse")

    /** Content types a browser shows as a page or text instead of saving. */
    private fun isPageType(type: String): Boolean {
        val t = type.lowercase(Locale.ROOT).substringBefore(';').trim()
        return t.startsWith("text/") || t.startsWith("image/") || t.startsWith("audio/") || t.startsWith("video/") ||
            t == "application/xhtml+xml" || t == "application/xml" || t == "application/json" ||
            t == "application/javascript" || t == "application/ld+json" || t.endsWith("+xml") || t.endsWith("+json")
    }

    /** True when the response is a file to download (binary, archive, attachment). */
    internal fun isFile(contentType: String?, disposition: String?, url: String): Boolean {
        if (disposition != null && disposition.trimStart().startsWith("attachment", true)) return true
        if (!contentType.isNullOrBlank()) return !isPageType(contentType)
        // no content type at all: fall back to the extension of the URL
        val path = url.substringBefore('#').substringBefore('?').lowercase(Locale.ROOT)
        return ARCHIVE_EXT.any { path.endsWith(".$it") }
    }

    internal fun fileName(disposition: String?, url: String): String {
        var name: String? = null
        if (disposition != null) {
            Regex("filename\\*\\s*=\\s*[^']*'[^']*'([^;]+)", RegexOption.IGNORE_CASE).find(disposition)?.let {
                name = runCatching { URLDecoder.decode(it.groupValues[1].trim().trim('"'), "UTF-8") }.getOrNull()
            }
            if (name.isNullOrBlank()) Regex("filename\\s*=\\s*\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(disposition)?.let {
                name = it.groupValues[1].trim()
            }
        }
        if (name.isNullOrBlank()) {
            name = runCatching {
                URLDecoder.decode(url.substringBefore('#').substringBefore('?').trimEnd('/').substringAfterLast('/'), "UTF-8")
            }.getOrNull()
        }
        val clean = (name ?: "").substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001f]"), "_").trim().trim('.')
        if (clean.isEmpty()) return "rom-download.bin"
        if (clean.length <= 120) return clean
        val dot = clean.lastIndexOf('.')
        return if (dot in 1 until clean.length && clean.length - dot <= 12) clean.take(120 - (clean.length - dot)) + clean.substring(dot) else clean.take(120)
    }


    private const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    /** Files at least this big, from a server that accepts byte ranges, are fetched over several connections. */
    private const val PARALLEL_MIN_BYTES = 16L * 1024 * 1024
    private const val CONNECTIONS = 6
    private const val CHUNK_BYTES = 8L * 1024 * 1024

    private class RangeUnsupported : IOException("Server does not honour byte ranges")

    private fun prepare(conn: HttpURLConnection) {
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = false
        // a browser-like request: some file hosts throttle unknown clients; identity keeps Content-Length and ranges exact
        conn.setRequestProperty("User-Agent", BROWSER_UA)
        conn.setRequestProperty("Accept", "*/*")
        conn.setRequestProperty("Accept-Encoding", "identity")
    }

    /** One chunk [from]..[to] (inclusive) written at its own position; a broken connection resumes where it stopped. */
    private suspend fun fetchChunk(url: String, from: Long, to: Long, conns: MutableSet<HttpURLConnection>,
                                   file: RandomAccessFile, written: AtomicLong, report: () -> Unit) {
        var pos = from
        var failures = 0
        val buf = ByteArray(256 * 1024)
        while (pos <= to) {
            currentCoroutineContext().ensureActive()
            val conn = URL(url).openConnection() as HttpURLConnection
            conns.add(conn)
            try {
                prepare(conn)
                conn.setRequestProperty("Range", "bytes=$pos-$to")
                val code = conn.responseCode
                if (code == HttpURLConnection.HTTP_OK) throw RangeUnsupported()
                if (code != HttpURLConnection.HTTP_PARTIAL) throw IOException("HTTP $code")
                val cr = conn.getHeaderField("Content-Range") ?: ""
                if (!cr.trim().startsWith("bytes $pos-", true)) throw RangeUnsupported()
                conn.inputStream.use { input ->
                    while (pos <= to) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf, 0, minOf(buf.size.toLong(), to - pos + 1).toInt())
                        if (n < 0) throw IOException("Connection closed early")
                        file.channel.write(ByteBuffer.wrap(buf, 0, n), pos)
                        pos += n
                        written.addAndGet(n.toLong())
                        failures = 0
                        report()
                    }
                }
            } catch (e: RangeUnsupported) {
                throw e
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                if (++failures > 3) throw e
            } finally { conns.remove(conn); conn.disconnect() }
        }
    }

    /** Fetches [total] bytes of [url] into [part] over several connections, one chunk at a time per worker. */
    private suspend fun downloadParallel(url: String, part: File, total: Long, conns: MutableSet<HttpURLConnection>,
                                         onProgress: (Long, Long) -> Unit) {
        RandomAccessFile(part, "rw").use { file ->
            file.setLength(total)
            val chunks = ((total + CHUNK_BYTES - 1) / CHUNK_BYTES).toInt()
            val next = AtomicInteger(0)
            val written = AtomicLong(0)
            val lastReport = AtomicLong(0)
            val report = {
                val now = System.nanoTime()
                val last = lastReport.get()
                if (now - last > 100_000_000L && lastReport.compareAndSet(last, now)) onProgress(written.get(), total)
            }
            coroutineScope {
                repeat(minOf(CONNECTIONS, chunks)) {
                    launch(Dispatchers.IO) {
                        while (true) {
                            val i = next.getAndIncrement()
                            if (i >= chunks) break
                            val from = i * CHUNK_BYTES
                            fetchChunk(url, from, minOf(from + CHUNK_BYTES, total) - 1, conns, file, written, report)
                        }
                    }
                }
            }
            if (written.get() != total) throw IOException("Download incomplete (${written.get()} of $total bytes)")
        }
    }

    /**
     * Checks [startUrl] and downloads it when it is a file. [onFile] is called once the link is known to be a file
     * (name, size or -1), [onProgress] with the bytes written and the total (or -1). Throws on a failure after the
     * download started; before that, any problem simply means "open the browser".
     */
    suspend fun fetch(ctx: Context, startUrl: String, onFile: (String, Long) -> Unit, onProgress: (Long, Long) -> Unit): Outcome =
        withContext(Dispatchers.IO) {
            var url = CatalogUrls.valid(startUrl) ?: return@withContext Outcome.Page
            val conns: MutableSet<HttpURLConnection> = ConcurrentHashMap.newKeySet()
            coroutineScope {
                // reads on HttpURLConnection ignore coroutine cancellation: closing the socket is what stops them
                val watcher = launch { try { awaitCancellation() } finally { conns.forEach { runCatching { it.disconnect() } } } }
                var committed = false
                var target: File? = null
                var part: File? = null
                try {
                    var hop = 0
                    var forceSingle = false
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val conn = URL(url).openConnection() as HttpURLConnection
                        conns.add(conn)
                        prepare(conn)
                        val code = conn.responseCode
                        if (code in listOf(301, 302, 303, 307, 308)) {
                            val location = conn.getHeaderField("Location")
                            conn.disconnect()
                            if (location == null || ++hop > 6) return@coroutineScope Outcome.Page
                            val next = CatalogUrls.valid(URL(URL(url), location).toExternalForm()) ?: return@coroutineScope Outcome.Page
                            if (url.startsWith("https:", true) && !next.startsWith("https:", true)) return@coroutineScope Outcome.Page
                            url = next
                            continue
                        }
                        if (code != HttpURLConnection.HTTP_OK) { conn.disconnect(); return@coroutineScope Outcome.Page }
                        val disposition = conn.getHeaderField("Content-Disposition")
                        if (!isFile(conn.contentType, disposition, url)) { conn.disconnect(); return@coroutineScope Outcome.Page }

                        committed = true
                        val name = fileName(disposition, url)
                        val total = conn.contentLengthLong
                        onFile(name, total)
                        val dir = downloadsDir(ctx)
                        target = File(dir, name)
                        part = File(dir, "$name.part")
                        var written = 0L
                        val ranges = conn.getHeaderField("Accept-Ranges")?.contains("bytes", true) == true
                        if (!forceSingle && ranges && total >= PARALLEL_MIN_BYTES) {
                            // the check connection is dropped; the file comes over several ranged connections
                            conns.remove(conn); conn.disconnect()
                            try {
                                downloadParallel(url, part, total, conns, onProgress)
                                target.delete()
                                if (!part.renameTo(target)) throw IOException("Cannot save ${target.name}")
                                onProgress(total, total)
                                return@coroutineScope Outcome.Saved(target)
                            } catch (_: RangeUnsupported) {
                                part.delete()
                                forceSingle = true
                                continue
                            }
                        }
                        try {
                            conn.inputStream.use { input -> part.outputStream().buffered(256 * 1024).use { out ->
                                val buf = ByteArray(256 * 1024)
                                var lastReport = 0L
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    out.write(buf, 0, n)
                                    written += n
                                    val now = System.nanoTime()
                                    if (now - lastReport > 100_000_000L) { lastReport = now; onProgress(written, total) }
                                }
                            } }
                        } catch (e: IOException) {
                            currentCoroutineContext().ensureActive()
                            throw e
                        } finally { conns.remove(conn); conn.disconnect() }
                        if (total >= 0 && written != total) throw IOException("Download incomplete ($written of $total bytes)")
                        target.delete()
                        if (!part.renameTo(target)) throw IOException("Cannot save ${target.name}")
                        onProgress(written, if (total >= 0) total else written)
                        return@coroutineScope Outcome.Saved(target)
                    }
                    @Suppress("UNREACHABLE_CODE")
                    Outcome.Page
                } catch (e: IOException) {
                    part?.delete()
                    // a failed check (no route, TLS error…) is not a reason to give up: let the browser try
                    if (!committed) { currentCoroutineContext().ensureActive(); Outcome.Page } else throw e
                } catch (e: Throwable) {
                    part?.delete()
                    throw e
                } finally { watcher.cancel() }
            }
        }

    /** Removes downloads left over from earlier sessions (a failed import keeps its file for a day). */
    fun purgeStale(ctx: Context, maxAgeMs: Long = 24L * 3600_000L) {
        val now = System.currentTimeMillis()
        downloadsDir(ctx).listFiles()?.forEach { if (now - it.lastModified() > maxAgeMs) it.delete() }
    }
}
