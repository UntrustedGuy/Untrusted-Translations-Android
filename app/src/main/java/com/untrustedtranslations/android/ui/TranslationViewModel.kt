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
import com.untrustedtranslations.android.persistence.ProjectStore
import com.untrustedtranslations.android.processing.OcrTranslationEngine
import com.untrustedtranslations.android.processing.PageRenderer
import com.untrustedtranslations.android.processing.ProjectExporter
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import java.util.UUID

enum class AppScreen { IMPORT, PAGE, EDITOR }

class TranslationViewModel(application: Application) : AndroidViewModel(application) {
    var screen by mutableStateOf(AppScreen.IMPORT); private set
    var project by mutableStateOf<ComicProject?>(null); private set
    var recentProjects by mutableStateOf<List<SavedProject>>(emptyList()); private set
    var selectedBlockIndex by mutableStateOf(0); private set
    var sourceScript by mutableStateOf(SourceScript.JAPANESE); private set
    var sourceLanguageTag by mutableStateOf("ja"); private set
    var targetLanguageTag by mutableStateOf("en"); private set
    var busyMessage by mutableStateOf<String?>(null); private set
    var errorMessage by mutableStateOf<String?>(null); private set
    var noticeMessage by mutableStateOf<String?>(null); private set

    private val undoStack = ArrayDeque<ComicProject>()
    private val redoStack = ArrayDeque<ComicProject>()
    private var autosaveJob: Job? = null

    val currentPage get() = project?.let { it.pages.getOrNull(it.currentPageIndex) }
    val currentBlock get() = currentPage?.blocks?.getOrNull(selectedBlockIndex)
    val isLastPage get() = project?.let { it.currentPageIndex == it.pages.lastIndex } ?: false
    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    init { refreshProjects() }

    fun selectSourceScript(value: SourceScript) { sourceScript = value; sourceLanguageTag = value.languageTag }
    fun setSourceLanguage(value: String) { sourceLanguageTag = value }
    fun setTargetLanguage(value: String) { targetLanguageTag = value }
    fun dismissError() { errorMessage = null }
    fun dismissNotice() { noticeMessage = null }

    fun importDocument(uri: Uri) = viewModelScope.launch {
        runBusy("Importing pages...") {
            project = ComicImporter.import(getApplication(), uri)
            resetHistory()
            selectedBlockIndex = 0
            screen = AppScreen.PAGE
            saveNow()
        }
        if (project != null) processCurrentPage()
    }

    fun resumeProject(saved: SavedProject) {
        project = saved.project
        sourceScript = saved.sourceScript
        sourceLanguageTag = saved.sourceLanguageTag
        targetLanguageTag = saved.targetLanguageTag
        selectedBlockIndex = 0
        resetHistory()
        screen = AppScreen.PAGE
    }

    fun deleteProject(saved: SavedProject) = viewModelScope.launch {
        runBusy("Deleting project...") { ProjectStore.delete(getApplication(), saved.project.id); refreshProjectsNow() }
    }

    fun processCurrentPage() = viewModelScope.launch {
        val page = currentPage ?: return@launch
        runBusy("Detecting and translating text...") {
            val blocks = OcrTranslationEngine.process(getApplication(), page, sourceScript, sourceLanguageTag, targetLanguageTag)
            recordState()
            replaceCurrentPage(
                page.copy(renderedSource = page.originalSource, blocks = blocks, processed = true, saved = false),
                record = false,
            )
            if (blocks.isEmpty()) noticeMessage = "No text was detected. Try another source script."
        }
    }

    fun openEditor() {
        if (currentPage?.blocks.isNullOrEmpty()) {
            errorMessage = "No text blocks are available. Detect again or add a text block manually."
            return
        }
        selectedBlockIndex = selectedBlockIndex.coerceIn(0, currentPage?.blocks?.lastIndex ?: 0)
        screen = AppScreen.EDITOR
    }

