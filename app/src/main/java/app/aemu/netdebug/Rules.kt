/* Added for AEmulator Plus, 2026-11-15: the HTTP debugger. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.netdebug

import java.util.concurrent.atomic.AtomicInteger

/** What a rule sees of a request. */
class ReqView(
    val scheme: String, val host: String, val port: Int, val method: String,
    val path: String, val rawQuery: String?, val headers: Headers, val body: ByteArray?,
) {
    val query: List<Pair<String, String>> by lazy { Http.parseQuery(rawQuery) }
    val url: String get() = "$scheme://$host" + (if ((scheme == "https" && port == 443) || (scheme == "http" && port == 80)) "" else ":$port") + path + (if (rawQuery != null) "?$rawQuery" else "")
    val bodyText: String? by lazy { body?.let { String(it, Charsets.UTF_8) } }
    val form: List<Pair<String, String>> by lazy {
        if (headers["Content-Type"]?.contains("x-www-form-urlencoded", true) == true) Http.parseQuery(bodyText) else emptyList()
    }
}

class Replace(val find: String, val replace: String, val regex: Boolean) {
    fun apply(s: String): String = if (regex) Regex(find).replace(s, replace) else s.replace(find, replace)
}

sealed class Action {
    object Passthrough : Action()
    object Block : Action()
    class Mock(
        val status: Int, val reason: String?, val headers: List<Pair<String, String>>,
        val body: ByteArray, val template: Boolean, val bodyText: String?,
    ) : Action()
    class Redirect(val host: String?, val port: Int?, val tls: Boolean?, val rewriteHost: Boolean) : Action()
    class Rewrite(
        val reqSet: List<Pair<String, String>>, val reqRemove: List<String>, val setPath: String?, val setMethod: String?,
        val reqBody: List<Replace>, val respStatus: Int?, val respSet: List<Pair<String, String>>, val respRemove: List<String>,
        val respBody: List<Replace>, val respBodyAll: String?,
    ) : Action() {
        val needsResponseBody get() = respBody.isNotEmpty() || respBodyAll != null
        val needsRequestBody get() = reqBody.isNotEmpty()
    }
}

class Rule(
    val id: String, val name: String, var enabled: Boolean,
    val scheme: String?, val host: String?, val port: Int?, val method: String?, val path: String?,
    val query: Map<String, String>, val headers: Map<String, String>, val body: String?,
    val action: Action, val delayMs: Long, val raw: Map<String, Any?>,
) {
    val hits = AtomicInteger()

    fun matches(r: ReqView): Boolean {
        if (!enabled) return false
        if (scheme != null && !scheme.equals(r.scheme, true)) return false
        if (host != null && !Glob.match(host, r.host, true)) return false
        if (port != null && port != r.port) return false
        if (method != null && method != "*" && method.split('|').none { it.trim().equals(r.method, true) }) return false
        if (path != null && !Glob.matchOrRegex(path, r.path, false)) return false
        for ((k, g) in query) {
            val v = r.query.firstOrNull { it.first == k }?.second ?: return false
            if (!Glob.matchOrRegex(g, v, false)) return false
        }
        for ((k, g) in headers) {
            val v = r.headers[k] ?: return false
            if (!Glob.matchOrRegex(g, v, true)) return false
        }
        if (body != null) {
            val t = r.bodyText ?: return false
            val ok = if (body.startsWith("re:")) Regex(body.substring(3), RegexOption.DOT_MATCHES_ALL).containsMatchIn(t) else t.contains(body.removePrefix("contains:"))
            if (!ok) return false
        }
        return true
    }

    val needsRequestBody: Boolean get() = body != null || (action as? Action.Rewrite)?.needsRequestBody == true
}

class DnsRule(val host: String, val answer: String)

object Glob {
    fun match(glob: String, s: String, ignoreCase: Boolean): Boolean {
        val g = if (ignoreCase) glob.lowercase() else glob
        val t = if (ignoreCase) s.lowercase() else s
        return globMatch(g, 0, t, 0)
    }

