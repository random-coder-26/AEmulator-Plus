/* AEmulator Plus addition, 2026-11-16: catalog downloads that keep going in the background. GPL-3.0; see NOTICE.md. */
package app.aemu.catalog

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import app.aemu.AppPrefs
import app.aemu.R
import app.aemu.core.userMessage
import app.aemu.ui.EXTRA_IMPORT
import app.aemu.ui.EXTRA_IMPORT_DELETE
import app.aemu.ui.MainActivity
import app.aemu.ui.RomCatalogActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/** What the catalog screen shows and reacts to. Only [Running] is a state of its own; the rest are results to [RomDownloadService.consume]. */
sealed interface DownloadStatus {
    data object Idle : DownloadStatus
    /** [name] is empty while the link is still being checked. */
    data class Running(val name: String = "", val done: Long = 0L, val total: Long = -1L, val speed: Long = 0L) : DownloadStatus
    /** The file is in the downloads folder, ready for the importer. */
    data class Finished(val file: File) : DownloadStatus
    /** The link is a web page, not a file: open it in the browser. */
    data class OpenInBrowser(val url: String) : DownloadStatus
    data class Failed(val message: String) : DownloadStatus
}

/** Smoothed transfer speed in bytes per second, fed with the running byte count. */
internal class SpeedMeter {
    private var lastNs = 0L
    private var lastBytes = 0L
    private var rate = 0.0
    @Synchronized fun update(bytes: Long): Long {
        val now = System.nanoTime()
        if (lastNs == 0L) { lastNs = now; lastBytes = bytes; return 0L }
        val dt = (now - lastNs) / 1e9
        if (dt >= 0.5) {
            val inst = (bytes - lastBytes) / dt
            rate = if (rate == 0.0) inst else rate * 0.7 + inst * 0.3
            lastNs = now; lastBytes = bytes
        }
        return rate.toLong()
    }
}

/**
 * Runs a catalog download as a foreground service, so it survives the screen turning off and the app being
 * minimised or swiped away from the foreground. One download at a time.
 *
 * The screen watches [status]. When the catalog screen is not visible at the end ([uiVisible] false) the result is
 * announced with a notification instead: tapping the "download complete" one hands the file to the importer.
 */
