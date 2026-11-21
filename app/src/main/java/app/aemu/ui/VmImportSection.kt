/* AEmulator Sunset addition, 2026-10-03. GPL-3.0; see LICENSE. */
package app.aemu.ui

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.aemu.R
import app.aemu.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun VmImportSection(img: GuestImage, busy: Boolean, setBusy: (Boolean) -> Unit, apply: (VmSettings) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var warning by remember { mutableStateOf<VmArchiveStorage.SettingsImport?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    fun accept(source: VmArchiveStorage.SettingsImport) {
        apply(source.image.settings)
        message = ctx.getString(R.string.vm_import_settings_done)
    }
    val pickSettings = rememberSingleFilePicker { uri ->
        picking = false
        if (uri != null) {
            setBusy(true)
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching {
                    val source = VmArchiveStorage.readSettings(ctx, uri)
                    source to source.differs(ctx, ImageStore.get(ctx, img.id) ?: img)
                } }
                setBusy(false)
                result.onSuccess { (source, differs) -> if (differs) warning = source else accept(source) }
                    .onFailure { message = ctx.getString(R.string.vm_import_failed, it.userMessage(ctx)) }
            }
        }
    }
    TextButton(enabled = !busy && !picking, modifier = Modifier.fillMaxWidth(), onClick = {
        picking = true; pickSettings.launch(arrayOf("*/*"))
    }) { Text(stringResource(R.string.vm_import_settings)) }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    warning?.let { source -> AlertDialog(
        onDismissRequest = { warning = null },
        title = { Text(stringResource(R.string.vm_import_settings_warning_title)) },
        text = { Text(stringResource(R.string.vm_import_settings_warning,
            "${source.image.brand} ${source.image.model} / ${source.image.release} / ${source.image.skin}",
            "${img.brand} ${img.model} / ${img.release} / ${img.skin}")) },
        dismissButton = { TextButton(onClick = { warning = null }) { Text(stringResource(R.string.cancel)) } },
        confirmButton = { TextButton(onClick = { warning = null; accept(source) }) { Text(stringResource(R.string.vm_import_settings)) } },
    ) }
}

/**
 * "Import partition" as a block of its own (title, description, button), shown next to the delta OTA one: boot, recovery,
 * OEM, vendor, odm, product, system_ext or a skin partition. The kind comes from the file; the person is asked when it does not say.
 */
@Composable
internal fun PartitionImportSection(img: GuestImage, busy: Boolean, setBusy: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var pendingPartition by remember { mutableStateOf<Uri?>(null) }   // a file that has to be told which partition it is
    fun startPartition(uri: Uri, kind: String) {
        val label = PartitionImport.label(kind)
        if (kind in PartitionImport.UNSUPPORTED) { message = ctx.getString(R.string.vm_import_partition_unsupported, label); return }
        setBusy(true)
        message = ctx.getString(R.string.vm_import_partition_working, label)
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { PartitionImport.install(ctx, img.id, uri, kind) } }
            setBusy(false)
            message = result.fold({
                when (kind) {
                    PartitionImport.BOOT -> ctx.getString(R.string.vm_import_boot_done)
                    PartitionImport.RECOVERY -> ctx.getString(R.string.vm_import_recovery_done)
                    else -> ctx.getString(R.string.vm_import_partition_done, label)
                }
            }, { ctx.getString(R.string.vm_import_failed, it.userMessage(ctx)) })
        }
    }
    val pickPartition = rememberSingleFilePicker { uri ->
        picking = false
        if (uri != null) scope.launch {
            val kind = withContext(Dispatchers.IO) { runCatching { PartitionImport.detect(ctx, uri) } }
            kind.onSuccess { if (it != null) { startPartition(uri, it) } else { pendingPartition = uri } }
                .onFailure { message = ctx.getString(R.string.vm_import_failed, it.userMessage(ctx)) }
        }
    }
    Text(stringResource(R.string.vm_import_partition), style = MaterialTheme.typography.titleMedium, modifier = Modifier.fillMaxWidth())
    ListItem(
        modifier = Modifier.fillMaxWidth(),
        headlineContent = { Text(stringResource(R.string.vm_import_partition_headline)) },
        supportingContent = { Text(message ?: stringResource(R.string.vm_import_partition_sub)) },
        trailingContent = {
            TextButton(enabled = !busy && !picking, onClick = { picking = true; pickPartition.launch(arrayOf("*/*")) }) {
                Text(stringResource(R.string.vm_import_partition_button))
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
    pendingPartition?.let { uri ->
        val dir = remember(uri) { VmPaths(ctx, img.id).dir }
        AlertDialog(
            onDismissRequest = { pendingPartition = null },
            title = { Text(stringResource(R.string.vm_import_partition_which)) },
            text = {
                Column {
                    Text(stringResource(R.string.vm_import_partition_which_info))
                    for (kind in PartitionImport.CHOICES) {
                        val have = PartitionImport.present(dir, kind) && !PartitionImport.replaceable(kind)
                        val label = PartitionImport.label(kind)
                        TextButton(enabled = !have, modifier = Modifier.fillMaxWidth(), onClick = { pendingPartition = null; startPartition(uri, kind) }) {
                            Text(if (have) stringResource(R.string.vm_import_partition_have, label) else label)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { pendingPartition = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/** Samsung only: import of the CSC package (carrier / country defaults in /system/csc), next to the OEM option. */
@Composable
internal fun CscImportSection(img: GuestImage, busy: Boolean, setBusy: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val root = VmPaths(ctx, img.id).root
    if (!remember(img.id) { CscImport.isSamsung(root) }) return
    var present by remember { mutableStateOf(CscImport.present(root)) }
    var message by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    val pickCsc = rememberSingleFilePicker { uri ->
        picking = false
        if (uri != null) {
            setBusy(true)
            message = ctx.getString(R.string.vm_import_csc_working)
            scope.launch {
                val result = withContext(Dispatchers.IO) { runCatching { CscImport.install(ctx, img.id, uri) } }
                present = CscImport.present(root)
                setBusy(false)
                message = result.fold({ ctx.getString(R.string.vm_import_csc_done) },
                    { ctx.getString(R.string.vm_import_failed, it.userMessage(ctx)) })
            }
        }
    }
    TextButton(enabled = !busy && !picking, modifier = Modifier.fillMaxWidth(), onClick = {
        picking = true; pickCsc.launch(arrayOf("*/*"))
    }) { Text(stringResource(if (present) R.string.vm_import_csc_replace else R.string.vm_import_csc)) }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}
