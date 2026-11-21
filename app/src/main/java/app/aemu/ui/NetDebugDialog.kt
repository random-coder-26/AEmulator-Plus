/* Added for AEmulator Plus, 2026-11-15: the HTTP debugger's screen, opened from the running machine's menu. GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.aemu.netdebug.Kind
import app.aemu.netdebug.NetDebugger
import app.aemu.netdebug.NetEntry
import app.aemu.netdebug.Presets
import kotlinx.coroutines.delay
import java.io.File

/** Log of the guest's requests, the rules that answer or change them, and the CA. Stays open while the guest runs. */
@Composable
fun NetDebugDialog(dbg: NetDebugger, vmId: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var tab by remember { mutableStateOf(0) }
    var version by remember { mutableStateOf(0L) }
    LaunchedEffect(dbg) { while (true) { version = dbg.log.version.get(); delay(500) } }

    fun toast(t: String) = Toast.makeText(ctx, t, Toast.LENGTH_LONG).show()
    fun outDir(): File = (ctx.getExternalFilesDir(null) ?: ctx.filesDir).also { it.mkdirs() }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("HTTP debugger", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("Log", "Rules", "Setup").forEachIndexed { i, t ->
                        TextButton(onClick = { tab = i }) { Text(if (tab == i) "● $t" else t) }
                    }
                }
                when (tab) {
                    0 -> LogTab(dbg, version, vmId, ::toast, ::outDir)
                    1 -> RulesTab(dbg, ::toast)
                    else -> SetupTab(dbg, ::toast, ::outDir)
                }
            }
        }
    }
}

@Composable
private fun LogTab(dbg: NetDebugger, version: Long, vmId: String, toast: (String) -> Unit, outDir: () -> File) {
    val ctx = LocalContext.current
    var filter by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<NetEntry?>(null) }
    var showDns by remember { mutableStateOf(false) }
    val entries = remember(version, filter, showDns) {
        dbg.log.snapshot().asReversed().filter { (showDns || it.kind != Kind.DNS) && (filter.isEmpty() || it.summary().contains(filter, true)) }.take(400)
    }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(filter, { filter = it }, label = { Text("Filter") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("DNS", fontSize = 12.sp); Switch(showDns, { showDns = it })
            TextButton(onClick = { dbg.log.clear() }) { Text("Clear") }
            TextButton(onClick = {
                runCatching {
                    val f = File(outDir(), "aemu-$vmId-${System.currentTimeMillis() / 1000}.har")
                    f.writeText(dbg.log.toHar()); toast("Saved ${f.absolutePath}")
                }.onFailure { toast("Cannot save: $it") }
            }) { Text("Save HAR") }
        }
        if (!dbg.running) Text("The debugger is not running: switch it on in the machine's settings and start the machine again.", color = MaterialTheme.colorScheme.error)
        LazyColumn(Modifier.fillMaxSize()) {
            items(entries, key = { it.id }) { e ->
                Column(Modifier.fillMaxWidth().clickable { open = e }.padding(vertical = 5.dp)) {
                    Text(e.summary(), fontSize = 12.sp, fontFamily = FontFamily.Monospace, maxLines = 3,
                        color = if (e.outcome == "failed" || e.status >= 500) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    if (e.error.isNotEmpty()) Text(e.error, fontSize = 11.sp, color = MaterialTheme.colorScheme.error, maxLines = 3)
                }
            }
        }
    }
    open?.let { e ->
        AlertDialog(
            onDismissRequest = { open = null },
            title = { Text(e.summary(), fontSize = 14.sp) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) { Text(detail(e), fontSize = 11.sp, fontFamily = FontFamily.Monospace) } },
            confirmButton = {
                Row {
                    if (e.kind == Kind.HTTP) TextButton(onClick = {
                        runCatching { dbg.addRules(mockOf(e)); toast("Rule added: answers this request as it was answered (Rules tab)") }.onFailure { toast("$it") }
                        open = null
                    }) { Text("Mock this") }
                    if (e.kind == Kind.HTTP) TextButton(onClick = {
                        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("curl", e.curl())); toast("curl command copied")
                    }) { Text("Copy curl") }
                    TextButton(onClick = { open = null }) { Text("Close") }
                }
            },
        )
    }
}

private fun body(h: List<Pair<String, String>>, b: app.aemu.netdebug.Body): String {
    if (b.total == 0L) return "(empty)"
    val shown = app.aemu.netdebug.Http.decodeForDisplay(app.aemu.netdebug.Headers(h.toMutableList()), b.bytes)
    val t = String(shown, Charsets.UTF_8)
    val binary = t.count { it == '\uFFFD' || (it < ' ' && it != '\n' && it != '\r' && it != '\t') } > t.length / 20
    return (if (binary) "(binary, ${b.total} bytes)\n" + shown.take(96).joinToString("") { "%02x".format(it) } else t) + if (b.truncated) "\n… (${b.total} bytes in all)" else ""
}

