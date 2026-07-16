package com.untrustedtranslations.android.model

import android.net.Uri

enum class ImportFormat { IMAGE, PDF, CBZ, ZIP }

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
    val source: Uri? = null,
    val blocks: List<TextBlock> = emptyList(),
    val saved: Boolean = false,
)

data class ComicProject(
    val title: String,
    val format: ImportFormat,
    val pages: List<ComicPage>,
    val currentPageIndex: Int = 0,
)
