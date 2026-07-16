package com.untrustedtranslations.android.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.material3.MenuAnchorType
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.untrustedtranslations.android.importer.ImportContract
import com.untrustedtranslations.android.model.ComicPage
import com.untrustedtranslations.android.model.FontChoice
import com.untrustedtranslations.android.model.SavedProject
import com.untrustedtranslations.android.model.SourceScript
import com.untrustedtranslations.android.model.TextAlignmentChoice
import java.text.DateFormat
import java.util.Date
import java.util.Locale

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
    Box(Modifier.fillMaxSize().background(AppColors.Void)) {
        when (vm.screen) {
            AppScreen.IMPORT -> ImportScreen(vm)
            AppScreen.PAGE -> PageScreen(vm)
            AppScreen.EDITOR -> EditorScreen(vm)
        }
        vm.busyMessage?.let { message -> BusyOverlay(message) }
    }
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
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(8.dp))
        Surface(shape = RoundedCornerShape(24.dp), color = Color(0xFF211A38), modifier = Modifier.size(72.dp)) {
            Icon(Icons.Default.FileOpen, null, Modifier.padding(19.dp), tint = AppColors.Cyan)
        }
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
        Selector(
            label = "Source text",
            value = vm.sourceScript.label,
            options = SourceScript.entries.map { it.label },
        ) { label -> vm.selectSourceScript(SourceScript.entries.first { it.label == label }) }
        Selector(
            label = "Source language",
            value = languageName(vm.sourceLanguageTag),
            options = targetTags.map(::languageName),
        ) { label -> vm.setSourceLanguage(targetTags.first { languageName(it) == label }) }
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
        Text(
            "A page advances only after you tap Save & Next.",
            color = AppColors.Muted,
            style = MaterialTheme.typography.bodySmall,
        )
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
            modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
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
                actions = {
                    IconButton(vm::undo, enabled = vm.canUndo) { Icon(Icons.Default.Undo, "Undo") }
                    IconButton(vm::redo, enabled = vm.canRedo) { Icon(Icons.Default.Redo, "Redo") }
                    TextButton(vm::save) { Text(if (page.saved) "Saved" else "Save") }
                    if (vm.isLastPage) {
                        Button(vm::saveAndExit) { Text("Save & Exit") }
                    } else {
                        Button(vm::saveAndNext) { Text("Save & Next") }
                    }
                    Spacer(Modifier.width(8.dp))
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(14.dp).fillMaxSize()) {
            PagePreview(page, Modifier.weight(1f).fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(vm::processCurrentPage, Modifier.weight(1f)) {
                    Icon(Icons.Default.Refresh, null)
                    Spacer(Modifier.width(5.dp))
                    Text("Detect")
                }
                OutlinedButton(vm::addTextBlock, Modifier.weight(1f)) {
                    Icon(Icons.Default.Add, null)
                    Spacer(Modifier.width(5.dp))
                    Text("Add text")
                }
                Button(vm::openEditor, Modifier.weight(1.15f), enabled = page.blocks.isNotEmpty()) {
                    Text("Editor (${page.blocks.size})")
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
                page.blocks.forEach { block ->
                    drawRect(
                        color = if (block.applied) AppColors.Cyan else AppColors.Violet,
                        topLeft = androidx.compose.ui.geometry.Offset(
                            block.bounds.left * size.width,
                            block.bounds.top * size.height,
                        ),
                        size = androidx.compose.ui.geometry.Size(
                            (block.bounds.right - block.bounds.left) * size.width,
                            (block.bounds.bottom - block.bounds.top) * size.height,
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
    val centerX = (block.bounds.left + block.bounds.right) / 2f
    val centerY = (block.bounds.top + block.bounds.bottom) / 2f
    val width = block.bounds.right - block.bounds.left
    val height = block.bounds.bottom - block.bounds.top
    Scaffold(
        containerColor = AppColors.Void,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(vm::closeEditor) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                title = { Text("Text ${vm.selectedBlockIndex + 1} of ${page.blocks.size}") },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppColors.Void),
                actions = {
                    IconButton(vm::undo, enabled = vm.canUndo) { Icon(Icons.Default.Undo, "Undo") }
                    IconButton(vm::redo, enabled = vm.canRedo) { Icon(Icons.Default.Redo, "Redo") }
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
            ValueSlider("Font size", block.style.fontSizeSp, 10f..96f, "sp", vm::updateFontSize)
            ValueSlider("Rotation", block.style.rotationDegrees, -180f..180f, " degrees", vm::updateRotation)
            Selector("Font", block.style.font.label, FontChoice.entries.map { it.label }) { label ->
                vm.updateFont(FontChoice.entries.first { it.label == label })
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
            Text("TEXT BOX", color = AppColors.Muted, style = MaterialTheme.typography.labelMedium)
            ValueSlider("Horizontal position", centerX, 0f..1f, "%") { vm.updateHorizontal(it) }
            ValueSlider("Vertical position", centerY, 0f..1f, "%") { vm.updateVertical(it) }
            ValueSlider("Box width", width, .05f..1f, "%") { vm.updateWidth(it) }
            ValueSlider("Box height", height, .05f..1f, "%") { vm.updateHeight(it) }
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(vm::previousBlock, enabled = vm.selectedBlockIndex > 0, modifier = Modifier.weight(1f)) {
                    Text("Previous")
                }
                OutlinedButton(
                    vm::nextBlock,
                    enabled = vm.selectedBlockIndex < page.blocks.lastIndex,
                    modifier = Modifier.weight(1f),
                ) { Text("Next text") }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
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