    private fun globMatch(g: String, gi0: Int, s: String, si0: Int): Boolean {
        var gi = gi0; var si = si0; var star = -1; var mark = 0
        while (si < s.length) {
            if (gi < g.length && (g[gi] == '?' || g[gi] == s[si])) { gi++; si++ }
            else if (gi < g.length && g[gi] == '*') { star = gi++; mark = si }
            else if (star >= 0) { gi = star + 1; si = ++mark }
            else return false
        }
        while (gi < g.length && g[gi] == '*') gi++
        return gi == g.length
    }

    /** "re:<regex>" is a regular expression (must match the whole value), anything else a glob */
    fun matchOrRegex(p: String, s: String, ignoreCase: Boolean): Boolean =
        if (p.startsWith("re:")) Regex(p.substring(3), if (ignoreCase) setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL) else setOf(RegexOption.DOT_MATCHES_ALL)).matches(s)
        else match(p, s, ignoreCase)
}

/** The rules of one VM: parsed from JSON, evaluated in order, first match wins. */
class RuleSet(val rules: List<Rule>, val dns: List<DnsRule>, val tlsPassthrough: List<String>) {
    val needsRequestBody: Boolean = rules.any { it.enabled && it.needsRequestBody }

    fun match(r: ReqView): Rule? = rules.firstOrNull { runCatching { it.matches(r) }.getOrDefault(false) }

    fun dnsAnswer(host: String): String? = dns.firstOrNull { Glob.match(it.host, host, true) }?.answer

    fun passesTls(host: String): Boolean = tlsPassthrough.any { Glob.match(it, host, true) }

    fun toJson(): Map<String, Any?> = mapOf(
        "version" to 1,
        "tlsPassthrough" to tlsPassthrough,
        "dns" to dns.map { mapOf("host" to it.host, "answer" to it.answer) },
        "rules" to rules.map { it.raw },
    )

    companion object {
        val EMPTY = RuleSet(emptyList(), emptyList(), emptyList())

        @Suppress("UNCHECKED_CAST")
        fun parse(text: String): RuleSet = fromMap(Json.parseObject(text))

        @Suppress("UNCHECKED_CAST")
        fun fromMap(m: Map<String, Any?>): RuleSet {
            val rules = (m["rules"] as? List<Any?>).orEmpty().mapIndexed { i, x -> parseRule(x as? Map<String, Any?> ?: error("rule $i is not an object"), i) }
            val dns = (m["dns"] as? List<Any?>).orEmpty().map { x -> val d = x as Map<String, Any?>; DnsRule(str(d["host"]) ?: error("dns rule without host"), str(d["answer"]) ?: "NXDOMAIN") }
            val pass = (m["tlsPassthrough"] as? List<Any?>).orEmpty().mapNotNull { str(it) }
            return RuleSet(rules, dns, pass)
        }

        private fun str(v: Any?): String? = v?.toString()
        private fun int(v: Any?): Int? = (v as? Number)?.toInt() ?: v?.toString()?.toIntOrNull()
        @Suppress("UNCHECKED_CAST") private fun strMap(v: Any?): Map<String, String> = (v as? Map<String, Any?>).orEmpty().mapValues { it.value.toString() }
        @Suppress("UNCHECKED_CAST") private fun pairs(v: Any?): List<Pair<String, String>> = strMap(v).entries.map { it.key to it.value }
        @Suppress("UNCHECKED_CAST") private fun strList(v: Any?): List<String> = (v as? List<Any?>).orEmpty().map { it.toString() }
        @Suppress("UNCHECKED_CAST") private fun replaces(v: Any?): List<Replace> = (v as? List<Any?>).orEmpty().map {
            val r = it as Map<String, Any?>
            Replace(str(r["find"]) ?: error("replace without find"), str(r["replace"]) ?: "", r["regex"] == true)
        }

        @Suppress("UNCHECKED_CAST")
        fun parseRule(m: Map<String, Any?>, index: Int): Rule {
            val mt = (m["match"] as? Map<String, Any?>).orEmpty()
            val a = (m["action"] as? Map<String, Any?>) ?: error("rule ${index + 1} has no action")
            val action = when (val t = str(a["type"])?.lowercase()) {
                "passthrough", "allow" -> Action.Passthrough
                "block", "drop" -> Action.Block
                "mock" -> {
                    val txt = str(a["body"])
                    val bytes = when {
                        a["bodyBase64"] != null -> java.util.Base64.getMimeDecoder().decode(str(a["bodyBase64"]))
                        a["bodyFile"] != null -> java.io.File(str(a["bodyFile"])!!).readBytes()
                        else -> (txt ?: "").toByteArray(Charsets.UTF_8)
                    }
                    Action.Mock(int(a["status"]) ?: 200, str(a["reason"]), pairs(a["headers"]), bytes, a["template"] == true, if (a["bodyBase64"] == null && a["bodyFile"] == null) txt ?: "" else null)
                }
                "redirect", "reroute" -> Action.Redirect(str(a["toHost"]), int(a["toPort"]), a["tls"] as? Boolean, a["rewriteHost"] != false)
                "rewrite" -> {
                    val rq = (a["request"] as? Map<String, Any?>).orEmpty(); val rs = (a["response"] as? Map<String, Any?>).orEmpty()
                    Action.Rewrite(pairs(rq["setHeaders"]), strList(rq["removeHeaders"]), str(rq["setPath"]), str(rq["setMethod"]), replaces(rq["bodyReplace"]),
                        int(rs["status"]), pairs(rs["setHeaders"]), strList(rs["removeHeaders"]), replaces(rs["bodyReplace"]), str(rs["body"]))
                }
                else -> error("rule ${index + 1}: unknown action '$t' (mock, block, redirect, rewrite, passthrough)")
            }
            val id = str(m["id"]) ?: "r${index + 1}"
            return Rule(id, str(m["name"]) ?: id, m["enabled"] != false,
                str(mt["scheme"])?.takeIf { it != "any" && it != "*" }, str(mt["host"]), int(mt["port"]), str(mt["method"]), str(mt["path"]),
                strMap(mt["query"]), strMap(mt["headers"]), str(mt["body"]), action, (a["delayMs"] as? Number)?.toLong() ?: 0L, m)
        }
    }
}

