package com.untrustedtranslations.android.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.graphics.Rect
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.untrustedtranslations.android.model.ComicPage
import com.untrustedtranslations.android.model.FontChoice
import com.untrustedtranslations.android.model.RelativeBounds
import com.untrustedtranslations.android.model.SourceScript
import com.untrustedtranslations.android.model.TextBlock
import kotlinx.coroutines.tasks.await
import java.util.UUID
import kotlin.math.abs
import kotlin.math.atan2

object OcrTranslationEngine {
    private data class Detection(
        val text: String,
        val bounds: RelativeBounds,
        val pixelBox: Rect,
        val cornerPoints: Array<Point>?,
        val confidence: Float,
    )

    suspend fun process(
        context: Context,
        page: ComicPage,
        script: SourceScript,
        sourceTag: String,
        targetTag: String,
        deepScan: Boolean = false,
    ): List<TextBlock> {
        val path = requireNotNull(page.originalSource.path)
        val bitmap = requireNotNull(BitmapFactory.decodeFile(path)) { "Unable to inspect page lettering." }
        val recognizer = recognizer(script)
        val detections = try {
            val original = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
            val candidates = extract(original, bitmap.width, bitmap.height, 1f).toMutableList()
            if (deepScan) {
            tileRects(bitmap.width, bitmap.height).forEach { tileRect ->
                val tile = Bitmap.createBitmap(
                    bitmap,
                    tileRect.left,
                    tileRect.top,
                    tileRect.width(),
                    tileRect.height(),
                )
                val scale = (1600f / maxOf(tile.width, tile.height)).coerceIn(1.25f, 2.2f)
                val enlarged = Bitmap.createScaledBitmap(
                    tile,
                    (tile.width * scale).toInt(),
                    (tile.height * scale).toInt(),
                    true,
                )
                val thresholded = mangaContrast(enlarged)
                try {
                    val tileResult = recognizer.process(InputImage.fromBitmap(enlarged, 0)).await()
                    candidates += extract(
                        tileResult,
                        bitmap.width,
                        bitmap.height,
                        scale,
                        tileRect.left,
                        tileRect.top,
                    )
                    val thresholdResult = recognizer.process(InputImage.fromBitmap(thresholded, 0)).await()
                    candidates += extract(
                        thresholdResult,
                        bitmap.width,
                        bitmap.height,
                        scale,
                        tileRect.left,
                        tileRect.top,
                    )
                } finally {
                    thresholded.recycle()
                    enlarged.recycle()
                    tile.recycle()
                }
            }
            }
            mergeDetections(candidates).filterNot(::looksLikeSoundEffect)
        } finally {
            recognizer.close()
        }

        return try {
            val translations = translateTexts(detections.map { it.text }, sourceTag, targetTag)
            detections.mapIndexed { index, detection ->
                val translated = translations[index]
                val estimated = LetteringStyleEstimator.estimate(
                    context = context,
                    bitmap = bitmap,
                    box = detection.pixelBox,
                    text = detection.text,
                    script = script,
                    cornerPoints = detection.cornerPoints,
                )
                val targetUsesVerticalWriting = targetTag == "ja" || targetTag.startsWith("zh")
                TextBlock(
                    id = UUID.randomUUID().toString(),
                    originalText = detection.text,
                    translatedText = translated,
                    bounds = translatedBounds(
                        source = detection.bounds,
                        translatedText = translated,
                        sourceWasVertical = estimated.vertical,
                        targetUsesVerticalWriting = targetUsesVerticalWriting,
                    ),
                    eraseBounds = detection.bounds,
                    style = estimated.copy(
                        font = if (targetUsesVerticalWriting) estimated.font else FontChoice.MANGA,
                        vertical = estimated.vertical && targetUsesVerticalWriting,
                    ),
                )
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun mangaContrast(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        pixels.indices.forEach { index ->
            val color = pixels[index]
            val alpha = color ushr 24
            val red = color ushr 16 and 0xFF
            val green = color ushr 8 and 0xFF
            val blue = color and 0xFF
            val luminance = (red * 299 + green * 587 + blue * 114) / 1000
            val value = if (luminance >= 178) 255 else 0
            pixels[index] = (alpha shl 24) or (value shl 16) or (value shl 8) or value
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun extract(
        result: Text,
        originalWidth: Int,
        originalHeight: Int,
        scale: Float,
        offsetX: Int = 0,
        offsetY: Int = 0,
    ): List<Detection> = result.textBlocks.mapNotNull { block ->
        val scaledBox = block.boundingBox ?: return@mapNotNull null
        val text = block.text.trim()
        if (text.isBlank()) return@mapNotNull null
        val box = Rect(
            ((scaledBox.left / scale).toInt() + offsetX).coerceIn(0, originalWidth - 1),
            ((scaledBox.top / scale).toInt() + offsetY).coerceIn(0, originalHeight - 1),
            ((scaledBox.right / scale).toInt() + offsetX).coerceIn(1, originalWidth),
            ((scaledBox.bottom / scale).toInt() + offsetY).coerceIn(1, originalHeight),
        )
        if (box.width() < 2 || box.height() < 2) return@mapNotNull null
        val confidences = block.lines.flatMap { it.elements }.map { it.confidence }.filter { it >= 0f }
        Detection(
            text = text,
            bounds = RelativeBounds(
                box.left / originalWidth.toFloat(),
                box.top / originalHeight.toFloat(),
                box.right / originalWidth.toFloat(),
                box.bottom / originalHeight.toFloat(),
            ),
            pixelBox = box,
            cornerPoints = block.cornerPoints?.map {
                Point((it.x / scale).toInt() + offsetX, (it.y / scale).toInt() + offsetY)
            }?.toTypedArray(),
            confidence = confidences.average().takeUnless { it.isNaN() }?.toFloat() ?: 0f,
        )
    }

    private fun tileRects(width: Int, height: Int): List<Rect> {
        val middleX = width / 2
        val middleY = height / 2
        val overlapX = (width * .08f).toInt()
        val overlapY = (height * .08f).toInt()
        return listOf(
            Rect(0, 0, middleX + overlapX, middleY + overlapY),
            Rect(middleX - overlapX, 0, width, middleY + overlapY),
            Rect(0, middleY - overlapY, middleX + overlapX, height),
            Rect(middleX - overlapX, middleY - overlapY, width, height),
        )
    }

    private fun mergeDetections(candidates: List<Detection>): List<Detection> {
        val merged = mutableListOf<Detection>()
        candidates.forEach { candidate ->
            val candidateKey = normalizedText(candidate.text)
            val duplicate = merged.indexOfFirst { existing ->
                overlapOverSmaller(existing.pixelBox, candidate.pixelBox) >= .45f ||
                    (candidateKey.length >= 4 && candidateKey == normalizedText(existing.text))
            }
            if (duplicate < 0) {
                merged += candidate
            } else {
                val existing = merged[duplicate]
                val existingLength = existing.text.count { !it.isWhitespace() }
                val candidateLength = candidate.text.count { !it.isWhitespace() }
                val relatedText = textsAreRelated(existing.text, candidate.text)
                val isMoreComplete = relatedText && candidateLength >= existingLength + 2 &&
                    candidateLength >= (existingLength * 1.25f)
                val isMoreConfident = relatedText && candidate.confidence >= existing.confidence + .18f &&
                    candidateLength >= existingLength
                if (isMoreComplete || isMoreConfident) {
                    merged[duplicate] = candidate
                }
            }
        }
        return merged.sortedWith(compareBy<Detection> { it.pixelBox.top }.thenBy { it.pixelBox.left })
    }

    private fun normalizedText(value: String): String = value.lowercase().filter { it.isLetterOrDigit() }

    private fun textsAreRelated(first: String, second: String): Boolean {
        val firstKey = normalizedText(first)
        val secondKey = normalizedText(second)
        if (firstKey.isBlank() || secondKey.isBlank()) return false
        if (firstKey in secondKey || secondKey in firstKey) return true
        val firstCharacters = firstKey.toSet()
        val secondCharacters = secondKey.toSet()
        val smallerSize = minOf(firstCharacters.size, secondCharacters.size).coerceAtLeast(1)
        val shared = firstCharacters.intersect(secondCharacters).size
        return shared.toFloat() / smallerSize >= .6f
    }

    private fun looksLikeSoundEffect(detection: Detection): Boolean {
        val compact = detection.text.filter { it.isLetterOrDigit() }
        if (compact.length <= 1) return true
        if (compact.length <= 6 && compact.toSet().size == 1) return true

        val points = detection.cornerPoints
        if (points != null && points.size >= 2 && compact.length <= 6) {
            val rawAngle = abs(Math.toDegrees(
                atan2(
                    (points[1].y - points[0].y).toDouble(),
                    (points[1].x - points[0].x).toDouble(),
                ),
            ).toFloat())
            val horizontalAngle = if (rawAngle > 90f) 180f - rawAngle else rawAngle
            if (horizontalAngle > 18f) return true
        }
        return false
    }

    private fun overlapOverSmaller(first: Rect, second: Rect): Float {
        val intersectionWidth = (minOf(first.right, second.right) - maxOf(first.left, second.left)).coerceAtLeast(0)
        val intersectionHeight = (minOf(first.bottom, second.bottom) - maxOf(first.top, second.top)).coerceAtLeast(0)
        val intersection = intersectionWidth.toLong() * intersectionHeight
        val smaller = minOf(first.width().toLong() * first.height(), second.width().toLong() * second.height())
        return if (smaller <= 0L) 0f else intersection.toFloat() / smaller
    }

    private fun translatedBounds(
        source: RelativeBounds,
        translatedText: String,
        sourceWasVertical: Boolean,
        targetUsesVerticalWriting: Boolean,
    ): RelativeBounds {
        if (targetUsesVerticalWriting) return source
        val characters = translatedText.count { !it.isWhitespace() }.coerceAtLeast(1)
        val lines = ((characters + 17) / 18).coerceAtLeast(1)
        val sourceWidth = source.right - source.left
        val sourceHeight = source.bottom - source.top
        val desiredWidth = maxOf(
            sourceWidth * if (sourceWasVertical) 3.2f else 1.4f,
            (.10f + characters * .007f).coerceAtMost(.45f),
        ).coerceIn(.08f, .5f)
        val desiredHeight = maxOf(
            sourceHeight * if (sourceWasVertical) .9f else 1.3f,
            .055f * lines,
        ).coerceIn(.05f, .32f)
        val centerX = (source.left + source.right) / 2f
        val centerY = (source.top + source.bottom) / 2f
        val left = (centerX - desiredWidth / 2f).coerceIn(0f, 1f - desiredWidth)
        val top = (centerY - desiredHeight / 2f).coerceIn(0f, 1f - desiredHeight)
        return RelativeBounds(left, top, left + desiredWidth, top + desiredHeight)
    }

    private suspend fun translateTexts(texts: List<String>, sourceTag: String, targetTag: String): List<String> {
        if (sourceTag == targetTag) return texts
        val source = requireNotNull(TranslateLanguage.fromLanguageTag(sourceTag)) {
            "Unsupported source language: $sourceTag"
        }
        val target = requireNotNull(TranslateLanguage.fromLanguageTag(targetTag)) {
            "Unsupported target language: $targetTag"
        }
        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(source)
                .setTargetLanguage(target)
                .build(),
        )
        return try {
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            texts.map { text ->
                if (text.isBlank()) text else translator.translate(text).await()
            }
        } finally {
            translator.close()
        }
    }

    suspend fun translateText(text: String, sourceTag: String, targetTag: String): String {
        if (text.isBlank() || sourceTag == targetTag) return text
        val source = requireNotNull(TranslateLanguage.fromLanguageTag(sourceTag)) {
            "Unsupported source language: $sourceTag"
        }
        val target = requireNotNull(TranslateLanguage.fromLanguageTag(targetTag)) {
            "Unsupported target language: $targetTag"
        }
        val translator = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(source)
                .setTargetLanguage(target)
                .build(),
        )
        return try {
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            translator.translate(text).await()
        } finally {
            translator.close()
        }
    }

    private fun recognizer(script: SourceScript): TextRecognizer = when (script) {
        SourceScript.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        SourceScript.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        SourceScript.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        SourceScript.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
}
