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
import kotlinx.coroutines.withContext
import java.nio.FloatBuffer
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

internal object RapidOcrPageEngine {
    private data class Detection(val rect: Rect, val score: Float)
    private data class Reading(val text: String, val score: Float)

    suspend fun process(
        context: Context,
        page: ComicPage,
        script: SourceScript,
        pack: ModelPackId,
    ): List<TextBlock> = withContext(Dispatchers.IO) {
        val dir = ModelPackManager.directory(context, pack)
        require(ModelPackManager.isInstalled(context, pack)) { "RapidOCR pack is not installed." }
        val bitmap = context.contentResolver.openInputStream(page.originalSource)?.use(BitmapFactory::decodeStream)
            ?: error("Could not open the comic page.")
        val environment = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(min(4, Runtime.getRuntime().availableProcessors()))
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        environment.createSession(java.io.File(dir, "det.onnx").absolutePath, options).use { detector ->
            environment.createSession(java.io.File(dir, "rec.onnx").absolutePath, options).use { recognizer ->
                val keys = listOf("#") + java.io.File(dir, "keys.txt").readLines() + " "
                detect(environment, detector, bitmap).mapNotNull { detection ->
                    val crop = Bitmap.createBitmap(
                        bitmap,
                        detection.rect.left,
                        detection.rect.top,
                        detection.rect.width(),
                        detection.rect.height(),
                    )
                    val readings = if (detection.rect.height() > detection.rect.width() * 1.35f) {
                        listOf(
                            recognize(environment, recognizer, rotate(crop, 90f), keys),
                            recognize(environment, recognizer, rotate(crop, -90f), keys),
                            recognize(environment, recognizer, crop, keys),
                        )
                    } else listOf(recognize(environment, recognizer, crop, keys))
                    val reading = readings.maxByOrNull { it.score } ?: return@mapNotNull null
                    val text = reading.text.trim()
                    if (reading.score < .38f || text.length < 2 || looksLikeSoundEffect(text, detection.rect)) {
                        return@mapNotNull null
                    }
                    val bounds = RelativeBounds(
                        detection.rect.left.toFloat() / bitmap.width,
                        detection.rect.top.toFloat() / bitmap.height,
                        detection.rect.right.toFloat() / bitmap.width,
                        detection.rect.bottom.toFloat() / bitmap.height,
                    )
                    TextBlock(
                        id = UUID.randomUUID().toString(),
                        originalText = text,
                        translatedText = text,
                        bounds = bounds,
                        eraseBounds = bounds,
                        style = LetteringStyleEstimator.estimate(
                            context, bitmap, detection.rect, text, script, null,
                        ),
                    )
                }.sortedWith(readingOrder(script))
            }
        }.also { options.close() }
    }

    private fun detect(env: OrtEnvironment, session: OrtSession, bitmap: Bitmap): List<Detection> {
        val maxSide = 1280f
        val scale = min(1f, maxSide / max(bitmap.width, bitmap.height))
        val width = ceil(bitmap.width * scale / 32f).toInt().coerceAtLeast(32) * 32
        val height = ceil(bitmap.height * scale / 32f).toInt().coerceAtLeast(32) * 32
        val resized = Bitmap.createScaledBitmap(bitmap, width, height, true)
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
            val expandX = (componentWidth * .18f).roundToInt().coerceAtLeast(2)
            val expandY = (componentHeight * .18f).roundToInt().coerceAtLeast(2)
            val left = ((minX - expandX).coerceAtLeast(0) * sourceWidth / width.toFloat()).roundToInt()
            val top = ((minY - expandY).coerceAtLeast(0) * sourceHeight / height.toFloat()).roundToInt()
            val right = (((maxX + expandX + 1).coerceAtMost(width)) * sourceWidth / width.toFloat()).roundToInt()
            val bottom = (((maxY + expandY + 1).coerceAtMost(height)) * sourceHeight / height.toFloat()).roundToInt()
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
        val width = (bitmap.width * height.toFloat() / bitmap.height).roundToInt().coerceIn(16, 960)
        val resized = Bitmap.createScaledBitmap(bitmap, width, height, true)
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
    }

    private fun bitmapTensor(bitmap: Bitmap, means: FloatArray, norms: FloatArray): FloatArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val plane = pixels.size
        return FloatArray(plane * 3).also { output ->
            pixels.forEachIndexed { index, color ->
                val channels = intArrayOf(color and 255, color shr 8 and 255, color shr 16 and 255)
                for (channel in 0..2) output[channel * plane + index] =
                    (channels[channel] - means[channel]) * norms[channel]
            }
        }
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

    private fun looksLikeSoundEffect(text: String, rect: Rect): Boolean {
        val compact = text.filter { it.isLetterOrDigit() }
        if (compact.length < 2) return true
        val punctuation = text.count { !it.isLetterOrDigit() && !it.isWhitespace() }
        val repeated = compact.groupingBy { it }.eachCount().values.maxOrNull() ?: 0
        return punctuation > compact.length ||
            (compact.length <= 5 && repeated >= compact.length - 1) ||
            (compact.length <= 4 && rect.width() > rect.height() * 2.8f)
    }

    private fun readingOrder(script: SourceScript) = Comparator<TextBlock> { first, second ->
        val vertical = script == SourceScript.JAPANESE &&
            (first.bounds.bottom - first.bounds.top) > (first.bounds.right - first.bounds.left)
        if (vertical && kotlin.math.abs(first.bounds.top - second.bounds.top) < .12f) {
            second.bounds.left.compareTo(first.bounds.left)
        } else {
            val row = first.bounds.top.compareTo(second.bounds.top)
            if (row != 0) row else first.bounds.left.compareTo(second.bounds.left)
        }
    }
}
