/* AEmulator Plus addition, 2026-11-15: built-in file browser for imports. GPL-3.0; see NOTICE.md. */
package app.aemu.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.Settings
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SdCard
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.aemu.AppPrefs
import app.aemu.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

// ---------------------------------------------------------------------------------------------------------------------
// Access to shared storage as plain files (Android 8 - 17)
// ---------------------------------------------------------------------------------------------------------------------

/**
 * What it takes to read shared storage with java.io.File on each Android version:
 *  - 8 - 9 (API 26-28): the READ/WRITE_EXTERNAL_STORAGE runtime permission.
 *  - 10 (API 29): the same permission *and* android:requestLegacyExternalStorage="true" in the manifest; without that flag
 *    scoped storage hides everything but media, which [scopedOnly] reports so that the browser can say so.
 *  - 11 - 17 (API 30+): "All files access" (MANAGE_EXTERNAL_STORAGE), granted on a Settings screen. The runtime permission
 *    alone only opens media there (and on 13+ it does nothing at all). Even with it, other apps' Android/data and
 *    Android/obb stay closed on 11+; such a folder simply shows as unreadable.
 */
internal object FileAccess {
    private val RUNTIME = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)

    fun runtimePermissions(): Array<String> = RUNTIME

    private fun runtimeGranted(ctx: Context) =
        RUNTIME.any { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    /** Android 10 with the permission but without the legacy-storage opt-out: files other than media cannot be read. */
    fun scopedOnly(ctx: Context): Boolean =
        Build.VERSION.SDK_INT >= 29 && Build.VERSION.SDK_INT < 30 && runtimeGranted(ctx) && !Environment.isExternalStorageLegacy()

    fun granted(ctx: Context): Boolean = when {
        Build.VERSION.SDK_INT >= 30 -> Environment.isExternalStorageManager()
        Build.VERSION.SDK_INT >= 29 -> runtimeGranted(ctx) && Environment.isExternalStorageLegacy()
        else -> runtimeGranted(ctx)
    }

    /** True where the permission is granted on a Settings screen instead of in a dialog. */
    val viaSettings: Boolean get() = Build.VERSION.SDK_INT >= 30

    fun openSettings(ctx: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        runCatching {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")))
        }.onFailure {
            runCatching { ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        }
    }
}

internal enum class PlaceKind { INTERNAL, REMOVABLE, DOWNLOADS, DOCUMENTS, FIRMWARE }

internal class Place(val label: String, val dir: File, val kind: PlaceKind)

/** Storages and the folders firmware usually sits in. Touches the file system: call it off the main thread. */
@Suppress("DEPRECATION")
internal fun browserPlaces(ctx: Context): List<Place> {
    val out = ArrayList<Place>()
    val seen = HashSet<String>()
    fun add(label: () -> String, dir: File, kind: PlaceKind) {
        if (!dir.isDirectory) return
        val key = runCatching { dir.canonicalPath }.getOrDefault(dir.path)
        if (seen.add(key)) out += Place(label(), dir, kind)
    }
    val primary = Environment.getExternalStorageDirectory()
    add({ ctx.getString(R.string.fb_internal) }, primary, PlaceKind.INTERNAL)

    // memory cards and USB drives; a volume without a name of its own is numbered ("SD card 1")
    var removable = 0
    fun card(name: String?): () -> String = {
        removable++
        name?.takeIf { it.isNotBlank() } ?: ctx.getString(R.string.fb_removable, removable)
    }
    if (Build.VERSION.SDK_INT >= 30) {
        val sm = ctx.getSystemService(StorageManager::class.java)
        sm?.storageVolumes?.forEach { v ->
            val d = v.directory ?: return@forEach
            if (!v.isPrimary && v.state == Environment.MEDIA_MOUNTED) add(card(v.getDescription(ctx)), d, PlaceKind.REMOVABLE)
        }
    } else {
        // the app-specific directory of every volume sits under that volume's root: /storage/XXXX-XXXX/Android/data/<pkg>/files
        ctx.getExternalFilesDirs(null).drop(1).forEach { f ->
            val p = f?.path ?: return@forEach
            val i = p.indexOf("/Android/data/")
            if (i > 0) add(card(null), File(p.substring(0, i)), PlaceKind.REMOVABLE)
        }
    }
    // whatever else vold has mounted (a USB drive has no app-specific directory)
    File("/storage").listFiles()?.forEach { d ->
        if (d.name != "self" && d.name != "emulated" && d.isDirectory && d.canRead()) add(card(d.name), d, PlaceKind.REMOVABLE)
    }

    add({ ctx.getString(R.string.fb_downloads) }, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), PlaceKind.DOWNLOADS)
    add({ ctx.getString(R.string.fb_documents) }, Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), PlaceKind.DOCUMENTS)
    add({ ctx.getString(R.string.fb_firmware) }, app.aemu.core.FirmwareFolder.dir, PlaceKind.FIRMWARE)
    return out
}

