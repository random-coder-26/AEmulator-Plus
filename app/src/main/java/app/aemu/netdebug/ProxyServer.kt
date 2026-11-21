/* Added for AEmulator Plus, 2026-11-15: the HTTP debugger. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.netdebug

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Where a guest's connections end up. Three kinds of door:
 *  - the header port (native engine): the guest's runner connects here for every outgoing TCP connection and first sends
 *    28 bytes: "AEHD", 1, family (4/6), port (big endian), address (16 bytes), 4 reserved. See native/common/aemu_nethook.h.
 *  - the port-80 and port-443 doors and the extra ports (qemu engine): qemu sends connections for those ports to the
 *    loopback address the guest was given for the name (see [NetDebugger.answer]); the address tells the host name.
 * Whatever it is, the bytes are read: HTTP is parsed and logged (and answered by rules), TLS is terminated when it may be,
 * anything else is relayed as it is.
 */
class ProxyServer(private val dbg: NetDebugger, private val qemuNames: Boolean) {
    private enum class Mode { HEADER, HTTP80, TLS443, EXTRA }
    private class Dest(val ip: InetAddress?, val port: Int, val hint: String?)

    private val servers = ArrayList<ServerSocket>()
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "netdebug-conn").also { it.isDaemon = true } }
    private val slots = Semaphore(256)
    @Volatile private var stopped = false
    var headerPort = 0; private set
    var http80Port = 0; private set
    var tls443Port = 0; private set

    fun start() {
        headerPort = listen(Mode.HEADER, 0, false)
        if (qemuNames) {
            http80Port = listen(Mode.HTTP80, 0, true)
            tls443Port = listen(Mode.TLS443, 0, true)
            for (p in dbg.config.extraPorts) runCatching { listen(Mode.EXTRA, p, true) }.onFailure { dbg.log.begin(Kind.TCP).also { e -> e.port = p; e.error = "cannot listen on extra port $p: $it" } }
        }
    }

    fun stop() {
        stopped = true
        for (s in servers) runCatching { s.close() }
        pool.shutdownNow()
    }

    /** [anyAddress]: the stand-in addresses are all of 127.0.0.0/8, so those doors listen on every address (loopback callers only are served) */
    private fun listen(mode: Mode, port: Int, anyAddress: Boolean): Int {
        val s = if (anyAddress) ServerSocket(port, 128) else ServerSocket(port, 128, InetAddress.getByName("127.0.0.1"))
        servers += s
        val fixed = s.localPort
        Thread({
            while (!stopped) {
                val c = try { s.accept() } catch (e: Exception) { break }
                if (!c.inetAddress.isLoopbackAddress || !slots.tryAcquire()) { runCatching { c.close() }; continue }
                pool.execute { try { handle(c, mode, fixed) } catch (e: Throwable) { /* one connection's trouble */ } finally { runCatching { c.close() }; slots.release() } }
            }
        }, "netdebug-accept-$mode").also { it.isDaemon = true }.start()
        return fixed
    }

    // ---------------------------------------------------------------------------------------------- one connection

    private fun handle(c: Socket, mode: Mode, doorPort: Int) {
        c.tcpNoDelay = true
        c.soTimeout = 120_000
        val inp = PushbackInputStream(BufferedInputStream(c.getInputStream(), 16384), 16)
        val out = c.getOutputStream()
        val dest: Dest = when (mode) {
            Mode.HEADER -> {
                val h = ByteArray(28)
                Tls.readFully(inp, h, 0, 28)
                if (h[0] != 'A'.code.toByte() || h[1] != 'E'.code.toByte() || h[2] != 'H'.code.toByte() || h[3] != 'D'.code.toByte()) return
                val port = Tls.u16(h, 6)
                val ip = if (h[5].toInt() == 6) InetAddress.getByAddress(h.copyOfRange(8, 24)) else InetAddress.getByAddress(h.copyOfRange(8, 12))
                Dest(ip, port, dbg.hostFor(ip.hostAddress ?: ""))
            }
            else -> {
                val local = c.localAddress
                val hint = dbg.hostFor(local.hostAddress ?: "")
                Dest(if (dbg.isFake(local)) null else null, if (mode == Mode.HTTP80) 80 else if (mode == Mode.TLS443) 443 else doorPort, hint)
            }
        }
        val first = ByteArray(8)
        val n = inp.read(first, 0, 8)
        if (n <= 0) return
        inp.unread(first, 0, n)
        val b0 = first[0].toInt() and 0xff
        when {
            b0 == 0x16 || (b0 == 0x80 && n > 2 && first[2].toInt() == 1) -> serveTls(c, inp, out, dest)
            Http.looksLikeRequest(first, n) -> serveHttp("http", inp, out, dest, dest.hint, "")
            else -> tunnel(c, inp, out, dest, dest.hint, Kind.TCP, null)
        }
    }

    // ---------------------------------------------------------------------------------------------- TLS

    private fun serveTls(c: Socket, inp: PushbackInputStream, out: OutputStream, dest: Dest) {
        val ch = Tls.readClientHello(inp) ?: return
        val ipStr = dest.ip?.hostAddress
        val host = ch.sni ?: dest.hint ?: ipStr?.let { dbg.hostFor(it) } ?: ipStr ?: "unknown"
        if (!dbg.config.mitm || dbg.rules.passesTls(host)) {
            tunnel(c, inp, out, Dest(dest.ip, dest.port, host), host, Kind.TLS, ch.raw)
            return
        }
        val e = dbg.log.begin(Kind.TLS)
        e.host = host; e.ip = ipStr ?: ""; e.port = dest.port
        val leaf = dbg.ca.leaf(host)
        val stream: SecureStream = try {
            val legacy = LegacyTlsServer(leaf)
            if (ch.maxVersion < 0x0303 && legacy.canServe(ch)) legacy.accept(ch, inp, out)
            else EngineStream(Tls.serverEngine(leaf), inp, out, ch.raw)
        } catch (ex: Exception) {
            e.outcome = "failed"
            e.error = "TLS handshake with the guest failed (the app may not trust the debugger's CA, or pins its server's certificate): " + (ex.message ?: ex.toString())
            e.durationMs = System.currentTimeMillis() - e.startedAt; dbg.log.touch()
            return
        }
        e.outcome = "terminated"; e.tls = stream.protocol + " " + stream.cipher
        e.durationMs = System.currentTimeMillis() - e.startedAt; dbg.log.touch()
        try { serveHttp("https", stream.input, stream.output, dest, host, stream.protocol + " " + stream.cipher) } finally { stream.close() }
    }

    // ---------------------------------------------------------------------------------------------- relaying

    private fun tunnel(c: Socket, inp: InputStream, out: OutputStream, dest: Dest, host: String?, kind: Kind, prefix: ByteArray?) {
        val e = dbg.log.begin(kind)
        e.host = host ?: ""; e.ip = dest.ip?.hostAddress ?: ""; e.port = dest.port; e.outcome = "tunnel"
        val up = try { connectUpstream(host, dest.ip, dest.port, false, null) } catch (ex: Exception) {
            e.outcome = "failed"; e.error = ex.message ?: ex.toString(); dbg.log.touch(); return
        }
        try {
            val head = ByteArrayOutputStream(); val tail = ByteArrayOutputStream()
            if (prefix != null) { up.out.write(prefix); up.out.flush(); e.bytesUp += prefix.size; head.write(prefix, 0, minOf(prefix.size, 64)) }
            val t = Thread({
                try {
                    val buf = ByteArray(16384)
                    while (true) { val r = up.ins.read(buf); if (r < 0) break; out.write(buf, 0, r); out.flush(); e.bytesDown += r; if (tail.size() < 64) tail.write(buf, 0, minOf(r, 64 - tail.size())) }
                } catch (_: Exception) {} finally { runCatching { c.shutdownOutput() } }
            }, "netdebug-tunnel").also { it.isDaemon = true; it.start() }
            try {
                val buf = ByteArray(16384)
                while (true) { val r = inp.read(buf); if (r < 0) break; up.out.write(buf, 0, r); up.out.flush(); e.bytesUp += r; if (head.size() < 64) head.write(buf, 0, minOf(r, 64 - head.size())) }
            } catch (_: Exception) {}
            runCatching { up.sock.shutdownOutput() }
            t.join(15_000)
            e.note = "first bytes up: " + preview(head.toByteArray()) + (if (tail.size() > 0) " | down: " + preview(tail.toByteArray()) else "")
        } finally {
            e.durationMs = System.currentTimeMillis() - e.startedAt; dbg.log.touch(); up.close()
        }
    }

    private fun preview(b: ByteArray) = b.joinToString("") { if (it in 32..126) it.toInt().toChar().toString() else "." }

    private class Upstream(val sock: Socket, val ins: InputStream, val out: OutputStream, val key: String) { fun close() { runCatching { sock.close() } } }

    private fun trustAll(): SSLSocketFactory {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>?, a: String?) {}
            override fun checkServerTrusted(c: Array<X509Certificate>?, a: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), SecureRandom()) }.socketFactory
    }

    private val lenientFactory: SSLSocketFactory by lazy { trustAll() }

    /** a connection to the real server; [ip] is where the guest was going, [host] what it is called (for TLS and as a fallback address) */
    private fun connectUpstream(host: String?, ip: InetAddress?, port: Int, tls: Boolean, sni: String?): Upstream {
        val candidates = ArrayList<InetAddress>()
        if (ip != null && !ip.isLoopbackAddress && !dbg.isFake(ip)) candidates += ip
        if (candidates.isEmpty() && host != null && !CertAuthority.isIpLiteral(host)) candidates += dbg.resolveReal(host)
        if (candidates.isEmpty()) throw IOException("no address to connect to for ${host ?: ip}")
        var last: Exception? = null
        for (a in candidates) {
            try {
                val raw = Socket()
                raw.tcpNoDelay = true
                raw.connect(InetSocketAddress(a, port), 15_000)
                raw.soTimeout = 60_000
                if (!tls) return Upstream(raw, BufferedInputStream(raw.getInputStream(), 16384), raw.getOutputStream(), "$host|$a|$port|0")
                val name = sni ?: host ?: a.hostAddress!!
                val f = if (dbg.config.ignoreUpstreamCertErrors) lenientFactory else SSLSocketFactory.getDefault() as SSLSocketFactory
                val s = f.createSocket(raw, name, port, true) as SSLSocket
                if (!dbg.config.ignoreUpstreamCertErrors) s.sslParameters = s.sslParameters.also { it.endpointIdentificationAlgorithm = "HTTPS" }
                s.startHandshake()
                return Upstream(s, BufferedInputStream(s.inputStream, 16384), s.outputStream, "$host|$a|$port|1")
            } catch (ex: Exception) { last = ex }
        }
        throw last ?: IOException("cannot connect")
    }

    // ---------------------------------------------------------------------------------------------- HTTP

    private fun keepAlive(version: String, h: Headers) = if (version == "HTTP/1.0") h.hasToken("Connection", "keep-alive") else !h.hasToken("Connection", "close")

    private fun serveHttp(scheme: String, cin: InputStream, cout: OutputStream, dest: Dest, sniHost: String?, tlsDesc: String) {
        var up: Upstream? = null
        try {
            while (true) {
                val head = Http.readRequestHead(cin) ?: break
                val e = dbg.log.begin(Kind.HTTP)
                val (done, next) = try { exchange(scheme, head, cin, cout, dest, sniHost, tlsDesc, up, e) } catch (ex: Exception) {
                    if (ex is EOFException && e.status == 0 && e.outcome.isEmpty()) { e.outcome = "failed"; e.error = "connection closed" }
                    else if (e.outcome.isEmpty() || e.outcome == "forwarded") { e.outcome = "failed"; if (e.error.isEmpty()) e.error = ex.message ?: ex.toString() }
                    e.durationMs = System.currentTimeMillis() - e.startedAt; dbg.log.touch()
                    up?.close(); up = null
                    break
                }
                up = next
                if (!done) break
            }
        } finally { up?.close() }
    }

    private fun capture(h: Headers, b: ByteArray, cap: Int): Body = Body(if (b.size > cap) b.copyOf(cap) else b, b.size.toLong(), b.size > cap)

    private fun hostOf(h: Headers, fallback: String?): String {
        val v = h["Host"]?.trim()
        if (!v.isNullOrEmpty()) return if (v.startsWith("[")) v.substringBefore(']').trimStart('[') else v.substringBefore(':').lowercase()
        return fallback ?: "unknown"
    }

    /** one request/response. Returns (keep the client connection, the upstream connection to keep for the next one) */
    private fun exchange(
        scheme: String, head: RequestHead, cin: InputStream, cout: OutputStream, dest: Dest, sniHost: String?, tlsDesc: String,
        reuse: Upstream?, e: NetEntry,
    ): Pair<Boolean, Upstream?> {
        val cap = dbg.config.maxBodyBytes
        val host = hostOf(head.headers, sniHost ?: dest.ip?.hostAddress)
        val (path, query) = Http.splitTarget(head.target)
        e.scheme = scheme; e.host = host; e.ip = dest.ip?.hostAddress ?: ""; e.port = dest.port; e.method = head.method
        e.path = if (query != null) "$path?$query" else path; e.httpVersion = head.version
        e.reqHeaders = head.headers.all.toList(); e.tls = tlsDesc

        // the request body, whole (rules look at it; a debugging proxy has no business streaming gigabytes)
        val framing = Http.requestFraming(head.headers)
        if (head.headers["Expect"]?.contains("100-continue", true) == true && framing !is Framing.None) { cout.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray()); cout.flush() }
        val reqBody = Http.readBodyFully(framing, cin, MAX_BODY) ?: run {
            e.outcome = "failed"; e.error = "request body over ${MAX_BODY / 1048576} MB"
            writeSimple(cout, 413, "Request body too large for the debugger", false)
            e.status = 413; e.durationMs = System.currentTimeMillis() - e.startedAt; dbg.log.touch(); return false to reuse
        }
        e.reqBody = capture(head.headers, reqBody, cap)
        val clientKeep = keepAlive(head.version, head.headers)

        val view = ReqView(scheme, host, dest.port, head.method, path, query, head.headers, reqBody)
        val rule = dbg.rules.match(view)
        if (rule != null) { rule.hits.incrementAndGet(); e.ruleName = rule.name }
        if (rule != null && rule.delayMs > 0) Thread.sleep(rule.delayMs)
        val act = rule?.action

        when (act) {
            is Action.Block -> { e.outcome = "blocked"; e.durationMs = System.currentTimeMillis() - e.startedAt; dbg.log.touch(); reuse?.close(); return false to null }
            is Action.Mock -> {
                var body = act.body
                var hdrs = act.headers
                if (act.template && act.bodyText != null) body = Template.expand(act.bodyText, view).toByteArray(Charsets.UTF_8)
                if (act.template) hdrs = hdrs.map { it.first to Template.expand(it.second, view) }
                val rh = ResponseHead("HTTP/1.1", act.status, act.reason ?: reasonOf(act.status), Headers())
                for ((k, v) in hdrs) rh.headers.add(k, v)
                if (!rh.headers.has("Date")) rh.headers.add("Date", java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME.format(java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)))
                if (!rh.headers.has("Server")) rh.headers.add("Server", "aemu-debugger")
                if (act.status != 204 && act.status != 304 && !(act.status in 100..199)) rh.headers.set("Content-Length", body.size.toString())
                rh.headers.set("Connection", if (clientKeep) "keep-alive" else "close")
                Http.writeResponseHead(cout, rh)
                if (!head.method.equals("HEAD", true) && act.status != 204 && act.status != 304) cout.write(body)
                cout.flush()
                e.status = rh.status; e.reason = rh.reason; e.respHeaders = rh.headers.all.toList(); e.respBody = capture(rh.headers, body, cap)
                e.outcome = "mocked"; e.durationMs = System.currentTimeMillis() - e.startedAt; dbg.log.touch()
                return clientKeep to reuse
            }
            else -> {}
        }

        // ---- forward (possibly changed)
        val out = RequestHead(head.method, head.target, head.version, head.headers.copy())
        var body = reqBody
        var upHost = host; var upPort = dest.port; var upTls = scheme == "https"; var upIp: InetAddress? = dest.ip
        var outcome = "forwarded"
        var rewrite: Action.Rewrite? = null
        if (act is Action.Redirect) {
            outcome = "redirected"
            act.host?.let { upHost = it; upIp = null }
            act.port?.let { upPort = it }
            act.tls?.let { upTls = it }
            if (act.rewriteHost) out.headers.set("Host", if ((upTls && upPort == 443) || (!upTls && upPort == 80)) upHost else "$upHost:$upPort")
        }
        if (act is Action.Rewrite) {
            outcome = "rewritten"; rewrite = act
            for ((k, v) in act.reqSet) out.headers.set(k, v)
            for (k in act.reqRemove) out.headers.remove(k)
            act.setMethod?.let { out.method = it }
            act.setPath?.let { out.target = it }
            if (act.reqBody.isNotEmpty()) {
                var t = String(Http.decodeForDisplay(out.headers, body), Charsets.UTF_8)
                for (r in act.reqBody) t = r.apply(t)
                body = t.toByteArray(Charsets.UTF_8); out.headers.remove("Content-Encoding")
            }
        }
        out.headers.remove("Proxy-Connection"); out.headers.remove("Expect")
        if (framing !is Framing.None || body.isNotEmpty()) { out.headers.remove("Transfer-Encoding"); out.headers.set("Content-Length", body.size.toString()) }

        var up = reuse
        val key = "$upHost|${upIp ?: ""}|$upPort|${if (upTls) 1 else 0}"
        if (up != null && !up.key.endsWith("|$upPort|${if (upTls) 1 else 0}")) { up.close(); up = null }
        var resp: ResponseHead? = null
        for (attempt in 0..1) {
            if (up == null) up = connectUpstream(upHost, upIp, upPort, upTls, if (CertAuthority.isIpLiteral(upHost)) null else upHost)
            try {
                Http.writeRequestHead(up.out, out); up.out.write(body); up.out.flush()
                var r = Http.readResponseHead(up.ins)
                while (r.status in 100..199 && r.status != 101) { Http.writeResponseHead(cout, r); cout.flush(); r = Http.readResponseHead(up.ins) }
                resp = r; break
            } catch (ex: Exception) {
                up.close(); val stale = attempt == 0 && reuse != null && up === reuse; up = null
                if (!stale) throw ex
            }
        }
        val rh = resp!!
        val upstream = up!!
        e.status = rh.status; e.reason = rh.reason; e.outcome = outcome; e.ip = upstream.sock.inetAddress?.hostAddress ?: e.ip

        if (rh.status == 101) {                                              // WebSocket and friends: from here on the bytes are not HTTP
            e.respHeaders = rh.headers.all.toList(); e.note = "upgraded: " + (rh.headers["Upgrade"] ?: "")
            Http.writeResponseHead(cout, rh); cout.flush()
            e.durationMs = System.currentTimeMillis() - e.startedAt; dbg.log.touch()
            pipe(cin, cout, upstream, e)
            return false to null
        }

        val rf = Http.responseFraming(head.method, rh.status, rh.headers)
        val needBody = rewrite?.needsResponseBody == true && rf !is Framing.None
        val srvKeep = keepAlive(rh.version, rh.headers) && rf !is Framing.UntilClose

        if (rewrite != null) {
            rewrite.respStatus?.let { rh.status = it; rh.reason = reasonOf(it) }
            for ((k, v) in rewrite.respSet) rh.headers.set(k, v)
            for (k in rewrite.respRemove) rh.headers.remove(k)
        }
        val keepBoth: Boolean
        if (needBody) {
            val raw = Http.readBodyFully(rf, upstream.ins, MAX_BODY) ?: throw IOException("response body over ${MAX_BODY / 1048576} MB: cannot rewrite it")
            var nb = raw
            val rw = rewrite!!
            var t = String(Http.decodeForDisplay(rh.headers, raw), Charsets.UTF_8)
            if (rw.respBodyAll != null) t = rw.respBodyAll
            for (r in rw.respBody) t = r.apply(t)
            nb = t.toByteArray(Charsets.UTF_8)
            rh.headers.remove("Content-Encoding"); rh.headers.remove("Transfer-Encoding"); rh.headers.set("Content-Length", nb.size.toString())
            Http.writeResponseHead(cout, rh); if (!head.method.equals("HEAD", true)) cout.write(nb); cout.flush()
            e.respBody = capture(rh.headers, nb, cap)
            keepBoth = clientKeep && keepAlive(rh.version, rh.headers) && rf !is Framing.UntilClose
        } else {
            if (rf is Framing.UntilClose) rh.headers.set("Connection", "close")
            Http.writeResponseHead(cout, rh); cout.flush()
            e.respHeaders = rh.headers.all.toList()
            e.respBody = Http.relayBody(rf, upstream.ins, cout, cap)
            keepBoth = clientKeep && srvKeep
        }
        e.respHeaders = rh.headers.all.toList()
        e.durationMs = System.currentTimeMillis() - e.startedAt; dbg.log.touch()
        if (!srvKeep) { upstream.close(); return keepBoth to null }
        return keepBoth to upstream
    }

    private fun pipe(cin: InputStream, cout: OutputStream, up: Upstream, e: NetEntry) {
        val t = Thread({
            try { val b = ByteArray(16384); while (true) { val r = up.ins.read(b); if (r < 0) break; cout.write(b, 0, r); cout.flush(); e.bytesDown += r } } catch (_: Exception) {}
        }, "netdebug-ws").also { it.isDaemon = true; it.start() }
        try { val b = ByteArray(16384); while (true) { val r = cin.read(b); if (r < 0) break; up.out.write(b, 0, r); up.out.flush(); e.bytesUp += r } } catch (_: Exception) {}
        up.close(); t.join(5_000)
    }

    private fun writeSimple(out: OutputStream, status: Int, text: String, keep: Boolean) {
        val b = text.toByteArray()
        out.write("HTTP/1.1 $status ${reasonOf(status)}\r\nContent-Type: text/plain\r\nContent-Length: ${b.size}\r\nConnection: ${if (keep) "keep-alive" else "close"}\r\n\r\n".toByteArray())
        out.write(b); out.flush()
    }

    private fun reasonOf(s: Int) = when (s) {
        200 -> "OK"; 201 -> "Created"; 202 -> "Accepted"; 204 -> "No Content"; 301 -> "Moved Permanently"; 302 -> "Found"; 304 -> "Not Modified"
        400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 413 -> "Payload Too Large"; 429 -> "Too Many Requests"
        500 -> "Internal Server Error"; 502 -> "Bad Gateway"; 503 -> "Service Unavailable"; else -> "Status $s"
    }

    private companion object { const val MAX_BODY = 16 * 1024 * 1024 }
}
