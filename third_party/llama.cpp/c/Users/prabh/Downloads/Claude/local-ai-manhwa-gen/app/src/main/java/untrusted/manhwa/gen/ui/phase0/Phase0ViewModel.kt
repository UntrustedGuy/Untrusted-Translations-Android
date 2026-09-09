package untrusted.manhwa.gen.ui.phase0

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.app.Application
import untrusted.manhwa.gen.engine.DownloadInfo
import untrusted.manhwa.gen.engine.EngineManager
import untrusted.manhwa.gen.engine.GenParams
import untrusted.manhwa.gen.engine.GenProgress
import untrusted.manhwa.gen.engine.ModelDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import java.io.File

/**
 * Phase 0 feasibility screen state. Models are picked via SAF and copied
 * into app-private storage (native code needs real file paths, not URIs).
 */
class Phase0ViewModel(app: Application) : AndroidViewModel(app) {

    var sdModelPath by mutableStateOf<String?>(null); private set
    var vaePath by mutableStateOf<String?>(null); private set
    var loraPath by mutableStateOf<String?>(null); private set
    var llmModelPath by mutableStateOf<String?>(null); private set

    // Download state per ModelDownloader spec id: DownloadInfo or null when idle
    var downloadInfo by mutableStateOf<Map<String, DownloadInfo>>(emptyMap()); private set

    var prompt by mutableStateOf(DEFAULT_PROMPT)
    var negativePrompt by mutableStateOf(DEFAULT_NEGATIVE)
    var loraStrength by mutableStateOf(0.7f)
    var extractionInput by mutableStateOf("")

    var busy by mutableStateOf(false); private set
    var status by mutableStateOf("Pick models to begin"); private set
    var resultImage by mutableStateOf<Bitmap?>(null); private set
    var resultText by mutableStateOf<String?>(null); private set
    var lastTimingMs by mutableStateOf<Long?>(null); private set

    /** Diffusion progress comes from the shared engine-wide tracker. */
    val progress: Pair<Int, Int>?
        get() = if (GenProgress.steps > 0) GenProgress.step to GenProgress.steps else null

    private val modelsDir: File
        get() = File(getApplication<Application>().getExternalFilesDir(null), "models").apply { mkdirs() }

    init {
        discoverModels()
    }

    /** Scan models dir and set paths for any previously imported/downloaded models. */
    private fun discoverModels() {
        modelsDir.listFiles()?.forEach { f ->
            when {
                f.name.startsWith("sd_") -> sdModelPath = f.absolutePath
                f.name.startsWith("vae_") -> vaePath = f.absolutePath
                f.name.startsWith("lora_") -> loraPath = f.absolutePath
                f.name.startsWith("llm_") -> llmModelPath = f.absolutePath
            }
        }
        val summary = ModelDownloader.downloadSummary(getApplication())
        val anyReady = summary.values.any { it }
        if (anyReady) {
            val parts = summary.filterValues { it }.keys.joinToString(", ") {
                when (it) {
                    "sd" -> "SD"; "vae" -> "VAE"; "llm" -> "LLM"; else -> it
                }
            }
            status = "Models ready: $parts"
        } else if (sdModelPath != null || llmModelPath != null) {
            status = "Some models found (may need re-download)"
        }
    }

    fun downloadModel(spec: ModelDownloader.ModelSpec) {
        viewModelScope.launch {
            downloadInfo = downloadInfo + (spec.id to DownloadInfo(
                bytesDone = 0,
                totalBytes = spec.sizeBytes,
                speedBps = 0.0,
                elapsedMs = 0,
            ))
            status = "Downloading ${spec.title}…"
            try {
                val file = ModelDownloader.download(getApplication(), spec) { info ->
                    downloadInfo = downloadInfo + (spec.id to info)
                }
                when (spec.id) {
                    "sd" -> sdModelPath = file.absolutePath
                    "vae" -> vaePath = file.absolutePath
                    "llm" -> llmModelPath = file.absolutePath
                }
                status = "${spec.title} ready"
            } catch (e: Exception) {
                status = "Download failed: ${e.message} — tap Get to retry"
            } finally {
                downloadInfo = downloadInfo - spec.id
            }
        }
    }

