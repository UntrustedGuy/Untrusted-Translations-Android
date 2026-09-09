package com.untrustedtranslations.android.processing

import com.untrustedtranslations.android.model.RelativeBounds
import com.untrustedtranslations.android.model.SourceScript
import com.untrustedtranslations.android.model.TextBlock

/** Groups line-level OCR blocks into bubble-level blocks so translation gets full sentences. */
internal object BlockGrouping {

    fun groupIntoBubbles(blocks: List<TextBlock>, script: SourceScript): List<TextBlock> {
        if (blocks.size < 2) return blocks
        val pool = blocks.toMutableList()
        var changed = true
        while (changed) {
            changed = false
            outer@ for (i in pool.indices) {
                for (j in i + 1 until pool.size) {
                    val merged = tryMerge(pool[i], pool[j], script) ?: continue
                    pool[i] = merged
                    pool.removeAt(j)
                    changed = true
                    break@outer
                }
            }
        }
        return ReadingOrder.sort(pool, script)
    }

    private fun tryMerge(a: TextBlock, b: TextBlock, script: SourceScript): TextBlock? {
        val boxA = a.bounds
        val boxB = b.bounds
        val vertical = script == SourceScript.JAPANESE
        val sameBubble = if (vertical) {
            val overlap = minOf(boxA.bottom, boxB.bottom) - maxOf(boxA.top, boxB.top)
            val smallerHeight = minOf(boxA.bottom - boxA.top, boxB.bottom - boxB.top).coerceAtLeast(1e-4f)
            val gap = if (boxA.left <= boxB.left) boxB.left - boxA.right else boxA.left - boxB.right
            val columnWidth = minOf(boxA.right - boxA.left, boxB.right - boxB.left).coerceAtLeast(1e-4f)
            val heightRatio = maxOf(boxA.bottom - boxA.top, boxB.bottom - boxB.top) / smallerHeight
            val combinedWidth = maxOf(boxA.right, boxB.right) - minOf(boxA.left, boxB.left)
            overlap / smallerHeight > .65f && gap >= -columnWidth * .25f &&
                gap < columnWidth * .55f && heightRatio < 2f &&
                combinedWidth < maxOf(boxA.right - boxA.left, boxB.right - boxB.left) * 2.8f
        } else {
            val overlap = minOf(boxA.right, boxB.right) - maxOf(boxA.left, boxB.left)
            val smallerWidth = minOf(boxA.right - boxA.left, boxB.right - boxB.left).coerceAtLeast(1e-4f)
            val gap = if (boxA.top <= boxB.top) boxB.top - boxA.bottom else boxA.top - boxB.bottom
            val lineHeight = minOf(boxA.bottom - boxA.top, boxB.bottom - boxB.top).coerceAtLeast(1e-4f)
            val widthRatio = maxOf(boxA.right - boxA.left, boxB.right - boxB.left) / smallerWidth
            val combinedHeight = maxOf(boxA.bottom, boxB.bottom) - minOf(boxA.top, boxB.top)
            overlap / smallerWidth > .7f && gap >= -lineHeight * .25f &&
                gap < lineHeight * .45f && widthRatio < 2.5f &&
                combinedHeight < maxOf(boxA.bottom - boxA.top, boxB.bottom - boxB.top) * 2.8f
        }
        if (!sameBubble) return null
        val ordered = ReadingOrder.sort(listOf(a, b), script)
        val separator = if (vertical || script == SourceScript.CHINESE) "" else " "
        val bounds = RelativeBounds(
            minOf(boxA.left, boxB.left), minOf(boxA.top, boxB.top),
            maxOf(boxA.right, boxB.right), maxOf(boxA.bottom, boxB.bottom),
        )
        val eraseBounds = if (a.eraseBounds != null && b.eraseBounds != null) {
            RelativeBounds(
                minOf(a.eraseBounds.left, b.eraseBounds.left), minOf(a.eraseBounds.top, b.eraseBounds.top),
                maxOf(a.eraseBounds.right, b.eraseBounds.right), maxOf(a.eraseBounds.bottom, b.eraseBounds.bottom),
            )
        } else bounds
        return ordered.first().copy(
            originalText = ordered.joinToString(separator) { it.originalText }.trim(),
            translatedText = "",
            bounds = bounds,
            eraseBounds = eraseBounds,
        )
    }

    fun filterDialogue(blocks: List<TextBlock>): List<TextBlock> = blocks.filterNot { block ->
        val compact = block.originalText.filter { it.isLetterOrDigit() }
        val width = block.bounds.right - block.bounds.left
        val height = block.bounds.bottom - block.bounds.top
        val punctuation = block.originalText.count { !it.isLetterOrDigit() && !it.isWhitespace() }
        val repeated = compact.groupingBy { it }.eachCount().values.maxOrNull() ?: 0
        compact.isEmpty() || punctuation > compact.length * 2 ||
            (compact.length in 5..10 && compact.toSet().size <= 2 && repeated >= compact.length - 1) ||
            (compact.length in 2..4 && width > height * 3.5f && repeated == compact.length)
    }
}
