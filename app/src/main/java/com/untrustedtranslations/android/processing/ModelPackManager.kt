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
    private const val COMIC_DETECTOR_BASE =
        "https://huggingface.co/ogkalu/comic-text-and-bubble-detector/resolve/16e8a622f91fabc6b5b65c96d32d1183f8843546"
    private const val HF_BASE =
        "https://huggingface.co/monkt/paddleocr-onnx/resolve/main"
    private const val NLLB_BASE =
        "https://github.com/niedev/RTranslator/releases/download/2.0.0"

    val packs = listOf(
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
            "Vision-transformer (DEiT encoder + BERT decoder) trained on manga. Reads cropped text regions like Gemini - understands the image, not just OCR glyphs. Japanese only.",
            151, 4, "Apache-2.0 Manga-OCR plus Apache-2.0 comic dialogue detector. May be slow on older devices.",
        ),
        ModelPackInfo(
            ModelPackId.NLLB_TRANSLATION,
            "NLLB high-quality offline translation",
            "RTranslator's optimized NLLB-200 model. Fully offline after download; ARM64 devices only.",
            950, 6, "ARM64 only. RTranslator code is Apache-2.0; NLLB model is non-commercial use only.",
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
            val definitions = files(id)
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
                "comic_dialogue_detector.onnx",
                "$COMIC_DETECTOR_BASE/detector-v4-s_int8.onnx",
                "5fe9e4f576e49d4e7e8b0e029d6d3cdc252abd4694113e1cae120e62c931ea79",
            ),
            PackFile(
                "encoder_model.onnx",
                "$MANGA_OCR_BASE/encoder_model.onnx",
                "f87668ae0f62d6f032dac6b213e8c0fea84cd15895ac8cab624cc9a2f49d4a27",
            ),
            PackFile(
                "decoder_model.onnx",
                "$MANGA_OCR_BASE/decoder_model.onnx",
                "6b1fb216d542c4b2a4fa5b9d7ae3522081eb85fb959d2cecd28055af956a8a5e",
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