    fun importModel(uri: Uri, kind: ModelKind) {
        viewModelScope.launch {
            busy = true
            status = "Copying ${kind.name.lowercase()} model into app storage…"
            val path = withContext(Dispatchers.IO) {
                val app = getApplication<Application>()
                val name = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "model"
                val dest = File(modelsDir, "${kind.prefix}$name")
                try {
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { input.copyTo(it) }
                    }
                } catch (e: Exception) {
                    status = "Import failed: ${e.message}"
                    return@withContext null
                }
                dest.takeIf { it.length() > 0 }?.absolutePath
            }
            when (kind) {
                ModelKind.SD -> sdModelPath = path
                ModelKind.VAE -> vaePath = path
                ModelKind.LORA -> loraPath = path
                ModelKind.LLM -> llmModelPath = path
            }
            status = if (path != null) "Imported ${File(path).name}" else "Import failed"
            busy = false
        }
    }

    fun generateImage() {
        val model = sdModelPath ?: run {
            status = "No SD model selected — download or import one first"
            return
        }
        viewModelScope.launch {
            busy = true
            resultImage = null
            status = "Loading SD model… (2 GB file, may take 1-2 min on first load)"
            val t0 = System.currentTimeMillis()
            try {
                // Add a timeout of 5 minutes for model loading
                withTimeout(300_000L) {
                    val ok = EngineManager.ensureSdLoaded(model, vaePath)
                    if (!ok) {
                        status = "SD model failed to load — check model file or re-download"
                        busy = false
                        return@launch
                    }
                }
            } catch (_: TimeoutCancellationException) {
                status = "SD model loading timed out (5 min) — file may be corrupted"
                busy = false
                return@launch
            }
            status = "Generating…"
            val bmp = EngineManager.txt2img(
                GenParams(
                    prompt = prompt,
                    negativePrompt = negativePrompt,
                    loras = loraPath?.let { listOf(it to loraStrength) } ?: emptyList(),
                )
            )
            lastTimingMs = System.currentTimeMillis() - t0
            resultImage = bmp
            status = if (bmp != null) "Done in ${lastTimingMs!! / 1000}s (incl. load)" else "Generation failed — check logcat"
            busy = false
        }
    }

    fun runExtraction() {
        val model = llmModelPath ?: run {
            status = "No LLM model selected — download or import one first"
            return
        }
        if (extractionInput.isBlank()) {
            status = "Paste some chapter text first"
            return
        }
        viewModelScope.launch {
            busy = true
            resultText = null
            status = "Loading LLM… (2 GB file, ~30 s)"
            val t0 = System.currentTimeMillis()
            try {
                withTimeout(120_000L) {
                    val ok = EngineManager.ensureLlmLoaded(model)
                    if (!ok) {
                        status = "LLM failed to load — check model file or re-download"
                        busy = false
                        return@launch
                    }
                }
            } catch (_: TimeoutCancellationException) {
                status = "LLM loading timed out (2 min) — file may be corrupted"
                busy = false
                return@launch
            }
            status = "Extracting…"
            val out = EngineManager.complete(
                system = EXTRACTION_SYSTEM_PROMPT,
                user = extractionInput.take(6000),
            )
            lastTimingMs = System.currentTimeMillis() - t0
            resultText = out
            status = if (out != null) "Done in ${lastTimingMs!! / 1000}s (incl. load)" else "Extraction failed — check logcat"
            busy = false
        }
    }

    fun cancel() {
        EngineManager.cancelAll()
        status = "Cancelling…"
    }

    enum class ModelKind(val prefix: String) { SD("sd_"), VAE("vae_"), LORA("lora_"), LLM("llm_") }

    companion object {
        const val DEFAULT_PROMPT =
            "<lora:unlocking her manhwa style_v1_0:0.7>, unlock, " +
                "high quality Korean webtoon panel, clean precise line art, " +
                "polished digital coloring, subtle cel shading, " +
                "adult man, tall athletic build, sharp masculine face, short black hair, " +
                "dark grey coat over a white shirt, walking through a narrow city street at dusk, " +
                "cinematic framing, detailed Korean urban background"

        const val DEFAULT_NEGATIVE =
            "worst quality, low quality, blurry, anime screenshot, Japanese anime style, " +
                "chibi, photorealistic, 3D render, glossy plastic skin, bad anatomy, bad hands, " +
                "extra fingers, deformed face, text, speech bubble, watermark, signature"

        const val EXTRACTION_SYSTEM_PROMPT =
            "You extract structured data from novel chapters. Reply with ONLY valid JSON, " +
                "no markdown fences, no commentary. Schema: {\"characters\":[{\"name\":string," +
                "\"appearance\":string}],\"locations\":[{\"name\":string,\"description\":string}]," +
                "\"items\":[{\"name\":string,\"description\":string}]}. Extract every named " +
                "character, location, and significant item from the user's text. For appearance, " +
                "include only visual details stated in the text."
    }
}