// ---------------------------------------------------------------------------------------------------------------------
// Listing and sorting
// ---------------------------------------------------------------------------------------------------------------------

internal enum class SortKey(val pref: String, val defaultAsc: Boolean) {
    NAME("name", true), DATE("date", false), SIZE("size", false);

    companion object { fun of(pref: String) = entries.firstOrNull { it.pref == pref } ?: DATE }
}

internal class Entry(val file: File, val isDir: Boolean, val size: Long, val modified: Long) {
    val name: String get() = file.name
    val path: String get() = file.path
}

private sealed interface Listing {
    data object Loading : Listing
    data object Unreadable : Listing
    class Ok(val entries: List<Entry>) : Listing
}

/** Folders first, then the files; "system_2" before "system_10". */
internal fun entryComparator(key: SortKey, asc: Boolean): Comparator<Entry> {
    val byName = Comparator<Entry> { a, b -> naturalCompare(a.name, b.name) }
    val by: Comparator<Entry> = when (key) {
        SortKey.NAME -> byName
        SortKey.DATE -> compareBy<Entry> { it.modified }.thenComparing(byName)
        SortKey.SIZE -> compareBy<Entry> { it.size }.thenComparing(byName)
    }
    return Comparator { a, b ->
        when {
            a.isDir != b.isDir -> if (a.isDir) -1 else 1
            // folders have no size of their own: keep them alphabetical whichever way the sizes run
            a.isDir && key == SortKey.SIZE -> byName.compare(a, b)
            asc -> by.compare(a, b)
            else -> by.compare(b, a)
        }
    }
}

internal fun naturalCompare(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]
        val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            var ei = i; while (ei < a.length && a[ei].isDigit()) ei++
            var ej = j; while (ej < b.length && b[ej].isDigit()) ej++
            val na = a.substring(i, ei).trimStart('0')
            val nb = b.substring(j, ej).trimStart('0')
            if (na.length != nb.length) return na.length - nb.length
            val c = na.compareTo(nb)
            if (c != 0) return c
            i = ei; j = ej
        } else {
            val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (c != 0) return c
            i++; j++
        }
    }
    return (a.length - i) - (b.length - j)
}

private fun readDir(dir: File): Listing {
    val all = dir.listFiles() ?: return Listing.Unreadable
    return Listing.Ok(all.map { f ->
        val d = f.isDirectory
        Entry(f, d, if (d) 0L else f.length(), f.lastModified())
    })
}

/** Where the browser opens: where it was last, else Download, else the list of storages (null). */
private fun startDir(ctx: Context): File? {
    fun ok(f: File?) = f != null && f.isDirectory && f.canRead()
    AppPrefs.browserDir(ctx)?.let(::File)?.takeIf(::ok)?.let { return it }
    @Suppress("DEPRECATION")
    return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).takeIf(::ok)
}

// ---------------------------------------------------------------------------------------------------------------------
// The browser
// ---------------------------------------------------------------------------------------------------------------------

/**
 * Full-screen file browser on plain file access. A tap on a file picks it at once. With [multiple], press and hold starts
 * a selection (also across folders): further taps tick files and the button at the bottom hands them over. Files are returned as file: URIs.
 * [onSystemPicker]: the way out to the system's document picker (cloud providers, and no-access situations).
 */
