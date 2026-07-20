package com.untrustedtranslations.android.processing

import android.content.Context
import com.untrustedtranslations.android.model.SourceScript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

enum class ModelPackId {
    COMIC_DIALOGUE_DETECTOR,
    RAPID_OCR_JAPANESE,
    RAPID_OCR_KOREAN,
    RAPID_OCR_CHINESE,
    RAPID_OCR_LATIN,
    RAPID_OCR_V5_JAPANESE,
    RAPID_OCR_V5_KOREAN,
    RAPID_OCR_V5_CHINESE,
    RAPID_OCR_V5_LATIN,
    MANGA_OCR_JAPANESE,
    BABERU_OCR_MULTILINGUAL,
    NLLB_TRANSLATION,
    LOCAL_LLM_LOW,
    LOCAL_LLM_MID,
    LOCAL_LLM_HIGH,
    VLM_OCR_HIGH,
}

data class ModelPackInfo(
    val id: ModelPackId,
    val title: String,
    val description: String,
    val downloadSizeMb: Int,
    val minimumRamGb: Int,
    val licenseNote: String,
)

data class ModelPackProgress(
    val pack: ModelPackId,
    val fileName: String,
    val downloadedBytes: Long,
    val totalBytes: Long,
) {
    val fraction: Float
        get() = if (totalBytes <= 0L) 0f else
            (downloadedBytes.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
}

private data class PackFile(val name: String, val url: String, val sha256: String? = null)

object ModelPackManager {
    private const val RAPID_BASE =
        "https://www.modelscope.cn/models/RapidAI/RapidOCR/resolve/master"
    private const val BABERU_BASE = "https://huggingface.co/genshiai-daichi/baberu-ocr/resolve/main"
    private const val MANGA_OCR_BASE =
        "https://huggingface.co/l0wgear/manga-ocr-2025-onnx/resolve/e8b27bbd3f424fe3877e0bda704d6a920e4f0a33"
    private const val ASSETS_BASE =
        "https://github.com/UntrustedGuy/Untrusted-Translations-Android/releases/download/model-assets-v1"
    private const val COMIC_DETECTOR_BASE =
        "https://huggingface.co/ogkalu/comic-text-and-bubble-detector/resolve/16e8a622f91fabc6b5b65c96d32d1183f8843546"
    private const val HF_BASE =
        "https://huggingface.co/monkt/paddleocr-onnx/resolve/main"
    private const val NLLB_BASE =
        "https://github.com/niedev/RTranslator/releases/download/2.0.0"
    private const val LOCAL_LLM_LOW_BASE =
        "https://huggingface.co/bartowski/Qwen_Qwen3-0.6B-GGUF/resolve/60b85c0e3d8fe0f6474f406922a26d12aca4550d"
    private const val LOCAL_LLM_MID_BASE =
        "https://huggingface.co/bartowski/Qwen_Qwen3-1.7B-GGUF/resolve/dcb19155b962dbb6389f4691a982043a8e651022"
    private const val LOCAL_LLM_HIGH_BASE =
        "https://huggingface.co/bartowski/Qwen_Qwen3-4B-GGUF/resolve/cb76885dc66d50759b207c5a48c4e78dfa00c638"
    private const val VLM_OCR_BASE =
        "https://huggingface.co/ggml-org/Qwen2-VL-2B-Instruct-GGUF/resolve/bb307c036e8a1ed7b663bbd0c35b41c4c9294cfd"

    val packs = listOf(
        ModelPackInfo(
            ModelPackId.COMIC_DIALOGUE_DETECTOR,
            "Comic dialogue detector",
            "Shared speech-bubble text detector used by every OCR recognizer. Excludes free text and sound effects.",
            12, 2, "Apache-2.0 ogkalu comic text and bubble detector.",
        ),
        ModelPackInfo(
            ModelPackId.RAPID_OCR_JAPANESE,
            "RapidOCR Japanese",
            "PaddleOCR/RapidOCR models tuned for Japanese text, including vertical manga dialogue.",
            18, 2, "Apache-2.0 engine; Baidu PaddleOCR model terms.",
        ),
        ModelPackInfo(
            ModelPackId.RAPID_OCR_KOREAN,
            "RapidOCR Korean",
            "PaddleOCR/RapidOCR models tuned for Korean manhwa dialogue.",
            17, 2, "Apache-2.0 engine; Baidu PaddleOCR model terms.",
        ),
        ModelPackInfo(
            ModelPackId.RAPID_OCR_CHINESE,
            "RapidOCR Chinese",
            "PaddleOCR/RapidOCR models tuned for Simplified Chinese manhua dialogue.",
            18, 2, "Apache-2.0 engine; Baidu PaddleOCR model terms.",
        ),
        ModelPackInfo(
            ModelPackId.RAPID_OCR_LATIN,
            "RapidOCR Latin",
            "PaddleOCR/RapidOCR models for English and other Latin-script comics.",
            16, 2, "Apache-2.0 engine; Baidu PaddleOCR model terms.",
        ),
        ModelPackInfo(
            ModelPackId.RAPID_OCR_V5_JAPANESE,
            "RapidOCR PP-OCRv5 Japanese",
            "Updated PP-OCRv5 detection model + v4 Japanese recognition from HuggingFace + ModelScope.",
            18, 2, "Apache-2.0; HuggingFace monkt/paddleocr-onnx + ModelScope models.",
        ),
        ModelPackInfo(
            ModelPackId.RAPID_OCR_V5_KOREAN,
            "RapidOCR PP-OCRv5 Korean",
            "Full PP-OCRv5 detection and recognition models from HuggingFace for Korean manhwa.",
            17, 2, "Apache-2.0; HuggingFace monkt/paddleocr-onnx.",
        ),
        ModelPackInfo(
            ModelPackId.RAPID_OCR_V5_CHINESE,
            "RapidOCR PP-OCRv5 Chinese",
            "Full PP-OCRv5 detection and recognition models from HuggingFace for Chinese manhua.",
            18, 2, "Apache-2.0; HuggingFace monkt/paddleocr-onnx.",
        ),
        ModelPackInfo(
            ModelPackId.RAPID_OCR_V5_LATIN,
            "RapidOCR PP-OCRv5 Latin",
            "Full PP-OCRv5 detection and recognition models from HuggingFace for Latin-script comics.",
            16, 2, "Apache-2.0; HuggingFace monkt/paddleocr-onnx.",
        ),
        ModelPackInfo(
            ModelPackId.BABERU_OCR_MULTILINGUAL,
            "Baberu OCR Multilingual",
            "Vision-transformer (DINOv2 encoder, 6-layer decoder). Supports Japanese, Chinese, and English from a single 115M-param checkpoint. Trained for manga speech bubbles; beats manga-ocr on Japanese.",
            121, 3, "Apache-2.0. ONNX int4 vision + int8 decoder. User must supply a text-detection stage via RapidOCR packs.",
        ),
        ModelPackInfo(
            ModelPackId.MANGA_OCR_JAPANESE,
            "Manga-OCR Japanese",
            "Japanese Manga-OCR recognizer (int8, ~2x faster). Uses the separately downloaded shared comic dialogue detector.",
            36, 3, "Apache-2.0 Manga-OCR. May be slow on older devices.",
        ),
        ModelPackInfo(
            ModelPackId.NLLB_TRANSLATION,
            "NLLB offline translation - legacy",
            "General-domain NLLB-200 translation from RTranslator. Offline and ARM64 only; manga dialogue quality can be weak.",
            950, 6, "ARM64 only. RTranslator code is Apache-2.0; NLLB model is non-commercial use only.",
        ),        ModelPackInfo(
            ModelPackId.LOCAL_LLM_LOW,
            "Local AI translation - Low",
            "Qwen3 0.6B Q4. Fastest contextual local translator for lower-memory devices.",
            485, 3, "Apache-2.0 Qwen3 model; GGUF quantization by bartowski. Fully offline.",
        ),
        ModelPackInfo(
            ModelPackId.LOCAL_LLM_MID,
            "Local AI translation - Mid",
            "Qwen3 1.7B Q4. Better tone and sentence formation with page-dialogue context.",
            1283, 5, "Apache-2.0 Qwen3 model; GGUF quantization by bartowski. Fully offline.",
        ),
        ModelPackInfo(
            ModelPackId.LOCAL_LLM_HIGH,
            "Local AI translation - High",
            "Qwen3 4B Q4. Best local translation quality; intended for devices with at least 7 GB RAM.",
            2498, 7, "Apache-2.0 Qwen3 model; GGUF quantization by bartowski. Fully offline.",
        ),        ModelPackInfo(
            ModelPackId.VLM_OCR_HIGH,
            "Comic AI Vision - High",
            "Qwen2-VL 2B is the sole recognizer for Comic AI Vision and reads crops supplied by the shared dialogue detector.",
            1696, 6, "Apache-2.0 Qwen2-VL model and official ggml-org GGUF projector. Fully offline.",
        ),
    )

    fun rapidPack(script: SourceScript, useV5: Boolean = false) = when (script) {
        SourceScript.JAPANESE -> if (useV5) ModelPackId.RAPID_OCR_V5_JAPANESE else ModelPackId.RAPID_OCR_JAPANESE
        SourceScript.KOREAN -> if (useV5) ModelPackId.RAPID_OCR_V5_KOREAN else ModelPackId.RAPID_OCR_KOREAN
        SourceScript.CHINESE -> if (useV5) ModelPackId.RAPID_OCR_V5_CHINESE else ModelPackId.RAPID_OCR_CHINESE
        SourceScript.LATIN -> if (useV5) ModelPackId.RAPID_OCR_V5_LATIN else ModelPackId.RAPID_OCR_LATIN
    }

    fun info(id: ModelPackId) = packs.first { it.id == id }

    fun directory(context: Context, id: ModelPackId) =
        File(context.filesDir, "model_packs/${id.name.lowercase()}")

    fun isInstalled(context: Context, id: ModelPackId): Boolean {
        val dir = directory(context, id)
        return File(dir, ".complete").isFile && files(id).all { File(dir, it.name).isFile }
    }

    suspend fun download(
        context: Context,
        id: ModelPackId,
        onProgress: (ModelPackProgress) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val destination = directory(context, id)
        destination.mkdirs()
        File(destination, ".complete").delete()
        try {
            val definitions = files(id).map { definition ->
                RemoteMaintenance.modelFileOverride(context, id, definition.name)?.let { override ->
                    definition.copy(url = override.url, sha256 = override.sha256)
                } ?: definition
            }
            definitions.forEachIndexed { index, definition ->
                coroutineContext.ensureActive()
                val target = File(destination, definition.name)
                val partial = File(destination, definition.name + ".part")
                val targetIsValid = target.isFile && definition.sha256?.let { expected ->
                    sha256(target).equals(expected, ignoreCase = true)
                } != false
                if (targetIsValid) {
                    onProgress(
                        ModelPackProgress(
                            id,
                            definition.name,
                            (index + 1L) * 1_000_000L,
                            definitions.size.toLong() * 1_000_000L,
                        ),
                    )
                    return@forEachIndexed
                }
                downloadFile(definition, partial) { downloaded, total ->
                    val completedWeight = index.toLong() * 1_000_000L
                    val currentWeight = if (total > 0L) downloaded * 1_000_000L / total else 0L
                    onProgress(
                        ModelPackProgress(
                            id,
                            definition.name,
                            completedWeight + currentWeight,
                            definitions.size.toLong() * 1_000_000L,
                        ),
                    )
                }
                definition.sha256?.let { expected ->
                    val actual = sha256(partial)
                    if (!actual.equals(expected, ignoreCase = true)) {
                        partial.delete()
                        error("Security check failed for ${definition.name}. Expected $expected but received $actual.")
                    }
                }
                if (target.exists()) target.delete()
                check(partial.renameTo(target)) { "Could not finish ${definition.name}." }
            }
            File(destination, ".complete").writeText("version=3\n")
        } catch (error: Throwable) {
            // Keep valid partial downloads so a cancelled multi-gigabyte pack can resume.
            throw error
        }
    }

    fun delete(context: Context, id: ModelPackId) {
        val root = File(context.filesDir, "model_packs").canonicalFile
        val target = directory(context, id).canonicalFile
        require(target.path.startsWith(root.path + File.separator)) { "Invalid model pack path." }
        target.deleteRecursively()
    }

    private fun downloadFile(
        definition: PackFile,
        partial: File,
        onProgress: (Long, Long) -> Unit,
    ) {
        val resumeAt = partial.takeIf { it.isFile }?.length() ?: 0L
        val connection = URL(definition.url).openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = 20_000
        connection.readTimeout = 45_000
        connection.setRequestProperty("User-Agent", "Untrusted-Translations-Android")
        if (resumeAt > 0L) connection.setRequestProperty("Range", "bytes=$resumeAt-")
        connection.connect()
        val append = resumeAt > 0L && connection.responseCode == HttpURLConnection.HTTP_PARTIAL
        check(connection.responseCode in 200..299) {
            "Download failed (${connection.responseCode}) for ${definition.name}."
        }
        val downloadedBefore = if (append) resumeAt else 0L
        val total = connection.contentLengthLong.takeIf { it > 0L }?.plus(downloadedBefore) ?: -1L
        connection.inputStream.use { input ->
            FileOutputStream(partial, append).buffered().use { output ->
                val buffer = ByteArray(128 * 1024)
                var downloaded = downloadedBefore
                onProgress(downloaded, total)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    downloaded += count
                    onProgress(downloaded, total)
                }
            }
        }
        connection.disconnect()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(128 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun files(id: ModelPackId): List<PackFile> = when (id) {
        ModelPackId.COMIC_DIALOGUE_DETECTOR -> listOf(
            PackFile(
                "comic_dialogue_detector.onnx",
                "$COMIC_DETECTOR_BASE/detector-v4-s_int8.onnx",
                "5fe9e4f576e49d4e7e8b0e029d6d3cdc252abd4694113e1cae120e62c931ea79",
            ),
        )
        ModelPackId.RAPID_OCR_JAPANESE -> rapidFiles(
            "japan_PP-OCRv4_rec_mobile.onnx",
            null,
            "japan_dict.txt",
        )
        ModelPackId.RAPID_OCR_KOREAN -> rapidFiles(
            "korean_PP-OCRv4_rec_mobile.onnx",
            null,
            "korean_dict.txt",
        )
        ModelPackId.RAPID_OCR_CHINESE -> rapidFiles(
            "ch_PP-OCRv4_rec_mobile.onnx",
            null,
            "ppocr_keys_v1.txt",
        )
        ModelPackId.RAPID_OCR_LATIN -> rapidFiles(
            "latin_PP-OCRv3_rec_mobile.onnx",
            null,
            "latin_dict.txt",
        )
        ModelPackId.RAPID_OCR_V5_JAPANESE -> listOf(
            PackFile("det.onnx", "$HF_BASE/detection/v5/det.onnx"),
            PackFile("rec.onnx", "$RAPID_BASE/onnx/PP-OCRv4/rec/japan_PP-OCRv4_rec_mobile.onnx"),
            PackFile("keys.txt", "$RAPID_BASE/paddle/PP-OCRv4/rec/japan_PP-OCRv4_rec_mobile/japan_dict.txt"),
        )
        ModelPackId.RAPID_OCR_V5_KOREAN -> listOf(
            PackFile("det.onnx", "$HF_BASE/detection/v5/det.onnx"),
            PackFile("rec.onnx", "$HF_BASE/languages/korean/rec.onnx"),
            PackFile("keys.txt", "$HF_BASE/languages/korean/dict.txt"),
        )
        ModelPackId.RAPID_OCR_V5_CHINESE -> listOf(
            PackFile("det.onnx", "$HF_BASE/detection/v5/det.onnx"),
            PackFile("rec.onnx", "$HF_BASE/languages/chinese/rec.onnx"),
            PackFile("keys.txt", "$HF_BASE/languages/chinese/dict.txt"),
        )
        ModelPackId.RAPID_OCR_V5_LATIN -> listOf(
            PackFile("det.onnx", "$HF_BASE/detection/v5/det.onnx"),
            PackFile("rec.onnx", "$HF_BASE/languages/latin/rec.onnx"),
            PackFile("keys.txt", "$HF_BASE/languages/latin/dict.txt"),
        )
        ModelPackId.BABERU_OCR_MULTILINGUAL -> listOf(
            PackFile("vision_int4.onnx", "$BABERU_BASE/onnx/vision_int4.onnx"),
            PackFile("decoder_prefill_int8.onnx", "$BABERU_BASE/onnx/decoder_prefill_int8.onnx"),
            PackFile("decoder_step_int8.onnx", "$BABERU_BASE/onnx/decoder_step_int8.onnx"),
            PackFile("config.json", "$BABERU_BASE/config.json"),
            PackFile("generation_config.json", "$BABERU_BASE/generation_config.json"),
            PackFile("vocab.json", "$BABERU_BASE/tokenizer/vocab.json"),
            PackFile("tokenizer_config.json", "$BABERU_BASE/tokenizer/tokenizer_config.json"),
        )
        ModelPackId.MANGA_OCR_JAPANESE -> listOf(
            PackFile(
                "encoder_model_int8.onnx",
                "$ASSETS_BASE/manga-ocr-2025-encoder-int8.onnx",
                "51ecce0762d4d27809f6866392729bcb74935bdd3c315f2f8f7c207654f7aac4",
            ),
            PackFile(
                "decoder_model_int8.onnx",
                "$ASSETS_BASE/manga-ocr-2025-decoder-int8.onnx",
                "acf45255addfe6c64840ab331de34d14c102ae990ac72ca6891831dfcd0b00bb",
            ),
            PackFile("config.json", "$MANGA_OCR_BASE/config.json", "cb53957d90b8469961b3a64f9b2ebe472d803bf1308e3505b51916aa4341c547"),
            PackFile("generation_config.json", "$MANGA_OCR_BASE/generation_config.json", "394166c379c675a6b044ee391bfdf7acbb58b7de68541e66e50607cf56576979"),
            PackFile("preprocessor_config.json", "$MANGA_OCR_BASE/preprocessor_config.json", "445c77049d082004aa07593344d5e1f521f1198228bf586196f70ce7ae021414"),
            PackFile("vocab.txt", "$MANGA_OCR_BASE/vocab.txt", "344fbb6b8bf18c57839e924e2c9365434697e0227fac00b88bb4899b78aa594d"),
        )
        ModelPackId.NLLB_TRANSLATION -> listOf(
            PackFile("NLLB_cache_initializer.onnx", "$NLLB_BASE/NLLB_cache_initializer.onnx"),
            PackFile("NLLB_decoder.onnx", "$NLLB_BASE/NLLB_decoder.onnx"),
            PackFile("NLLB_embed_and_lm_head.onnx", "$NLLB_BASE/NLLB_embed_and_lm_head.onnx"),
            PackFile("NLLB_encoder.onnx", "$NLLB_BASE/NLLB_encoder.onnx"),
            PackFile(
                "sentencepiece_bpe.model",
                "https://raw.githubusercontent.com/niedev/RTranslator/e1cd028ac73b072c84773b259e48d841ca8f87d1/app/src/main/assets/sentencepiece_bpe.model",
            ),
        )
        ModelPackId.LOCAL_LLM_LOW -> listOf(
            PackFile(
                "model.gguf",
                "$LOCAL_LLM_LOW_BASE/Qwen_Qwen3-0.6B-Q4_K_M.gguf",
                "9acfc1e001311f34b4252001b626f2e466d592a42065f66571bff3790d4e1b14",
            ),
        )
        ModelPackId.LOCAL_LLM_MID -> listOf(
            PackFile(
                "model.gguf",
                "$LOCAL_LLM_MID_BASE/Qwen_Qwen3-1.7B-Q4_K_M.gguf",
                "72c5c3cb38fa32d5256e2fe30d03e7a64c6c79e668ad84057e3bd66e250b24fb",
            ),
        )
        ModelPackId.LOCAL_LLM_HIGH -> listOf(
            PackFile(
                "model.gguf",
                "$LOCAL_LLM_HIGH_BASE/Qwen_Qwen3-4B-Q4_K_M.gguf",
                "fbe1d5edd4ce802ae3ae7c7e4ab7d09789d697fdac1fc7929f8df4ca3c41bae3",
            ),
        )
        ModelPackId.VLM_OCR_HIGH -> listOf(
            PackFile(
                "model.gguf",
                "$VLM_OCR_BASE/Qwen2-VL-2B-Instruct-Q4_K_M.gguf",
                "5745685d2e607a82a0696c1118e56a2a1ae0901da450fd9cd4f161c6b62867d7",
            ),
            PackFile(
                "mmproj.gguf",
                "$VLM_OCR_BASE/mmproj-Qwen2-VL-2B-Instruct-Q8_0.gguf",
                "a0ad91f00a7a80dcf84d719a61b00ee2e07b71794f4ee2dfa81a254621a8c418",
            ),
        )
    }

    private fun rapidFiles(recognition: String, recognitionHash: String?, dictionary: String) = listOf(
        PackFile(
            "det.onnx",
            "$RAPID_BASE/onnx/PP-OCRv4/det/multi_PP-OCRv3_det_mobile.onnx",
        ),
        PackFile(
            "cls.onnx",
            "$RAPID_BASE/onnx/PP-OCRv4/cls/ch_ppocr_mobile_v2.0_cls_mobile.onnx",
        ),
        PackFile("rec.onnx", "$RAPID_BASE/onnx/PP-OCRv4/rec/$recognition", recognitionHash),
        PackFile(
            "keys.txt",
            "$RAPID_BASE/paddle/PP-OCRv4/rec/${recognition.removeSuffix(".onnx")}/$dictionary",
        ),
    )
}