class RomDownloadService : Service() {
    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppPrefs.wrap(base))
    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) { job?.cancel(); return START_NOT_STICKY }
        val url = intent?.getStringExtra(EXTRA_URL)
        // a service started with startForegroundService has to go foreground at once, even when it has nothing to do
        val started = runCatching { goForeground(progressNote(this, DownloadStatus.Running())) }.isSuccess
        if (url == null || !started) {
            _status.value = if (!started) DownloadStatus.Failed(getString(R.string.rom_dl_cannot_start)) else DownloadStatus.Idle
            stopSelf()
            return START_NOT_STICKY
        }
        val ctx: Context = this
        // keeps the CPU running with the screen off; the timeout is only a safety net
        val wake = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "aemu:romdownload")
            .apply { setReferenceCounted(false); acquire(6L * 3600_000L) }
        job = scope.launch {
            try {
                RomDownloader.purgeStale(ctx)
                val meter = SpeedMeter()
                var lastNote = 0L
                fun push(next: DownloadStatus.Running) {
                    _status.value = next
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastNote >= 1000) {
                        lastNote = now
                        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(ID_PROGRESS, progressNote(ctx, next)) }
                    }
                }
                val outcome = RomDownloader.fetch(ctx, url,
                    onFile = { name, total -> push(DownloadStatus.Running(name = name, total = total)) },
                    onProgress = { done, total ->
                        val cur = _status.value as? DownloadStatus.Running ?: return@fetch
                        push(cur.copy(done = done, total = total, speed = meter.update(done)))
                    })
                when (outcome) {
                    is RomDownloader.Outcome.Page ->
                        if (uiVisible) _status.value = DownloadStatus.OpenInBrowser(url)
                        else { _status.value = DownloadStatus.Idle; announcePage(ctx, url) }
                    is RomDownloader.Outcome.Saved ->
                        if (uiVisible) _status.value = DownloadStatus.Finished(outcome.file)
                        else { _status.value = DownloadStatus.Idle; announceDone(ctx, outcome.file) }
                }
            } catch (e: CancellationException) {
                _status.value = DownloadStatus.Idle
            } catch (e: Exception) {
                val message = e.userMessage(ctx)
                if (uiVisible) _status.value = DownloadStatus.Failed(message)
                else { _status.value = DownloadStatus.Idle; announceFailed(ctx, message) }
            } finally {
                runCatching { if (wake.isHeld) wake.release() }
                Handler(Looper.getMainLooper()).post {
                    runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun goForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID_PROGRESS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(ID_PROGRESS, n)
    }

    override fun onDestroy() {
        // the system took the service down (or it finished): a download still running has nowhere to run any more
        job?.takeIf { it.isActive }?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URL = "url"
        private const val ACTION_CANCEL = "app.aemu.ROM_DOWNLOAD_CANCEL"
        private const val CH_PROGRESS = "rom_download"
        private const val CH_DONE = "rom_download_done"
        private const val ID_PROGRESS = 40
        private const val ID_DONE = 41

        private val _status = MutableStateFlow<DownloadStatus>(DownloadStatus.Idle)
        val status: StateFlow<DownloadStatus> = _status

        /** Set by the catalog screen while it is on screen. */
        @Volatile var uiVisible = false

        // the download lives in the process, not in a screen or in one service instance
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        @Volatile private var job: Job? = null

        /** Starts downloading [url]; false when another download is already running. */
        fun start(ctx: Context, url: String): Boolean {
            synchronized(this) {
                if (_status.value is DownloadStatus.Running) return false
                _status.value = DownloadStatus.Running()
            }
            val ok = runCatching {
                ctx.startForegroundService(Intent(ctx, RomDownloadService::class.java).putExtra(EXTRA_URL, url))
            }.isSuccess
            if (!ok) _status.value = DownloadStatus.Failed(ctx.getString(R.string.rom_dl_cannot_start))
            return true
        }

        fun cancel() { job?.cancel() }

        /** The screen has dealt with a finished / failed result. */
        fun consume() { if (_status.value !is DownloadStatus.Running) _status.value = DownloadStatus.Idle }

        private fun mutable(): Int = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        private fun ensureChannels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            runCatching {
                nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, ctx.getString(R.string.rom_dl_channel), NotificationManager.IMPORTANCE_LOW))
                nm.createNotificationChannel(NotificationChannel(CH_DONE, ctx.getString(R.string.rom_dl_channel_done), NotificationManager.IMPORTANCE_DEFAULT))
            }
        }

        private fun progressNote(ctx: Context, s: DownloadStatus.Running): Notification {
            ensureChannels(ctx)
            val open = PendingIntent.getActivity(ctx, 0,
                Intent(ctx, RomCatalogActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP), mutable())
            val cancel = PendingIntent.getService(ctx, 1,
                Intent(ctx, RomDownloadService::class.java).setAction(ACTION_CANCEL), mutable())
            val b = Notification.Builder(ctx, CH_PROGRESS)
                .setSmallIcon(R.drawable.ic_stat_vm)
                .setContentTitle(ctx.getString(if (s.name.isEmpty()) R.string.catalog_download_checking else R.string.catalog_download_title))
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(Notification.Action.Builder(null, ctx.getString(R.string.cancel), cancel).build())
            if (s.name.isNotEmpty()) {
                val sizes = if (s.total > 0) "${android.text.format.Formatter.formatShortFileSize(ctx, s.done)} / ${android.text.format.Formatter.formatShortFileSize(ctx, s.total)}"
                    else android.text.format.Formatter.formatShortFileSize(ctx, s.done)
                b.setContentText(s.name).setSubText(sizes)
            }
            if (s.total > 0) b.setProgress(100, ((s.done * 100) / s.total).toInt().coerceIn(0, 100), false)
            else b.setProgress(0, 0, true)
            return b.build()
        }

        private fun post(ctx: Context, n: Notification) {
            runCatching { ctx.getSystemService(NotificationManager::class.java).notify(ID_DONE, n) }
        }

        private fun announceDone(ctx: Context, file: File) {
            ensureChannels(ctx)
            val import = PendingIntent.getActivity(ctx, 2,
                Intent(ctx, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(EXTRA_IMPORT, file.absolutePath)
                    .putExtra(EXTRA_IMPORT_DELETE, true), mutable())
            post(ctx, Notification.Builder(ctx, CH_DONE).setSmallIcon(R.drawable.ic_stat_vm)
                .setContentTitle(ctx.getString(R.string.rom_dl_done))
                .setContentText(ctx.getString(R.string.rom_dl_done_text, file.name))
                .setContentIntent(import).setAutoCancel(true).build())
        }

        private fun announcePage(ctx: Context, url: String) {
            val safe = CatalogUrls.valid(url) ?: return
            ensureChannels(ctx)
            val view = PendingIntent.getActivity(ctx, 3,
                Intent(Intent.ACTION_VIEW, Uri.parse(safe)).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), mutable())
            post(ctx, Notification.Builder(ctx, CH_DONE).setSmallIcon(R.drawable.ic_stat_vm)
                .setContentTitle(ctx.getString(R.string.rom_dl_page))
                .setContentText(ctx.getString(R.string.rom_dl_page_text))
                .setContentIntent(view).setAutoCancel(true).build())
        }

        private fun announceFailed(ctx: Context, message: String) {
            ensureChannels(ctx)
            val open = PendingIntent.getActivity(ctx, 4, Intent(ctx, RomCatalogActivity::class.java), mutable())
            post(ctx, Notification.Builder(ctx, CH_DONE).setSmallIcon(R.drawable.ic_stat_vm)
                .setContentTitle(ctx.getString(R.string.rom_dl_failed))
                .setContentText(ctx.getString(R.string.catalog_download_failed, message))
                .setContentIntent(open).setAutoCancel(true).build())
        }
    }
}
