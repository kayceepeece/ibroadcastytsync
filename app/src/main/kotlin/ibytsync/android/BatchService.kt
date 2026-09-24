package ibytsync.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import ibytsync.core.download.YtDlpAndroidImpl
import ibytsync.core.pipeline.BatchConfig
import ibytsync.core.pipeline.BatchEvent
import ibytsync.core.pipeline.BatchOrchestrator
import ibytsync.core.pipeline.BatchRunner
import ibytsync.core.pipeline.PipelineHooks
import ibytsync.core.pipeline.QueueRow
import ibytsync.core.pipeline.RowStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import ibytsync.core.settings.SettingsStore
import ibytsync.core.storage.FolderStore
import ibytsync.core.pipeline.SaveHooks
import java.io.File

class BatchService : Service() {

    companion object {
        const val CHANNEL_ID = "ibytsync_batch"
        const val NOTIF_ID = 41
        const val ACTION_CANCEL = "ibytsync.android.CANCEL_BATCH"
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var runner: BatchRunner? = null
    private var rows: List<QueueRow> = emptyList()
    private var latest: List<QueueRow> = emptyList()

    inner class LocalBinder : Binder() {
        fun service(): BatchService = this@BatchService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Batch uploads", NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            scope.launch { runner?.cancelAndJoin(); stopSelf() }
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    fun startBatch(
        initial: List<QueueRow>,
        cfg: BatchConfig,
        hooks: PipelineHooks? = null,
        onEvent: (BatchEvent) -> Unit
    ) {
        rows = initial
        latest = initial
        val engine = YtDlpAndroidImpl(this)
        engine.init()
        val pipelineHooks = hooks ?: AndroidPipelineHooks(this, engine, SettingsStore(this))
        val savedTree = FolderStore.savedTree(this)
        val saveHooks = if (savedTree != null) {
            object : SaveHooks {
                override fun saveFile(tmp: File): FolderStore.SaveOutcome =
                    FolderStore.save(this@BatchService, savedTree, tmp, true)
            }
        } else null
        val tmpDir = getExternalFilesDir("youtubedl-android") ?: File(filesDir, "youtubedl-android")
        tmpDir.mkdirs()
        val runner = BatchRunner(
            {
                BatchOrchestrator(
                    downloader = engine,
                    hooks = pipelineHooks,
                    saveHooks = saveHooks,
                    streamOpener = { uriStr ->
                        try {
                            contentResolver.openInputStream(android.net.Uri.parse(uriStr))
                        } catch (_: Exception) {
                            null
                        }
                    }
                )
            },
            tmpDir
        )
        this.runner = runner
        sweepStalePartials()
        startForeground(NOTIF_ID, buildNotification(0, initial.size, "Starting…"))
        runner.runBatch(initial, cfg) { event ->
            when (event) {
                is BatchEvent.RowUpdate -> {
                    latest = latest.map { if (it.id == event.row.id) event.row else it }
                }
                is BatchEvent.Progress -> {
                    val pct = if (event.progress.total > 0) {
                        (event.progress.done * 100 / event.progress.total).coerceIn(0, 100)
                    } else 0
                    updateNotification(
                        event.progress.done, event.progress.total,
                        "${event.progress.currentTitle} — ${event.progress.currentState} ($pct%)",
                        event.progress.done, event.progress.total
                    )
                }
                is BatchEvent.Finished -> {
                    updateNotification(1, 1, event.summary.message())
                    stopSelf()
                }
                is BatchEvent.Cancelled -> {
                    latest = event.completedRows
                    updateNotification(0, 0, "Cancelled")
                    stopSelf()
                }
            }
            try {
                onEvent(event)
            } catch (_: Exception) {
            }
        }
    }

    fun currentRows(): List<QueueRow> = latest

    fun isRunning(): Boolean = runner?.isRunning() == true

    fun sweepStalePartials() {
        try {
            val dir = getExternalFilesDir("youtubedl-android") ?: return
            dir.listFiles { f -> f.name.startsWith("tb_") }?.forEach { f ->
                try {
                    if (System.currentTimeMillis() - f.lastModified() > 24L * 60L * 60L * 1000L) f.delete()
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    fun cancelBatch() {
        scope.launch {
            runner?.cancelAndJoin()
            stopSelf()
        }
    }

    private fun buildNotification(done: Int, total: Int, text: String, progressDone: Int? = null, progressTotal: Int? = null): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, BatchService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("YT Sync for iBroadcast ($done/$total)")
            .setContentText(text.take(200))
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(open)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancel)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (progressDone != null && progressTotal != null && progressTotal > 0) {
            builder.setProgress(progressTotal, progressDone, false)
        }
        return builder.build()
    }

    private var lastNotifMs: Long = 0L

    private fun updateNotification(done: Int, total: Int, text: String, progressDone: Int? = null, progressTotal: Int? = null) {
        val now = System.currentTimeMillis()
        val terminal = text.startsWith("Done —") || text == "Cancelled"
        if (!terminal && now - lastNotifMs < 1000L) return
        lastNotifMs = now
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(done, total, text, progressDone, progressTotal))
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
