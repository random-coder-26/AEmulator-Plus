package app.aemu.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.aemu.R
import app.aemu.catalog.*
import app.aemu.core.userMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Full-screen form that sends a ROM to the submission worker at [apiBase]; submissions are moderated server-side. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubmitRomDialog(apiBase: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var variants by remember { mutableStateOf<List<RomVariantOption>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var variantId by rememberSaveable { mutableIntStateOf(-1) }
    var versionId by rememberSaveable { mutableIntStateOf(-1) }
    var name by rememberSaveable { mutableStateOf("") }
    var romVersion by rememberSaveable { mutableStateOf("") }
    var device by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var sizeMb by rememberSaveable { mutableStateOf("") }
    var emulator by rememberSaveable { mutableStateOf("") }
    var by by rememberSaveable { mutableStateOf("") }
    var notes by rememberSaveable { mutableStateOf("") }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var sendError by remember { mutableStateOf<String?>(null) }
    var done by rememberSaveable { mutableStateOf(false) }
    var notDirect by remember { mutableStateOf(false) }

    LaunchedEffect(apiBase, revision) {
        loadError = null
        try { variants = RomSubmitClient.loadCategories(apiBase) }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { loadError = e.userMessage(ctx) }
    }

    val variant = variants?.firstOrNull { it.id == variantId }
    val version = variant?.versions?.firstOrNull { it.id == versionId }
    val normalizedUrl = CatalogUrls.valid(url)?.takeIf { it.length <= RomSubmission.MAX_URL }
    val sizeBytes: Long? = sizeMb.trim().let { raw ->
        if (raw.isEmpty()) null else raw.replace(',', '.').toDoubleOrNull()
            ?.takeIf { it.isFinite() && it >= 0 && it <= 1_000_000.0 }?.let { (it * 1048576).toLong() }
    }
    val sizeInvalid = sizeMb.isNotBlank() && sizeBytes == null
    val valid = variant != null && version != null && name.isNotBlank() && romVersion.isNotBlank() &&
        device.isNotBlank() && by.isNotBlank() && normalizedUrl != null && !sizeInvalid

    fun send() {
        showErrors = true
        if (!valid || sending || variant == null || version == null || normalizedUrl == null) return
        sending = true
        sendError = null
        scope.launch {
            try {
                // only direct links (archive.org, or a server that answers with a file) are accepted
                if (!RomSubmitClient.isDirectFileLink(normalizedUrl)) { notDirect = true; return@launch }
                RomSubmitClient.submit(apiBase, RomSubmission(variant.id, version.id, name.trim(), romVersion.trim(),
                    device.trim(), by.trim(), normalizedUrl, sizeBytes, emulator.trim().ifEmpty { null }, notes.trim().ifEmpty { null }))
                done = true
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { sendError = e.userMessage(ctx) }
            finally { sending = false }
        }
    }

    fun clearForNext() {
        // Keep who/what-emulator/category: people usually submit several ROMs in a row.
        name = ""; romVersion = ""; url = ""; sizeMb = ""; notes = ""; showErrors = false; sendError = null; done = false
    }

    if (notDirect) AlertDialog(
        onDismissRequest = { notDirect = false },
        title = { Text(stringResource(R.string.submit_not_direct_title)) },
        text = { Text(stringResource(R.string.submit_not_direct_text)) },
        confirmButton = { TextButton(onClick = { notDirect = false }) { Text(stringResource(R.string.ok)) } },
    )

    Dialog(onDismissRequest = { if (!sending) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(topBar = {
                TopAppBar(title = { Text(stringResource(R.string.submit_title)) }, navigationIcon = {
                    IconButton(onClick = onDismiss, enabled = !sending) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back))
                    }
                })
            }) { pad ->
                Column(Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when {
                        done -> {
                            Text(stringResource(R.string.submit_success), style = MaterialTheme.typography.bodyLarge)
                            Button(onClick = { clearForNext() }, Modifier.fillMaxWidth()) { Text(stringResource(R.string.submit_another)) }
                            TextButton(onClick = onDismiss, Modifier.fillMaxWidth()) { Text(stringResource(R.string.submit_close)) }
                        }
                        variants == null -> {
                            if (loadError == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                            else {
                                Text(stringResource(R.string.submit_categories_failed, loadError.orEmpty()), color = MaterialTheme.colorScheme.error)
                                TextButton(onClick = { revision++ }) { Text(stringResource(R.string.catalog_retry)) }
                            }
                        }
                        else -> {
                            Text(stringResource(R.string.submit_intro), style = MaterialTheme.typography.bodyMedium)
                            Picker(stringResource(R.string.submit_variant), variant?.name.orEmpty(), true,
                                variants.orEmpty().map { it.id to it.name }, showErrors && variant == null) {
                                if (it != variantId) { variantId = it; versionId = -1 }
                            }
                            Picker(stringResource(R.string.submit_version), version?.name.orEmpty(), variant != null,
                                variant?.versions.orEmpty().map { it.id to it.name }, showErrors && version == null,
                                if (variant == null) stringResource(R.string.submit_pick_variant_first) else null) { versionId = it }
                            Field(stringResource(R.string.submit_name), name, RomSubmission.MAX_NAME, showErrors && name.isBlank()) { name = it }
                            Field(stringResource(R.string.submit_rom_version), romVersion, RomSubmission.MAX_ROM_VERSION, showErrors && romVersion.isBlank()) { romVersion = it }
                            Field(stringResource(R.string.submit_device), device, RomSubmission.MAX_DEVICE, showErrors && device.isBlank()) { device = it }
                            Field(stringResource(R.string.submit_url), url, RomSubmission.MAX_URL, showErrors && normalizedUrl == null,
                                if (showErrors && normalizedUrl == null) stringResource(R.string.submit_bad_url) else null) { url = it }
                            Field(stringResource(R.string.submit_size_mb), sizeMb, 16, showErrors && sizeInvalid,
                                if (showErrors && sizeInvalid) stringResource(R.string.submit_bad_size) else null) { sizeMb = it }
                            Field(stringResource(R.string.submit_emulator), emulator, RomSubmission.MAX_EMULATOR, false) { emulator = it }
                            Field(stringResource(R.string.submit_by), by, RomSubmission.MAX_SUBMITTED_BY, showErrors && by.isBlank()) { by = it }
                            Field(stringResource(R.string.submit_notes), notes, RomSubmission.MAX_NOTES, false, singleLine = false) { notes = it }
                            sendError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                            Button(onClick = ::send, enabled = !sending, modifier = Modifier.fillMaxWidth()) {
                                if (sending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                else Text(stringResource(R.string.submit_send))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Field(label: String, value: String, max: Int, isError: Boolean, message: String? = null,
    singleLine: Boolean = true, onChange: (String) -> Unit) {
    OutlinedTextField(value, { onChange(it.take(max)) }, Modifier.fillMaxWidth(), singleLine = singleLine,
        minLines = if (singleLine) 1 else 3, label = { Text(label) }, isError = isError,
        supportingText = { Text(message ?: if (isError) stringResource(R.string.submit_required) else "") })
}

/** Read-only field that opens a menu; avoids ExposedDropdownMenuBox, whose anchor API changes between Material3 versions. */
@Composable
private fun Picker(label: String, value: String, enabled: Boolean, options: List<Pair<Int, String>>, isError: Boolean,
    placeholder: String? = null, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(value, {}, Modifier.fillMaxWidth(), readOnly = true, enabled = enabled, singleLine = true,
            label = { Text(label) }, isError = isError, placeholder = placeholder?.let { { Text(it) } },
            trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, null) },
            supportingText = { Text(if (isError) stringResource(R.string.submit_required) else "") })
        // Transparent overlay so taps on the whole field open the menu (a disabled/readOnly text field swallows clicks).
        if (enabled) Box(Modifier.matchParentSize().padding(bottom = 20.dp).clickable { open = true })
        DropdownMenu(open, { open = false }) {
            options.forEach { (id, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { onPick(id); open = false }) }
        }
    }
}
