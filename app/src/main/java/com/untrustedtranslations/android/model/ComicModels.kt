package com.untrustedtranslations.android.model

import android.net.Uri

enum class ImportFormat { IMAGE, PDF, CBZ, ZIP, FOLDER }

enum class OcrProvider(val label: String) {
    GEMINI_FREE("Gemini Free (online)"),
    ML_KIT("Google ML Kit (offline)"),
    RAPID_OCR("RapidOCR pack (download)"),
    RAPID_OCR_V5("RapidOCR PP-OCRv5 (download / experimental)"),
    MANGA_OCR("Manga-OCR (download / experimental / Japanese)"),
}

enum class TranslationProvider(val label: String, val paid: Boolean = false) {
    GEMINI_FREE("Gemini Free (online)"),
    ML_KIT("Google ML Kit (offline)"),
    NLLB("NLLB high quality (download)"),
    LOCAL_AI("Local AI LLM (download)"),
    OPENAI("OpenAI API (paid)", paid = true),
    GOOGLE_UNOFFICIAL("Google Translate (unofficial / experimental)"),
    ANTHROPIC("Claude API (paid)", paid = true),
    OPENAI_COMPATIBLE("Custom OpenAI-compatible API", paid = true),
}

enum class SourceScript(val label: String, val languageTag: String) {
    JAPANESE("Japanese", "ja"),
    KOREAN("Korean", "ko"),
    CHINESE("Chinese", "zh"),
    LATIN("English / Latin", "en"),
}

enum class FontChoice(val label: String) {
    AUTO("Auto / system"),
    SANS("Sans"),
    SERIF("Serif"),
    CONDENSED("Condensed"),
    MONOSPACE("Monospace"),
    CASUAL("Casual"),
    MANGA("Manga"),
}

enum class TextAlignmentChoice(val label: String) {
    START("Left"), CENTER("Center"), END("Right"),
}

data class TextStyle(
    val fontSizeSp: Float = 22f,
    val rotationDegrees: Float = 0f,
    val font: FontChoice = FontChoice.AUTO,
    val alignment: TextAlignmentChoice = TextAlignmentChoice.CENTER,
    val bold: Boolean = true,
    val italic: Boolean = false,
    val vertical: Boolean = false,
    val textColorArgb: Long = 0xFF000000,
    val backgroundColorArgb: Long? = null,
)

data class RelativeBounds(val left: Float, val top: Float, val right: Float, val bottom: Float)

data class TextBlock(
    val id: String,
    val originalText: String,
    val translatedText: String,
    val bounds: RelativeBounds,
    /** Tight OCR glyph bounds used only to remove source lettering. Null for manually added text. */
    val eraseBounds: RelativeBounds? = bounds,
    val style: TextStyle = TextStyle(),
    val applied: Boolean = false,
)

data class ComicPage(
    val id: String,
    val displayName: String,
    val originalSource: Uri,
    val renderedSource: Uri = originalSource,
    val blocks: List<TextBlock> = emptyList(),
    val saved: Boolean = false,
    val processed: Boolean = false,
)

data class ComicProject(
    val id: String,
    val title: String,
    val format: ImportFormat,
    val pages: List<ComicPage>,
    val currentPageIndex: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
)

data class GlossaryEntry(
    val id: String,
    val sourceText: String,
    val targetText: String,
    val caseSensitive: Boolean = false,
    val notes: String = "",
)

data class TranslationMemory(
    val sourceText: String,
    val targetText: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val usageCount: Int = 1,
)

data class SavedProject(
    val project: ComicProject,
    val sourceScript: SourceScript,
    val sourceLanguageTag: String = sourceScript.languageTag,
    val targetLanguageTag: String,
)
