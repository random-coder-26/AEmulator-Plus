/* Added for AEmulator Plus, 2026-11-15: the HTTP debugger. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.netdebug

import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

class NetDebugConfig(
    /** terminate the guest's HTTPS here (needs the CA in the guest's trust store); off: HTTPS is relayed and only logged as a connection */
    var mitm: Boolean = true,
    /** accept an upstream server's bad certificate (a debugging aid for test servers); off by default */
    var ignoreUpstreamCertErrors: Boolean = false,
    /** how much of each body the log keeps */
    var maxBodyBytes: Int = 256 * 1024,
    /** ports (besides 80 and 443) the qemu engine's name-based redirect also listens on */
    var extraPorts: List<Int> = listOf(5222, 5223, 5228, 8080, 8443),
) {
    fun copyOf() = NetDebugConfig(mitm, ignoreUpstreamCertErrors, maxBodyBytes, extraPorts)

    fun toJson(): Map<String, Any?> = mapOf("mitm" to mitm, "ignoreUpstreamCertErrors" to ignoreUpstreamCertErrors, "maxBodyBytes" to maxBodyBytes, "extraPorts" to extraPorts)

    companion object {
        fun fromJson(m: Map<String, Any?>): NetDebugConfig {
            val d = NetDebugConfig()
            return NetDebugConfig(
                m["mitm"] as? Boolean ?: d.mitm, m["ignoreUpstreamCertErrors"] as? Boolean ?: d.ignoreUpstreamCertErrors,
                (m["maxBodyBytes"] as? Number)?.toInt()?.coerceIn(0, 8 * 1024 * 1024) ?: d.maxBodyBytes,
                (m["extraPorts"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() }?.filter { it in 1024..65535 } ?: d.extraPorts,
            )
        }
    }
}

/** The ports the guest side is pointed at. 0 = not listening. */
class NetDebugPorts(val header: Int, val http80: Int, val https443: Int, val dnsUdp: Int)

/**
 * The HTTP debugger of one VM: a proxy the guest's connections are sent to, the CA that lets it read HTTPS, rules that
 * answer or change requests, and the log. No Android classes in here: [dir] keeps this VM's rules and settings, [caDir] the CA
 * (one for the whole app, so a guest can keep trusting it).
 */
class NetDebugger(private val dir: File, private val caDir: File, private val say: (String) -> Unit = {}) {
    val log = NetLog()
    @Volatile var config = NetDebugConfig()
        private set
    @Volatile var rules: RuleSet = RuleSet.EMPTY
        private set
    val ca: CertAuthority by lazy { CertAuthority.loadOrCreate(caDir) }

    private var proxy: ProxyServer? = null
    private var dnsServer: DnsResponder? = null
    @Volatile var fakeIps = false
        private set
    val running: Boolean get() = proxy != null

    // ---- what DNS taught us: ip -> name (for TLS without SNI), and the loopback stand-ins of the qemu engine
    private val ipToHost = ConcurrentHashMap<String, String>()
    private val fakeToHost = ConcurrentHashMap<String, String>()
    private val hostToFake = ConcurrentHashMap<String, String>()
    private val nextFake = java.util.concurrent.atomic.AtomicInteger(1)

    init { load() }

    // ---------------------------------------------------------------------------------------------- settings

    private fun file(n: String) = File(dir, n)

    fun load() {
        runCatching { file("config.json").takeIf { it.isFile }?.let { config = NetDebugConfig.fromJson(Json.parseObject(it.readText())) } }.onFailure { say("netdebug: config.json: $it") }
        runCatching { file("rules.json").takeIf { it.isFile }?.let { rules = RuleSet.parse(it.readText()) } }.onFailure { say("netdebug: rules.json: $it") }
    }

    fun saveConfig(c: NetDebugConfig) {
        config = c
        dir.mkdirs(); file("config.json").writeText(Json.stringify(c.toJson(), true))
    }

    /** Replaces the rules by [text] (the JSON of [RuleSet.toJson]); throws with a readable message if it does not parse. */
    fun setRulesText(text: String) {
        val rs = try { RuleSet.parse(text) } catch (e: Exception) { throw IllegalArgumentException(e.message ?: e.toString(), e) }
        rules = rs
        dir.mkdirs(); file("rules.json").writeText(text)
    }

    fun rulesText(): String = file("rules.json").takeIf { it.isFile }?.readText() ?: Json.stringify(RuleSet.EMPTY.toJson(), true)

    /** adds the rules of a preset (or any rule JSON) to the current ones */
    fun addRules(json: String) {
        val add = RuleSet.parse(json)
        val cur = RuleSet.parse(rulesText())
        val ids = cur.rules.map { it.id }.toSet()
        val merged = Json.parseObject(rulesText()).toMutableMap()
        @Suppress("UNCHECKED_CAST")
        val l = ((merged["rules"] as? List<Any?>).orEmpty() + add.rules.filter { it.id !in ids }.map { it.raw }).toList()
        merged["rules"] = l
        @Suppress("UNCHECKED_CAST")
        val dns = ((merged["dns"] as? List<Any?>).orEmpty() + add.dns.map { mapOf("host" to it.host, "answer" to it.answer) })
        if (dns.isNotEmpty()) merged["dns"] = dns
        setRulesText(Json.stringify(merged, true))
    }

    // ---------------------------------------------------------------------------------------------- run

    /** [qemuNames]: the qemu engine only redirects ports 80/443/53 and drops the address, so names are answered with loopback stand-ins */
    @Synchronized
    fun start(qemuNames: Boolean, dnsUdp: Boolean): NetDebugPorts {
        stop()
        fakeIps = qemuNames
        val p = ProxyServer(this, qemuNames)
        p.start()
        proxy = p
        val dns = if (dnsUdp) DnsResponder(this).also { it.start() } else null
        dnsServer = dns
        say("netdebug: listening (header ${p.headerPort}, http ${p.http80Port}, tls ${p.tls443Port}, dns ${dns?.port ?: 0}, names ${if (qemuNames) "stand-in" else "real"})")
        return NetDebugPorts(p.headerPort, p.http80Port, p.tls443Port, dns?.port ?: 0)
    }

    @Synchronized
    fun stop() {
        proxy?.stop(); proxy = null
        dnsServer?.stop(); dnsServer = null
        ipToHost.clear(); fakeToHost.clear(); hostToFake.clear()
    }

    // ---------------------------------------------------------------------------------------------- names

    fun hostFor(ip: String): String? = ipToHost[ip] ?: fakeToHost[ip]
    fun isFake(ip: InetAddress): Boolean = ip.address.let { it.size == 4 && it[0].toInt() == 127 && it[1].toInt() == 77 }

    /** the real addresses of [host], by the phone's resolver and nothing else */
    fun resolveReal(host: String): List<InetAddress> =
        InetAddress.getAllByName(host).toList().let { l -> l.filterIsInstance<Inet4Address>().ifEmpty { l } }

    /**
     * The guest asked for [host]. Returns what to answer: the address a DNS rule names, the stand-in (qemu engine), or the
     * real ones; null = the name does not exist. [log] false for an answer the caller logs itself.
     */
    fun answer(host: String, record: Boolean = true): List<InetAddress>? {
        val e = if (record) log.begin(Kind.DNS) else null
        e?.host = host
        val ruled = rules.dnsAnswer(host)
        val addrs: List<InetAddress>? = try {
            when {
                ruled == null -> resolveReal(host)
                ruled.equals("NXDOMAIN", true) -> null
                else -> listOf(InetAddress.getByName(ruled))
            }
        } catch (ex: Exception) { e?.error = ex.toString(); null }
        if (e != null) {
            e.outcome = if (ruled != null) "rule" else "resolved"
            e.note = addrs?.joinToString { it.hostAddress ?: "?" } ?: "NXDOMAIN"
            e.durationMs = System.currentTimeMillis() - e.startedAt
            log.touch()
        }
        if (addrs == null) return null
        for (a in addrs) a.hostAddress?.let { ipToHost[it] = host }
        if (!fakeIps) return addrs
        val fake = hostToFake.getOrPut(host.lowercase()) {
            val n = nextFake.getAndUpdate { if (it >= 65000) 1 else it + 1 }
            "127.77.${n shr 8}.${n and 0xff}".also { fakeToHost[it] = host.lowercase() }
        }
        return listOf(InetAddress.getByName(fake))
    }
}
