package com.untrustedtranslations.android.processing

import android.content.Context
import com.untrustedtranslations.android.model.ComicPage
import com.untrustedtranslations.android.model.SourceScript
import com.untrustedtranslations.android.model.TextBlock

/**
 * FOSS flavor: Google ML Kit is closed-source, so this build ships without it.
 * The UI hides the ML Kit provider entries (BuildConfig.FLAVOR == "foss"); these
 * stubs only exist so shared code that references the engine still compiles.
 */
object OcrTranslationEngine {
    suspend fun process(
        context: Context,
        page: ComicPage,
        script: SourceScript,
        sourceTag: String,
        targetTag: String,
        deepScan: Boolean = false,
    ): List<TextBlock> = throw UnsupportedOperationException(
        "ML Kit OCR is not included in the FOSS build. Use Comic AI instead.",
    )

    suspend fun translateText(text: String, sourceTag: String, targetTag: String): String =
        throw UnsupportedOperationException(
            "ML Kit translation is not included in the FOSS build. Use Local AI instead.",
        )
}
