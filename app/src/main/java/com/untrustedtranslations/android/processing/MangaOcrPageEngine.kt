package com.untrustedtranslations.android.processing

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.untrustedtranslations.android.model.ComicPage
import com.untrustedtranslations.android.model.RelativeBounds
import com.untrustedtranslations.android.model.SourceScript
import com.untrustedtranslations.android.model.TextBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.text.Normalizer
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.exp

internal object MangaOcrPageEngine {
    private const val START_TOKEN = 2L
    private const val END_TOKEN = 3L
    private const val MAX_TOKENS = 64

    private data class Reading(val text: String, val confidence: Float)

    private val vocabularyCache = AtomicReference<Pair<String, List<String>>?>(null)

    private fun vocabularyFor(file: java.io.File): List<String> {
        vocabularyCache.get()?.let { (path, lines) -> if (path == file.absolutePath) return lines }
        val lines = file.readLines(Charsets.UTF_8)
        vocabularyCache.set(file.absolutePath to lines)
        return lines
    }

    suspend fun process(
        context: Context,
        page: ComicPage,
        script: SourceScript,
        detectorPack: ModelPackId,
        recognitionPack: ModelPackId,
        deepScan: Boolean = false,
    ): List<TextBlock> = withContext(Dispatchers.IO) {
        require(script == SourceScript.JAPANESE) { "Manga-OCR supports Japanese text only." }
        require(ModelPackManager.isInstalled(context, detectorPack)) { "Comic dialogue detector not installed." }
        require(ModelPackManager.isInstalled(context, recognitionPack)) { "Manga-OCR pack not installed." }
        val detectorDirectory = ModelPackManager.directory(context, detectorPack)
        val directory = ModelPackManager.directory(context, recognitionPack)
        val bitmap = context.contentResolver.openInputStream(page.originalSource)
            ?.use(BitmapFactory::decodeStream)
            ?: error("Cannot open page.")
        try {
            val regions = ComicDialogueDetector.detect(
                "shared_comic_dialogue_detector",
                java.io.File(detectorDirectory, "comic_dialogue_detector.onnx"),
                bitmap,
                minimumScore = if (deepScan) .22f else .35f,
                pageKey = page.originalSource.toString(),
            )
            val environment = OnnxSessionCache.environment
            val encoder = OnnxSessionCache.getOrCreate(
                "${recognitionPack.name}_encoder",
                java.io.File(directory, "encoder_model.onnx"),
            )
            val decoder = OnnxSessionCache.getOrCreate(
                "${recognitionPack.name}_decoder",
                java.io.File(directory, "decoder_model_int8.onnx"),
            )
            validateModelContract(encoder, decoder)
            val vocabulary = vocabularyFor(java.io.File(directory, "vocab.txt"))
            // Autoregressive decoding is the bottleneck; two crops in flight overlaps the
            // encoder of one bubble with the decoder loop of another.
            val parallelism = Semaphore(
                minOf(4, maxOf(2, Runtime.getRuntime().availableProcessors() / 2)),
            )
            val blocks = coroutineScope {
                regions.map { region ->
                    async {
                        parallelism.withPermit {
                            val recognitionRect = paddedCrop(region.rect, bitmap.width, bitmap.height)
                            val crop = Bitmap.createBitmap(
                                bitmap,
                                recognitionRect.left,
                                recognitionRect.top,
                                recognitionRect.width(),
                                recognitionRect.height(),
                            )
                            val reading = try {
                                recognize(environment, encoder, decoder, crop, vocabulary)
                            } finally {
                                crop.recycle()
                            }
                            if (reading.text.isBlank() || reading.confidence < .18f) return@withPermit null
                            val bounds = RelativeBounds(
                                region.rect.left / bitmap.width.toFloat(),
                                region.rect.top / bitmap.height.toFloat(),
                                region.rect.right / bitmap.width.toFloat(),
                                region.rect.bottom / bitmap.height.toFloat(),
                            )
                            TextBlock(
                                id = UUID.randomUUID().toString(),
                                originalText = reading.text,
                                translatedText = reading.text,
                                bounds = bounds,
                                eraseBounds = bounds,
                                style = LetteringStyleEstimator.estimate(
                                    context,
                                    bitmap,
                                    region.rect,
                                    reading.text,
                                    script,
                                    null,
                                ),
                            )
                        }
                    }
                }.awaitAll().filterNotNull()
            }
            // Each region is already one speech-bubble text block. Grouping here could merge
            // adjacent bubbles and heuristic filtering could discard legitimate one-character replies.
            ReadingOrder.sort(blocks, script)
        } finally {
            bitmap.recycle()
        }
    }

    private fun paddedCrop(rect: android.graphics.Rect, pageWidth: Int, pageHeight: Int): android.graphics.Rect {
        val horizontalPadding = maxOf(12, rect.width() / 2)
        val verticalPadding = maxOf(8, rect.height() / 8)
        return android.graphics.Rect(
            (rect.left - horizontalPadding).coerceAtLeast(0),
            (rect.top - verticalPadding).coerceAtLeast(0),
            (rect.right + horizontalPadding).coerceAtMost(pageWidth),
            (rect.bottom + verticalPadding).coerceAtMost(pageHeight),
        )
    }