/** ${...} in a mocked response: what the request said, the time, random values. */
object Template {
    private val rnd = java.security.SecureRandom()
    fun expand(s: String, r: ReqView): String = Regex("\\$\\{([^}]+)}").replace(s) { m ->
        val k = m.groupValues[1]
        when {
            k == "host" -> r.host
            k == "path" -> r.path
            k == "method" -> r.method
            k == "url" -> r.url
            k == "now.unix" -> (System.currentTimeMillis() / 1000).toString()
            k == "now.iso" -> java.time.Instant.now().toString()
            k == "uuid" -> java.util.UUID.randomUUID().toString()
            k.startsWith("random.hex:") -> ByteArray((k.substringAfter(':').toIntOrNull() ?: 8).coerceIn(1, 256) / 2 + 1).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }.take(k.substringAfter(':').toIntOrNull()?.coerceIn(1, 256) ?: 8)
            k.startsWith("query.") -> r.query.firstOrNull { it.first == k.substring(6) }?.second ?: ""
            k.startsWith("form.") -> r.form.firstOrNull { it.first == k.substring(5) }?.second ?: ""
            k.startsWith("header.") -> r.headers[k.substring(7)] ?: ""
            else -> m.value
        }
    }
}

/** Rule sets ready to be added from the debugger's screen. They are starting points: look at what the image asks for first. */
object Presets {
    class Preset(val id: String, val title: String, val json: String)

    val all = listOf(
        Preset("connectivity", "Connectivity checks answer 204",
            """{"rules":[{"id":"connectivity","name":"Connectivity checks","match":{"scheme":"any","host":"*","path":"*generate_204"},"action":{"type":"mock","status":204}},
               {"id":"gen204","name":"google gen_204","match":{"host":"*.google.com","path":"/gen_204"},"action":{"type":"mock","status":204}}]}"""),
        Preset("clientlogin", "Google ClientLogin (Android 1.x - 2.x sign-in) always succeeds",
            """{"rules":[{"id":"clientlogin","name":"ClientLogin accepts any account","match":{"host":"www.google.com","path":"/accounts/ClientLogin","method":"POST"},
               "action":{"type":"mock","status":200,"template":true,"headers":{"Content-Type":"text/plain"},
               "body":"SID=${'$'}{random.hex:40}\nLSID=${'$'}{random.hex:40}\nAuth=${'$'}{random.hex:60}\n"}}]}"""),
    )
}