    fun closeEditor() { screen = AppScreen.PAGE }
    fun updateOriginal(value: String) = editBlock { copy(originalText = value, applied = false) }
    fun updateTranslation(value: String) = editBlock { copy(translatedText = value, applied = false) }
    fun updateFontSize(value: Float) = editBlock { copy(style = style.copy(fontSizeSp = value), applied = false) }
    fun updateRotation(value: Float) = editBlock { copy(style = style.copy(rotationDegrees = value), applied = false) }
    fun updateFont(value: FontChoice) = editBlock { copy(style = style.copy(font = value), applied = false) }
    fun updateAlignment(value: TextAlignmentChoice) = editBlock { copy(style = style.copy(alignment = value), applied = false) }
    fun updateBold(value: Boolean) = editBlock { copy(style = style.copy(bold = value), applied = false) }
    fun updateItalic(value: Boolean) = editBlock { copy(style = style.copy(italic = value), applied = false) }
    fun updateVertical(value: Boolean) = editBlock { copy(style = style.copy(vertical = value), applied = false) }
    fun updateTextColor(value: Long) = editBlock { copy(style = style.copy(textColorArgb = value), applied = false) }
    fun previousBlock() { if (selectedBlockIndex > 0) selectedBlockIndex-- }
    fun nextBlock() { if (selectedBlockIndex < (currentPage?.blocks?.lastIndex ?: 0)) selectedBlockIndex++ }

    fun updateBounds(value: RelativeBounds) = editBlock { copy(bounds = value, applied = false) }
    fun updateHorizontal(center: Float) = editBlock { copy(bounds = resizeBounds(bounds, centerX = center), applied = false) }
    fun updateVertical(center: Float) = editBlock { copy(bounds = resizeBounds(bounds, centerY = center), applied = false) }
    fun updateWidth(width: Float) = editBlock { copy(bounds = resizeBounds(bounds, width = width), applied = false) }
    fun updateHeight(height: Float) = editBlock { copy(bounds = resizeBounds(bounds, height = height), applied = false) }

    fun addTextBlock() {
        val page = currentPage ?: return
        recordState()
        val block = TextBlock(UUID.randomUUID().toString(), "", "", RelativeBounds(.2f, .35f, .8f, .55f))
        replaceCurrentPage(page.copy(blocks = page.blocks + block, saved = false), record = false)
        selectedBlockIndex = page.blocks.size
        screen = AppScreen.EDITOR
    }

    fun deleteCurrentBlock() = viewModelScope.launch {
        val page = currentPage ?: return@launch
        if (page.blocks.isEmpty()) return@launch
        recordState()
        val blocks = page.blocks.toMutableList().apply { removeAt(selectedBlockIndex) }
        val rendered = PageRenderer.apply(getApplication(), page, blocks)
        replaceCurrentPage(page.copy(renderedSource = rendered, blocks = blocks, saved = false), record = false)
        if (blocks.isEmpty()) { selectedBlockIndex = 0; screen = AppScreen.PAGE }
        else selectedBlockIndex = selectedBlockIndex.coerceAtMost(blocks.lastIndex)
    }

    fun translateCurrentBlock() = viewModelScope.launch {
        val block = currentBlock ?: return@launch
        runBusy("Translating selected text...") {
            val translation = OcrTranslationEngine.translateText(block.originalText, sourceLanguageTag, targetLanguageTag)
            editBlock { copy(translatedText = translation, applied = false) }
        }
    }

    fun applyBlock() = viewModelScope.launch {
        val page = currentPage ?: return@launch
        val block = currentBlock ?: return@launch
        runBusy("Replacing text on page...") {
            val blocks = page.blocks.toMutableList().apply { this[selectedBlockIndex] = block.copy(applied = true) }
            val rendered = PageRenderer.apply(getApplication(), page, blocks)
            recordState()
            replaceCurrentPage(page.copy(renderedSource = rendered, blocks = blocks, saved = false), record = false)
        }
    }

    fun undo() {
        val current = project ?: return
        if (undoStack.isEmpty()) return
        redoStack.addLast(current)
        project = undoStack.removeLast()
        selectedBlockIndex = selectedBlockIndex.coerceAtMost(currentPage?.blocks?.lastIndex?.coerceAtLeast(0) ?: 0)
        scheduleAutosave()
    }

