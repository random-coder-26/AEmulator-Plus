/* Added for AEmulator Plus, 2026-11-15: the HTTP debugger. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.netdebug

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress

/**
 * A DNS server for guests that resolve names themselves with UDP (Android before 4.0 has no netd to ask; newer firmware
 * only when its resolver is not the system's). Answers A queries through [NetDebugger.answer] (so DNS rules apply and names
 * are remembered), says "no such record" to everything else: no IPv6 inside the guest, so nothing goes round the proxy.
 */
class DnsResponder(private val dbg: NetDebugger) {
    private var sock: DatagramSocket? = null
    private var thread: Thread? = null
    val port: Int get() = sock?.localPort ?: 0

    fun start() {
        val s = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        sock = s
        thread = Thread({ loop(s) }, "netdebug-dns").also { it.isDaemon = true; it.start() }
    }

    fun stop() { runCatching { sock?.close() }; sock = null }

    private fun loop(s: DatagramSocket) {
        val buf = ByteArray(1500)
        while (!s.isClosed) {
            val p = DatagramPacket(buf, buf.size)
            try { s.receive(p) } catch (e: Exception) { return }
            val q = buf.copyOf(p.length)
            val from = p.socketAddress
            Thread({ runCatching { reply(q)?.let { r -> s.send(DatagramPacket(r, r.size, from)) } } }, "netdebug-dns-q").also { it.isDaemon = true }.start()
        }
    }

    internal fun reply(q: ByteArray): ByteArray? {
        if (q.size < 12 || (q[2].toInt() and 0x80) != 0) return null              // too short, or itself a response
        val qd = Tls.u16(q, 4)
        if (qd != 1) return null
        var p = 12
        val labels = ArrayList<String>()
        while (p < q.size && q[p].toInt() != 0) { val l = q[p].toInt() and 0xff; if (l > 63 || p + 1 + l > q.size) return null; labels += String(q, p + 1, l, Charsets.US_ASCII); p += 1 + l }
        p += 1
        if (p + 4 > q.size) return null
        val qtype = Tls.u16(q, p); val qEnd = p + 4
        val name = labels.joinToString(".")
        val question = q.copyOfRange(12, qEnd)
        var rcode = 0
        var answers = emptyList<Inet4Address>()
        if (qtype == 1 || qtype == 255) {
            val a = dbg.answer(name)
            if (a == null) rcode = 3 else answers = a.filterIsInstance<Inet4Address>()
        }
        val out = java.io.ByteArrayOutputStream()
        out.write(q, 0, 2)
        out.write(0x81); out.write(0x80 or rcode)                                  // response, recursion desired + available
        out.write(0); out.write(1); out.write(0); out.write(answers.size); out.write(0); out.write(0); out.write(0); out.write(0)
        out.write(question)
        for (a in answers) {
            out.write(0xC0); out.write(0x0C); out.write(0); out.write(1); out.write(0); out.write(1)
            out.write(0); out.write(0); out.write(0); out.write(30); out.write(0); out.write(4); out.write(a.address)
        }
        return out.toByteArray()
    }
}
