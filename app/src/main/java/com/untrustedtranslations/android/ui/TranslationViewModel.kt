package com.untrustedtranslations.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.untrustedtranslations.android.model.*

enum class AppScreen { IMPORT, PAGE, EDITOR }

class TranslationViewModel : ViewModel() {
    var screen by mutableStateOf(AppScreen.IMPORT); private set
    var project by mutableStateOf<ComicProject?>(null); private set
    var selectedBlockIndex by mutableStateOf(0); private set

    val currentPage get() = project?.let { it.pages.getOrNull(it.currentPageIndex) }
    val currentBlock get() = currentPage?.blocks?.getOrNull(selectedBlockIndex)

    fun loadDemoProject() {
        val blocks = listOf(
            TextBlock("b1", "まさか…君なのか？", "No way… is that you?", RelativeBounds(.16f, .12f, .73f, .30f)),
            TextBlock("b2", "ずっと探していた。", "I've been looking for you.", RelativeBounds(.29f, .60f, .83f, .77f)),
        )
        project = ComicProject("Demo manga", ImportFormat.CBZ, (1..6).map { ComicPage("p$it", "Page $it", blocks = blocks) })
        screen = AppScreen.PAGE
    }

    fun openEditor() { selectedBlockIndex = 0; screen = AppScreen.EDITOR }
    fun closeEditor() { screen = AppScreen.PAGE }
    fun updateTranslation(value: String) = updateBlock { copy(translatedText = value, applied = false) }
    fun updateFontSize(value: Float) = updateBlock { copy(style = style.copy(fontSizeSp = value), applied = false) }
    fun applyBlock() = updateBlock { copy(applied = true) }
    fun previousBlock() { if (selectedBlockIndex > 0) selectedBlockIndex-- }
    fun nextBlock() { if (selectedBlockIndex < (currentPage?.blocks?.lastIndex ?: 0)) selectedBlockIndex++ }
    fun save() = updatePage { copy(saved = true) }

    // This is deliberately the only function that changes pages.
    fun saveAndNext() {
        save()
        val current = project ?: return
        if (current.currentPageIndex < current.pages.lastIndex) {
            project = current.copy(currentPageIndex = current.currentPageIndex + 1)
            selectedBlockIndex = 0
            screen = AppScreen.PAGE
        }
    }

    private fun updateBlock(transform: TextBlock.() -> TextBlock) {
        val page = currentPage ?: return
        val blocks = page.blocks.toMutableList().apply { this[selectedBlockIndex] = this[selectedBlockIndex].transform() }
        updatePage { copy(blocks = blocks, saved = false) }
    }

    private fun updatePage(transform: ComicPage.() -> ComicPage) {
        val current = project ?: return
        val pages = current.pages.toMutableList().apply { this[current.currentPageIndex] = this[current.currentPageIndex].transform() }
        project = current.copy(pages = pages)
    }
}
