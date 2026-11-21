package app.aemu.ui

import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.aemu.R
import app.aemu.core.GuestVm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class LaunchPkg(val name: String, val apk: String?)
private data class LaunchActivity(val name: String, val label: String?, val exported: Boolean?)

private sealed interface Loaded<out T> {
    data object Loading : Loaded<Nothing>
    data class Ok<T>(val items: List<T>) : Loaded<T>
}

private val PKG_NAME = Regex("[A-Za-z0-9_.]+")

/** `pm list packages -f`: "package:<apk path>=<name>" (or "package:<name>" where -f is not understood). */
private fun parsePmList(out: String): List<LaunchPkg> = out.lineSequence().map { it.trim() }.filter { it.startsWith("package:") }.mapNotNull { line ->
    val body = line.removePrefix("package:")
    val eq = body.lastIndexOf('=')
    val name = (if (eq >= 0) body.substring(eq + 1) else body).trim()
    if (!PKG_NAME.matches(name)) null else LaunchPkg(name, if (eq > 0) body.substring(0, eq) else null)
}.distinctBy { it.name }.sortedBy { it.name.lowercase() }.toList()

/**
 * Added for AEmulator Plus (2026-11-18): the guest's root is a plain folder on the phone, so when `pm` gives nothing (it is slow
 * under qemu, or the guest is not answering) the packages are read from the APK files themselves: system/app, data/app and the like.
 */
@Suppress("DEPRECATION")
private fun packagesFromApks(ctx: android.content.Context, vm: GuestVm): List<LaunchPkg> {
    val pm = ctx.packageManager
    val found = LinkedHashMap<String, LaunchPkg>()
    for (dir in listOf("system/app", "system/priv-app", "vendor/app", "data/app", "data/app-private")) {
        val base = File(vm.paths.root, dir).takeIf { it.isDirectory } ?: continue
        base.walkTopDown().maxDepth(4).filter { it.isFile && it.name.endsWith(".apk", ignoreCase = true) }.forEach { f ->
            val name = runCatching { pm.getPackageArchiveInfo(f.path, 0)?.packageName }.getOrNull() ?: return@forEach
            if (PKG_NAME.matches(name)) found.putIfAbsent(name, LaunchPkg(name, "/" + f.relativeTo(vm.paths.root).path))
        }
    }
    return found.values.sortedBy { it.name.lowercase() }
}

/** The package list and, when it came up empty, what the guest said (shown under "no packages" so a failure is not silent). */
private class PackageResult(val items: List<LaunchPkg>, val note: String?)

private fun listPackages(ctx: android.content.Context, vm: GuestVm): PackageResult {
    var raw = ""
    for (cmd in listOf("pm list packages -f", "pm list packages")) {
        raw = runCatching { vm.guestShell(cmd, 60_000) }.getOrElse { it.toString() }
        val parsed = parsePmList(raw)
        if (parsed.isNotEmpty()) return PackageResult(parsed, null)
    }
    val fromFiles = runCatching { packagesFromApks(ctx, vm) }.getOrDefault(emptyList())
    return PackageResult(fromFiles, raw.trim().lineSequence().take(4).joinToString("\n").take(300).ifEmpty { null })
}

/**
 * Every activity the APK declares, read on the host from the APK itself (the guest's root is a plain folder on the phone),
 * which also finds activities without an intent filter. Null when the file cannot be read.
 */
@Suppress("DEPRECATION")
private fun activitiesFromApk(ctx: android.content.Context, apk: File): List<LaunchActivity>? = runCatching {
    val pm = ctx.packageManager
    val info = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_ACTIVITIES) ?: return@runCatching null
    info.applicationInfo?.let { it.sourceDir = apk.path; it.publicSourceDir = apk.path }
    info.activities?.map { a ->
        LaunchActivity(a.name, runCatching { a.loadLabel(pm).toString() }.getOrNull()?.takeIf { it.isNotBlank() && it != a.name }, a.exported)
    }?.sortedBy { it.name.lowercase() }
}.getOrNull()

/** The fallback: the activities that appear in the guest's own "Activity Resolver Table" for the package (those with an intent filter). */
private fun activitiesFromDumpsys(vm: GuestVm, pkg: String): List<LaunchActivity> {
    val out = vm.guestShell("dumpsys package $pkg", 45_000)
    val start = out.indexOf("Activity Resolver Table:")
    if (start < 0) return emptyList()
    val end = listOf("Receiver Resolver Table:", "Service Resolver Table:", "Provider Resolver Table:", "Permissions:", "Registered ContentProviders:")
        .map { out.indexOf(it, start + 1) }.filter { it > 0 }.minOrNull() ?: out.length
    return Regex("(?<![\\w.])" + Regex.escape(pkg) + "/([\\w.$]+)").findAll(out.substring(start, end))
        .map { it.groupValues[1] }.distinct().map { LaunchActivity(it, null, null) }.toList()
}

