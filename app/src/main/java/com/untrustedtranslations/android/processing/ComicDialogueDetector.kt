package com.untrustedtranslations.android.processing

import ai.onnxruntime.OnnxTensor
import android.graphics.Bitmap
import android.graphics.Rect
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/** Detects speech-bubble text while deliberately excluding free text and sound effects. */
internal object ComicDialogueDetector {
    private const val INPUT_SIZE = 640
    private const val TEXT_BUBBLE_LABEL = 1L
    private const val MIN_SCORE = .35f

    data class Region(val rect: Rect, val confidence: Float)

    fun detect(cacheKey: String, model: File, bitmap: Bitmap): List<Region> {
        val environment = OnnxSessionCache.environment
        val session = OnnxSessionCache.getOrCreate(cacheKey, model)
        require("images" in session.inputNames && "orig_target_sizes" in session.inputNames) {
            "Unsupported comic dialogue detector inputs."
        }
        val resized = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        try {
            val imageValues = rgbTensor(resized)
            // This exported RT-DETR model expects width first and height second.
            val originalSize = longArrayOf(bitmap.width.toLong(), bitmap.height.toLong())
            OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(imageValues),
                longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong()),
            ).use { imageTensor ->
                OnnxTensor.createTensor(
                    environment,
                    LongBuffer.wrap(originalSize),
                    longArrayOf(1, 2),
                ).use { sizeTensor ->
                    session.run(
                        mapOf("images" to imageTensor, "orig_target_sizes" to sizeTensor),
                    ).use { result ->
                        @Suppress("UNCHECKED_CAST")
                        val labels = (result.get("labels").orElse(result[0]) as OnnxTensor).value
                            as Array<LongArray>
                        @Suppress("UNCHECKED_CAST")
                        val boxes = (result.get("boxes").orElse(result[1]) as OnnxTensor).value
                            as Array<Array<FloatArray>>
                        @Suppress("UNCHECKED_CAST")
                        val scores = (result.get("scores").orElse(result[2]) as OnnxTensor).value
                            as Array<FloatArray>
                        return labels[0].indices.mapNotNull { index ->
                            if (labels[0][index] != TEXT_BUBBLE_LABEL || scores[0][index] < MIN_SCORE) {
                                return@mapNotNull null
                            }
                            val box = boxes[0][index]
                            val left = box[0].toInt().coerceIn(0, bitmap.width - 1)
                            val top = box[1].toInt().coerceIn(0, bitmap.height - 1)
                            val right = box[2].toInt().coerceIn(left + 1, bitmap.width)
                            val bottom = box[3].toInt().coerceIn(top + 1, bitmap.height)
                            if (right - left < 3 || bottom - top < 3) null
                            else Region(Rect(left, top, right, bottom), scores[0][index])
                        }.suppressOverlaps()
                    }
                }
            }
        } finally {
            if (resized !== bitmap) resized.recycle()
        }
    }

    private fun rgbTensor(bitmap: Bitmap): FloatArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val plane = pixels.size
        return FloatArray(plane * 3).also { output ->
            pixels.forEachIndexed { index, color ->
                output[index] = (color ushr 16 and 255) / 255f
                output[plane + index] = (color ushr 8 and 255) / 255f
                output[plane * 2 + index] = (color and 255) / 255f
            }
        }
    }

    private fun List<Region>.suppressOverlaps(): List<Region> {
        val kept = mutableListOf<Region>()
        sortedByDescending { it.confidence }.forEach { candidate ->
            if (kept.none { intersectionOverUnion(candidate.rect, it.rect) > .55f }) kept += candidate
        }
        return kept
    }

    private fun intersectionOverUnion(a: Rect, b: Rect): Float {
        val intersectionWidth = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0)
        val intersectionHeight = (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0)
        val intersection = intersectionWidth.toLong() * intersectionHeight
        val union = a.width().toLong() * a.height() + b.width().toLong() * b.height() - intersection
        return if (union <= 0L) 0f else intersection.toFloat() / union
    }
}
