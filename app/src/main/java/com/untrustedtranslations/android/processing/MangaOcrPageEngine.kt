package com.untrustedtranslations.android.processing

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.untrustedtranslations.android.model.ComicPage
import com.untrustedtranslations.android.model.RelativeBounds
import com.untrustedtranslations.android.model.SourceScript
import com.untrustedtranslations.android.model.TextBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
    private const val RECOGNITION_BATCH_SIZE = 4

    private data class Reading(val text: String, val confidence: Float)
    private data class RegionCrop(val region: ComicDialogueDetector.Region, val crop: Bitmap)

    private val vocabularyCache = AtomicReference<Pair<String, List<String>>?>(null)
    @Volatile private var batchedDecoderSupported: Boolean? = null

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
        val totalStarted = System.nanoTime()
        val bitmap = context.contentResolver.openInputStream(page.originalSource)
            ?.use(BitmapFactory::decodeStream)
            ?: error("Cannot open page.")
        try {
            val detectorStarted = System.nanoTime()
            val regions = ComicDialogueDetector.detect(
                "shared_comic_dialogue_detector",
                java.io.File(detectorDirectory, "comic_dialogue_detector.onnx"),
                bitmap,
                minimumScore = if (deepScan) .22f else .35f,
                pageKey = page.originalSource.toString(),
            )
            val detectorMs = elapsedMs(detectorStarted)
            if (regions.isEmpty()) {
                Log.i("MangaOCR", "regions=0 detector=${detectorMs}ms total=${elapsedMs(totalStarted)}ms")
                return@withContext emptyList()
            }

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

            val crops = regions.map { region ->
                val recognitionRect = paddedCrop(region.rect, bitmap.width, bitmap.height)
                RegionCrop(
                    region,
                    Bitmap.createBitmap(
                        bitmap,
                        recognitionRect.left,
                        recognitionRect.top,
                        recognitionRect.width(),
                        recognitionRect.height(),
                    ),
                )
            }

            val recognitionStarted = System.nanoTime()
            val readings = try {
                recognizeMany(environment, encoder, decoder, crops.map { it.crop }, vocabulary)
            } finally {
                crops.forEach { it.crop.recycle() }
            }
            val recognitionMs = elapsedMs(recognitionStarted)
            val styleStarted = System.nanoTime()

            val blocks = crops.indices.mapNotNull { index ->
                val reading = readings[index]
                if (reading.text.isBlank() || reading.confidence < .18f) return@mapNotNull null
                val region = crops[index].region
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
            val sorted = ReadingOrder.sort(blocks, script)
            Log.i(
                "MangaOCR",
                "regions=${regions.size} kept=${sorted.size} detector=${detectorMs}ms " +
                    "recognize=${recognitionMs}ms style=${elapsedMs(styleStarted)}ms total=${elapsedMs(totalStarted)}ms " +
                    "batch=${batchedDecoderSupported != false}",
            )
            sorted
        } finally {
            bitmap.recycle()
        }
    }

    private fun recognizeMany(
        environment: OrtEnvironment,
        encoder: OrtSession,
        decoder: OrtSession,
        bitmaps: List<Bitmap>,
        vocabulary: List<String>,
    ): List<Reading> {
        if (bitmaps.isEmpty()) return emptyList()
        if (batchedDecoderSupported == false) {
            return bitmaps.map { recognizeBatch(environment, encoder, decoder, listOf(it), vocabulary).single() }
        }

        val output = ArrayList<Reading>(bitmaps.size)
        bitmaps.chunked(RECOGNITION_BATCH_SIZE).forEach { chunk ->
            if (chunk.size == 1) {
                output += recognizeBatch(environment, encoder, decoder, chunk, vocabulary)
                return@forEach
            }
            try {
                output += recognizeBatch(environment, encoder, decoder, chunk, vocabulary)
                batchedDecoderSupported = true
            } catch (batchFailure: Exception) {
                // Some third-party ONNX exports hard-code batch=1. Keep compatibility with those
                // packs while using batched decoding automatically whenever the model supports it.
                batchedDecoderSupported = false
                chunk.forEach { bitmap ->
                    output += recognizeBatch(environment, encoder, decoder, listOf(bitmap), vocabulary)
                }
            }
        }
        return output
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

    /**
     * Runs several speech bubbles through one encoder/decoder batch. The old implementation
     * launched one decoder Run per bubble per token and converted every 3-D logits result into
     * nested Java arrays. That conversion is particularly expensive on Android. This path keeps
     * inference in flat NIO buffers and reads only the final time-step logits needed for greedy
     * generation.
     */
    private fun recognizeBatch(
        environment: OrtEnvironment,
        encoder: OrtSession,
        decoder: OrtSession,
        bitmaps: List<Bitmap>,
        vocabulary: List<String>,
    ): List<Reading> {
        require(bitmaps.isNotEmpty())
        val batchSize = bitmaps.size
        val pixelCountPerImage = 3 * 224 * 224
        val pixelBuffer = directFloatBuffer(batchSize * pixelCountPerImage)

        bitmaps.forEach { bitmap ->
            val resized = Bitmap.createScaledBitmap(bitmap, 224, 224, true)
            try {
                appendRgbImageTensor(resized, pixelBuffer)
            } finally {
                if (resized !== bitmap) resized.recycle()
            }
        }
        pixelBuffer.rewind()

        var finalReadings: List<Reading>? = null
        OnnxTensor.createTensor(
            environment,
            pixelBuffer,
            longArrayOf(batchSize.toLong(), 3, 224, 224),
        ).use { imageTensor ->
            encoder.run(mapOf("pixel_values" to imageTensor)).use { encoderResult ->
                val hidden = encoderResult.get("last_hidden_state").orElse(encoderResult[0]) as OnnxTensor
                val generated = Array(batchSize) { LongArray(MAX_TOKENS + 1).apply { this[0] = START_TOKEN } }
                val generatedSizes = IntArray(batchSize) { 1 }
                val seenTrigrams = Array(batchSize) { HashMap<Long, MutableSet<Long>>() }
                val probabilitySums = FloatArray(batchSize)
                val probabilityCounts = IntArray(batchSize)
                val done = BooleanArray(batchSize)

                for (step in 0 until MAX_TOKENS) {
                    if (done.all { it }) break
                    val sequenceLength = step + 1
                    val idBuffer = directLongBuffer(batchSize * sequenceLength)
                    for (batch in 0 until batchSize) {
                        while (done[batch] && generatedSizes[batch] < sequenceLength) {
                            generated[batch][generatedSizes[batch]++] = END_TOKEN
                        }
                        for (position in 0 until sequenceLength) {
                            idBuffer.put(generated[batch][position])
                        }
                    }
                    idBuffer.rewind()

                    OnnxTensor.createTensor(
                        environment,
                        idBuffer,
                        longArrayOf(batchSize.toLong(), sequenceLength.toLong()),
                    ).use { idTensor ->
                        decoder.run(
                            mapOf(
                                "input_ids" to idTensor,
                                "encoder_hidden_states" to hidden,
                            ),
                        ).use { decoderResult ->
                            val logitsTensor = decoderResult.get("logits").orElse(decoderResult[0]) as OnnxTensor
                            val shape = logitsTensor.info.shape
                            require(shape.size == 3) { "Unsupported Manga-OCR logits shape." }
                            val outputBatch = shape[0].toInt()
                            val outputSequence = shape[1].toInt()
                            val vocabularySize = shape[2].toInt()
                            require(outputBatch == batchSize && outputSequence >= 1 && vocabularySize > 0) {
                                "Unsupported Manga-OCR decoder output dimensions."
                            }
                            val logits = logitsTensor.floatBuffer
                                ?: error("Manga-OCR decoder logits are not floating point.")
                            val lastPosition = outputSequence - 1

                            for (batch in 0 until batchSize) {
                                if (done[batch]) continue
                                val offset = ((batch * outputSequence + lastPosition) * vocabularySize)
                                val choice = chooseToken(
                                    logits,
                                    offset,
                                    vocabularySize,
                                    generated[batch],
                                    generatedSizes[batch],
                                    seenTrigrams[batch],
                                )
                                if (choice.index.toLong() == END_TOKEN) {
                                    generated[batch][generatedSizes[batch]++] = END_TOKEN
                                    done[batch] = true
                                    continue
                                }

                                generated[batch][generatedSizes[batch]++] = choice.index.toLong()
                                if (generatedSizes[batch] >= 3) {
                                    val first = generated[batch][generatedSizes[batch] - 3]
                                    val second = generated[batch][generatedSizes[batch] - 2]
                                    val third = generated[batch][generatedSizes[batch] - 1]
                                    seenTrigrams[batch].getOrPut(pairKey(first, second)) { HashSet() }.add(third)
                                }
                                probabilitySums[batch] += choice.probability
                                probabilityCounts[batch]++
                            }
                        }
                    }
                }

                finalReadings = List(batchSize) { batch ->
                    val endExclusive = generatedSizes[batch].let { size ->
                        if (size > 1 && generated[batch][size - 1] == END_TOKEN) size - 1 else size
                    }
                    Reading(
                        decode(generated[batch], 1, endExclusive, vocabulary),
                        if (probabilityCounts[batch] == 0) 0f
                        else probabilitySums[batch] / probabilityCounts[batch],
                    )
                }
            }
        }
        return finalReadings ?: error("Manga-OCR batch did not produce a result.")
    }

    private data class TokenChoice(val index: Int, val probability: Float)

    private fun chooseToken(
        logits: FloatBuffer,
        offset: Int,
        vocabularySize: Int,
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
        for (candidate in 0 until vocabularySize) {
            val value = logits.get(offset + candidate).toDouble()
            if (value > softmaxMax) {
                softmaxSum = if (softmaxMax.isFinite()) {
                    softmaxSum * exp(softmaxMax - value) + 1.0
                } else 1.0
                softmaxMax = value
            } else {
                softmaxSum += exp(value - softmaxMax)
            }
            if (candidate == 0 || banned?.contains(candidate.toLong()) == true) continue
            if (best == 0 || value > logits.get(offset + best)) best = candidate
        }
        val bestLogit = if (best == 0) Double.NEGATIVE_INFINITY else logits.get(offset + best).toDouble()
        val probability = if (best == 0 || softmaxSum <= 0.0) 0f else
            (exp(bestLogit - softmaxMax) / softmaxSum).toFloat()
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

    private fun appendRgbImageTensor(bitmap: Bitmap, output: FloatBuffer) {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        pixels.forEach { color -> output.put(((color ushr 16 and 255) / 255f - .5f) / .5f) }
        pixels.forEach { color -> output.put(((color ushr 8 and 255) / 255f - .5f) / .5f) }
        pixels.forEach { color -> output.put(((color and 255) / 255f - .5f) / .5f) }
    }

    private fun directFloatBuffer(elements: Int): FloatBuffer =
        ByteBuffer.allocateDirect(elements * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()

    private fun directLongBuffer(elements: Int): LongBuffer =
        ByteBuffer.allocateDirect(elements * Long.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
            .asLongBuffer()

    private fun elapsedMs(started: Long): Long = (System.nanoTime() - started) / 1_000_000L
}
