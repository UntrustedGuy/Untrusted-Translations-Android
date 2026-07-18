package com.untrustedtranslations.android.processing

import com.untrustedtranslations.android.model.SourceScript
import com.untrustedtranslations.android.model.TextBlock

internal object ReadingOrder {
    fun sort(blocks: List<TextBlock>, script: SourceScript): List<TextBlock> {
        if (script != SourceScript.JAPANESE) return blocks.sortedWith(LtrRowComparator)
        if (blocks.size < 2) return blocks
        val medianWidth = blocks.map { it.bounds.right - it.bounds.left }.sorted()[blocks.size / 2]
        val gap = (medianWidth * .6f).coerceAtLeast(.02f)
        val columns = mutableListOf<MutableList<TextBlock>>()
        for (block in blocks.sortedByDescending { centerX(it) }) {
            val column = columns
                .filter { kotlin.math.abs(columnCenter(it) - centerX(block)) < gap }
                .minByOrNull { kotlin.math.abs(columnCenter(it) - centerX(block)) }
            if (column != null) column += block else columns += mutableListOf(block)
        }
        return columns
            .sortedByDescending(::columnCenter)
            .flatMap { column -> column.sortedBy { it.bounds.top } }
    }

    private fun centerX(block: TextBlock) = (block.bounds.left + block.bounds.right) / 2f
    private fun columnCenter(column: List<TextBlock>) = column.map(::centerX).average().toFloat()

    private val LtrRowComparator = Comparator<TextBlock> { first, second ->
        val row = first.bounds.top.compareTo(second.bounds.top)
        if (row != 0) row else first.bounds.left.compareTo(second.bounds.left)
    }
}
