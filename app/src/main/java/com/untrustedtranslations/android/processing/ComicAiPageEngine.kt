package com.untrustedtranslations.android.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import com.untrustedtranslations.android.model.ComicPage
import com.untrustedtranslations.android.model.RelativeBounds
import com.untrustedtranslations.android.model.SourceScript
import com.untrustedtranslations.android.model.TextBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Comic-specific dialogue detection plus script-specific on-device recognition. */
internal object ComicAiPageEngine {
    suspend fun process(
        context: Context,
        page: ComicPage,
        script: SourceScript,
        detectorPack: ModelPackId,
        recognitionPack: ModelPackId,
    ): List<TextBlock> = withContext(Dispatchers.IO) {
        require(ModelPackManager.isInstalled(context, detectorPack)) { "Comic AI detector is not installed." }
        require(ModelPackManager.isInstalled(context, recognitionPack)) { "Recognition pack is not installed." }
        val bitmap = context.contentResolver.openInputStream(page.originalSource)
            ?.use(BitmapFactory::decodeStream)
            ?: error("Cannot open page.")
        try {
            val detectorFile = File(ModelPackManager.directory(context, detectorPack), "comic_dialogue_detector.onnx")
            val regions = ComicDialogueDetector.detect("comic_ai_dialogue", detectorFile, bitmap)
            val blocks = regions.mapNotNull { region ->
                val cropRect = paddedCrop(region.rect, bitmap.width, bitmap.height)
                val crop = Bitmap.createBitmap(bitmap, cropRect.left, cropRect.top, cropRect.width(), cropRect.height())
                val reading = try {
                    RapidOcrPageEngine.recognizeComicCrop(context, recognitionPack, crop)
                } finally {
                    crop.recycle()
                }
                if (reading.text.isBlank() || reading.confidence < .30f) return@mapNotNull null
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
                        context, bitmap, region.rect, reading.text, script, null,
                    ),
                )
            }
            ReadingOrder.sort(blocks, script)
        } finally {
            bitmap.recycle()
        }
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
}
