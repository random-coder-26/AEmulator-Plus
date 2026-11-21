/* Added for AEmulator Plus, 2026-11-15: the HTTP debugger. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.netdebug

import java.util.concurrent.atomic.AtomicLong

enum class Kind { HTTP, TLS, TCP, DNS }

/** One line of the log: an HTTP exchange, a connection that was only relayed, or a name lookup. Fields are filled in as it goes. */
class NetEntry(val id: Long, val kind: Kind, val startedAt: Long = System.currentTimeMillis()) {
    @Volatile var scheme = ""
    @Volatile var host = ""
    @Volatile var ip = ""
    @Volatile var port = 0
    @Volatile var method = ""
    @Volatile var path = ""              // with the query
    @Volatile var status = 0
    @Volatile var reason = ""
    @Volatile var httpVersion = "HTTP/1.1"
    @Volatile var reqHeaders: List<Pair<String, String>> = emptyList()
    @Volatile var respHeaders: List<Pair<String, String>> = emptyList()
    @Volatile var reqBody: Body = Body.EMPTY
    @Volatile var respBody: Body = Body.EMPTY
    @Volatile var durationMs = -1L
    @Volatile var ruleName = ""
    @Volatile var outcome = ""           // forwarded, mocked, blocked, rewritten, redirected, tunnel, failed
    @Volatile var tls = ""               // "TLSv1 TLS_RSA_WITH_AES_128_CBC_SHA" when the debugger terminated TLS
    @Volatile var error = ""
    @Volatile var bytesUp = 0L
    @Volatile var bytesDown = 0L
    @Volatile var note = ""

    val url: String get() = when (kind) {
        Kind.DNS -> host
        Kind.HTTP -> "$scheme://$host" + (if ((scheme == "https" && port == 443) || (scheme == "http" && port == 80)) "" else ":$port") + path
        else -> "${host.ifEmpty { ip }}:$port"
    }

    fun summary(): String = when (kind) {
        Kind.HTTP -> "$method $url -> ${if (status > 0) status else error.ifEmpty { "..." }} [$outcome]"
        Kind.DNS -> "DNS $host -> $note"
        else -> "${kind.name} $url [$outcome] up=$bytesUp down=$bytesDown"
    }

    fun curl(): String {
        val sb = StringBuilder("curl -i -X $method")
        for ((k, v) in reqHeaders) if (!k.equals("Content-Length", true) && !k.equals("Host", true)) sb.append(" -H '").append("$k: $v".replace("'", "'\\''")).append("'")
        if (reqBody.bytes.isNotEmpty()) sb.append(" --data-binary '").append(String(reqBody.bytes, Charsets.UTF_8).replace("'", "'\\''")).append("'")
        sb.append(" '").append(url.replace("'", "%27")).append("'")
        return sb.toString()
    }
}

/** The log of one VM: the last [capacity] entries, newest last. [version] changes whenever something did, for a screen that polls. */
class NetLog(private val capacity: Int = 2000) {
    private val items = ArrayDeque<NetEntry>()
    private val ids = AtomicLong()
    val version = AtomicLong()

    fun begin(kind: Kind): NetEntry {
        val e = NetEntry(ids.incrementAndGet(), kind)
        synchronized(items) { items.addLast(e); while (items.size > capacity) items.removeFirst() }
        version.incrementAndGet()
        return e
    }

    fun touch() { version.incrementAndGet() }
    fun snapshot(): List<NetEntry> = synchronized(items) { items.toList() }
    fun clear() { synchronized(items) { items.clear() }; version.incrementAndGet() }

    /** HAR 1.2: opens in Chrome's devtools, Charles, Fiddler... */
    fun toHar(): String {
        val entries = snapshot().filter { it.kind == Kind.HTTP }.map { e ->
            fun hdrs(l: List<Pair<String, String>>) = l.map { mapOf("name" to it.first, "value" to it.second) }
            fun content(h: List<Pair<String, String>>, b: Body): Map<String, Any?> {
                val hd = Headers(h.toMutableList())
                val decoded = Http.decodeForDisplay(hd, b.bytes)
                val mime = hd["Content-Type"] ?: ""
                val text = String(decoded, Charsets.UTF_8)
                val binary = decoded.isNotEmpty() && text.count { it == '\uFFFD' || (it < ' ' && it != '\n' && it != '\r' && it != '\t') } > decoded.size / 20
                return if (binary) mapOf("size" to b.total, "mimeType" to mime, "text" to java.util.Base64.getEncoder().encodeToString(decoded), "encoding" to "base64")
                else mapOf("size" to b.total, "mimeType" to mime, "text" to text)
            }
            val q = Http.splitTarget(e.path).second
            mapOf(
                "startedDateTime" to java.time.Instant.ofEpochMilli(e.startedAt).toString(),
                "time" to maxOf(e.durationMs, 0),
                "request" to mapOf(
                    "method" to e.method, "url" to e.url, "httpVersion" to e.httpVersion, "cookies" to emptyList<Any>(),
                    "headers" to hdrs(e.reqHeaders), "queryString" to Http.parseQuery(q).map { mapOf("name" to it.first, "value" to it.second) },
                    "headersSize" to -1, "bodySize" to e.reqBody.total,
                ) + (if (e.reqBody.total > 0) mapOf("postData" to mapOf("mimeType" to (Headers(e.reqHeaders.toMutableList())["Content-Type"] ?: ""), "text" to String(e.reqBody.bytes, Charsets.UTF_8))) else emptyMap()),
                "response" to mapOf(
                    "status" to e.status, "statusText" to e.reason, "httpVersion" to e.httpVersion, "cookies" to emptyList<Any>(),
                    "headers" to hdrs(e.respHeaders), "content" to content(e.respHeaders, e.respBody), "redirectURL" to (Headers(e.respHeaders.toMutableList())["Location"] ?: ""),
                    "headersSize" to -1, "bodySize" to e.respBody.total,
                ),
                "cache" to emptyMap<String, Any>(),
                "timings" to mapOf("send" to 0, "wait" to maxOf(e.durationMs, 0), "receive" to 0),
                "serverIPAddress" to e.ip,
                "comment" to listOf(e.outcome, e.ruleName, e.tls).filter { it.isNotEmpty() }.joinToString(" | "),
            )
        }
        return Json.stringify(mapOf("log" to mapOf("version" to "1.2", "creator" to mapOf("name" to "AEmulator Plus HTTP debugger", "version" to "1"), "entries" to entries)), pretty = true)
    }
}
