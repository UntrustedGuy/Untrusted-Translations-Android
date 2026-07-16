package com.untrustedtranslations.android.model

import android.net.Uri

enum class ImportFormat { IMAGE, PDF, CBZ, ZIP }
enum class SourceScript(val label: String, val languageTag: String) {
    JAPANESE("Japanese", "ja"), KOREAN("Korean", "ko"), CHINESE("Chinese", "zh"), LATIN("English / Latin", "en")
}

data class TextStyle(val fontSizeSp: Float = 22f, val rotationDegrees: Float = 0f)
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
    val title: String,
    val format: ImportFormat,
    val pages: List<ComicPage>,
    val currentPageIndex: Int = 0,
)
