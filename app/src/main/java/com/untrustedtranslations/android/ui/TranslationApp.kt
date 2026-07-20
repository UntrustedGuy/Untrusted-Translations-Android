package com.untrustedtranslations.android.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.untrustedtranslations.android.BuildConfig
import com.untrustedtranslations.android.R
import com.untrustedtranslations.android.importer.ImportContract
import com.untrustedtranslations.android.model.*
import com.untrustedtranslations.android.processing.*
import java.text.DateFormat
import kotlin.math.roundToInt
import java.util.Date
import java.util.Locale

// 100% on the text-size controls equals this font size, matching the default block size.
private const val BASE_TEXT_SIZE_SP = 24f

private val targetTags = listOf(
    "af", "ar", "be", "bg", "bn", "ca", "cs", "cy", "da", "de", "el", "en", "eo", "es",
    "et", "fa", "fi", "fr", "ga", "gl", "gu", "he", "hi", "hr", "ht", "hu", "id", "is",
    "it", "ja", "ka", "kn", "ko", "lt", "lv", "mk", "mr", "ms", "mt", "nl", "no", "pl",
    "pt", "ro", "ru", "sk", "sl", "sq", "sv", "sw", "ta", "te", "th", "tl", "tr", "uk",
    "ur", "vi", "zh",
)

private val textColors = linkedMapOf(
    0xFF000000L to "Black",
    0xFFFFFFFFL to "White",
    0xFFB91C1CL to "Red",
    0xFF1D4ED8L to "Blue",
    0xFFFACC15L to "Yellow",
)

private fun languageName(tag: String): String =
    Locale.forLanguageTag(tag).getDisplayLanguage(Locale.getDefault()).replaceFirstChar { it.titlecase() }

@Composable
fun TranslationApp(vm: TranslationViewModel = viewModel()) {
    if (vm.screen == AppScreen.EDITOR) BackHandler(onBack = vm::saveAndCloseEditor)
    Box(Modifier.fillMaxSize().background(AppColors.Void)) {
        when (vm.screen) {
            AppScreen.IMPORT -> ImportScreen(vm)
            AppScreen.PAGE -> PageScreen(vm)
            AppScreen.EDITOR -> EditorScreen(vm)
        }
        vm.busyMessage?.let { message -> BusyOverlay(message) }
    }
    if (vm.showAddTextDialog) AddTextDialog(vm)
    if (vm.showAiSettingsDialog) AiSettingsDialog(vm)
    vm.errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = vm::dismissError,
            confirmButton = { TextButton(vm::dismissError) { Text("OK") } },
            title = { Text("Could not continue") },
            text = { Text(message) },
        )
    }
    vm.noticeMessage?.let { message ->
        AlertDialog(
            onDismissRequest = vm::dismissNotice,
            confirmButton = { TextButton(vm::dismissNotice) { Text("OK") } },
            title = { Text("Finished") },
            text = { Text(message) },
        )
    }
    if (vm.showUpdatePrompt) {
        AlertDialog(
            onDismissRequest = vm::dismissUpdatePrompt,
            confirmButton = { TextButton(vm::acceptUpdatePrompt) { Text("Update") } },
            dismissButton = { TextButton(vm::dismissUpdatePrompt) { Text("Not now") } },
            title = { Text("Update found") },
            text = { Text("Version ${vm.availableUpdate?.version} is available. Would you like to update?") },
        )
    }
}

@Composable

