package com.untrustedtranslations.android.processing

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Rect
import com.untrustedtranslations.android.model.ComicPage
import com.untrustedtranslations.android.model.TextBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Final dialogue gate shared by OCR providers that first inspect the whole page. */
internal object DialogueOnlyFilter {
    suspend fun keepDialogue(
        context: Context,
        page: ComicPage,
        blocks: List<TextBlock>,
        deepScan: Boolean,
    ): List<TextBlock> = withContext(Dispatchers.IO) {
        if (blocks.isEmpty()) return@withContext emptyList()
        val detectorPack = ModelPackId.COMIC_DIALOGUE_DETECTOR
        require(ModelPackManager.isInstalled(context, detectorPack)) {
            "Download the Comic dialogue detector first."
        }
        val bitmap = context.contentResolver.openInputStream(page.originalSource)
            ?.use(BitmapFactory::decodeStream)
            ?: error("Cannot open page for dialogue filtering.")
        try {
            val detector = File(
                ModelPackManager.directory(context, detectorPack),
                "comic_dialogue_detector.onnx",
            )
            val regions = ComicDialogueDetector.detect(
                cacheKey = "shared_comic_dialogue_detector",
                model = detector,
                bitmap = bitmap,
                minimumScore = if (deepScan) .22f else .35f,
            )
            blocks.filter { block ->
                val source = block.eraseBounds ?: block.bounds
                val rect = Rect(
                    (source.left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1),
                    (source.top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1),
                    (source.right * bitmap.width).toInt().coerceIn(1, bitmap.width),
                    (source.bottom * bitmap.height).toInt().coerceIn(1, bitmap.height),
                )
                regions.any { region ->
                    region.rect.contains(rect.centerX(), rect.centerY()) ||
                        overlapFraction(rect, region.rect) >= .30f
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun overlapFraction(a: Rect, b: Rect): Float {
        val width = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0)
        val height = (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0)
        val area = a.width().coerceAtLeast(1).toLong() * a.height().coerceAtLeast(1)
        return (width.toLong() * height).toFloat() / area
    }
}
