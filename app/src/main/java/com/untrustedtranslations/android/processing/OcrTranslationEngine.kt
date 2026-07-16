package com.untrustedtranslations.android.processing

import android.content.Context
import android.graphics.BitmapFactory
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.untrustedtranslations.android.model.*
import kotlinx.coroutines.tasks.await
import java.util.UUID

object OcrTranslationEngine {
    suspend fun process(context: Context, page: ComicPage, script: SourceScript, targetTag: String): List<TextBlock> {
        val recognizer = recognizer(script)
        val result = try {
            recognizer.process(InputImage.fromFilePath(context, page.originalSource)).await()
        } finally {
            recognizer.close()
        }
        val path = requireNotNull(page.originalSource.path)
        val dimensions = BitmapFactory.Options().also { it.inJustDecodeBounds = true; BitmapFactory.decodeFile(path, it) }
        val width = dimensions.outWidth.coerceAtLeast(1).toFloat()
        val height = dimensions.outHeight.coerceAtLeast(1).toFloat()
        val sourceLanguage = TranslateLanguage.fromLanguageTag(script.languageTag)
        val targetLanguage = TranslateLanguage.fromLanguageTag(targetTag)
        requireNotNull(sourceLanguage) { "Unsupported source language." }
        requireNotNull(targetLanguage) { "Unsupported target language." }
        val translator = if (sourceLanguage == targetLanguage) null else Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(sourceLanguage).setTargetLanguage(targetLanguage).build()
        )
        try {
            translator?.downloadModelIfNeeded(DownloadConditions.Builder().build())?.await()
            return result.textBlocks.mapNotNull { block ->
                val box = block.boundingBox ?: return@mapNotNull null
                val original = block.text.trim()
                if (original.isBlank()) return@mapNotNull null
                val translated = translator?.translate(original)?.await() ?: original
                TextBlock(
                    id = UUID.randomUUID().toString(),
                    originalText = original,
                    translatedText = translated,
                    bounds = RelativeBounds(box.left / width, box.top / height, box.right / width, box.bottom / height),
                )
            }
        } finally {
            translator?.close()
        }
    }

    private fun recognizer(script: SourceScript): TextRecognizer = when (script) {
        SourceScript.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        SourceScript.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        SourceScript.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        SourceScript.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
}
