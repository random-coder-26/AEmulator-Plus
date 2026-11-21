/* Added for AEmulator Plus, 2026-11-15: the HTTP debugger. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.netdebug

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Header list: order and spelling as sent, lookups ignore case. */
class Headers(private val l: MutableList<Pair<String, String>> = ArrayList()) {
    val all: List<Pair<String, String>> get() = l
    operator fun get(name: String): String? = l.firstOrNull { it.first.equals(name, true) }?.second
    fun getAll(name: String): List<String> = l.filter { it.first.equals(name, true) }.map { it.second }
    fun add(name: String, value: String) { l += name to value }
    fun set(name: String, value: String) {
        val i = l.indexOfFirst { it.first.equals(name, true) }
        if (i < 0) { l += name to value; return }
        l[i] = l[i].first to value
        var j = l.size - 1
        while (j > i) { if (l[j].first.equals(name, true)) l.removeAt(j); j-- }
    }
    fun remove(name: String) { l.removeAll { it.first.equals(name, true) } }
    fun has(name: String) = l.any { it.first.equals(name, true) }
    fun hasToken(name: String, token: String) = getAll(name).any { v -> v.split(',').any { it.trim().equals(token, true) } }
    fun copy() = Headers(ArrayList(l))
}

class RequestHead(var method: String, var target: String, var version: String, val headers: Headers)
class ResponseHead(var version: String, var status: Int, var reason: String, val headers: Headers)

sealed class Framing {
    object None : Framing()
    class Length(val n: Long) : Framing()
    object Chunked : Framing()
    object UntilClose : Framing()
}

/** A body as seen by the log: the first bytes, and how long it really was. */
class Body(val bytes: ByteArray, val total: Long, val truncated: Boolean) {
    companion object { val EMPTY = Body(ByteArray(0), 0, false) }
}

object Http {
    private const val MAX_LINE = 65536
    private const val MAX_HEADERS = 400
    private val METHODS = setOf("GET", "POST", "PUT", "DELETE", "HEAD", "OPTIONS", "PATCH", "TRACE", "CONNECT", "PROPFIND", "PROPPATCH", "MKCOL", "COPY", "MOVE", "LOCK", "UNLOCK", "REPORT", "SEARCH", "PURGE")

    /** true if [first] bytes look like the start of an HTTP/1.x request */
    fun looksLikeRequest(first: ByteArray, n: Int): Boolean {
        val s = String(first, 0, minOf(n, 8), Charsets.ISO_8859_1)
        val sp = s.indexOf(' ')
        return if (sp > 0) s.substring(0, sp) in METHODS else n < 8 && METHODS.any { it.startsWith(s) }
    }

