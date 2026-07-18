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

internal object ComicVisionPageEngine {
    suspend fun process(
        context: Context,
        page: ComicPage,
        script: SourceScript,
        detectorPack: ModelPackId,
        visionPack: ModelPackId,
        fallbackRecognitionPack: ModelPackId,
    ): List<TextBlock> = withContext(Dispatchers.IO) {
        val bitmap = context.contentResolver.openInputStream(page.originalSource)
            ?.use(BitmapFactory::decodeStream)
            ?: error("Cannot open page.")
        try {
            val detector = File(ModelPackManager.directory(context, detectorPack), "comic_dialogue_detector.onnx")
            val regions = ComicDialogueDetector.detect("comic_vision_dialogue", detector, bitmap)
            val blocks = regions.mapNotNull { region ->
                val cropRect = paddedCrop(region.rect, bitmap.width, bitmap.height)
                val crop = Bitmap.createBitmap(bitmap, cropRect.left, cropRect.top, cropRect.width(), cropRect.height())
                val text = try {
                    VisionLlmRuntime.recognize(context, visionPack, crop, script).ifBlank {
                        RapidOcrPageEngine.recognizeComicCrop(context, fallbackRecognitionPack, crop).text
                    }
                } finally {
                    crop.recycle()
                }.trim()
                if (text.isBlank()) return@mapNotNull null
                val bounds = RelativeBounds(
                    region.rect.left / bitmap.width.toFloat(),
                    region.rect.top / bitmap.height.toFloat(),
                    region.rect.right / bitmap.width.toFloat(),
                    region.rect.bottom / bitmap.height.toFloat(),
                )
                TextBlock(
                    id = UUID.randomUUID().toString(),
                    originalText = text,
                    translatedText = text,
                    bounds = bounds,
                    eraseBounds = bounds,
                    style = LetteringStyleEstimator.estimate(context, bitmap, region.rect, text, script, null),
                )
            }
            ReadingOrder.sort(blocks, script)
        } finally {
            bitmap.recycle()
        }
    }

    private fun paddedCrop(rect: Rect, width: Int, height: Int): Rect = Rect(
        (rect.left - maxOf(12, rect.width() / 2)).coerceAtLeast(0),
        (rect.top - maxOf(10, rect.height() / 8)).coerceAtLeast(0),
        (rect.right + maxOf(12, rect.width() / 2)).coerceAtMost(width),
        (rect.bottom + maxOf(10, rect.height() / 8)).coerceAtMost(height),
    )
}