    fun redo() {
        val current = project ?: return
        if (redoStack.isEmpty()) return
        undoStack.addLast(current)
        project = redoStack.removeLast()
        selectedBlockIndex = selectedBlockIndex.coerceAtMost(currentPage?.blocks?.lastIndex?.coerceAtLeast(0) ?: 0)
        scheduleAutosave()
    }

    fun save() = currentPage?.let { replaceCurrentPage(it.copy(saved = true)) }

    fun saveAndNext() {
        save()
        val current = project ?: return
        if (current.currentPageIndex >= current.pages.lastIndex) return
        recordState()
        project = current.copy(currentPageIndex = current.currentPageIndex + 1, updatedAt = System.currentTimeMillis())
        selectedBlockIndex = 0
        screen = AppScreen.PAGE
        scheduleAutosave()
        if (currentPage?.processed != true) processCurrentPage()
    }

    fun saveAndExit() = viewModelScope.launch {
        save()
        val current = project ?: return@launch
        runBusy("Exporting translated comic...") {
            saveNow()
            val destination = ProjectExporter.export(getApplication(), current)
            project = null
            selectedBlockIndex = 0
            resetHistory()
            screen = AppScreen.IMPORT
            noticeMessage = "Saved translated comic to $destination"
            refreshProjectsNow()
        }
    }

    private fun editBlock(transform: TextBlock.() -> TextBlock) {
        val page = currentPage ?: return
        val block = currentBlock ?: return
        recordState()
        val blocks = page.blocks.toMutableList().apply { this[selectedBlockIndex] = block.transform() }
        replaceCurrentPage(page.copy(blocks = blocks, saved = false), record = false)
    }

    private fun replaceCurrentPage(page: ComicPage, record: Boolean = true) {
        val current = project ?: return
        if (record) recordState()
        val pages = current.pages.toMutableList().apply { this[current.currentPageIndex] = page }
        project = current.copy(pages = pages, updatedAt = System.currentTimeMillis())
        scheduleAutosave()
    }

    private fun recordState() {
        project?.let { undoStack.addLast(it); while (undoStack.size > 50) undoStack.removeFirst() }
        redoStack.clear()
    }

    private fun resetHistory() { undoStack.clear(); redoStack.clear() }

    private fun scheduleAutosave() {
        autosaveJob?.cancel()
        autosaveJob = viewModelScope.launch { delay(300); saveNow() }
    }

    private suspend fun saveNow() {
        project?.let { ProjectStore.save(getApplication(), it, sourceScript, sourceLanguageTag, targetLanguageTag) }
        refreshProjectsNow()
    }

    private fun refreshProjects() = viewModelScope.launch { refreshProjectsNow() }
    private suspend fun refreshProjectsNow() { recentProjects = ProjectStore.list(getApplication()) }

    private suspend fun runBusy(message: String, action: suspend () -> Unit) {
        busyMessage = message; errorMessage = null
        try { action() } catch (error: Throwable) { errorMessage = error.message ?: "Something went wrong." }
        finally { busyMessage = null }
    }

    private fun resizeBounds(bounds: RelativeBounds, centerX: Float? = null, centerY: Float? = null, width: Float? = null, height: Float? = null): RelativeBounds {
        val newWidth = (width ?: bounds.right - bounds.left).coerceIn(.05f, 1f)
        val newHeight = (height ?: bounds.bottom - bounds.top).coerceIn(.05f, 1f)
        val x = (centerX ?: (bounds.left + bounds.right) / 2f).coerceIn(newWidth / 2f, 1f - newWidth / 2f)
        val y = (centerY ?: (bounds.top + bounds.bottom) / 2f).coerceIn(newHeight / 2f, 1f - newHeight / 2f)
        return RelativeBounds(x - newWidth / 2f, y - newHeight / 2f, x + newWidth / 2f, y + newHeight / 2f)
    }
}
