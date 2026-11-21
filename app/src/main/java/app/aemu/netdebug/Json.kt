/* Added for AEmulator Plus, 2026-11-15: the HTTP debugger. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.netdebug

/**
 * A small JSON reader/writer with no Android dependency (org.json is not in a plain JVM, and the debugger's core is
 * tested there). Values are Map<String, Any?>, List<Any?>, String, Double/Long, Boolean or null.
 */
object Json {
    class ParseError(msg: String) : Exception(msg)

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value()
        p.ws()
        if (p.i != text.length) throw ParseError("trailing data at ${p.i}")
        return v
    }

    @Suppress("UNCHECKED_CAST")
    fun parseObject(text: String): Map<String, Any?> = parse(text) as? Map<String, Any?> ?: throw ParseError("not an object")

    fun stringify(v: Any?, pretty: Boolean = false): String = StringBuilder().also { write(it, v, pretty, 0) }.toString()

    private fun write(sb: StringBuilder, v: Any?, pretty: Boolean, depth: Int) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean -> sb.append(v)
            is Int, is Long, is Short, is Byte -> sb.append(v.toString())
            is Float, is Double -> {
                val d = (v as Number).toDouble()
                if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append(d.toLong()) else sb.append(d)
            }
            is Map<*, *> -> {
                if (v.isEmpty()) { sb.append("{}"); return }
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(',')
                    first = false
                    if (pretty) { sb.append('\n'); indent(sb, depth + 1) }
                    quote(sb, k.toString()); sb.append(if (pretty) ": " else ":")
                    write(sb, x, pretty, depth + 1)
                }
                if (pretty) { sb.append('\n'); indent(sb, depth) }
                sb.append('}')
            }
            is Iterable<*> -> {
                val l = v.toList()
                if (l.isEmpty()) { sb.append("[]"); return }
                sb.append('[')
                var first = true
                for (x in l) {
                    if (!first) sb.append(',')
                    first = false
                    if (pretty) { sb.append('\n'); indent(sb, depth + 1) }
                    write(sb, x, pretty, depth + 1)
                }
                if (pretty) { sb.append('\n'); indent(sb, depth) }
                sb.append(']')
            }
            else -> quote(sb, v.toString())
        }
    }

    private fun indent(sb: StringBuilder, n: Int) { repeat(n) { sb.append("  ") } }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' || c == '\u2028' || c == '\u2029' -> sb.append("\\u%04x".format(c.code))
            else -> sb.append(c)
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            if (i >= s.length) throw ParseError("unexpected end")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw ParseError("unexpected '$c' at $i")
            }
        }
        fun lit(w: String, v: Any?): Any? {
            if (!s.startsWith(w, i)) throw ParseError("bad literal at $i")
            i += w.length; return v
        }
        fun num(): Any {
            val st = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val t = s.substring(st, i)
            return t.toLongOrNull() ?: t.toDoubleOrNull() ?: throw ParseError("bad number '$t'")
        }
        fun str(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw ParseError("unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) throw ParseError("bad escape")
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                            '/' -> sb.append('/'); '\\' -> sb.append('\\'); '"' -> sb.append('"')
                            'u' -> {
                                if (i + 4 > s.length) throw ParseError("bad \\u escape")
                                sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4
                            }
                            else -> throw ParseError("bad escape \\$e")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }
        fun arr(): List<Any?> {
            i++
            val l = ArrayList<Any?>()
            ws()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (true) {
                ws(); l.add(value()); ws()
                if (i >= s.length) throw ParseError("unterminated array")
                when (s[i++]) { ',' -> continue; ']' -> return l; else -> throw ParseError("expected , or ] at ${i - 1}") }
            }
        }
        fun obj(): Map<String, Any?> {
            i++
            val m = LinkedHashMap<String, Any?>()
            ws()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') throw ParseError("expected key at $i")
                val k = str(); ws()
                if (i >= s.length || s[i] != ':') throw ParseError("expected : at $i")
                i++; ws()
                m[k] = value(); ws()
                if (i >= s.length) throw ParseError("unterminated object")
                when (s[i++]) { ',' -> continue; '}' -> return m; else -> throw ParseError("expected , or } at ${i - 1}") }
            }
        }
    }
}
