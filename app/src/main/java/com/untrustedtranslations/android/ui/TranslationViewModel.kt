package com.untrustedtranslations.android.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.untrustedtranslations.android.importer.ComicImporter
import com.untrustedtranslations.android.model.*
import com.untrustedtranslations.android.processing.OcrTranslationEngine
import com.untrustedtranslations.android.processing.PageRenderer
import com.untrustedtranslations.android.processing.ProjectExporter
import kotlinx.coroutines.launch

enum class AppScreen { IMPORT, PAGE, EDITOR }

class TranslationViewModel(application: Application) : AndroidViewModel(application) {
    var screen by mutableStateOf(AppScreen.IMPORT); private set
    var project by mutableStateOf<ComicProject?>(null); private set
    var selectedBlockIndex by mutableStateOf(0); private set
    var sourceScript by mutableStateOf(SourceScript.JAPANESE); private set
    var targetLanguageTag by mutableStateOf("en"); private set
    var busyMessage by mutableStateOf<String?>(null); private set
    var errorMessage by mutableStateOf<String?>(null); private set
    var noticeMessage by mutableStateOf<String?>(null); private set

    val currentPage get() = project?.let { it.pages.getOrNull(it.currentPageIndex) }
    val currentBlock get() = currentPage?.blocks?.getOrNull(selectedBlockIndex)
    val isLastPage get() = project?.let { it.currentPageIndex == it.pages.lastIndex } ?: false

    fun selectSourceScript(value: SourceScript) { sourceScript = value }
    fun setTargetLanguage(value: String) { targetLanguageTag = value }
    fun dismissError() { errorMessage = null }

    fun importDocument(uri: Uri) = viewModelScope.launch {
        runBusy("Importing pages…") {
            project = ComicImporter.import(getApplication(), uri)
            selectedBlockIndex = 0
            screen = AppScreen.PAGE
        }
        if (project != null) processCurrentPage()
    }

    fun processCurrentPage() = viewModelScope.launch {
        val page = currentPage ?: return@launch
        runBusy("Detecting and translating text…") {
            val blocks = OcrTranslationEngine.process(getApplication(), page, sourceScript, targetLanguageTag)
            replaceCurrentPage(page.copy(blocks = blocks, processed = true, saved = false))
            if (blocks.isEmpty()) noticeMessage = "No text was detected. Try another source script."
        }
    }

    fun openEditor() {
        if (currentPage?.blocks.isNullOrEmpty()) {
            errorMessage = "No text blocks are available yet. Run detection again or change the source script."
            return
        }
        selectedBlockIndex = 0
        screen = AppScreen.EDITOR
    }

    fun closeEditor() { screen = AppScreen.PAGE }
    fun updateTranslation(value: String) = updateBlock { copy(translatedText = value, applied = false) }
    fun updateFontSize(value: Float) = updateBlock { copy(style = style.copy(fontSizeSp = value), applied = false) }
    fun previousBlock() { if (selectedBlockIndex > 0) selectedBlockIndex-- }
    fun nextBlock() { if (selectedBlockIndex < (currentPage?.blocks?.lastIndex ?: 0)) selectedBlockIndex++ }

    fun applyBlock() = viewModelScope.launch {
        val page = currentPage ?: return@launch
        val block = currentBlock ?: return@launch
        runBusy("Replacing text on page…") {
            val rendered = PageRenderer.apply(getApplication(), page, block)
            val blocks = page.blocks.toMutableList().apply {
                this[selectedBlockIndex] = block.copy(applied = true)
            }
            replaceCurrentPage(page.copy(renderedSource = rendered, blocks = blocks, saved = false))
        }
    }

    fun save() = currentPage?.let { replaceCurrentPage(it.copy(saved = true)) }

    fun saveAndNext() {
        save()
        val current = project ?: return
        if (current.currentPageIndex >= current.pages.lastIndex) return
        project = current.copy(currentPageIndex = current.currentPageIndex + 1)
        selectedBlockIndex = 0
        screen = AppScreen.PAGE
        processCurrentPage()
    }

    fun saveAndExit() = viewModelScope.launch {
        save()
        val current = project ?: return@launch
        runBusy("Exporting translated comic…") {
            val destination = ProjectExporter.export(getApplication(), current)
            project = null
            selectedBlockIndex = 0
            screen = AppScreen.IMPORT
            noticeMessage = "Saved translated comic to $destination"
        }
    }

    private fun updateBlock(transform: TextBlock.() -> TextBlock) {
        val page = currentPage ?: return
        val blocks = page.blocks.toMutableList().apply { this[selectedBlockIndex] = this[selectedBlockIndex].transform() }
        replaceCurrentPage(page.copy(blocks = blocks, saved = false))
    }

    private fun replaceCurrentPage(page: ComicPage) {
        val current = project ?: return
        val pages = current.pages.toMutableList().apply { this[current.currentPageIndex] = page }
        project = current.copy(pages = pages)
    }

    private suspend fun runBusy(message: String, action: suspend () -> Unit) {
        busyMessage = message
        errorMessage = null
        try { action() } catch (error: Throwable) {
            errorMessage = error.message ?: "Something went wrong."
        } finally { busyMessage = null }
    }
}
