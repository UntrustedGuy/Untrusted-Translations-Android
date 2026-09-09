package untrusted.manhwa.gen.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InterruptedIOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Tracks live download progress with speed and ETA for UI display.
 */
data class DownloadInfo(
    val bytesDone: Long,
    val totalBytes: Long,
    /** Bytes per second (smoothed). */
    val speedBps: Double,
    /** Elapsed milliseconds since the download started. */
    val elapsedMs: Long,
) {
    val fraction: Float
        get() = if (totalBytes > 0) (bytesDone.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f

    val percent: Int
        get() = (fraction * 100).toInt()

    /** Estimated remaining seconds, or null if speed is too low to estimate. */
    val etaSec: Int?
        get() {
            val remaining = totalBytes - bytesDone
            if (remaining <= 0) return 0
            if (speedBps <= 0) return null
            return (remaining / speedBps).toInt().coerceAtLeast(0)
        }

    val speedText: String
        get() = when {
            speedBps >= 1_000_000 -> "%.1f MB/s".format(speedBps / 1_000_000)
            speedBps >= 1_000 -> "%.0f KB/s".format(speedBps / 1_000)
            else -> "%.0f B/s".format(speedBps)
        }

    val etaText: String?
        get() {
            val sec = etaSec ?: return null
            return when {
                sec < 60 -> "${sec}s"
                sec < 3600 -> "${sec / 60}m ${sec % 60}s"
                else -> "${sec / 3600}h ${(sec % 3600) / 60}m"
            }
        }

    val summary: String
        get() {
            val pct = percent
            val eta = etaText
            return buildString {
                append("$pct%")
                append(" · $speedText")
                if (eta != null) append(" · ETA $eta")
            }
        }
}

/**
 * Downloads the required models straight into app storage with resume
 * support (HTTP Range). URLs verified 2026-07-20.
 *
 * The style LoRA is NOT here: Shakker.ai requires a login and has no
 * stable direct URL, so it must be imported via the file picker.
 */
object ModelDownloader {

    data class ModelSpec(
        val id: String,
        val title: String,
        val fileName: String,      // prefix matches Phase0ViewModel.ModelKind discovery
        val url: String,
        val sizeBytes: Long,
    )

    val SPECS = listOf(
        ModelSpec(
            id = "sd",
            title = "AOM3 checkpoint (uncensored SD1.5)",
            fileName = "sd_AOM3_orangemixs.safetensors",
            url = "https://huggingface.co/WarriorMama777/OrangeMixs/resolve/main/Models/AbyssOrangeMix3/AOM3_orangemixs.safetensors",
            sizeBytes = 2_132_626_071,
        ),
        ModelSpec(
            id = "vae",
            title = "OrangeMix VAE (fixes washed-out colors)",
            fileName = "vae_orangemix.vae.pt",
            url = "https://huggingface.co/WarriorMama777/OrangeMixs/resolve/main/VAEs/orangemix.vae.pt",
            sizeBytes = 822_802_803,
        ),
        ModelSpec(
            id = "llm",
            title = "Qwen2.5-3B-Instruct Q4 (text extraction)",
            fileName = "llm_qwen2.5-3b-instruct-q4_k_m.gguf",
            url = "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf",
            sizeBytes = 2_104_932_768,
        ),
    )

    fun modelsDir(context: Context): File =
        File(context.getExternalFilesDir(null), "models").apply { mkdirs() }

    fun isDownloaded(context: Context, spec: ModelSpec): Boolean {
        val f = File(modelsDir(context), spec.fileName)
        return f.exists() && f.length() == spec.sizeBytes
    }

    /** Scans models dir and returns info about what's downloaded. */
    fun downloadSummary(context: Context): Map<String, Boolean> {
        val dir = modelsDir(context)
        return SPECS.associate { spec ->
            val f = File(dir, spec.fileName)
            spec.id to (f.exists() && f.length() == spec.sizeBytes)
        }
    }

    /**
     * Blocking download with resume, retry, and progress/speed tracking.
     * Progress callback receives a [DownloadInfo] with speed and ETA.
     * Throws on persistent failure after [maxRetries] retries.
     */
    suspend fun download(
        context: Context,
        spec: ModelSpec,
        onProgress: (DownloadInfo) -> Unit,
        maxRetries: Int = 3,
    ): File = withContext(Dispatchers.IO) {
        val finalFile = File(modelsDir(context), spec.fileName)
        if (finalFile.exists() && finalFile.length() == spec.sizeBytes) return@withContext finalFile

        var lastError: Exception? = null
        val startTime = System.currentTimeMillis()

        for (attempt in 1..maxRetries) {
            try {
                return@withContext downloadAttempt(context, spec, startTime, onProgress)
            } catch (e: SocketTimeoutException) {
                lastError = e
                // Exponential backoff: 2s, 4s, 8s
                if (attempt < maxRetries) {
                    Thread.sleep((2000L shl (attempt - 1)).coerceAtMost(10_000))
                }
            } catch (e: InterruptedIOException) {
                throw e // cancellation, don't retry
            } catch (e: Exception) {
                lastError = e
                if (attempt < maxRetries) {
                    Thread.sleep(2000L)
                }
            }
        }
        throw lastError ?: error("Download failed after $maxRetries attempts")
    }

    private fun downloadAttempt(
        context: Context,
        spec: ModelSpec,
        startTime: Long,
        onProgress: (DownloadInfo) -> Unit,
    ): File {
        val finalFile = File(modelsDir(context), spec.fileName)
        val part = File(modelsDir(context), spec.fileName + ".part")
        var offset = if (part.exists()) part.length() else 0L
        if (offset > spec.sizeBytes) { part.delete(); offset = 0 }

        var conn: HttpURLConnection? = null
        try {
            var url = URL(spec.url)
            var redirects = 0
            while (true) {
                conn = (url.openConnection() as HttpURLConnection).apply {
                    connectTimeout = 20_000
                    readTimeout = 60_000  // longer read timeout for slow connections
                    instanceFollowRedirects = false
                    if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
                }
                val code = conn!!.responseCode
                if (code in 301..308) {
                    val loc = conn!!.getHeaderField("Location")
                        ?: error("redirect without Location")
                    conn!!.disconnect()
                    url = if (loc.startsWith("http")) URL(loc) else URL(url, loc)
                    check(++redirects <= 5) { "too many redirects" }
                    continue
                }
                check(code == 200 || code == 206) { "HTTP $code for ${spec.title}" }
                if (code == 200 && offset > 0) {
                    part.delete()
                    offset = 0
                }
                break
            }

            // Compute content length from header for validation
            val contentLength = conn!!.contentLengthLong
            val expectedTotal = if (contentLength > 0 && offset == 0L) contentLength else spec.sizeBytes

            conn!!.inputStream.use { input ->
                java.io.FileOutputStream(part, offset > 0).use { out ->
                    val buf = ByteArray(256 * 1024)
                    var done = offset
                    var lastReportTime = startTime
                    var lastReportBytes = done
                    val speedSmoothFactor = 0.3 // EMA smoothing
                    var smoothedSpeed = 0.0

                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n

                        val now = System.currentTimeMillis()
                        val elapsed = now - startTime
                        // Report at most every 200ms to avoid UI floods
                        if (now - lastReportTime >= 200 || done >= expectedTotal) {
                            val dt = ((now - lastReportTime).toDouble() / 1000.0).coerceAtLeast(0.001)
                            val dBytes = (done - lastReportBytes).toDouble()
                            val instantSpeed = dBytes / dt
                            smoothedSpeed = if (smoothedSpeed <= 0) instantSpeed
                            else smoothedSpeed * (1 - speedSmoothFactor) + instantSpeed * speedSmoothFactor

                            onProgress(DownloadInfo(
                                bytesDone = done,
                                totalBytes = expectedTotal,
                                speedBps = smoothedSpeed,
                                elapsedMs = elapsed,
                            ))
                            lastReportTime = now
                            lastReportBytes = done
                        }
                    }
                }
            }
        } finally {
            conn?.disconnect()
        }

        check(part.length() == spec.sizeBytes) {
            "size mismatch for ${spec.title}: got ${part.length()}, want ${spec.sizeBytes}"
        }
        check(part.renameTo(finalFile)) { "rename failed for ${spec.title}" }
        finalFile
    }
}
