package com.untrustedtranslations.android.model

import android.net.Uri

enum class ImportFormat { IMAGE, PDF, CBZ, ZIP }

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
)

data class RelativeBounds(val left: Float, val top: Float, val right: Float, val bottom: Float)

data class TextBlock(
    val id: String,
    val originalText: String,
    val translatedText: String,
    val bounds: RelativeBounds,
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

data class SavedProject(
    val project: ComicProject,
    val sourceScript: SourceScript,
    val targetLanguageTag: String,
)
