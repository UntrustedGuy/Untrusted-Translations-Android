package com.untrustedtranslations.android.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.untrustedtranslations.android.importer.ComicImporter
import com.untrustedtranslations.android.model.*
import com.untrustedtranslations.android.persistence.AiSettings
import com.untrustedtranslations.android.persistence.ProjectStore
import com.untrustedtranslations.android.persistence.SecureAiSettings
import com.untrustedtranslations.android.processing.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import java.util.UUID
import com.untrustedtranslations.android.util.AiTier
import com.untrustedtranslations.android.util.DeviceTierDetector

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
    var placementUpdating by mutableStateOf(false); private set
    var showAddTextDialog by mutableStateOf(false); private set
    var manualTextDraft by mutableStateOf(""); private set
    var manualBackgroundArgb by mutableStateOf<Long?>(null); private set
    var ocrProvider by mutableStateOf(OcrProvider.ML_KIT); private set
    var translationProvider by mutableStateOf(TranslationProvider.ML_KIT); private set
    val deviceProfile = DeviceTierDetector.profile(application)
    var localTranslationPack by mutableStateOf(
        when (deviceProfile.recommendedTier) {
            AiTier.LOW -> ModelPackId.LOCAL_LLM_LOW
            AiTier.MID -> ModelPackId.LOCAL_LLM_MID
            AiTier.HIGH -> ModelPackId.LOCAL_LLM_HIGH
        },
    ); private set
    var geminiApiKeyDraft by mutableStateOf(""); private set
    var openAiApiKeyDraft by mutableStateOf(""); private set
    var anthropicApiKeyDraft by mutableStateOf(""); private set
    var compatibleApiKeyDraft by mutableStateOf(""); private set
    var compatibleBaseUrlDraft by mutableStateOf(""); private set
    var compatibleModelDraft by mutableStateOf(""); private set
    var showAiSettingsDialog by mutableStateOf(false); private set
    var modelPackProgress by mutableStateOf<ModelPackProgress?>(null); private set
    var availableUpdate by mutableStateOf<AppUpdateInfo?>(null); private set
    var checkingForUpdates by mutableStateOf(false); private set
    var showUpdatePrompt by mutableStateOf(false); private set
    private var packRevision by mutableStateOf(0)
    val hasGeminiApiKey get() = geminiApiKeyDraft.isNotBlank()

    private val undoStack = ArrayDeque<ComicProject>()
    private val redoStack = ArrayDeque<ComicProject>()
    private var autosaveJob: Job? = null
    private var placementRenderJob: Job? = null
    private var placementGeneration = 0
    private var perf = PerformanceProfiler()

    val currentPage get() = project?.let { it.pages.getOrNull(it.currentPageIndex) }
    val currentBlock get() = currentPage?.blocks?.getOrNull(selectedBlockIndex)
    val editorBlockIndices get() = currentPage?.blocks?.indices?.filter { index ->
        currentPage?.blocks?.get(index)?.eraseBounds != null
    }.orEmpty()
    val editorPosition get() = editorBlockIndices.indexOf(selectedBlockIndex)
    val editorBlockCount get() = editorBlockIndices.size
    val isLastEditorBlock get() = editorPosition == editorBlockIndices.lastIndex
    val isLastPage get() = project?.let { it.currentPageIndex == it.pages.lastIndex } ?: false
    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    init {
        loadProviderDrafts()
        refreshProjects()
        checkForUpdates()
    }

    fun selectSourceScript(value: SourceScript) { sourceScript = value; sourceLanguageTag = value.languageTag }
    fun setSourceLanguage(value: String) { sourceLanguageTag = value }
    fun setTargetLanguage(value: String) { targetLanguageTag = value }
    fun dismissError() { errorMessage = null }
    fun dismissNotice() { noticeMessage = null }

    fun checkForUpdates(showResult: Boolean = false) = viewModelScope.launch {
        if (checkingForUpdates) return@launch
        checkingForUpdates = true
        val refreshed = RemoteMaintenance.refresh(getApplication())
        availableUpdate = RemoteMaintenance.availableAppUpdate(getApplication())
        if (showResult) {
            if (availableUpdate != null) {
                showUpdatePrompt = true
            } else {
                noticeMessage = if (refreshed) "No updates found." else "Could not check right now."
            }
        }
        checkingForUpdates = false
    }

    fun dismissUpdatePrompt() { showUpdatePrompt = false }

    fun acceptUpdatePrompt() {
        showUpdatePrompt = false
        openAvailableUpdate()
    }

    fun openAvailableUpdate() {
        val url = availableUpdate?.releaseUrl ?: return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        getApplication<Application>().startActivity(intent)
    }

    private fun loadProviderDrafts() {
        SecureAiSettings.load(getApplication()).let {
            ocrProvider = it.ocrProvider
            translationProvider = it.translationProvider
            runCatching { ModelPackId.valueOf(it.localTranslationPackName) }
                .getOrNull()
                ?.takeIf { pack -> pack in LocalLlmTranslationEngine.localLlmPacks }
                ?.let { pack -> localTranslationPack = pack }

            geminiApiKeyDraft = it.geminiApiKey
            openAiApiKeyDraft = it.openAiApiKey
            anthropicApiKeyDraft = it.anthropicApiKey
            compatibleApiKeyDraft = it.compatibleApiKey
            compatibleBaseUrlDraft = it.compatibleBaseUrl
            compatibleModelDraft = it.compatibleModel
        }
    }

    fun openAiSettings() {
        loadProviderDrafts()
        showAiSettingsDialog = true
    }
    fun dismissAiSettings() { showAiSettingsDialog = false }
    fun chooseOcrProvider(value: OcrProvider) { ocrProvider = value }
    fun chooseTranslationProvider(value: TranslationProvider) { translationProvider = value }
    fun chooseLocalTranslationPack(value: ModelPackId) {
        require(value in LocalLlmTranslationEngine.localLlmPacks)
        localTranslationPack = value
    }
    fun updateGeminiApiKey(value: String) { geminiApiKeyDraft = value }
    fun updateOpenAiApiKey(value: String) { openAiApiKeyDraft = value }
    fun updateAnthropicApiKey(value: String) { anthropicApiKeyDraft = value }
    fun updateCompatibleApiKey(value: String) { compatibleApiKeyDraft = value }
    fun updateCompatibleBaseUrl(value: String) { compatibleBaseUrlDraft = value }
    fun updateCompatibleModel(value: String) { compatibleModelDraft = value }

    fun clearGeminiApiKey() {
        geminiApiKeyDraft = ""
        if (ocrProvider == OcrProvider.GEMINI_FREE) ocrProvider = OcrProvider.ML_KIT
        if (translationProvider == TranslationProvider.GEMINI_FREE) {
            translationProvider = TranslationProvider.ML_KIT
        }
        saveProviderDrafts()
    }
    fun clearPaidApiKey(provider: TranslationProvider) {
        when (provider) {
            TranslationProvider.OPENAI -> openAiApiKeyDraft = ""
            TranslationProvider.ANTHROPIC -> anthropicApiKeyDraft = ""
            TranslationProvider.OPENAI_COMPATIBLE -> compatibleApiKeyDraft = ""
            else -> Unit
        }
        saveProviderDrafts()
    }

    fun saveAiSettings() {
        val usesGemini =
            ocrProvider == OcrProvider.GEMINI_FREE ||
                translationProvider == TranslationProvider.GEMINI_FREE
        val problem = when {
            ocrProvider == OcrProvider.MANGA_OCR && sourceScript != SourceScript.JAPANESE ->
                "Manga-OCR recognizes Japanese only. Choose another primary dialogue recognizer for ${sourceScript.label}."
            usesGemini && geminiApiKeyDraft.isBlank() ->
                "Paste a Gemini API key, or choose another provider."
            translationProvider == TranslationProvider.OPENAI && openAiApiKeyDraft.isBlank() ->
                "Paste an OpenAI API key, or choose another translator."
            translationProvider == TranslationProvider.ANTHROPIC && anthropicApiKeyDraft.isBlank() ->
                "Paste a Claude API key, or choose another translator."
            translationProvider == TranslationProvider.OPENAI_COMPATIBLE &&
                !compatibleBaseUrlDraft.startsWith("https://") ->
                "The custom API URL must start with https://."
            translationProvider == TranslationProvider.OPENAI_COMPATIBLE &&
                compatibleModelDraft.isBlank() ->
                "Enter the custom API model name."
            else -> null
        }
        if (problem != null) {
            errorMessage = problem
            return
        }
        saveProviderDrafts()
        showAiSettingsDialog = false
        noticeMessage = when {
            translationProvider.paid ->
                "Paid API selected. Your provider may charge your account for every translation."
            translationProvider == TranslationProvider.GOOGLE_UNOFFICIAL ->
                "Unofficial Google Translate is enabled. It is free but may stop working without notice."
            else -> "OCR and translation providers saved."
        }
    }

    private fun saveProviderDrafts() {
        SecureAiSettings.save(
            getApplication(),
            AiSettings(
                ocrProvider = ocrProvider,
                translationProvider = translationProvider,
                localTranslationPackName = localTranslationPack.name,
                geminiApiKey = geminiApiKeyDraft,
                openAiApiKey = openAiApiKeyDraft,
                anthropicApiKey = anthropicApiKeyDraft,
                compatibleApiKey = compatibleApiKeyDraft,
                compatibleBaseUrl = compatibleBaseUrlDraft,
                compatibleModel = compatibleModelDraft,
            ),
        )
    }

    fun isPackInstalled(id: ModelPackId): Boolean {
        @Suppress("UNUSED_VARIABLE") val revision = packRevision
        return ModelPackManager.isInstalled(getApplication(), id)
    }

    fun downloadPack(id: ModelPackId) = viewModelScope.launch {
        if (modelPackProgress != null) return@launch
        try {
            ModelPackManager.download(getApplication(), id) { modelPackProgress = it }
            packRevision++

            noticeMessage = "${ModelPackManager.info(id).title} is ready."
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            errorMessage = error.message ?: "Could not download the model pack."
        } finally {
            modelPackProgress = null
        }
    }

    fun deletePack(id: ModelPackId) = viewModelScope.launch {
        if (id == ModelPackId.NLLB_TRANSLATION) NllbTranslationEngine.release()
        if (id in LocalLlmTranslationEngine.localLlmPacks) LocalLlmTranslationEngine.release()
        if (id == ModelPackId.VLM_OCR_HIGH) VisionLlmRuntime.release()
        if (id == ModelPackId.COMIC_DIALOGUE_DETECTOR) {
            OnnxSessionCache.release("shared_comic_dialogue_detector")
        }
        OnnxSessionCache.release(id.name)
        ModelPackManager.delete(getApplication(), id)
        if (id in requiredOcrPacks(ocrProvider)) ocrProvider = OcrProvider.ML_KIT
        if (id == requiredTranslationPack(translationProvider)) translationProvider = TranslationProvider.ML_KIT
        packRevision++
        saveProviderDrafts()
    }

    private fun requiredOcrPacks(provider: OcrProvider): List<ModelPackId> =
        listOf(ModelPackId.COMIC_DIALOGUE_DETECTOR) + when (provider) {
            OcrProvider.RAPID_OCR -> listOf(ModelPackManager.rapidPack(sourceScript))
            OcrProvider.RAPID_OCR_V5 -> listOf(ModelPackManager.rapidPack(sourceScript, useV5 = true))
            OcrProvider.MANGA_OCR -> listOf(ModelPackId.MANGA_OCR_JAPANESE)
            OcrProvider.COMIC_AI_VISION -> listOf(ModelPackId.VLM_OCR_HIGH)
            OcrProvider.GEMINI_FREE, OcrProvider.ML_KIT -> emptyList()
        }

    private fun requiredTranslationPack(provider: TranslationProvider): ModelPackId? = when (provider) {
        TranslationProvider.NLLB -> ModelPackId.NLLB_TRANSLATION
        TranslationProvider.LOCAL_AI -> localTranslationPack
        else -> null
    }

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

    fun importFolder(uri: Uri) = viewModelScope.launch {
        runBusy("Importing image folder...") {
            project = ComicImporter.importFolder(getApplication(), uri)
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

    fun processCurrentPage(deepScan: Boolean = false) = viewModelScope.launch {
        val page = currentPage ?: return@launch
        if (ocrProvider == OcrProvider.MANGA_OCR && sourceScript != SourceScript.JAPANESE) {
            errorMessage = "Manga-OCR recognizes Japanese only. Choose another primary dialogue recognizer."
            return@launch
        }
        val ocrPack = ModelPackManager.rapidPack(sourceScript, ocrProvider == OcrProvider.RAPID_OCR_V5)
        requiredOcrPacks(ocrProvider).forEach { pack ->
            if (!isPackInstalled(pack)) {
                errorMessage = "Download the ${ModelPackManager.info(pack).title} pack first."
                return@launch
            }
        }
        requiredTranslationPack(translationProvider)?.let { pack ->
            if (!isPackInstalled(pack)) {
                errorMessage = "Download the ${ModelPackManager.info(pack).title} pack first."
                return@launch
            }
        }
        val message = when (ocrProvider) {
            OcrProvider.GEMINI_FREE -> "Detecting dialogue with Gemini..."
            OcrProvider.RAPID_OCR -> "Detecting dialogue with RapidOCR..."
            OcrProvider.RAPID_OCR_V5 -> "Detecting dialogue with PP-OCRv5..."
            OcrProvider.MANGA_OCR -> "Reading dialogue with Comic AI..."
            OcrProvider.COMIC_AI_VISION -> "Reading dialogue with local Comic AI Vision..."
            OcrProvider.ML_KIT -> if (deepScan) "Deep scanning dialogue..." else "Detecting dialogue..."
        }
        perf.start()
        runBusy(message) {
            perf.lap("Detect")
            val primaryDetected = when (ocrProvider) {
                OcrProvider.GEMINI_FREE -> GeminiPageEngine.process(
                    getApplication(), page, sourceScript, sourceLanguageTag,
                    targetLanguageTag, geminiApiKeyDraft,
                )
                OcrProvider.ML_KIT -> OcrTranslationEngine.process(
                    getApplication(), page, sourceScript, sourceLanguageTag,
                    targetLanguageTag, deepScan,
                )
                OcrProvider.RAPID_OCR,
                OcrProvider.RAPID_OCR_V5 -> RapidOcrPageEngine.process(
                    getApplication(), page, sourceScript, ocrPack,
                )
                OcrProvider.MANGA_OCR -> MangaOcrPageEngine.process(
                    getApplication(),
                    page,
                    sourceScript,
                    ModelPackId.COMIC_DIALOGUE_DETECTOR,
                    ModelPackId.MANGA_OCR_JAPANESE,
                    deepScan,
                )
                OcrProvider.COMIC_AI_VISION -> ComicVisionPageEngine.process(
                    getApplication(),
                    page,
                    sourceScript,
                    ModelPackId.COMIC_DIALOGUE_DETECTOR,
                    ModelPackId.VLM_OCR_HIGH,
                    deepScan,
                )
            }
            val detected = when (ocrProvider) {
                OcrProvider.GEMINI_FREE,
                OcrProvider.ML_KIT,
                OcrProvider.RAPID_OCR,
                OcrProvider.RAPID_OCR_V5 -> DialogueOnlyFilter.keepDialogue(
                    getApplication(), page, primaryDetected, deepScan,
                )
                OcrProvider.MANGA_OCR,
                OcrProvider.COMIC_AI_VISION -> primaryDetected
            }
            val manualBlocks = page.blocks.filter { it.eraseBounds == null }
            if (ocrProvider == OcrProvider.COMIC_AI_VISION) {
                VisionLlmRuntime.release()
                perf.lap("Unload vision")
            }
            perf.lap("Translate")
            val translated = if (
                (ocrProvider == OcrProvider.GEMINI_FREE &&
                    translationProvider == TranslationProvider.GEMINI_FREE) ||
                (ocrProvider == OcrProvider.ML_KIT &&
                    translationProvider == TranslationProvider.ML_KIT)
            ) detected else {
                val priorDialogue = mutableListOf<Pair<String, String>>()
                detected.map { block ->
                    val attempt = runCatching {
                        translateWithSelectedProvider(block.originalText, priorDialogue)
                    }
                    val result = attempt.getOrElse { error ->
                        if (errorMessage == null) errorMessage = "Translation failed: ${error.message}"
                        block.originalText
                    }
                    if (attempt.isSuccess && result.isNotBlank()) {
                        priorDialogue += block.originalText to result
                        if (priorDialogue.size > 8) priorDialogue.removeAt(0)
                    }
                    block.withAutomaticTranslationLayout(result)
                }
            }
            recordState()
            replaceCurrentPage(
                page.copy(renderedSource = page.originalSource, blocks = translated + manualBlocks, processed = true, saved = false),
                record = false,
            )
            if (translated.isEmpty()) {
                if (errorMessage == null) noticeMessage =
                    "No dialogue or caption text was found. Sound effects are intentionally ignored."
            }
            perf.log("${page.displayName} ocr=${ocrProvider.name} tl=${translationProvider.name}")
        }
        when (translationProvider) {
            TranslationProvider.NLLB -> NllbTranslationEngine.release()
            TranslationProvider.LOCAL_AI -> LocalLlmTranslationEngine.release()
            else -> Unit
        }
        if (ocrProvider == OcrProvider.COMIC_AI_VISION) VisionLlmRuntime.release()
    }


    fun openEditor() {
        val detectedBlocks = editorBlockIndices
        if (detectedBlocks.isEmpty()) {
            errorMessage = "No detected text is available. Run Detect first."
            return
        }
        if (selectedBlockIndex !in detectedBlocks) selectedBlockIndex = detectedBlocks.first()
        screen = AppScreen.EDITOR
    }

    fun closeEditor() { screen = AppScreen.PAGE }
    fun saveAndCloseEditor() {
        if (busyMessage != null) return
        save()
        screen = AppScreen.PAGE
    }
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
    fun previousBlock() {
        val indices = editorBlockIndices
        val position = indices.indexOf(selectedBlockIndex)
        if (position > 0) selectedBlockIndex = indices[position - 1]
    }
    fun nextBlock() {
        val indices = editorBlockIndices
        val position = indices.indexOf(selectedBlockIndex)
        if (position in 0 until indices.lastIndex) selectedBlockIndex = indices[position + 1]
    }

    fun updateBounds(value: RelativeBounds) = editBlock { copy(bounds = value, applied = false) }
    fun updateHorizontal(center: Float) = editBlock { copy(bounds = resizeBounds(bounds, centerX = center), applied = false) }
    fun updateVertical(center: Float) = editBlock { copy(bounds = resizeBounds(bounds, centerY = center), applied = false) }
    fun updateWidth(width: Float) = editBlock { copy(bounds = resizeBounds(bounds, width = width), applied = false) }
    fun updateHeight(height: Float) = editBlock { copy(bounds = resizeBounds(bounds, height = height), applied = false) }
    fun selectBlock(index: Int) {
        if (index in (currentPage?.blocks?.indices ?: IntRange.EMPTY)) selectedBlockIndex = index
    }

    fun commitPageTransform(index: Int, bounds: RelativeBounds, rotationDegrees: Float, resized: Boolean) {
        val page = currentPage ?: return
        val block = page.blocks.getOrNull(index) ?: return
        if (busyMessage != null) return
        if (block.bounds == bounds && block.style.rotationDegrees == rotationDegrees) return
        val oldHeight = (block.bounds.bottom - block.bounds.top).coerceAtLeast(.001f)
        val newHeight = (bounds.bottom - bounds.top).coerceAtLeast(.001f)
        val nextFontSize = if (resized) {
            (block.style.fontSizeSp * newHeight / oldHeight).coerceIn(8f, 160f)
        } else {
            block.style.fontSizeSp
        }
        val transformed = block.copy(
            bounds = bounds,
            style = block.style.copy(
                fontSizeSp = nextFontSize,
                rotationDegrees = rotationDegrees,
            ),
            applied = true,
        )
        val blocks = page.blocks.toMutableList().apply { this[index] = transformed }
        recordState()
        replaceCurrentPage(page.copy(blocks = blocks, saved = false), record = false)
        selectedBlockIndex = index
        schedulePlacementRender(page.id)
    }

    fun setBlockFontSize(index: Int, sizeSp: Float) {
        val page = currentPage ?: return
        val block = page.blocks.getOrNull(index) ?: return
        if (busyMessage != null || !block.applied) return
        val nextSize = sizeSp.coerceIn(6f, 160f)
        if (nextSize == block.style.fontSizeSp) return
        val blocks = page.blocks.toMutableList().apply {
            this[index] = block.copy(style = block.style.copy(fontSizeSp = nextSize))
        }
        recordState()
        replaceCurrentPage(page.copy(blocks = blocks, saved = false), record = false)
        selectedBlockIndex = index
        schedulePlacementRender(page.id)
    }

    fun scaleBlockFontSize(index: Int, factor: Float) {
        val block = currentPage?.blocks?.getOrNull(index) ?: return
        setBlockFontSize(index, block.style.fontSizeSp * factor)
    }

    private fun schedulePlacementRender(pageId: String) {
        val generation = ++placementGeneration
        placementRenderJob?.cancel()
        placementUpdating = true
        placementRenderJob = viewModelScope.launch {
            try {
                val pageForRender = currentPage ?: return@launch
                if (pageForRender.id != pageId) return@launch
                val rendered = PageRenderer.apply(getApplication(), pageForRender, pageForRender.blocks)
                if (generation == placementGeneration) {
                    val latest = currentPage
                    if (latest?.id == pageId) {
                        replaceCurrentPage(latest.copy(renderedSource = rendered), record = false)
                    }
                }
            } catch (error: Throwable) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                if (generation == placementGeneration) {
                    errorMessage = error.message ?: "Unable to update text placement."
                }
            } finally {
                if (generation == placementGeneration) placementUpdating = false
            }
        }
    }



    fun showAddTextEditor() {
        manualTextDraft = ""
        manualBackgroundArgb = null
        showAddTextDialog = true
    }

    fun dismissAddTextEditor() { showAddTextDialog = false }
    fun updateManualText(value: String) { manualTextDraft = value }
    fun updateManualBackground(value: Long?) { manualBackgroundArgb = value }

    fun confirmAddText() = viewModelScope.launch {
        val page = currentPage ?: return@launch
        val text = manualTextDraft.trim()
        if (text.isBlank() || busyMessage != null) return@launch
        runBusy("Adding text to page...") {
            val block = TextBlock(
                id = UUID.randomUUID().toString(),
                originalText = "",
                translatedText = text,
                bounds = RelativeBounds(.3f, .44f, .7f, .56f),
                eraseBounds = null,
                style = TextStyle(
                    fontSizeSp = 24f,
                    font = FontChoice.MANGA,
                    alignment = TextAlignmentChoice.CENTER,
                    bold = true,
                    textColorArgb = if (manualBackgroundArgb == 0xFF000000L) 0xFFFFFFFFL else 0xFF000000L,
                    backgroundColorArgb = manualBackgroundArgb,
                ),
                applied = true,
            )
            val blocks = page.blocks + block
            val rendered = PageRenderer.apply(getApplication(), page, blocks)
            recordState()
            replaceCurrentPage(
                page.copy(renderedSource = rendered, blocks = blocks, saved = false),
                record = false,
            )
            selectedBlockIndex = blocks.lastIndex
            manualTextDraft = ""
            showAddTextDialog = false
            screen = AppScreen.PAGE
        }
    }

    fun deleteCurrentBlock() = viewModelScope.launch {
        if (busyMessage != null) return@launch
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
            val translation = translateWithSelectedProvider(block.originalText)
            editBlock { withAutomaticTranslationLayout(translation).copy(applied = false) }
        }
        when (translationProvider) {
            TranslationProvider.NLLB -> NllbTranslationEngine.release()
            TranslationProvider.LOCAL_AI -> LocalLlmTranslationEngine.release()
            else -> Unit
        }
    }

    private suspend fun translateWithSelectedProvider(
        text: String,
        priorDialogue: List<Pair<String, String>> = emptyList(),
    ): String =
        when (translationProvider) {
            TranslationProvider.GEMINI_FREE -> GeminiPageEngine.translateText(
                getApplication(), text, sourceLanguageTag, targetLanguageTag, geminiApiKeyDraft,
            )
            TranslationProvider.ML_KIT -> OcrTranslationEngine.translateText(
                text, sourceLanguageTag, targetLanguageTag,
            )
            TranslationProvider.NLLB -> NllbTranslationEngine.translate(
                getApplication(), text, sourceLanguageTag, targetLanguageTag,
            )
            TranslationProvider.LOCAL_AI -> LocalLlmTranslationEngine.translate(
                getApplication(), localTranslationPack, text, sourceLanguageTag, targetLanguageTag,
                priorDialogue,
            )
            TranslationProvider.GOOGLE_UNOFFICIAL -> RemoteTranslationEngines.unofficialGoogle(
                text, sourceLanguageTag, targetLanguageTag,
            )
            TranslationProvider.OPENAI -> RemoteTranslationEngines.openAi(
                text, sourceLanguageTag, targetLanguageTag, openAiApiKeyDraft,
            )
            TranslationProvider.ANTHROPIC -> RemoteTranslationEngines.anthropic(
                text, sourceLanguageTag, targetLanguageTag, anthropicApiKeyDraft,
            )
            TranslationProvider.OPENAI_COMPATIBLE -> RemoteTranslationEngines.openAiCompatible(
                text = text,
                source = sourceLanguageTag,
                target = targetLanguageTag,
                apiKey = compatibleApiKeyDraft,
                baseUrl = compatibleBaseUrlDraft,
                model = compatibleModelDraft,
            )
        }

    fun applyBlock() = viewModelScope.launch {
        if (busyMessage != null) return@launch
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

    fun save() {
        if (busyMessage != null || placementUpdating) return
        currentPage?.let { replaceCurrentPage(it.copy(saved = true)) }
    }
    fun previousPage() {
        if (busyMessage != null || placementUpdating) return
        save()
        val current = project ?: return
        if (current.currentPageIndex <= 0) return
        recordState()
        project = current.copy(
            currentPageIndex = current.currentPageIndex - 1,
            updatedAt = System.currentTimeMillis(),
        )
        selectedBlockIndex = 0
        screen = AppScreen.PAGE
        scheduleAutosave()
        if (currentPage?.processed != true) processCurrentPage()
    }



    fun saveAndNext() {
        if (busyMessage != null || placementUpdating) return
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
        if (busyMessage != null || placementUpdating) return@launch
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

    fun returnHome() {
        if (busyMessage != null || placementUpdating) return
        save()
        selectedBlockIndex = 0
        resetHistory()
        screen = AppScreen.IMPORT
        refreshProjects()
        checkForUpdates()
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

    private fun TextBlock.withAutomaticTranslationLayout(translation: String): TextBlock {
        val targetUsesVerticalWriting = targetLanguageTag == "ja" || targetLanguageTag.startsWith("zh")
        val sourceWasVertical = style.vertical
        val adaptedBounds = if (sourceWasVertical && !targetUsesVerticalWriting) {
            horizontalTranslationBounds(bounds, translation)
        } else {
            bounds
        }
        return copy(
            translatedText = translation,
            bounds = adaptedBounds,
            style = style.copy(
                font = if (targetUsesVerticalWriting) style.font else FontChoice.MANGA,
                vertical = sourceWasVertical && targetUsesVerticalWriting,
            ),
        )
    }

    private fun horizontalTranslationBounds(source: RelativeBounds, translatedText: String): RelativeBounds {
        val characters = translatedText.count { !it.isWhitespace() }.coerceAtLeast(1)
        val lines = ((characters + 17) / 18).coerceAtLeast(1)
        val sourceWidth = source.right - source.left
        val sourceHeight = source.bottom - source.top
        val desiredWidth = maxOf(sourceWidth * 3.2f, (.10f + characters * .007f).coerceAtMost(.45f))
            .coerceIn(.08f, .5f)
        val desiredHeight = maxOf(sourceHeight * .9f, .055f * lines).coerceIn(.05f, .32f)
        val centerX = (source.left + source.right) / 2f
        val centerY = (source.top + source.bottom) / 2f
        val left = (centerX - desiredWidth / 2f).coerceIn(0f, 1f - desiredWidth)
        val top = (centerY - desiredHeight / 2f).coerceIn(0f, 1f - desiredHeight)
        return RelativeBounds(left, top, left + desiredWidth, top + desiredHeight)
    }
    private fun resizeBounds(bounds: RelativeBounds, centerX: Float? = null, centerY: Float? = null, width: Float? = null, height: Float? = null): RelativeBounds {
        val newWidth = (width ?: bounds.right - bounds.left).coerceIn(.05f, 1f)
        val newHeight = (height ?: bounds.bottom - bounds.top).coerceIn(.05f, 1f)
        val x = (centerX ?: (bounds.left + bounds.right) / 2f).coerceIn(newWidth / 2f, 1f - newWidth / 2f)
        val y = (centerY ?: (bounds.top + bounds.bottom) / 2f).coerceIn(newHeight / 2f, 1f - newHeight / 2f)
        return RelativeBounds(x - newWidth / 2f, y - newHeight / 2f, x + newWidth / 2f, y + newHeight / 2f)
    }

    override fun onCleared() {
        OnnxSessionCache.releaseAll()
        super.onCleared()
    }
}
