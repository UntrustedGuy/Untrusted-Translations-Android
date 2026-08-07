package com.untrustedtranslations.android.processing

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import com.untrustedtranslations.android.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal object RapidOcrPageEngine {
    private data class Detection(val rect: Rect, val score: Float)
    private data class Reading(val text: String, val score: Float)

    private val keysCache = ConcurrentHashMap<String, List<String>>()

    private fun keysFor(pack: ModelPackId, dir: java.io.File): List<String> =
        keysCache.getOrPut(pack.name) {
            listOf("#") + java.io.File(dir, "keys.txt").readLines() + " "
        }

    /**
     * Recognizes a crop, running the angle classifier first, and only trying rotated
     * variants for tall crops when the upright reading is not already confident.
     */
    private fun readBest(
        env: OrtEnvironment,
        recognizer: OrtSession,
        classifier: OrtSession?,
        crop: Bitmap,
        keys: List<String>,
    ): Reading {
        fun readOrientation(candidate: Bitmap): Reading {
            val base = recognize(env, recognizer, candidate, keys)
            // Most comic crops are already upright. Only pay for the angle classifier and
            // a second recognition pass when the first reading is weak.
            if (classifier == null || base.score >= .72f || !shouldFlip(env, classifier, candidate)) {
                return base
            }
            val flipped = rotate(candidate, 180f)
            return try {
                maxOf(base, recognize(env, recognizer, flipped, keys), compareBy { it.score })
            } finally {
                flipped.recycle()
            }
        }

        val base = readOrientation(crop)
        if (crop.height <= crop.width * 1.35f || base.score >= .78f) return base

        val clockwise = rotate(crop, 90f)
        val firstRotated = try { readOrientation(clockwise) } finally { clockwise.recycle() }
        var best = if (firstRotated.score > base.score) firstRotated else base
        // Avoid the third orientation when the first rotated reading is already clear.
        if (best.score >= .74f || best.score >= base.score + .12f) return best

        val counterClockwise = rotate(crop, -90f)
        val secondRotated = try { readOrientation(counterClockwise) } finally { counterClockwise.recycle() }
        if (secondRotated.score > best.score) best = secondRotated
        return best
    }

    suspend fun process(
        context: Context,
        page: ComicPage,
        script: SourceScript,
        pack: ModelPackId,
        deepScan: Boolean = false,
    ): List<TextBlock> = withContext(Dispatchers.IO) {
        val dir = ModelPackManager.directory(context, pack)
        require(ModelPackManager.isInstalled(context, pack)) { "RapidOCR pack is not installed." }
        val bitmap = context.contentResolver.openInputStream(page.originalSource)?.use(BitmapFactory::decodeStream)
            ?: error("Could not open the comic page.")
        val environment = OnnxSessionCache.environment
        val dialogueDetectorPack = ModelPackId.COMIC_DIALOGUE_DETECTOR
        require(ModelPackManager.isInstalled(context, dialogueDetectorPack)) {
            "Comic dialogue detector is not installed."
        }
        val dialogueDetector = java.io.File(
            ModelPackManager.directory(context, dialogueDetectorPack),
            "comic_dialogue_detector.onnx",
        )
        val recognizer = OnnxSessionCache.getOrCreate("${pack.name}_rec", java.io.File(dir, "rec.onnx"))
        val classifierFile = java.io.File(dir, "cls.onnx")
        val classifier = if (classifierFile.isFile) {
            OnnxSessionCache.getOrCreate("${pack.name}_cls", classifierFile)
        } else null
        val keys = keysFor(pack, dir)
        // The shared comic detector already finds dialogue regions and is required by every
        // OCR provider. Using those regions directly avoids a second full-page DBNet pass and
        // avoids recognizing sound effects that would be discarded immediately afterwards.
        val regions = ComicDialogueDetector.detect(
            cacheKey = "shared_comic_dialogue_detector",
            model = dialogueDetector,
            bitmap = bitmap,
            minimumScore = if (deepScan) .22f else .35f,
            pageKey = page.originalSource.toString(),
        )
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
                            readBest(environment, recognizer, classifier, crop, keys)
                        } finally {
                            crop.recycle()
                        }
                        val text = reading.text.trim()
                        if (reading.score < .38f || text.isBlank()) return@withPermit null
                        val bounds = RelativeBounds(
                            region.rect.left.toFloat() / bitmap.width,
                            region.rect.top.toFloat() / bitmap.height,
                            region.rect.right.toFloat() / bitmap.width,
                            region.rect.bottom.toFloat() / bitmap.height,
                        )
                        TextBlock(
                            id = UUID.randomUUID().toString(),
                            originalText = text,
                            translatedText = text,
                            bounds = bounds,
                            eraseBounds = bounds,
                            style = LetteringStyleEstimator.estimate(
                                context, bitmap, region.rect, text, script, null,
                            ),
                        )
                    }
                }
            }.awaitAll().filterNotNull()
        }
        bitmap.recycle()
        ReadingOrder.sort(blocks, script)
    }

    private fun paddedCrop(rect: Rect, pageWidth: Int, pageHeight: Int): Rect {
        val horizontalPadding = maxOf(10, rect.width() / 3)
        val verticalPadding = maxOf(8, rect.height() / 10)
        return Rect(
            (rect.left - horizontalPadding).coerceAtLeast(0),
            (rect.top - verticalPadding).coerceAtLeast(0),
            (rect.right + horizontalPadding).coerceAtMost(pageWidth),
            (rect.bottom + verticalPadding).coerceAtMost(pageHeight),
        )
    }

    data class CropReading(val text: String, val confidence: Float)

    /** Recognizes a dialogue crop supplied by the comic-aware detector. */
    fun recognizeComicCrop(context: Context, pack: ModelPackId, bitmap: Bitmap): CropReading {
        val directory = ModelPackManager.directory(context, pack)
        val environment = OnnxSessionCache.environment
        val recognizer = OnnxSessionCache.getOrCreate(
            "${pack.name}_rec",
            java.io.File(directory, "rec.onnx"),
        )
        val classifierFile = java.io.File(directory, "cls.onnx")
        val classifier = classifierFile.takeIf { it.isFile }?.let {
            OnnxSessionCache.getOrCreate("${pack.name}_cls", it)
        }
        val keys = keysFor(pack, directory)
        val reading = readBest(environment, recognizer, classifier, bitmap, keys)
        return CropReading(reading.text.trim(), reading.score)
    }
    private fun shouldFlip(env: OrtEnvironment, session: OrtSession, bitmap: Bitmap): Boolean {
        val resized = Bitmap.createScaledBitmap(bitmap, 192, 48, true)
        try {
            val data = bitmapTensor(
                resized,
                floatArrayOf(127.5f, 127.5f, 127.5f),
                floatArrayOf(1f / 127.5f, 1f / 127.5f, 1f / 127.5f),
            )
            OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, 3, 48, 192)).use { input ->
                session.run(mapOf(session.inputNames.first() to input)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val output = result[0].value as Array<FloatArray>
                    val logits = output[0]
                    if (logits.size < 2) return false
                    return logits[1] > logits[0] && logits[1] > .9f
                }
            }
        } finally {
            if (resized !== bitmap) resized.recycle()
        }
    }

    private fun detect(env: OrtEnvironment, session: OrtSession, bitmap: Bitmap): List<Detection> {
        val maxSide = 960f
        val scale = min(1f, maxSide / max(bitmap.width, bitmap.height))
        val width = ceil(bitmap.width * scale / 32f).toInt().coerceAtLeast(32) * 32
        val height = ceil(bitmap.height * scale / 32f).toInt().coerceAtLeast(32) * 32
        val resized = Bitmap.createScaledBitmap(bitmap, width, height, true)
        try {
            val data = bitmapTensor(
                resized,
                floatArrayOf(0.485f * 255f, 0.456f * 255f, 0.406f * 255f),
                floatArrayOf(1f / 0.229f / 255f, 1f / 0.224f / 255f, 1f / 0.225f / 255f),
            )
            OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, 3, height.toLong(), width.toLong())).use { input ->
                session.run(mapOf(session.inputNames.first() to input)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val output = result[0].value as Array<Array<Array<FloatArray>>>
                    val probabilities = output[0][0]
                    return components(probabilities, bitmap.width, bitmap.height)
                }
            }
        } finally {
            if (resized !== bitmap) resized.recycle()
        }
    }

    private fun components(map: Array<FloatArray>, sourceWidth: Int, sourceHeight: Int): List<Detection> {
        val height = map.size
        val width = map.firstOrNull()?.size ?: return emptyList()
        val active = BooleanArray(width * height)
        for (y in 0 until height) for (x in 0 until width) {
            if (map[y][x] >= .30f) {
                active[y * width + x] = true
                if (x + 1 < width) active[y * width + x + 1] = true
                if (y + 1 < height) active[(y + 1) * width + x] = true
            }
        }
        val visited = BooleanArray(active.size)
        val queue = IntArray(active.size)
        val detections = mutableListOf<Detection>()
        for (start in active.indices) {
            if (!active[start] || visited[start]) continue
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var minX = width
            var minY = height
            var maxX = 0
            var maxY = 0
            var pixels = 0
            var scoreSum = 0f
            while (head < tail) {
                val index = queue[head++]
                val x = index % width
                val y = index / width
                minX = min(minX, x); maxX = max(maxX, x)
                minY = min(minY, y); maxY = max(maxY, y)
                scoreSum += map[y][x]; pixels++
                val neighbors = intArrayOf(index - 1, index + 1, index - width, index + width)
                for (next in neighbors) {
                    if (next !in active.indices || visited[next] || !active[next]) continue
                    if ((next == index - 1 || next == index + 1) && next / width != y) continue
                    visited[next] = true
                    queue[tail++] = next
                }
            }
            val componentWidth = maxX - minX + 1
            val componentHeight = maxY - minY + 1
            val score = scoreSum / pixels.coerceAtLeast(1)
            if (pixels < 10 || componentWidth < 4 || componentHeight < 4 || score < .42f) continue
            // DB unclip: distance = area * ratio / perimeter (approximated on the component box)
            val area = pixels.toFloat()
            val perimeter = 2f * (componentWidth + componentHeight)
            val distance = (area * 1.6f / perimeter).roundToInt().coerceIn(2, max(componentWidth, componentHeight))
            val left = ((minX - distance).coerceAtLeast(0) * sourceWidth / width.toFloat()).roundToInt()
            val top = ((minY - distance).coerceAtLeast(0) * sourceHeight / height.toFloat()).roundToInt()
            val right = (((maxX + distance + 1).coerceAtMost(width)) * sourceWidth / width.toFloat()).roundToInt()
            val bottom = (((maxY + distance + 1).coerceAtMost(height)) * sourceHeight / height.toFloat()).roundToInt()
            if (right - left >= 8 && bottom - top >= 8) {
                detections += Detection(Rect(left, top, right, bottom), score)
            }
        }
        return mergeOverlaps(detections)
    }

    private fun recognize(
        env: OrtEnvironment,
        session: OrtSession,
        bitmap: Bitmap,
        keys: List<String>,
    ): Reading {
        val height = 48
        val width = (bitmap.width * height.toFloat() / bitmap.height).roundToInt().coerceIn(16, 640)
        val resized = Bitmap.createScaledBitmap(bitmap, width, height, true)
        try {
            val data = bitmapTensor(
                resized,
                floatArrayOf(127.5f, 127.5f, 127.5f),
                floatArrayOf(1f / 127.5f, 1f / 127.5f, 1f / 127.5f),
            )
            OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, 3, height.toLong(), width.toLong())).use { input ->
                session.run(mapOf(session.inputNames.first() to input)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val output = result[0].value as Array<Array<FloatArray>>
                    var previous = -1
                    var score = 0f
                    var count = 0
                    val text = buildString {
                        output[0].forEach { timestep ->
                            var best = 0
                            for (index in 1 until timestep.size) if (timestep[index] > timestep[best]) best = index
                            if (best > 0 && best < keys.size && best != previous) {
                                append(keys[best])
                                score += timestep[best]
                                count++
                            }
                            previous = best
                        }
                    }
                    return Reading(text, if (count == 0) 0f else score / count)
                }
            }
        } finally {
            if (resized !== bitmap) resized.recycle()
        }
    }

    private fun bitmapTensor(bitmap: Bitmap, means: FloatArray, norms: FloatArray): FloatArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val plane = pixels.size
        val output = FloatArray(plane * 3)
        val mean0 = means[0]; val mean1 = means[1]; val mean2 = means[2]
        val norm0 = norms[0]; val norm1 = norms[1]; val norm2 = norms[2]
        for (index in pixels.indices) {
            val color = pixels[index]
            output[index] = ((color shr 16 and 255) - mean0) * norm0
            output[plane + index] = ((color shr 8 and 255) - mean1) * norm1
            output[plane * 2 + index] = ((color and 255) - mean2) * norm2
        }
        return output
    }

    private fun rotate(bitmap: Bitmap, degrees: Float): Bitmap =
        Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true)

    private fun mergeOverlaps(input: List<Detection>): List<Detection> {
        val result = mutableListOf<Detection>()
        for (candidate in input.sortedByDescending { it.score }) {
            val overlap = result.indexOfFirst { other ->
                val intersection = Rect()
                intersection.setIntersect(candidate.rect, other.rect) &&
                    intersection.width() * intersection.height() >
                    min(candidate.rect.width() * candidate.rect.height(), other.rect.width() * other.rect.height()) * .65f
            }
            if (overlap < 0) result += candidate
        }
        return result
    }

}