    fun readLine(inp: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = inp.read()
            if (c < 0) return if (sb.isEmpty()) null else throw EOFException("closed inside a line")
            if (c == '\n'.code) { if (sb.isNotEmpty() && sb.last() == '\r') sb.setLength(sb.length - 1); return sb.toString() }
            sb.append(c.toChar())
            if (sb.length > MAX_LINE) throw IOException("line too long")
        }
    }

    private fun readHeaders(inp: InputStream): Headers {
        val h = Headers()
        var n = 0
        while (true) {
            val line = readLine(inp) ?: throw EOFException("closed inside the headers")
            if (line.isEmpty()) return h
            if (++n > MAX_HEADERS) throw IOException("too many headers")
            if (line[0] == ' ' || line[0] == '\t') {                       // obsolete line folding
                val last = h.all.lastOrNull() ?: continue
                h.set(last.first, last.second + " " + line.trim()); continue
            }
            val c = line.indexOf(':')
            if (c <= 0) continue
            h.add(line.substring(0, c).trim(), line.substring(c + 1).trim())
        }
    }

    fun readRequestHead(inp: InputStream): RequestHead? {
        var line = readLine(inp) ?: return null
        while (line.isEmpty()) line = readLine(inp) ?: return null           // stray CRLF between requests
        val p = line.split(' ', limit = 3)
        if (p.size < 3 || !p[2].startsWith("HTTP/")) throw IOException("not an HTTP request: " + line.take(60))
        return RequestHead(p[0], p[1], p[2], readHeaders(inp))
    }

    fun readResponseHead(inp: InputStream): ResponseHead {
        val line = readLine(inp) ?: throw EOFException("upstream closed without an answer")
        val p = line.split(' ', limit = 3)
        if (p.size < 2 || !p[0].startsWith("HTTP/")) throw IOException("not an HTTP response: " + line.take(60))
        return ResponseHead(p[0], p[1].toIntOrNull() ?: throw IOException("bad status line"), p.getOrElse(2) { "" }, readHeaders(inp))
    }

    fun writeRequestHead(out: OutputStream, h: RequestHead) = writeHead(out, "${h.method} ${h.target} ${h.version}", h.headers)
    fun writeResponseHead(out: OutputStream, h: ResponseHead) = writeHead(out, "${h.version} ${h.status} ${h.reason}", h.headers)

    private fun writeHead(out: OutputStream, first: String, h: Headers) {
        val sb = StringBuilder(first).append("\r\n")
        for ((k, v) in h.all) sb.append(k).append(": ").append(v).append("\r\n")
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    fun requestFraming(h: Headers): Framing = when {
        h.hasToken("Transfer-Encoding", "chunked") -> Framing.Chunked
        else -> h["Content-Length"]?.trim()?.toLongOrNull()?.let { if (it > 0) Framing.Length(it) else Framing.None } ?: Framing.None
    }

    fun responseFraming(method: String, status: Int, h: Headers): Framing = when {
        method.equals("HEAD", true) || status in 100..199 || status == 204 || status == 304 -> Framing.None
        h.hasToken("Transfer-Encoding", "chunked") -> Framing.Chunked
        h["Content-Length"]?.trim()?.toLongOrNull() != null -> h["Content-Length"]!!.trim().toLong().let { if (it > 0) Framing.Length(it) else Framing.None }
        else -> Framing.UntilClose
    }

    /**
     * Moves one message body from [inp] to [out] (null: read and drop), keeping its framing as it was, and returns what
     * the log should keep of it: the first [cap] bytes (decoded from chunks) and the full length.
     */
    fun relayBody(f: Framing, inp: InputStream, out: OutputStream?, cap: Int): Body {
        val keep = ByteArrayOutputStream()
        var total = 0L
        fun note(b: ByteArray, n: Int) { total += n; if (keep.size() < cap) keep.write(b, 0, minOf(n, cap - keep.size())) }
        val buf = ByteArray(16384)
        when (f) {
            is Framing.None -> {}
            is Framing.Length -> {
                var left = f.n
                while (left > 0) {
                    val r = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (r < 0) throw EOFException("body cut short")
                    out?.write(buf, 0, r); note(buf, r); left -= r
                }
            }
            is Framing.UntilClose -> while (true) {
                val r = inp.read(buf); if (r < 0) break
                out?.write(buf, 0, r); out?.flush(); note(buf, r)
            }
            is Framing.Chunked -> while (true) {
                val line = readLine(inp) ?: throw EOFException("chunked body cut short")
                val size = line.substringBefore(';').trim().toLongOrNull(16) ?: throw IOException("bad chunk size '$line'")
                out?.write((line + "\r\n").toByteArray(Charsets.ISO_8859_1))
                if (size == 0L) {
                    while (true) {                                            // trailers
                        val t = readLine(inp) ?: throw EOFException("chunked trailer cut short")
                        out?.write((t + "\r\n").toByteArray(Charsets.ISO_8859_1))
                        if (t.isEmpty()) break
                    }
                    break
                }
                var left = size
                while (left > 0) {
                    val r = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (r < 0) throw EOFException("chunk cut short")
                    out?.write(buf, 0, r); note(buf, r); left -= r
                }
                val crlf = readLine(inp) ?: throw EOFException("chunk cut short")
                if (crlf.isNotEmpty()) throw IOException("bad chunk end")
                out?.write("\r\n".toByteArray())
                out?.flush()
            }
        }
        out?.flush()
        return Body(keep.toByteArray(), total, total > keep.size())
    }

    /** Reads a whole body into memory, or null when it is longer than [max] (nothing is lost: [first] holds what was read). */
    fun readBodyFully(f: Framing, inp: InputStream, max: Int): ByteArray? {
        val sink = ByteArrayOutputStream()
        val b = relayBody(f, inp, sink, max + 1)
        return if (b.total > max) null else b.bytes
    }

    /** the same body, decoded from its Content-Encoding for display (gzip, deflate); the bytes as they are when unknown */
    fun decodeForDisplay(headers: Headers, body: ByteArray): ByteArray {
        val enc = headers["Content-Encoding"]?.trim()?.lowercase() ?: return body
        return runCatching {
            val ins: InputStream = when (enc) {
                "gzip", "x-gzip" -> java.util.zip.GZIPInputStream(body.inputStream())
                "deflate" -> java.util.zip.InflaterInputStream(body.inputStream())
                else -> return body
            }
            val out = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            try { while (true) { val n = ins.read(buf); if (n < 0) break; out.write(buf, 0, n) } } catch (_: IOException) { /* a cut-short capture: what decoded is shown */ }
            out.toByteArray()
        }.getOrDefault(body)
    }

    fun splitTarget(target: String): Pair<String, String?> {                // path, raw query
        val t = if (target.startsWith("http://", true) || target.startsWith("https://", true)) target.substringAfter("://").let { "/" + it.substringAfter('/', "") } else target
        val q = t.indexOf('?')
        return if (q < 0) t to null else t.substring(0, q) to t.substring(q + 1)
    }

    fun parseQuery(q: String?): List<Pair<String, String>> =
        if (q.isNullOrEmpty()) emptyList() else q.split('&').filter { it.isNotEmpty() }.map {
            val e = it.indexOf('=')
            val k = if (e < 0) it else it.substring(0, e); val v = if (e < 0) "" else it.substring(e + 1)
            urlDecode(k) to urlDecode(v)
        }

    fun urlDecode(s: String): String = runCatching { java.net.URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)
}
