package com.untrustedtranslations.android.model

import android.net.Uri

enum class ImportFormat { IMAGE, PDF, CBZ, ZIP, FOLDER }

enum class OcrProvider(val label: String) {
    GEMINI_FREE("Gemini Free (online / dialogue-only)"),
    ML_KIT("Google ML Kit (offline / dialogue-only)"),
    RAPID_OCR("RapidOCR (download / dialogue-only)"),
    RAPID_OCR_V5("PP-OCRv5 (download / dialogue-only)"),
    MANGA_OCR("Manga-OCR (Japanese / dialogue-only)"),
    COMIC_AI_VISION("Qwen2-VL Vision (large / dialogue-only)"),
}

enum class TranslationProvider(val label: String, val paid: Boolean = false) {
    GEMINI_FREE("Gemini Free (online)"),
    ML_KIT("Google ML Kit (offline)"),
    NLLB("NLLB offline (legacy / download)"),
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
    AUTO("Auto / System"),
    MANGA("Manga / Comic"),
    ACTION("Action / Impact"),
    SANS("Modern Sans"),
    SERIF("Classic Serif"),
    CONDENSED("Condensed"),
    MONOSPACE("Monospace"),
    CASUAL("Casual Script"),
    GOTHIC("Gothic Bold"),
    VINTAGE("Vintage Cursive"),
    HANDWRITING("Handwriting"),
    CURSIVE("Cursive"),
    DISPLAY("Display"),
    ROUNDED("Rounded"),
    PIXEL("Pixel Art"),
    COMIC_SANS("Comic Sans"),
    IMPACT("Impact"),
    BRUSH("Brush Script"),
    STENCIL("Stencil"),
    TYPEWRITER("Typewriter"),
    BLACKLETTER("Blackletter"),
    ART_DECO("Art Deco"),
    GRAFFITI("Graffiti"),
    SCRIPT("Script"),
    SLAB_SERIF("Slab Serif"),
    THIN("Thin"),
    HEAVY("Heavy"),
    WIDE("Wide"),
    NARROW("Narrow"),
    CUSTOM("Custom Font")
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
    val textColorArgb: Long = 0xFF000000L,
    val textOpacity: Float = 1.0f,
    val backgroundColorArgb: Long? = null,
    val backgroundOpacity: Float = 1.0f,
    val backgroundCornerRadiusDp: Float = 8f,
    val backgroundPaddingDp: Float = 6f,
    val strokeWidthSp: Float = 0f,
    val strokeColorArgb: Long = 0xFFFFFFFFL,
    val shadowBlurRadiusSp: Float = 0f,
    val shadowDxSp: Float = 0f,
    val shadowDySp: Float = 0f,
    val shadowColorArgb: Long = 0x99000000L,
    val letterSpacingEm: Float = 0f,
    val lineSpacingMultiplier: Float = 1.0f,
    val curveSweepAngle: Float = 0f,
    val underline: Boolean = false,
    val strikethrough: Boolean = false,
    val highlightColorArgb: Long? = null,
    val highlightOpacity: Float = 0.5f,
    val gradientEnabled: Boolean = false,
    val gradientStartColorArgb: Long = 0xFFFF0000L,
    val gradientEndColorArgb: Long = 0xFF0000FFL,
    val gradientAngleDegrees: Float = 0f,
    val perspective3dX: Float = 0f,
    val perspective3dY: Float = 0f,
    val zIndex: Int = 0,
    val locked: Boolean = false,
    val visible: Boolean = true,
)

data class SavedStyle(
    val name: String,
    val style: TextStyle,
    val createdAt: Long = System.currentTimeMillis()
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
