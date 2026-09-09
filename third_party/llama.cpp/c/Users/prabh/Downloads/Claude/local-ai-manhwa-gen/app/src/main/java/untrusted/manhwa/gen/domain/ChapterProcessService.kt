package untrusted.manhwa.gen.domain

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import untrusted.manhwa.gen.MainActivity
import untrusted.manhwa.gen.R
import untrusted.manhwa.gen.data.AppDb
import untrusted.manhwa.gen.data.ChapterStatus
import untrusted.manhwa.gen.engine.EngineManager
import java.io.File

/** Observable processing state shared between the service and the UI. */
object ChapterProcessState {
    var chapterId by mutableLongStateOf(-1L)
        internal set
    var running by mutableStateOf(false)
        internal set
    var status by mutableStateOf("")
        internal set

    /** List of step descriptions with completion flag, for richer progress UI. */
    var steps by mutableStateOf<List<ProcessStep>>(emptyList())
        internal set
}

data class ProcessStep(
    val label: String,
    val completed: Boolean,
    val current: Boolean = false,
)

/**
 * Foreground service for chapter text analysis (LLM passes). A foreground
 * service keeps the 2 GB LLM process out of the low-memory killer's
 * crosshairs and lets processing survive the user leaving the screen —
 * running this in a ViewModel got the app killed on memory-pressured ROMs.
 */
class ChapterProcessService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            EngineManager.cancelAll()
            job?.cancel()
            ChapterProcessState.running = false
            ChapterProcessState.status = "Cancelled"
            ChapterProcessState.steps = emptyList()
            stopSelf()
            return START_NOT_STICKY
        }
        val chapterId = intent?.getLongExtra(EXTRA_CHAPTER_ID, -1) ?: -1
        val projectId = intent?.getLongExtra(EXTRA_PROJECT_ID, -1) ?: -1
        if (chapterId <= 0 || projectId <= 0) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (ChapterProcessState.running) return START_NOT_STICKY

        startForeground(NOTIF_ID, buildNotification("Reading chapter…"))
        ChapterProcessState.chapterId = chapterId
        ChapterProcessState.running = true
        ChapterProcessState.status = "Preparing…"
        ChapterProcessState.steps = listOf(
            ProcessStep("Loading language model", false, current = true),
            ProcessStep("Extracting characters & locations", false),
            ProcessStep("Splitting into scenes", false),
            ProcessStep("Saving results", false),
        )
        job = scope.launch { process(projectId, chapterId) }
        return START_NOT_STICKY
    }

    private fun markStepComplete(index: Int) {
        ChapterProcessState.steps = ChapterProcessState.steps.toMutableList().apply {
            if (index < size) {
                this[index] = this[index].copy(completed = true, current = false)
                if (index + 1 < size) {
                    this[index + 1] = this[index + 1].copy(current = true)
                }
            }
        }
    }

    private suspend fun process(projectId: Long, chapterId: Long) {
        val db = AppDb.get(applicationContext)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "manhwagen:process")
        wakeLock.acquire(2 * 60 * 60 * 1000L)
        try {
            val chapter = db.chapters().byId(chapterId) ?: run {
                setStatus("Chapter not found")
                return
            }
            val llm = ModelStore.llm(applicationContext)
            if (llm == null) {
                setStatus("LLM model missing — download it in Model Setup first")
                return
            }
            val text = runCatching { File(chapter.txtPath).readText() }.getOrNull()
            if (text.isNullOrBlank()) {
                setStatus("Could not read the chapter file")
                return
            }

            setStatus("Loading language model… (~30 s)")
            if (!EngineManager.ensureLlmLoaded(llm)) {
                setStatus("Language model failed to load — check file or re-download")
                return
            }
            markStepComplete(0)

            val processor = ChapterProcessor(db)
            val textLen = text.length
            val t0 = System.currentTimeMillis()

            // Step 1: Extract entities
            setStatus("Finding characters, locations, and items…")
            val entityCount = processor.extractEntities(projectId, chapterId, text) { statusMsg ->
                setStatus(statusMsg)
            }
            markStepComplete(1)

            // Step 2: Extract scenes
            setStatus("Splitting into visual scenes…")
            val sceneCount = processor.extractScenes(chapterId, text) { statusMsg ->
                setStatus(statusMsg)
            }
            markStepComplete(2)

            // Step 3: Save results
            val elapsed = (System.currentTimeMillis() - t0) / 1000
            db.chapters().update(
                chapter.copy(status = ChapterStatus.PROCESSED, processedAt = System.currentTimeMillis())
            )
            markStepComplete(3)

            val entitiesWord = if (entityCount == 1) "1 entity" else "$entityCount entities"
            val scenesWord = if (sceneCount == 1) "1 scene" else "$sceneCount scenes"
            ChapterProcessState.status = ""
            setStatus("Done — $entitiesWord, $scenesWord in ${elapsed}s")
        } catch (_: CancellationException) {
            // user cancelled — status already set by ACTION_STOP
        } finally {
            ChapterProcessState.running = false
            if (wakeLock.isHeld) wakeLock.release()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun setStatus(text: String) {
        ChapterProcessState.status = text
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Chapter analysis", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val tapIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, ChapterProcessService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Analyzing chapter")
            .setContentText(text)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .addAction(0, "Cancel", stopIntent)
            .build()
    }

    override fun onDestroy() {
        job?.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CHAPTER_ID = "chapterId"
        const val EXTRA_PROJECT_ID = "projectId"
        const val ACTION_STOP = "untrusted.manhwa.gen.STOP_PROCESS"
        const val CHANNEL_ID = "chapter_process"
        const val NOTIF_ID = 43

        fun start(context: Context, projectId: Long, chapterId: Long) {
            val i = Intent(context, ChapterProcessService::class.java)
                .putExtra(EXTRA_PROJECT_ID, projectId)
                .putExtra(EXTRA_CHAPTER_ID, chapterId)
            context.startForegroundService(i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ChapterProcessService::class.java).setAction(ACTION_STOP))
        }
    }
}
