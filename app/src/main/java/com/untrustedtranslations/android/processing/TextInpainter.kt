package com.untrustedtranslations.android.processing

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import java.util.ArrayDeque
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/** Removes high-contrast glyph pixels while retaining as much surrounding artwork as possible. */
object TextInpainter {
    fun erase(bitmap: Bitmap, bounds: RectF) {
        val margin = (bounds.width().coerceAtMost(bounds.height()) * .08f).coerceIn(4f, 18f)
        val left = floor(bounds.left - margin).toInt().coerceIn(0, bitmap.width - 1)
        val top = floor(bounds.top - margin).toInt().coerceIn(0, bitmap.height - 1)
        val right = ceil(bounds.right + margin).toInt().coerceIn(left + 1, bitmap.width)
        val bottom = ceil(bounds.bottom + margin).toInt().coerceIn(top + 1, bitmap.height)
        val width = right - left
        val height = bottom - top
        if (width < 3 || height < 3) return

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, left, top, width, height)
        val reference = borderMedian(pixels, width, height)
        val dispersion = borderDispersion(pixels, width, height, reference)
        val threshold = (28f + dispersion * 1.35f).coerceIn(28f, 105f)

        val coreLeft = floor(bounds.left).toInt().minus(left).coerceIn(1, width - 2)
        val coreTop = floor(bounds.top).toInt().minus(top).coerceIn(1, height - 2)
        val coreRight = ceil(bounds.right).toInt().minus(left).coerceIn(coreLeft + 1, width - 1)
        val coreBottom = ceil(bounds.bottom).toInt().minus(top).coerceIn(coreTop + 1, height - 1)
        val mask = BooleanArray(pixels.size)
        for (y in coreTop until coreBottom) {
            for (x in coreLeft until coreRight) {
                val index = y * width + x
                mask[index] = colorDistance(pixels[index], reference) >= threshold
            }
        }

        dilate(mask, width, height, radius = if (bounds.width().coerceAtMost(bounds.height()) > 180f) 2 else 1)
        if (mask.none { it }) return
        propagateSurroundings(pixels, mask, width, height, reference)
        bitmap.setPixels(pixels, 0, width, left, top, width, height)
    }

    private fun propagateSurroundings(
        pixels: IntArray,
        mask: BooleanArray,
        width: Int,
        height: Int,
        fallback: Int,
    ) {
        val queue = ArrayDeque<Int>()
        val queued = BooleanArray(mask.size)
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val index = y * width + x
                if (mask[index] && hasKnownNeighbor(mask, index, width)) {
                    queue.addLast(index)
                    queued[index] = true
                }
            }
        }
        while (queue.isNotEmpty()) {
            val index = queue.removeFirst()
            if (!mask[index]) continue
            pixels[index] = neighborAverage(pixels, mask, index, width, fallback)
            mask[index] = false
            val x = index % width
            val y = index / width
            for (dy in -1..1) for (dx in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = x + dx
                val ny = y + dy
                if (nx !in 1 until width - 1 || ny !in 1 until height - 1) continue
                val neighbor = ny * width + nx
                if (mask[neighbor] && !queued[neighbor]) {
                    queued[neighbor] = true
                    queue.addLast(neighbor)
                }
            }
        }
        mask.indices.filter { mask[it] }.forEach { pixels[it] = fallback }
    }

    private fun neighborAverage(
        pixels: IntArray,
        mask: BooleanArray,
        index: Int,
        width: Int,
        fallback: Int,
    ): Int {
        val x = index % width
        val y = index / width
        var red = 0
        var green = 0
        var blue = 0
        var count = 0
        for (dy in -1..1) for (dx in -1..1) {
            if (dx == 0 && dy == 0) continue
            val neighbor = (y + dy) * width + (x + dx)
            if (!mask[neighbor]) {
                red += Color.red(pixels[neighbor])
                green += Color.green(pixels[neighbor])
                blue += Color.blue(pixels[neighbor])
                count++
            }
        }
        return if (count == 0) fallback else Color.rgb(red / count, green / count, blue / count)
    }

    private fun hasKnownNeighbor(mask: BooleanArray, index: Int, width: Int): Boolean {
        val offsets = intArrayOf(-width - 1, -width, -width + 1, -1, 1, width - 1, width, width + 1)
        return offsets.any { !mask[index + it] }
    }

    private fun dilate(mask: BooleanArray, width: Int, height: Int, radius: Int) {
        repeat(radius) {
            val source = mask.copyOf()
            for (y in 1 until height - 1) for (x in 1 until width - 1) {
                val index = y * width + x
                if (!source[index]) {
                    mask[index] = source[index - width] || source[index + width] ||
                        source[index - 1] || source[index + 1]
                }
            }
        }
    }

    private fun borderMedian(pixels: IntArray, width: Int, height: Int): Int {
        val samples = borderSamples(pixels, width, height)
        return Color.rgb(
            samples.map(Color::red).sorted()[samples.size / 2],
            samples.map(Color::green).sorted()[samples.size / 2],
            samples.map(Color::blue).sorted()[samples.size / 2],
        )
    }

    private fun borderDispersion(pixels: IntArray, width: Int, height: Int, reference: Int): Float {
        val distances = borderSamples(pixels, width, height).map { colorDistance(it, reference) }.sorted()
        return distances[distances.size / 2]
    }

    private fun borderSamples(pixels: IntArray, width: Int, height: Int): List<Int> {
        val step = (width.coerceAtMost(height) / 24).coerceAtLeast(1)
        val samples = mutableListOf<Int>()
        for (x in 0 until width step step) {
            samples += pixels[x]
            samples += pixels[(height - 1) * width + x]
        }
        for (y in 0 until height step step) {
            samples += pixels[y * width]
            samples += pixels[y * width + width - 1]
        }
        return samples
    }

    private fun colorDistance(first: Int, second: Int): Float {
        val red = Color.red(first) - Color.red(second)
        val green = Color.green(first) - Color.green(second)
        val blue = Color.blue(first) - Color.blue(second)
        return sqrt((red * red + green * green + blue * blue).toFloat())
    }
}