@Composable
internal fun FileBrowserDialog(
    multiple: Boolean,
    onDismiss: () -> Unit,
    onPicked: (List<Uri>) -> Unit,
    onSystemPicker: () -> Unit,
) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(FileAccess.granted(ctx)) }
    val life = LocalLifecycleOwner.current
    DisposableEffect(life) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) granted = FileAccess.granted(ctx) }
        life.lifecycle.addObserver(obs)
        onDispose { life.lifecycle.removeObserver(obs) }
    }

    var places by remember { mutableStateOf<List<Place>>(emptyList()) }
    var placesLoaded by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf<File?>(null) }
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(granted) {
        if (!granted) return@LaunchedEffect
        val first = !started
        val (found, start) = withContext(Dispatchers.IO) { browserPlaces(ctx) to (if (first) startDir(ctx) else null) }
        places = found
        placesLoaded = true
        if (first) { started = true; current = start }
    }
    // search: filters the names in the open folder; it is cleared when another folder is opened
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    fun go(dir: File?) { current = dir; AppPrefs.setBrowserDir(ctx, dir?.path); searching = false; query = "" }

    var sortKey by remember { mutableStateOf(SortKey.of(AppPrefs.browserSort(ctx))) }
    var asc by remember { mutableStateOf(AppPrefs.browserSortAsc(ctx)) }
    var hidden by remember { mutableStateOf(AppPrefs.browserHidden(ctx)) }
    val selected = remember { mutableStateListOf<Entry>() }

    fun toggle(e: Entry) {
        val i = selected.indexOfFirst { it.path == e.path }
        if (i >= 0) selected.removeAt(i) else selected.add(e)
    }

    /** The parent folder, or the list of storages once the top of a storage is reached. */
    fun up(dir: File): File? {
        if (places.any { it.dir == dir && it.kind != PlaceKind.DOWNLOADS && it.kind != PlaceKind.DOCUMENTS && it.kind != PlaceKind.FIRMWARE }) return null
        val parent = dir.parentFile ?: return null
        if (parent.path == "/" || parent.path == "/storage" || parent.path == "/storage/emulated") return null
        return if (parent.canRead()) parent else null
    }

    // Back is the only way the dialog asks to be dismissed (it fills the screen): first ends a selection, then one folder up, then out
    Dialog(onDismissRequest = {
        if (selected.isNotEmpty()) selected.clear()
        else if (searching) { searching = false; query = "" }
        else current?.let { go(up(it)) } ?: onDismiss()
    }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                val dir = current
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { if (dir != null) go(up(dir)) else onDismiss() }) {
                        if (dir != null) Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.fb_up))
                        else Icon(Icons.Rounded.Close, stringResource(R.string.cancel))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            dir?.let { d -> places.firstOrNull { it.dir == d }?.label ?: d.name.ifEmpty { d.path } }
                                ?: stringResource(if (multiple) R.string.fb_title_multi else R.string.fb_title_single),
                            style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (dir != null) Text(dir.path, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                            overflow = TextOverflow.StartEllipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (granted && dir != null) IconButton(onClick = {
                        if (searching) query = "" // a tap on the icon while searching closes the field
                        searching = !searching
                    }) { Icon(Icons.Rounded.Search, stringResource(R.string.fb_search)) }
                    TextButton(onClick = onSystemPicker) { Text(stringResource(R.string.fb_system_picker)) }
                }

                if (!granted) {
                    AccessPane(onGranted = { granted = FileAccess.granted(ctx) }, onSystemPicker = onSystemPicker)
                } else {
                    if (searching && dir != null) {
                        val focus = remember { FocusRequester() }
                        val focusManager = LocalFocusManager.current
                        LaunchedEffect(Unit) { focus.requestFocus() }
                        OutlinedTextField(
                            value = query, onValueChange = { query = it },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).focusRequester(focus),
                            singleLine = true,
                            placeholder = { Text(stringResource(R.string.fb_search_hint)) },
                            leadingIcon = { Icon(Icons.Rounded.Search, null) },
                            trailingIcon = if (query.isNotEmpty()) ({
                                IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, stringResource(R.string.fb_clear)) }
                            }) else null,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() }),
                        )
                    }
                    // sorting: tap a key to sort by it, tap it again to turn the direction round
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (k in SortKey.entries) FilterChip(
                            selected = sortKey == k,
                            onClick = {
                                if (sortKey == k) asc = !asc else { sortKey = k; asc = k.defaultAsc }
                                AppPrefs.setBrowserSort(ctx, sortKey.pref, asc)
                            },
                            label = { Text(stringResource(when (k) { SortKey.NAME -> R.string.fb_sort_name; SortKey.DATE -> R.string.fb_sort_date; SortKey.SIZE -> R.string.fb_sort_size })) },
                            leadingIcon = if (sortKey == k) ({
                                Icon(if (asc) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward, null, Modifier.size(18.dp))
                            }) else null,
                        )
                        FilterChip(selected = hidden, onClick = { hidden = !hidden; AppPrefs.setBrowserHidden(ctx, hidden) },
                            label = { Text(stringResource(R.string.fb_hidden)) })
                    }
                    Spacer(Modifier.height(4.dp))
                    HorizontalDivider()

                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (dir == null) PlacesList(places, placesLoaded) { go(it) }
                        else FolderList(dir, multiple, sortKey, asc, hidden, query, selected,
                            onOpen = { go(it) },
                            // like a file manager: a tap imports at once, unless files are being selected; press and hold starts selecting
                            onFile = { e ->
                                if (multiple && selected.isNotEmpty()) toggle(e)
                                else onPicked(listOf(Uri.fromFile(e.file)))
                            },
                            onHold = { e -> if (multiple) toggle(e) })
                    }

                    if (multiple && selected.isNotEmpty()) {
                        HorizontalDivider()
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(stringResource(R.string.fb_selected, selected.size), style = MaterialTheme.typography.titleSmall)
                                Text(Formatter.formatFileSize(ctx, selected.sumOf { it.size }), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { selected.clear() }) { Text(stringResource(R.string.fb_clear)) }
                            Button(onClick = { onPicked(selected.map { Uri.fromFile(it.file) }) }) { Text(stringResource(R.string.fb_import)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun PlacesList(places: List<Place>, loaded: Boolean, onOpen: (File) -> Unit) {
    if (places.isEmpty()) { Centered { if (loaded) Message(stringResource(R.string.fb_empty)) else CircularProgressIndicator() }; return }
    LazyColumn(Modifier.fillMaxSize()) {
        items(places, key = { it.dir.path }) { p ->
            ListItem(
                modifier = Modifier.clickable { onOpen(p.dir) },
                leadingContent = {
                    Icon(when (p.kind) {
                        PlaceKind.INTERNAL -> Icons.Rounded.PhoneAndroid
                        PlaceKind.REMOVABLE -> if (p.dir.path.contains("usb", true)) Icons.Rounded.Usb else Icons.Rounded.SdCard
                        PlaceKind.DOWNLOADS -> Icons.Rounded.Download
                        PlaceKind.DOCUMENTS, PlaceKind.FIRMWARE -> Icons.Rounded.Folder
                    }, null)
                },
                headlineContent = { Text(p.label) },
                supportingContent = { Text(p.dir.path, maxLines = 1, overflow = TextOverflow.StartEllipsis) },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FolderList(
    dir: File, multiple: Boolean, key: SortKey, asc: Boolean, showHidden: Boolean, query: String,
    selected: List<Entry>, onOpen: (File) -> Unit, onFile: (Entry) -> Unit, onHold: (Entry) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val ctx = LocalContext.current
    val listing by produceState<Listing>(Listing.Loading, dir) {
        value = Listing.Loading
        value = withContext(Dispatchers.IO) { readDir(dir) }
    }
    val state = rememberLazyListState()
    LaunchedEffect(dir) { state.scrollToItem(0) }
    val date = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    when (val l = listing) {
        Listing.Loading -> Centered { CircularProgressIndicator() }
        Listing.Unreadable -> Centered { Message(stringResource(R.string.fb_unreadable)) }
        is Listing.Ok -> {
            val needle = query.trim()
            val shown = remember(l, key, asc, showHidden, needle) {
                l.entries
                    .filter { showHidden || !it.name.startsWith(".") }
                    .filter { needle.isEmpty() || it.name.contains(needle, ignoreCase = true) }
                    .sortedWith(entryComparator(key, asc))
            }
            if (shown.isEmpty()) Centered { Message(stringResource(if (needle.isEmpty()) R.string.fb_empty else R.string.fb_no_matches)) }
            else LazyColumn(Modifier.fillMaxSize(), state = state) {
                items(shown, key = { it.path }) { e ->
                    val checked = !e.isDir && selected.any { it.path == e.path }
                    ListItem(
                        modifier = Modifier.combinedClickable(
                            onClick = { if (e.isDir) onOpen(e.file) else onFile(e) },
                            onLongClick = if (e.isDir) null else ({ haptic.performHapticFeedback(HapticFeedbackType.LongPress); onHold(e) }),
                        ),
                        leadingContent = { Icon(if (e.isDir) Icons.Rounded.Folder else Icons.Rounded.Description, null) },
                        headlineContent = { Text(e.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            val when_ = date.format(Date(e.modified))
                            Text(if (e.isDir) when_ else Formatter.formatFileSize(ctx, e.size) + "  ·  " + when_)
                        },
                        trailingContent = if (multiple && selected.isNotEmpty() && !e.isDir) ({ Checkbox(checked = checked, onCheckedChange = null) }) else null,
                        colors = ListItemDefaults.colors(containerColor = if (checked) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent),
                    )
                }
            }
        }
    }
}

/** Shown while there is no file access: asks for it (a dialog up to Android 10, the All files access screen from 11). */
@Composable
internal fun AccessPane(onGranted: () -> Unit, onSystemPicker: () -> Unit) {
    val ctx = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onGranted() }
    val scoped = FileAccess.scopedOnly(ctx)
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Rounded.FolderOpen, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.fb_access_title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(if (scoped) R.string.fb_access_scoped else R.string.fb_access_text),
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        if (!scoped) Button(onClick = { if (FileAccess.viaSettings) FileAccess.openSettings(ctx) else ask.launch(FileAccess.runtimePermissions()) }) {
            Text(stringResource(R.string.allow))
        }
        TextButton(onClick = onSystemPicker) { Text(stringResource(R.string.fb_use_system_picker)) }
    }
}

@Composable
internal fun Centered(content: @Composable () -> Unit) =
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) { content() }

@Composable
internal fun Message(text: String) =
    Text(text, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)

// ---------------------------------------------------------------------------------------------------------------------
// The picker the screens use
// ---------------------------------------------------------------------------------------------------------------------

/** What [rememberFilePicker] hands out. [launch] takes the MIME filter of the old picker call, which is not needed here. */
class FilePicker internal constructor(private val open: () -> Unit) {
    fun launch() = open()
    @Suppress("UNUSED_PARAMETER")
    fun launch(mimeTypes: Array<String>) = open()
}

/**
 * A file picker for importing: the built-in browser (default) or, if the user turned that off in the app settings, the
 * system's document picker. [onResult] gets the chosen files, or an empty list when the picker was dismissed.
 * [multiple]: several files at once (the system picker then also allows several).
 */
@Composable
fun rememberFilePicker(multiple: Boolean = false, onResult: (List<Uri>) -> Unit): FilePicker {
    val ctx = LocalContext.current
    val result by rememberUpdatedState(onResult)
    var browser by remember { mutableStateOf(false) }
    val systemMulti = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { result(it) }
    val systemOne = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { result(listOfNotNull(it)) }
    val system = { if (multiple) systemMulti.launch(arrayOf("*/*")) else systemOne.launch(arrayOf("*/*")) }

    if (browser) FileBrowserDialog(
        multiple = multiple,
        onDismiss = { browser = false; result(emptyList()) },
        onPicked = { browser = false; result(it) },
        onSystemPicker = { browser = false; system() },
    )
    return remember(multiple) { FilePicker { if (AppPrefs.builtinBrowser(ctx)) browser = true else system() } }
}

/** [rememberFilePicker] for a single file; the callback gets null when nothing was picked, as with the system picker. */
@Composable
fun rememberSingleFilePicker(onResult: (Uri?) -> Unit): FilePicker =
    rememberFilePicker(multiple = false) { onResult(it.firstOrNull()) }