private fun detail(e: NetEntry): String = buildString {
    append("outcome: ${e.outcome}"); if (e.ruleName.isNotEmpty()) append("  rule: ${e.ruleName}"); append('\n')
    if (e.tls.isNotEmpty()) append("tls: ${e.tls}\n")
    if (e.ip.isNotEmpty()) append("server: ${e.ip}:${e.port}\n")
    if (e.error.isNotEmpty()) append("error: ${e.error}\n")
    if (e.note.isNotEmpty()) append("${e.note}\n")
    if (e.kind == Kind.HTTP) {
        append("\n${e.method} ${e.path} ${e.httpVersion}\n")
        for ((k, v) in e.reqHeaders) append("$k: $v\n")
        append("\n").append(body(e.reqHeaders, e.reqBody)).append("\n\n")
        append("${e.httpVersion} ${e.status} ${e.reason}\n")
        for ((k, v) in e.respHeaders) append("$k: $v\n")
        append("\n").append(body(e.respHeaders, e.respBody))
    }
}

/** a rule that answers this request with what it got */
private fun mockOf(e: NetEntry): String {
    val path = e.path.substringBefore('?')
    val ct = e.respHeaders.firstOrNull { it.first.equals("Content-Type", true) }?.second
    val text = String(app.aemu.netdebug.Http.decodeForDisplay(app.aemu.netdebug.Headers(e.respHeaders.toMutableList()), e.respBody.bytes), Charsets.UTF_8)
    val rule: Map<String, Any?> = mapOf(
        "id" to "mock-${e.id}", "name" to "Mock ${e.method} ${e.host}$path",
        "match" to mapOf<String, Any?>("scheme" to e.scheme, "host" to e.host, "path" to path, "method" to e.method),
        "action" to mapOf<String, Any?>("type" to "mock", "status" to (if (e.status > 0) e.status else 200), "headers" to (if (ct != null) mapOf("Content-Type" to ct) else emptyMap<String, String>()), "body" to text),
    )
    return app.aemu.netdebug.Json.stringify(mapOf("rules" to listOf(rule)))
}

@Composable
private fun RulesTab(dbg: NetDebugger, toast: (String) -> Unit) {
    var text by remember { mutableStateOf(dbg.rulesText()) }
    var err by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("Rules are JSON: {\"rules\":[{\"match\":{host,path,method,query,headers,body},\"action\":{type: mock|block|redirect|rewrite|passthrough …}}], \"dns\":[{host,answer}], \"tlsPassthrough\":[hosts]}. First match wins. Saved rules apply to the next request.", fontSize = 12.sp)
        Row {
            TextButton(onClick = {
                runCatching { dbg.setRulesText(text) }.onSuccess { err = ""; toast("Saved") }.onFailure { err = it.message ?: it.toString() }
            }) { Text("Save") }
            for (p in Presets.all) TextButton(onClick = {
                runCatching { dbg.addRules(p.json); text = dbg.rulesText(); err = "" }.onFailure { err = it.message ?: it.toString() }
            }) { Text("+ " + p.title.substringBefore(" (").take(26), fontSize = 12.sp) }
        }
        if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
        OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 14, textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp))
    }
}

@Composable
private fun SetupTab(dbg: NetDebugger, toast: (String) -> Unit, outDir: () -> File) {
    var cfg by remember { mutableStateOf(dbg.config) }
    fun save(c: app.aemu.netdebug.NetDebugConfig) { cfg = c; runCatching { dbg.saveConfig(c) } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Read HTTPS (man in the middle)")
                Text("On: the debugger ends the guest's TLS with its own certificate, so requests can be read, answered and changed. Off: HTTPS is relayed untouched and only logged as a connection. Applies to new connections.", fontSize = 12.sp)
            }
            Switch(cfg.mitm, { v -> save(cfg.copyOf().also { it.mitm = v }) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Accept bad server certificates")
                Text("For test servers with self-signed certificates. Off: a server the phone does not trust gets a 502.", fontSize = 12.sp)
            }
            Switch(cfg.ignoreUpstreamCertErrors, { v -> save(cfg.copyOf().also { it.ignoreUpstreamCertErrors = v }) })
        }
        TextButton(onClick = {
            runCatching {
                val f = File(outDir(), "aemu-http-debugger-ca.crt"); f.writeText(dbg.ca.caPem); toast("Saved ${f.absolutePath}")
            }.onFailure { toast("Cannot save: $it") }
        }) { Text("Save the CA certificate") }
        Text("The CA is put into the guest's system trust store at every start (system/etc/security/cacerts, cacerts.bks on Android 1.x–3.x). An app that pins its server's certificate, or ships its own trust list, still refuses it: add its host to tlsPassthrough in the rules, or watch the log for \"TLS handshake with the guest failed\".", fontSize = 12.sp)
        Text("Native engine: every outgoing TCP connection of the guest goes through the debugger, any port. qemu engine: ports 80, 443 and the extra ports in config.json; names resolve to loopback stand-ins while the debugger is on.", fontSize = 12.sp)
    }
}