    private fun validateModelContract(encoder: OrtSession, decoder: OrtSession) {
        require("pixel_values" in encoder.inputNames) { "Unsupported Manga-OCR encoder input." }
        require("input_ids" in decoder.inputNames && "encoder_hidden_states" in decoder.inputNames) {
            "Unsupported Manga-OCR decoder inputs."
        }
    }

    private fun recognize(
        environment: OrtEnvironment,
        encoder: OrtSession,
        decoder: OrtSession,
        bitmap: Bitmap,
        vocabulary: List<String>,
    ): Reading {
        val resized = Bitmap.createScaledBitmap(bitmap, 224, 224, true)
        try {
            val pixels = rgbImageTensor(resized)
            OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(pixels),
                longArrayOf(1, 3, 224, 224),
            ).use { imageTensor ->
                encoder.run(mapOf("pixel_values" to imageTensor)).use { encoderResult ->
                    val hidden = encoderResult.get("last_hidden_state").orElse(encoderResult[0]) as OnnxTensor
                    val generated = LongArray(MAX_TOKENS + 1)
                    generated[0] = START_TOKEN
                    var generatedSize = 1
                    val seenTrigrams = HashMap<Long, MutableSet<Long>>()
                    var probabilitySum = 0f
                    var probabilityCount = 0
                    repeat(MAX_TOKENS) {
                        OnnxTensor.createTensor(
                            environment,
                            LongBuffer.wrap(generated, 0, generatedSize),
                            longArrayOf(1, generatedSize.toLong()),
                        ).use { idTensor ->
                            decoder.run(
                                mapOf(
                                    "input_ids" to idTensor,
                                    "encoder_hidden_states" to hidden,
                                ),
                            ).use { decoderResult ->
                                @Suppress("UNCHECKED_CAST")
                                val logits = decoderResult.get("logits").orElse(decoderResult[0]).value
                                    as Array<Array<FloatArray>>
                                val last = logits[0].last()
                                val choice = chooseToken(last, generated, generatedSize, seenTrigrams)
                                if (choice.index.toLong() == END_TOKEN) {
                                    return Reading(
                                        decode(generated, 1, generatedSize, vocabulary),
                                        if (probabilityCount == 0) 0f else probabilitySum / probabilityCount,
                                    )
                                }
                                generated[generatedSize++] = choice.index.toLong()
                                if (generatedSize >= 3) {
                                    val first = generated[generatedSize - 3]
                                    val second = generated[generatedSize - 2]
                                    val third = generated[generatedSize - 1]
                                    seenTrigrams.getOrPut(pairKey(first, second)) { HashSet() }.add(third)
                                }
                                probabilitySum += choice.probability
                                probabilityCount++
                            }
                        }
                    }
                    return Reading(
                        decode(generated, 1, generatedSize, vocabulary),
                        if (probabilityCount == 0) 0f else probabilitySum / probabilityCount,
                    )
                }
            }
        } finally {
            if (resized !== bitmap) resized.recycle()
        }
    }

    private data class TokenChoice(val index: Int, val probability: Float)

    private fun chooseToken(
        logits: FloatArray,
        generated: LongArray,
        generatedSize: Int,
        seenTrigrams: Map<Long, Set<Long>>,
    ): TokenChoice {
        val banned = if (generatedSize >= 2) {
            seenTrigrams[pairKey(generated[generatedSize - 2], generated[generatedSize - 1])]
        } else null
        var best = 0
        var softmaxMax = Double.NEGATIVE_INFINITY
        var softmaxSum = 0.0
        for (candidate in logits.indices) {
            val value = logits[candidate].toDouble()
            if (value > softmaxMax) {
                softmaxSum = if (softmaxMax.isFinite()) {
                    softmaxSum * exp(softmaxMax - value) + 1.0
                } else 1.0
                softmaxMax = value
            } else {
                softmaxSum += exp(value - softmaxMax)
            }
            if (candidate == 0 || banned?.contains(candidate.toLong()) == true) continue
            if (best == 0 || logits[candidate] > logits[best]) best = candidate
        }
        val probability = if (best == 0 || softmaxSum <= 0.0) 0f else
            (exp(logits[best].toDouble() - softmaxMax) / softmaxSum).toFloat()
        return TokenChoice(best, probability)
    }

    private fun pairKey(first: Long, second: Long): Long =
        ((first and 0xffffffffL) shl 32) xor (second and 0xffffffffL)

    private fun decode(ids: LongArray, from: Int, to: Int, vocabulary: List<String>): String {
        val raw = buildString {
            for (index in from until to) {
                val token = vocabulary.getOrNull(ids[index].toInt()) ?: continue
                if (token.startsWith("[") && token.endsWith("]")) continue
                append(token.removePrefix("##"))
            }
        }
        return Normalizer.normalize(raw, Normalizer.Form.NFKC).trim()
    }

    private fun rgbImageTensor(bitmap: Bitmap): FloatArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val plane = pixels.size
        return FloatArray(plane * 3).also { output ->
            pixels.forEachIndexed { index, color ->
                output[index] = ((color ushr 16 and 255) / 255f - .5f) / .5f
                output[plane + index] = ((color ushr 8 and 255) / 255f - .5f) / .5f
                output[plane * 2 + index] = ((color and 255) / 255f - .5f) / .5f
            }
        }
    }
}