private fun AiSettingsDialog(vm: TranslationViewModel) {
    val usesGemini =
        vm.ocrProvider == OcrProvider.GEMINI_FREE ||
            vm.translationProvider == TranslationProvider.GEMINI_FREE
    AlertDialog(
        onDismissRequest = vm::dismissAiSettings,
        title = { Text("OCR & translation") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("OCR engine", fontWeight = FontWeight.Bold, color = AppColors.Cyan)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("[LOW] ML Kit", color = AppColors.Muted, style = MaterialTheme.typography.labelSmall)
                    Text("[MID] RapidOCR / PP-OCRv5", color = AppColors.Muted, style = MaterialTheme.typography.labelSmall)
                    Text("[HIGH] Gemini / Vision", color = AppColors.Violet, style = MaterialTheme.typography.labelSmall)
                }
                Selector(
                    label = "Primary dialogue recognizer",
                    value = vm.ocrProvider.label,
                    options = OcrProvider.entries
                        .filterNot { BuildConfig.FLAVOR == "foss" && it == OcrProvider.ML_KIT }
                        .map { it.label },
                ) { label ->
                    vm.chooseOcrProvider(OcrProvider.entries.first { it.label == label })
                }
                ProviderNote(
                    "Active pipeline: Comic dialogue detector → ${vm.ocrProvider.label}. Downloaded recognizers stay inactive unless selected above.",
                )
                ModelPackCard(vm, ModelPackId.COMIC_DIALOGUE_DETECTOR)
                when (vm.ocrProvider) {
                    OcrProvider.RAPID_OCR -> {
                        ProviderNote("Selected script: ${vm.sourceScript.label}. Only its matching RapidOCR pack is used.")
                        ModelPackCard(vm, ModelPackManager.rapidPack(vm.sourceScript))
                    }
                    OcrProvider.RAPID_OCR_V5 -> {
                        ProviderNote("Experimental PP-OCRv5 recognition for ${vm.sourceScript.label}.")
                        ModelPackCard(vm, ModelPackManager.rapidPack(vm.sourceScript, useV5 = true))
                    }
                    OcrProvider.MANGA_OCR -> {
                        ProviderNote("Japanese-only Manga-OCR. It is not used by Qwen Vision or other recognizers.")
                        ModelPackCard(vm, ModelPackId.MANGA_OCR_JAPANESE)
                    }
                    OcrProvider.COMIC_AI_VISION -> {
                        ProviderNote(
                            "Qwen2-VL is the only recognizer in this mode. Manga-OCR and RapidOCR are not required or used.",
                        )
                        ProviderNote("Download: about 1.7 GB. Device: ${vm.deviceProfile.summary}")
                        ModelPackCard(vm, ModelPackId.VLM_OCR_HIGH)
                    }
                    OcrProvider.ML_KIT -> ProviderNote(
                        "ML Kit reads candidates; the shared detector keeps only speech-bubble dialogue.",
                    )
                    OcrProvider.GEMINI_FREE -> ProviderNote(
                        "Gemini reads the page with context; its returned boxes are still checked by the shared dialogue detector. Requires a Gemini API key.",
                    )
                }

                Text("Translation engine", fontWeight = FontWeight.Bold, color = AppColors.Cyan)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("[LOW] ML Kit", color = AppColors.Muted, style = MaterialTheme.typography.labelSmall)
                    Text("[MID] Google / Local AI", color = AppColors.Muted, style = MaterialTheme.typography.labelSmall)
                    Text("[HIGH] Local AI High / Gemini / APIs", color = AppColors.Violet, style = MaterialTheme.typography.labelSmall)
                }
                Selector(
                    label = "Primary translator",
                    value = vm.translationProvider.label,
                    options = TranslationProvider.entries
                        .filterNot { BuildConfig.FLAVOR == "foss" && it == TranslationProvider.ML_KIT }
                        .map { it.label },
                ) { label ->
                    vm.chooseTranslationProvider(
                        TranslationProvider.entries.first { it.label == label },
                    )
                }
                ProviderNote(
                    "Active translator: ${vm.translationProvider.label}. Downloaded translation models stay inactive unless selected.",
                )
                when (vm.translationProvider) {
                    TranslationProvider.NLLB -> ModelPackCard(vm, ModelPackId.NLLB_TRANSLATION)
                    TranslationProvider.LOCAL_AI -> {
                        ProviderNote("Device: ${vm.deviceProfile.summary}")
                        ProviderNote(
                            "Models are optional downloads and run fully on this Android device. Higher tiers improve context and sentence quality but need more RAM.",
                        )
                        ProviderNote(
                            "Download any number of models, then tap Use model on exactly one. The choice is remembered after restarting the app.",
                        )
                        listOf(
                            ModelPackId.LOCAL_LLM_LOW,
                            ModelPackId.LOCAL_LLM_MID,
                            ModelPackId.LOCAL_LLM_HIGH,
                        ).forEach { pack ->
                            ModelPackCard(
                                vm = vm,
                                id = pack,
                                active = vm.localTranslationPack == pack,
                                onUse = { vm.chooseLocalTranslationPack(pack) },
                            )
                        }
                    }
                    TranslationProvider.GOOGLE_UNOFFICIAL -> ProviderNote(
                        "Unofficial / experimental. Free and needs no key or login, but Google can change or block it at any time. No cookies are used.",
                        warning = true,
                    )
                    TranslationProvider.ML_KIT -> ProviderNote(
                        "Free and offline. Fast, but its sentence quality can be weaker for context-heavy dialogue.",
                    )
                    TranslationProvider.GEMINI_FREE -> ProviderNote(
                        "Context-aware online translation using the user's free Gemini quota.",
                    )
                    TranslationProvider.OPENAI,
                    TranslationProvider.ANTHROPIC,
                    TranslationProvider.OPENAI_COMPATIBLE -> ProviderNote(
                        "Paid API option. Your provider may charge for every request. The app never enables billing for you.",
                        warning = true,
                    )
                }

                if (usesGemini) {
                    ProviderSecretField(
                        value = vm.geminiApiKeyDraft,
                        onValueChange = vm::updateGeminiApiKey,
                        label = "Gemini API key",
                    )
                    ProviderNote(
                        "Create it in Google AI Studio. Keep billing disabled to stay on the free quota; processing stops when that quota is exhausted.",
                    )
                    if (vm.hasGeminiApiKey) {
                        TextButton(vm::clearGeminiApiKey) { Text("Remove Gemini key / switch account") }
                    }
                }

                when (vm.translationProvider) {
                    TranslationProvider.OPENAI -> {
                        ProviderSecretField(
                            vm.openAiApiKeyDraft,
                            vm::updateOpenAiApiKey,
                            "OpenAI API key",
                        )
                        TextButton({ vm.clearPaidApiKey(TranslationProvider.OPENAI) }) {
                            Text("Remove OpenAI key")
                        }
                    }
                    TranslationProvider.ANTHROPIC -> {
                        ProviderSecretField(
                            vm.anthropicApiKeyDraft,
                            vm::updateAnthropicApiKey,
                            "Claude API key",
                        )
                        TextButton({ vm.clearPaidApiKey(TranslationProvider.ANTHROPIC) }) {
                            Text("Remove Claude key")
                        }
                    }
                    TranslationProvider.OPENAI_COMPATIBLE -> {
                        OutlinedTextField(
                            value = vm.compatibleBaseUrlDraft,
                            onValueChange = vm::updateCompatibleBaseUrl,
                            label = { Text("API base URL (https://...)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(
                            value = vm.compatibleModelDraft,
                            onValueChange = vm::updateCompatibleModel,
                            label = { Text("Model name") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        ProviderSecretField(
                            vm.compatibleApiKeyDraft,
                            vm::updateCompatibleApiKey,
                            "API key (optional if server needs none)",
                        )
                        TextButton({ vm.clearPaidApiKey(TranslationProvider.OPENAI_COMPATIBLE) }) {
                            Text("Remove custom API key")
                        }
                    }
                    else -> Unit
                }

                ProviderNote(
                    "Sound effects are intentionally ignored. ChatGPT and Claude website logins cannot be used as API credit.",
                )
            }
        },
        confirmButton = { Button(vm::saveAiSettings) { Text("Save") } },
        dismissButton = { TextButton(vm::dismissAiSettings) { Text("Cancel") } },
    )
}

@Composable
private fun ProviderSecretField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ProviderNote(text: String, warning: Boolean = false) {
    Text(
        text,
        color = if (warning) Color(0xFFFFB86C) else AppColors.Muted,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun ModelPackCard(
    vm: TranslationViewModel,
    id: ModelPackId,
    active: Boolean = false,
    onUse: (() -> Unit)? = null,
) {
    val info = ModelPackManager.info(id)
    val installed = vm.isPackInstalled(id)
    val progress = vm.modelPackProgress?.takeIf { it.pack == id }
    Card(colors = CardDefaults.cardColors(containerColor = AppColors.SurfaceRaised)) {
        Column(
            Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(info.title, fontWeight = FontWeight.Bold)
            Text(info.description, color = AppColors.Muted, style = MaterialTheme.typography.bodySmall)
            Text(
                "Download: about ${info.downloadSizeMb} MB - minimum recommended RAM: ${info.minimumRamGb} GB",
                color = AppColors.Cyan,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(info.licenseNote, color = AppColors.Muted, style = MaterialTheme.typography.bodySmall)
            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress.fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Downloading ${progress.fileName}... ${(progress.fraction * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (installed) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (active) "Active" else "Installed",
                        color = if (active) AppColors.Violet else AppColors.Cyan,
                        modifier = Modifier.weight(1f),
                    )
                    if (!active && onUse != null) {
                        TextButton(onUse) { Text("Use model") }
                    }
                    TextButton({ vm.deletePack(id) }) { Text("Delete pack") }
                }
            } else {
                OutlinedButton(
                    onClick = { vm.downloadPack(id) },
                    enabled = vm.modelPackProgress == null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Download pack")
                }
            }
        }
    }
}

@Composable
private fun AddTextDialog(vm: TranslationViewModel) {
    AlertDialog(
        onDismissRequest = vm::dismissAddTextEditor,
        title = { Text("Add text") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                OutlinedTextField(
                    value = vm.manualTextDraft,
                    onValueChange = vm::updateManualText,
                    label = { Text("Text") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Font: Manga (Comic Neue Bold)",
                    color = AppColors.Cyan,
                    fontWeight = FontWeight.Bold,
                )
                Text("Background", color = AppColors.Muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = vm.manualBackgroundArgb == null,
                        onClick = { vm.updateManualBackground(null) },
                        label = { Text("None") },
                    )
                    FilterChip(
                        selected = vm.manualBackgroundArgb == 0xFFFFFFFFL,
                        onClick = { vm.updateManualBackground(0xFFFFFFFFL) },
                        label = { Text("White") },
                    )
                    FilterChip(
                        selected = vm.manualBackgroundArgb == 0xFF000000L,
                        onClick = { vm.updateManualBackground(0xFF000000L) },
                        label = { Text("Black") },
                    )
                }
                Text(
                    "Added text starts in the page center. Move, resize, or rotate it directly on the page.",
                    color = AppColors.Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            Button(vm::confirmAddText, enabled = vm.manualTextDraft.isNotBlank()) { Text("Add") }
        },
        dismissButton = {
            TextButton(vm::dismissAddTextEditor) { Text("Cancel") }
        },
    )
}


@Composable
private fun BusyOverlay(message: String) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = .72f)),
        contentAlignment = Alignment.Center,
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = AppColors.SurfaceRaised)) {
            Row(
                Modifier.padding(24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularProgressIndicator(Modifier.size(28.dp))
                Text(message)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImportScreen(vm: TranslationViewModel) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::importDocument)
    }
    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(vm::importFolder)
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Image(
            painter = painterResource(R.drawable.ic_launcher_art),
            contentDescription = "Untrusted Translations icon",
            modifier = Modifier.size(104.dp),
            contentScale = ContentScale.Fit,
        )
        Text(
            "Untrusted\nTranslations",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Black,
            lineHeight = 42.sp,
        )
        Text(
            "Detect, translate, correct, and letter complete comics on your phone.",
            color = AppColors.Muted,
            style = MaterialTheme.typography.bodyLarge,
        )
        vm.availableUpdate?.let { update ->
            Card(colors = CardDefaults.cardColors(containerColor = AppColors.SurfaceRaised)) {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Update available: ${update.version}", fontWeight = FontWeight.Bold)
                    Text(
                        "Open the official GitHub release to download it. Android will ask before installing.",
                        color = AppColors.Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = vm::openAvailableUpdate) { Text("Open download page") }
                }
            }
        }
        OutlinedButton(
            onClick = vm::openAiSettings,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Settings, null)
            Spacer(Modifier.width(8.dp))
            Text("OCR: ${vm.ocrProvider.label}  ?  Translation: ${vm.translationProvider.label}")
        }
        Selector(
            label = "Text detection script",
            value = vm.sourceScript.label,
            options = SourceScript.entries.map { it.label },
        ) { label -> vm.selectSourceScript(SourceScript.entries.first { it.label == label }) }
        Selector(
            label = "Translate from",
            value = languageName(vm.sourceLanguageTag),
            options = targetTags.map(::languageName),
        ) { label -> vm.setSourceLanguage(targetTags.first { languageName(it) == label }) }
        Text(
            "Detection script chooses the OCR model. Translate from chooses the actual language; many languages share the Latin script.",
            color = AppColors.Muted,
            style = MaterialTheme.typography.bodySmall,
        )
        Selector(
            label = "Translate to",
            value = languageName(vm.targetLanguageTag),
            options = targetTags.map(::languageName),
        ) { label -> vm.setTargetLanguage(targetTags.first { languageName(it) == label }) }
        Button(
            onClick = { launcher.launch(ImportContract.mimeTypes) },
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Icon(Icons.Default.FileOpen, null)
            Spacer(Modifier.width(10.dp))
            Text("Import image, PDF, CBZ or ZIP")
        }
        OutlinedButton(
            onClick = { folderLauncher.launch(null) },
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Icon(Icons.Default.FileOpen, null)
            Spacer(Modifier.width(10.dp))
            Text("Select folder of images")
        }
        Text(
            "A page advances only after you tap Save & Next.",
            color = AppColors.Muted,
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedButton(
            onClick = { vm.checkForUpdates(showResult = true) },
            enabled = !vm.checkingForUpdates,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (vm.checkingForUpdates) {
                CircularProgressIndicator(Modifier.size(18.dp))
            } else {
                Icon(Icons.Default.Refresh, null)
            }
            Spacer(Modifier.width(8.dp))
            Text(if (vm.checkingForUpdates) "Checking..." else "Check updates and model links")
        }
        if (vm.recentProjects.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("YOUR PROJECTS", color = AppColors.Cyan, style = MaterialTheme.typography.labelLarge)
            vm.recentProjects.forEach { saved -> ProjectCard(saved, vm) }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun ProjectCard(saved: SavedProject, vm: TranslationViewModel) {
    val project = saved.project
    Card(colors = CardDefaults.cardColors(containerColor = AppColors.SurfaceRaised)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(project.title, fontWeight = FontWeight.Bold, maxLines = 1)
            Text(
                "Page ${project.currentPageIndex + 1} of ${project.pages.size}  •  " +
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(project.updatedAt)),
                color = AppColors.Muted,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button({ vm.resumeProject(saved) }, Modifier.weight(1f)) { Text("Resume") }
                IconButton({ vm.deleteProject(saved) }) { Icon(Icons.Default.Delete, "Delete project") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Selector(label: String, value: String, options: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option) },
                    onClick = { expanded = false; onSelect(option) },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PageScreen(vm: TranslationViewModel) {
    val project = vm.project ?: return
    val page = vm.currentPage ?: return
    var transformMode by remember(page.id) { mutableStateOf(PageTransformMode.RESIZE) }
    Scaffold(
        containerColor = AppColors.Void,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(project.title, maxLines = 1)
                        Text(
                            "${page.displayName}  •  ${project.currentPageIndex + 1}/${project.pages.size}",
                            color = AppColors.Muted,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppColors.Void),
                navigationIcon = {
                    IconButton(vm::returnHome, enabled = !vm.placementUpdating) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Save project and return home")
                    }
                },
                actions = {
                    IconButton(vm::undo, enabled = vm.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
                    IconButton(vm::redo, enabled = vm.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
                    IconButton(vm::openAiSettings) { Icon(Icons.Default.Settings, "AI and OCR settings") }
                    Spacer(Modifier.width(8.dp))
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(14.dp).fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ManipulablePagePreview(
                page = page,
                selectedBlockIndex = vm.selectedBlockIndex,
                mode = transformMode,
                onSelectBlock = vm::selectBlock,
                onTransformCommitted = vm::commitPageTransform,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = transformMode == PageTransformMode.RESIZE,
                    onClick = { transformMode = PageTransformMode.RESIZE },
                    label = { Text("Move / Resize") },
                    enabled = page.blocks.any { it.applied },
                )
                FilterChip(
                    selected = transformMode == PageTransformMode.ROTATE,
                    onClick = { transformMode = PageTransformMode.ROTATE },
                    label = { Text("Move / Rotate") },
                    enabled = page.blocks.any { it.applied },
                )
            }
            val selectedBlock = page.blocks.getOrNull(vm.selectedBlockIndex)
            if (selectedBlock?.applied == true) {
                val committedPercent = (selectedBlock.style.fontSizeSp / BASE_TEXT_SIZE_SP * 100)
                    .coerceIn(25f, 400f)
                // The slider only previews locally while dragging; a single commit fires on
                // release — committing every tick re-rendered the page bitmap per pixel moved.
                var sliderPercent by remember(selectedBlock.id, selectedBlock.style.fontSizeSp) {
                    mutableStateOf(committedPercent)
                }
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Text size", color = AppColors.Muted, style = MaterialTheme.typography.labelMedium)
                    Slider(
                        value = sliderPercent,
                        onValueChange = { sliderPercent = it },
                        onValueChangeFinished = {
                            vm.setBlockFontSize(vm.selectedBlockIndex, BASE_TEXT_SIZE_SP * sliderPercent / 100f)
                        },
                        valueRange = 25f..400f,
                        modifier = Modifier.weight(1f),
                    )
                    TextSizeField(
                        percent = sliderPercent,
                        onCommit = { typed ->
                            sliderPercent = typed
                            vm.setBlockFontSize(vm.selectedBlockIndex, BASE_TEXT_SIZE_SP * typed / 100f)
                        },
                    )
                }
            }
            Text(
                if (transformMode == PageTransformMode.RESIZE) {
                    "Pinch the selected text to resize it live; pinch anywhere else to zoom the page. Drag text to move; edge handles resize the box."
                } else {
                    "Pinch empty page area to zoom. Tap applied text. Drag inside to move; drag the round handle above it to rotate."
                },
                color = AppColors.Muted,
                style = MaterialTheme.typography.bodySmall,
            )
            if (vm.placementUpdating) {
                Text(
                    "Saving text placement…",
                    color = AppColors.Cyan,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    vm::previousPage,
                    modifier = Modifier.weight(1f),
                    enabled = project.currentPageIndex > 0 && !vm.placementUpdating,
                ) { Text("Previous page") }
                OutlinedButton(
                    vm::save,
                    modifier = Modifier.weight(1f),
                    enabled = !vm.placementUpdating,
                ) { Text("Save page") }
                Button(
                    onClick = { if (vm.isLastPage) vm.saveAndExit() else vm.saveAndNext() },
                    modifier = Modifier.weight(1f),
                    enabled = !vm.placementUpdating,
                ) {
                    Text(if (vm.isLastPage) "Save & Exit" else "Save & Next")
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ vm.processCurrentPage(deepScan = true) }, Modifier.weight(1f)) {
                    Icon(Icons.Default.Refresh, null)
                    Spacer(Modifier.width(5.dp))
                    Text(if (vm.ocrProvider == OcrProvider.GEMINI_FREE) "Online rescan" else "Deep scan")
                }
                OutlinedButton(vm::showAddTextEditor, Modifier.weight(1f)) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(5.dp))
                    Text("Add text")
                }
                Button(vm::openEditor, Modifier.weight(1.15f), enabled = vm.editorBlockCount > 0) {
                    Text("Editor (${vm.editorBlockCount})")
                }
            }
        }
    }
}

