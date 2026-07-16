package com.untrustedtranslations.android.processing

import android.content.Context
import android.graphics.BitmapFactory
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.untrustedtranslations.android.model.ComicPage
import com.untrustedtranslations.android.model.RelativeBounds
import com.untrustedtranslations.android.model.SourceScript
import com.untrustedtranslations.android.model.TextBlock
import kotlinx.coroutines.tasks.await
import java.util.UUID

object OcrTranslationEngine {
    suspend fun process(
        context: Context,
        page: ComicPage,
        script: SourceScript,
        targetTag: String,
    ): List<TextBlock> {
        val recognizer = recognizer(script)
        val result = try {
            recognizer.process(InputImage.fromFilePath(context, page.originalSource)).await()
        } finally {
            recognizer.close()
        }

        val path = requireNotNull(page.originalSource.path)
        val dimensions = BitmapFactory.Options().also {
            it.inJustDecodeBounds = true
            BitmapFactory.decodeFile(path, it)
        }
        val width = dimensions.outWidth.coerceAtLeast(1).toFloat()
        val height = dimensions.outHeight.coerceAtLeast(1).toFloat()

        return result.textBlocks.mapNotNull { block ->
            val box = block.boundingBox ?: return@mapNotNull null
            val original = block.text.trim()
            if (original.isBlank()) return@mapNotNull null
            TextBlock(
                id = UUID.randomUUID().toString(),
                originalText = original,
                translatedText = translateText(original, script.languageTag, targetTag),
                bounds = RelativeBounds(
                    left = (box.left / width).coerceIn(0f, 1f),
                    top = (box.top / height).coerceIn(0f, 1f),
                    right = (box.right / width).coerceIn(0f, 1f),
                    bottom = (box.bottom / height).coerceIn(0f, 1f),
                ),
            )
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
