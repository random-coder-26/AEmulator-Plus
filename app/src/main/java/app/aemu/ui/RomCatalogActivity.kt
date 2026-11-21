package app.aemu.ui

import app.aemu.core.userMessage
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import android.text.format.Formatter
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.aemu.AppPrefs
import app.aemu.R
import app.aemu.catalog.*
import kotlinx.coroutines.CancellationException

class RomCatalogActivity : ComponentActivity() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppPrefs.wrap(base))
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { AemuTheme { CatalogScreen(::finish) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CatalogScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    // The catalog source is fixed: it cannot be changed or seen in the UI.
    val source = CatalogUrls.DEFAULT
    var revision by remember { mutableIntStateOf(0) }
    var catalog by remember { mutableStateOf<RomCatalog?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedSection by rememberSaveable { mutableIntStateOf(-1) }
    // the sub category folder open inside the selected section, -1 = the section itself
    var selectedSub by rememberSaveable { mutableIntStateOf(-1) }
    var submitting by rememberSaveable { mutableStateOf(false) }
    val apiBase = CatalogUrls.apiBase(source)
    // The download runs in RomDownloadService, so it goes on with the screen off or the app minimised. A download
    // button first checks the link: a file (binary / archive) is downloaded and handed to the importer on the home
    // screen; a web page opens in the browser.
    val status by RomDownloadService.status.collectAsState()
    var dialogHidden by rememberSaveable { mutableStateOf(false) }
    val startDownload: (String) -> Unit = start@{ url ->
        if (!RomDownloadService.start(ctx, url)) {
            android.widget.Toast.makeText(ctx, R.string.rom_dl_busy, android.widget.Toast.LENGTH_SHORT).show()
            return@start
        }
        dialogHidden = false
    }
    // while this screen is visible the service reports to it; otherwise it announces the result with a notification
    val life = LocalLifecycleOwner.current
    DisposableEffect(life) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_START) RomDownloadService.uiVisible = true
            else if (e == Lifecycle.Event.ON_STOP) RomDownloadService.uiVisible = false
        }
        RomDownloadService.uiVisible = life.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        life.lifecycle.addObserver(obs)
        onDispose { life.lifecycle.removeObserver(obs); RomDownloadService.uiVisible = false }
    }
    LaunchedEffect(status) {
        when (val s = status) {
            is DownloadStatus.Finished -> {
                RomDownloadService.consume()
                ctx.startActivity(Intent(ctx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(EXTRA_IMPORT, s.file.absolutePath)
                    .putExtra(EXTRA_IMPORT_DELETE, true))
                (ctx as? android.app.Activity)?.finish()
            }
            is DownloadStatus.OpenInBrowser -> { RomDownloadService.consume(); openBrowser(ctx, s.url) }
            is DownloadStatus.Failed -> {
                RomDownloadService.consume()
                android.widget.Toast.makeText(ctx, ctx.getString(R.string.catalog_download_failed, s.message),
                    android.widget.Toast.LENGTH_LONG).show()
            }
            else -> {}
        }
    }
    val section = catalog?.sections?.getOrNull(selectedSection)
    val sub = section?.subsections?.getOrNull(selectedSub)
    // one level up: folder -> section -> index
    val goUp: () -> Unit = { if (selectedSub >= 0) selectedSub = -1 else selectedSection = -1 }
    BackHandler(enabled = selectedSection >= 0) { goUp() }
    LaunchedEffect(source, revision) {
        loading = true
        error = null
        try {
            val loaded = CatalogLoader.load(source)
            catalog = loaded
            if (selectedSection !in loaded.sections.indices) selectedSection = -1
            if (loaded.sections.getOrNull(selectedSection)?.subsections?.indices?.contains(selectedSub) != true) selectedSub = -1
        }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.userMessage(ctx) }
        finally { loading = false }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(sub?.name ?: section?.name?.ifEmpty { stringResource(R.string.catalog_roms) }
            ?: stringResource(R.string.catalog_title)) }, navigationIcon = {
            IconButton(onClick = { if (selectedSection >= 0) goUp() else onBack() }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(
                    if (selectedSection >= 0 && selectedSub < 0) R.string.catalog_back_sections else R.string.back))
            }
        }, actions = {
            if (apiBase != null) IconButton(onClick = { submitting = true }) { Icon(Icons.Rounded.Add, stringResource(R.string.submit_action)) }
            IconButton(onClick = { selectedSection = -1; selectedSub = -1; revision++ }, enabled = !loading) { Icon(Icons.Rounded.Refresh, stringResource(R.string.catalog_refresh)) }
        })
    }) { pad ->
        key(selectedSection, selectedSub) {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(pad), state = rememberLazyListState(),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            error?.let { problem -> item {
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.catalog_load_failed, problem), color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { revision++ }, enabled = !loading) { Text(stringResource(R.string.catalog_retry)) }
                    }
                }
            } }
            catalog?.let { list ->
                if (selectedSection < 0 && list.motd.isNotEmpty()) item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Text(list.motd, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (list.warningLines.isNotEmpty()) item {
                    Text(stringResource(R.string.catalog_warnings, list.warningLines.take(10).joinToString(", ") +
                        if (list.warningLines.size > 10) "…" else ""),
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (list.sections.isEmpty()) item { Text(stringResource(R.string.catalog_no_roms)) }
                if (section == null) list.sections.forEachIndexed { sectionIndex, entry ->
                    item(key = "section-$sectionIndex") {
                        Card(Modifier.fillMaxWidth()) {
                            ListItem(modifier = Modifier.clickable { selectedSection = sectionIndex },
                                headlineContent = { Text(entry.name.ifEmpty { stringResource(R.string.catalog_roms) },
                                    style = MaterialTheme.typography.titleMedium) },
                                supportingContent = {
                                    Column {
                                        Text(if (entry.romCount == 0) stringResource(R.string.catalog_no_roms)
                                            else pluralStringResource(R.plurals.catalog_rom_count, entry.romCount, entry.romCount))
                                        if (entry.comment.isNotEmpty()) Text(entry.comment, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                },
                                trailingContent = { Icon(Icons.Rounded.ChevronRight, null) })
                        }
                    }
                } else if (sub != null) {
                    // inside a sub category folder: just its ROMs
                    if (sub.roms.isEmpty()) item(key = "sub-empty") {
                        Text(stringResource(R.string.catalog_no_roms), Modifier.padding(vertical = 8.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    sub.roms.forEachIndexed { romIndex, rom ->
                        item(key = "rom-$romIndex") { CatalogRomCard(rom, startDownload) }
                    }
                } else {
                    item(key = "section-info") {
                        Column {
                            if (section.comment.isNotEmpty()) Text(section.comment, style = MaterialTheme.typography.bodyMedium)
                            section.source?.let { url -> TextButton(onClick = { openBrowser(ctx, url) }) {
                                Text(stringResource(R.string.catalog_section_source))
                            } }
                            if (section.romCount == 0) Text(stringResource(R.string.catalog_no_roms),
                                Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    // sub categories are folders, like the sections themselves
                    section.subsections.forEachIndexed { subIndex, entry ->
                        item(key = "sub-$subIndex") {
                            Card(Modifier.fillMaxWidth()) {
                                ListItem(modifier = Modifier.clickable { selectedSub = subIndex },
                                    headlineContent = { Text(entry.name, style = MaterialTheme.typography.titleMedium) },
                                    supportingContent = { Text(pluralStringResource(R.plurals.catalog_rom_count, entry.roms.size, entry.roms.size)) },
                                    trailingContent = { Icon(Icons.Rounded.ChevronRight, null) })
                            }
                        }
                    }
                    // ROMs filed under no category sit directly in the section, after the folders
                    section.roms.forEachIndexed { romIndex, rom ->
                        item(key = "rom-$romIndex") { CatalogRomCard(rom, startDownload) }
                    }
                }
            }
            }
        }
    }
    (status as? DownloadStatus.Running)?.takeIf { !dialogHidden }?.let { d ->
        AlertDialog(
            // no dismissing by a tap outside or Back: Hide or Cancel is the way out
            onDismissRequest = {},
            title = { Text(stringResource(if (d.name.isEmpty()) R.string.catalog_download_checking else R.string.catalog_download_title)) },
            text = { Column {
                if (d.name.isNotEmpty()) {
                    Text(d.name, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                }
                if (d.total > 0) LinearProgressIndicator(progress = { (d.done.toFloat() / d.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                if (d.name.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    val sizes = if (d.total > 0) "${Formatter.formatShortFileSize(ctx, d.done)} / ${Formatter.formatShortFileSize(ctx, d.total)}"
                        else Formatter.formatShortFileSize(ctx, d.done)
                    Text(if (d.speed > 0) "$sizes (${Formatter.formatShortFileSize(ctx, d.speed)}/s)" else sizes,
                        style = MaterialTheme.typography.bodySmall)
                }
            } },
            // the download carries on in its notification while the dialog is hidden
            dismissButton = { TextButton(onClick = { dialogHidden = true }) { Text(stringResource(R.string.rom_dl_hide)) } },
            confirmButton = { TextButton(onClick = { RomDownloadService.cancel() }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (submitting && apiBase != null) SubmitRomDialog(apiBase) { submitting = false }
}

@Composable
private fun CatalogRomCard(rom: CatalogRom, onDownload: (String) -> Unit) {
    val ctx = LocalContext.current
    rom.details?.let { SubmittedRomCard(rom, it, onDownload); return }
    val status = when (rom.status) {
        0 -> R.string.catalog_status_no_boot
        1 -> R.string.catalog_status_issues
        2 -> R.string.catalog_status_works
        else -> R.string.catalog_status_unknown
    }
    val statusColor = when (rom.status) {
        0 -> MaterialTheme.colorScheme.error
        1 -> MaterialTheme.colorScheme.tertiary
        2 -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(rom.device, style = MaterialTheme.typography.titleMedium)
                Text(listOf("Android ${rom.android}", rom.skin).filter { it.isNotBlank() }.joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(status), color = statusColor, style = MaterialTheme.typography.labelLarge)
                if (rom.comment.isNotEmpty()) Text(rom.comment, style = MaterialTheme.typography.bodyMedium)
                if (rom.url == null) Text(stringResource(R.string.catalog_no_url), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            rom.url?.let { url -> RomActions(url, rom.device, onDownload) }
        }
    }
}

/** Card for entries from the JSON catalog: name, device, ROM version, size, emulator, notes, submitter. */
@Composable
private fun SubmittedRomCard(rom: CatalogRom, d: RomDetails, onDownload: (String) -> Unit) {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(d.name, style = MaterialTheme.typography.titleMedium)
                Text(rom.device, style = MaterialTheme.typography.bodyMedium)
                // the version category is the folder this card is in, so it is not repeated here
                if (d.romVersion.isNotBlank()) Text(d.romVersion, style = MaterialTheme.typography.bodyMedium)
                val extras = listOfNotNull(d.fileSizeBytes?.let { Formatter.formatShortFileSize(ctx, it) },
                    d.emulatorVersion?.let { stringResource(R.string.catalog_tested_on, it) })
                if (extras.isNotEmpty()) Text(extras.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                d.notes?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (d.submittedBy.isNotBlank()) Text(stringResource(R.string.catalog_submitted_by, d.submittedBy),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (rom.url == null) Text(stringResource(R.string.catalog_no_url), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            rom.url?.let { url -> RomActions(url, rom.device, onDownload) }
        }
    }
}

/** The download button with a share button under it: share hands the ROM's download link to the system share sheet. */
@Composable
private fun RomActions(url: String, device: String, onDownload: (String) -> Unit) {
    val ctx = LocalContext.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = { onDownload(url) }) {
            Icon(Icons.Rounded.Download, stringResource(R.string.catalog_download_rom, device))
        }
        IconButton(onClick = { shareLink(ctx, url, device) }) {
            Icon(Icons.Rounded.Share, stringResource(R.string.catalog_share_rom, device))
        }
    }
}

private fun shareLink(ctx: Context, url: String, device: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, device)
        putExtra(Intent.EXTRA_TEXT, url)
    }
    try {
        ctx.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) { }
}