@Composable
private fun PagePreview(page: ComicPage, modifier: Modifier = Modifier) {
    val bitmap = remember(page.renderedSource) { page.renderedSource.path?.let(BitmapFactory::decodeFile) }
    Box(
        modifier.background(AppColors.Surface, RoundedCornerShape(18.dp))
            .border(1.dp, Color(0xFF343240), RoundedCornerShape(18.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap == null) {
            Text("Unable to display page", color = AppColors.Muted)
        } else {
            Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize().padding(6.dp), contentScale = ContentScale.Fit)
            Canvas(Modifier.fillMaxSize().padding(6.dp)) {
                val scale = minOf(size.width / bitmap.width, size.height / bitmap.height)
                val imageWidth = bitmap.width * scale
                val imageHeight = bitmap.height * scale
                val imageLeft = (size.width - imageWidth) / 2f
                val imageTop = (size.height - imageHeight) / 2f
                page.blocks.forEach { block ->
                    drawRect(
                        color = if (block.applied) AppColors.Cyan else AppColors.Violet,
                        topLeft = androidx.compose.ui.geometry.Offset(
                            imageLeft + block.bounds.left * imageWidth,
                            imageTop + block.bounds.top * imageHeight,
                        ),
                        size = androidx.compose.ui.geometry.Size(
                            (block.bounds.right - block.bounds.left) * imageWidth,
                            (block.bounds.bottom - block.bounds.top) * imageHeight,
                        ),
                        style = Stroke(2.dp.toPx()),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditorScreen(vm: TranslationViewModel) {
    val page = vm.currentPage ?: return
    val block = vm.currentBlock ?: return
    Scaffold(
        containerColor = AppColors.Void,
        topBar = {
            TopAppBar(
                title = { Text("Text ${vm.editorPosition + 1} of ${vm.editorBlockCount}") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppColors.Void),
                actions = {
                    IconButton(vm::undo, enabled = vm.canUndo) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo") }
                    IconButton(vm::redo, enabled = vm.canRedo) { Icon(Icons.AutoMirrored.Filled.Redo, "Redo") }
                    IconButton(vm::deleteCurrentBlock) { Icon(Icons.Default.Delete, "Delete text block") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("ORIGINAL OCR — EDIT IF DETECTION IS WRONG", color = AppColors.Muted, style = MaterialTheme.typography.labelMedium)
            OutlinedTextField(
                value = block.originalText,
                onValueChange = vm::updateOriginal,
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
            )
            OutlinedButton(vm::translateCurrentBlock, Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Refresh, null)
                Spacer(Modifier.width(8.dp))
                Text("Translate this text again")
            }
            Text("TRANSLATION — EDIT BEFORE APPLYING", color = AppColors.Cyan, style = MaterialTheme.typography.labelMedium)
            OutlinedTextField(
                value = block.translatedText,
                onValueChange = vm::updateTranslation,
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
            )
            Selector("Font", block.style.font.label, FontChoice.entries.map { it.label }) { label ->
                vm.updateFont(FontChoice.entries.first { it.label == label })
            }
            Text("Text size: ${(block.style.fontSizeSp / BASE_TEXT_SIZE_SP * 100).roundToInt()}%")
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Slider(
                    value = (block.style.fontSizeSp / BASE_TEXT_SIZE_SP * 100).coerceIn(25f, 400f),
                    onValueChange = { vm.updateFontSize(BASE_TEXT_SIZE_SP * it / 100f) },
                    valueRange = 25f..400f,
                    modifier = Modifier.weight(1f),
                )
                TextSizeField(
                    percent = (block.style.fontSizeSp / BASE_TEXT_SIZE_SP * 100).coerceIn(25f, 400f),
                    onCommit = { vm.updateFontSize(BASE_TEXT_SIZE_SP * it / 100f) },
                )
            }
            Selector("Alignment", block.style.alignment.label, TextAlignmentChoice.entries.map { it.label }) { label ->
                vm.updateAlignment(TextAlignmentChoice.entries.first { it.label == label })
            }
            Selector("Text color", textColors.getValue(block.style.textColorArgb), textColors.values.toList()) { label ->
                vm.updateTextColor(textColors.entries.first { it.value == label }.key)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(block.style.bold, { vm.updateBold(!block.style.bold) }, { Text("Bold") })
                FilterChip(block.style.italic, { vm.updateItalic(!block.style.italic) }, { Text("Italic") })
                FilterChip(block.style.vertical, { vm.updateVertical(!block.style.vertical) }, { Text("Vertical") })
            }
            Text(
                "Automatic manga formatting is the default. These controls are optional; size, position, and rotation remain adjustable on the page.",
                color = AppColors.Muted,
                style = MaterialTheme.typography.bodySmall,
            )
            Surface(
                color = AppColors.SurfaceRaised,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    block.translatedText.ifBlank { "Translation preview" },
                    fontSize = block.style.fontSizeSp.coerceAtMost(48f).sp,
                    modifier = Modifier.padding(20.dp),
                    color = AppColors.Text,
                )
            }
            Button(vm::applyBlock, Modifier.fillMaxWidth().height(54.dp), enabled = block.translatedText.isNotBlank()) {
                if (block.applied) {
                    Icon(Icons.Default.Check, null)
                    Spacer(Modifier.width(8.dp))
                }
                Text(if (block.applied) "Apply changes again" else "Replace original text on page")
            }
            if (vm.isLastEditorBlock) {
                Button(
                    vm::saveAndCloseEditor,
                    modifier = Modifier.fillMaxWidth().height(54.dp),
                    enabled = block.applied,
                ) { Text("Save page & close editor") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(vm::previousBlock, enabled = vm.editorPosition > 0, modifier = Modifier.weight(1f)) {
                    Text("Previous")
                }
                OutlinedButton(
                    vm::nextBlock,
                    enabled = vm.editorPosition < vm.editorBlockCount - 1,
                    modifier = Modifier.weight(1f),
                ) { Text("Next text") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TextSizeField(
    percent: Float,
    onCommit: (Float) -> Unit,
) {
    var text by remember(percent.roundToInt()) { mutableStateOf(percent.roundToInt().toString()) }
    val commit = {
        text.toFloatOrNull()?.let { typed -> onCommit(typed.coerceIn(25f, 400f)) }
        Unit
    }
    OutlinedTextField(
        value = text,
        onValueChange = { input -> text = input.filter { it.isDigit() }.take(3) },
        modifier = Modifier
            .width(84.dp)
            .onFocusChanged { if (!it.isFocused) commit() },
        textStyle = MaterialTheme.typography.labelLarge.copy(
            color = AppColors.Cyan,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        ),
        suffix = { Text("%", color = AppColors.Muted) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Number,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { commit() }),
    )
}

@Composable
private fun ValueSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    suffix: String,
    onChange: (Float) -> Unit,
) {
    val shown = if (suffix == "%") (value * 100).toInt() else value.toInt()
    Text("$label: $shown$suffix")
    Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range)
}