private fun loadActivities(ctx: android.content.Context, vm: GuestVm, pkg: LaunchPkg): List<LaunchActivity> {
    val file = pkg.apk?.let { File(vm.paths.root, it.removePrefix("/")) }?.takeIf { it.isFile }
    val fromApk = file?.let { activitiesFromApk(ctx, it) }
    return if (!fromApk.isNullOrEmpty()) fromApk else activitiesFromDumpsys(vm, pkg.name)
}

/** Menu entry "Activity launcher": all packages of the guest, then the activities of the one tapped, and a tap on an activity starts it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ActivityLauncherDialog(vm: GuestVm, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var packages by remember { mutableStateOf<Loaded<LaunchPkg>>(Loaded.Loading) }
    var packagesNote by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<LaunchPkg?>(null) }
    var activities by remember { mutableStateOf<Loaded<LaunchActivity>>(Loaded.Loading) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val r = withContext(Dispatchers.IO) { runCatching { listPackages(ctx, vm) }.getOrElse { PackageResult(emptyList(), it.toString()) } }
        packagesNote = r.note
        packages = Loaded.Ok(r.items)
    }
    LaunchedEffect(selected) {
        val pkg = selected ?: return@LaunchedEffect
        activities = Loaded.Loading
        activities = Loaded.Ok(withContext(Dispatchers.IO) { runCatching { loadActivities(ctx, vm, pkg) }.getOrDefault(emptyList()) })
    }

    fun start(pkg: LaunchPkg, a: LaunchActivity) {
        Toast.makeText(ctx, ctx.getString(R.string.al_starting, a.name.substringAfterLast('.')), Toast.LENGTH_SHORT).show()
        scope.launch {
            val out = withContext(Dispatchers.IO) { runCatching { vm.guestShell("am start -n '${pkg.name}/${a.name}'", 60_000) }.getOrElse { it.toString() } }
            val problem = out.lineSequence().map { it.trim() }.firstOrNull { it.startsWith("Error") || "Exception" in it || "Permission Denial" in it }
            if (problem != null) Toast.makeText(ctx, problem.take(200), Toast.LENGTH_LONG).show()
        }
    }

    fun back() { if (selected != null) { selected = null; query = "" } else onDismiss() }

    Dialog(onDismissRequest = ::back, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.al_title), style = MaterialTheme.typography.titleMedium)
                        selected?.let { Text(it.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, stringResource(R.string.close)) }
                }
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), singleLine = true,
                    placeholder = { Text(stringResource(if (selected == null) R.string.al_search_packages else R.string.al_search_activities)) })
                HorizontalDivider()
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val needle = query.trim()
                    val pkg = selected
                    if (pkg == null) when (val p = packages) {
                        Loaded.Loading -> Centered { CircularProgressIndicator() }
                        is Loaded.Ok -> {
                            val shown = p.items.filter { needle.isEmpty() || it.name.contains(needle, ignoreCase = true) }
                            if (shown.isEmpty()) Centered {
                                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(16.dp)) {
                                    Text(stringResource(R.string.al_no_packages))
                                    if (needle.isEmpty()) packagesNote?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            else LazyColumn(Modifier.fillMaxSize()) {
                                items(shown, key = { it.name }) { it2 ->
                                    ListItem(modifier = Modifier.clickable { selected = it2; query = "" },
                                        headlineContent = { Text(it2.name, maxLines = 2, overflow = TextOverflow.Ellipsis) })
                                }
                            }
                        }
                    } else when (val a = activities) {
                        Loaded.Loading -> Centered { CircularProgressIndicator() }
                        is Loaded.Ok -> {
                            val shown = a.items.filter { needle.isEmpty() || it.name.contains(needle, ignoreCase = true) || it.label?.contains(needle, ignoreCase = true) == true }
                            if (shown.isEmpty()) Centered { Text(stringResource(R.string.al_no_activities)) }
                            else LazyColumn(Modifier.fillMaxSize()) {
                                items(shown, key = { it.name }) { act ->
                                    ListItem(modifier = Modifier.clickable { start(pkg, act) },
                                        headlineContent = { Text(act.label ?: act.name.substringAfterLast('.'), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                        supportingContent = {
                                            Text(act.name + if (act.exported == false) "  ·  " + stringResource(R.string.al_not_exported) else "",
                                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
