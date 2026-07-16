package com.untrustedtranslations.android.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.untrustedtranslations.android.model.SourceScript

private val targets = linkedMapOf("en" to "English", "es" to "Spanish", "fr" to "French", "de" to "German", "hi" to "Hindi", "pt" to "Portuguese")

@Composable fun TranslationApp(vm: TranslationViewModel = viewModel()) {
    Box(Modifier.fillMaxSize().background(AppColors.Void)) {
        when (vm.screen) {
            AppScreen.IMPORT -> ImportScreen(vm)
            AppScreen.PAGE -> PageScreen(vm)
            AppScreen.EDITOR -> EditorScreen(vm)
        }
        vm.busyMessage?.let { message ->
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .72f)), contentAlignment = Alignment.Center) {
                Card(colors = CardDefaults.cardColors(containerColor = AppColors.SurfaceRaised)) {
                    Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        CircularProgressIndicator(Modifier.size(28.dp)); Text(message)
                    }
                }
            }
        }
    }
    vm.errorMessage?.let { message -> AlertDialog(onDismissRequest = vm::dismissError, confirmButton = { TextButton(vm::dismissError) { Text("OK") } }, title = { Text("Couldn’t continue") }, text = { Text(message) }) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ImportScreen(vm: TranslationViewModel) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importDocument) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.Center) {
        Surface(shape = RoundedCornerShape(28.dp), color = Color(0xFF211A38), modifier = Modifier.size(76.dp)) {
            Icon(Icons.Default.FileOpen, null, Modifier.padding(20.dp), tint = AppColors.Cyan)
        }
        Spacer(Modifier.height(24.dp))
        Text("Untrusted\nTranslations", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black, lineHeight = 42.sp)
        Spacer(Modifier.height(12.dp))
        Text("Translate full comics without giving up control of the final wording or lettering.", color = AppColors.Muted, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(28.dp))
        Selector("Source text", vm.sourceScript.label, SourceScript.entries.map { it.label }) { label -> vm.selectSourceScript(SourceScript.entries.first { it.label == label }) }
        Spacer(Modifier.height(12.dp))
        Selector("Translate to", targets.getValue(vm.targetLanguageTag), targets.values.toList()) { label -> vm.setTargetLanguage(targets.entries.first { it.value == label }.key) }
        Spacer(Modifier.height(24.dp))
        Button(onClick = { launcher.launch(ImportContract.mimeTypes) }, Modifier.fillMaxWidth().height(56.dp)) {
            Icon(Icons.Default.FileOpen, null); Spacer(Modifier.width(10.dp)); Text("Import image, PDF, CBZ or ZIP")
        }
        vm.noticeMessage?.let { Spacer(Modifier.height(16.dp)); Text(it, color = AppColors.Cyan) }
        Spacer(Modifier.height(18.dp))
        Text("Pages are processed only as you reach them. The app advances only when you choose Save & Next.", color = AppColors.Muted, style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun Selector(label: String, value: String, options: List<String>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded, { expanded = !expanded }) {
        OutlinedTextField(value, {}, readOnly = true, label = { Text(label) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth())
        ExposedDropdownMenu(expanded, { expanded = false }) { options.forEach { option -> DropdownMenuItem({ Text(option) }, onClick = { expanded = false; onSelect(option) }) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun PageScreen(vm: TranslationViewModel) {
    val project = vm.project ?: return; val page = vm.currentPage ?: return
    Scaffold(containerColor = AppColors.Void, topBar = { TopAppBar(
        title = { Column { Text(project.title, maxLines = 1); Text("${page.displayName} · ${project.currentPageIndex + 1}/${project.pages.size}", color = AppColors.Muted, style = MaterialTheme.typography.labelMedium) } },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = AppColors.Void),
        actions = {
            TextButton(vm::save) { Text(if (page.saved) "Saved" else "Save") }
            if (vm.isLastPage) Button(vm::saveAndExit) { Text("Save & Exit") } else Button(vm::saveAndNext) { Text("Save & Next") }
            Spacer(Modifier.width(8.dp))
        }
    ) }) { padding ->
        Column(Modifier.padding(padding).padding(14.dp).fillMaxSize()) {
            PagePreview(page, Modifier.weight(1f).fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(vm::processCurrentPage, Modifier.weight(1f)) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(6.dp)); Text("Detect again") }
                Button(vm::openEditor, Modifier.weight(1f), enabled = page.blocks.isNotEmpty()) { Text("Open Editor (${page.blocks.size})") }
            }
        }
    }
}

@Composable private fun PagePreview(page: ComicPage, modifier: Modifier = Modifier) {
    val bitmap = remember(page.renderedSource) { page.renderedSource.path?.let(BitmapFactory::decodeFile) }
    Box(modifier.background(AppColors.Surface, RoundedCornerShape(18.dp)).border(1.dp, Color(0xFF343240), RoundedCornerShape(18.dp)), contentAlignment = Alignment.Center) {
        if (bitmap == null) Text("Unable to display page", color = AppColors.Muted) else {
            Image(bitmap.asImageBitmap(), null, Modifier.fillMaxSize().padding(6.dp), contentScale = ContentScale.Fit)
            Canvas(Modifier.fillMaxSize().padding(6.dp)) {
                page.blocks.forEach { block ->
                    drawRect(if (block.applied) AppColors.Cyan else AppColors.Violet, topLeft = androidx.compose.ui.geometry.Offset(block.bounds.left * size.width, block.bounds.top * size.height), size = androidx.compose.ui.geometry.Size((block.bounds.right - block.bounds.left) * size.width, (block.bounds.bottom - block.bounds.top) * size.height), style = Stroke(2.dp.toPx()))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun EditorScreen(vm: TranslationViewModel) {
    val page = vm.currentPage ?: return; val block = vm.currentBlock ?: return
    Scaffold(containerColor = AppColors.Void, topBar = { TopAppBar(
        navigationIcon = { IconButton(vm::closeEditor) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        title = { Text("Text ${vm.selectedBlockIndex + 1} of ${page.blocks.size}") },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = AppColors.Void),
        actions = { TextButton(vm::save) { Text(if (page.saved) "Saved" else "Save") } }
    ) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("ORIGINAL OCR", color = AppColors.Muted, style = MaterialTheme.typography.labelMedium)
            OutlinedTextField(block.originalText, {}, readOnly = true, modifier = Modifier.fillMaxWidth())
            Text("TRANSLATION — EDIT BEFORE APPLYING", color = AppColors.Cyan, style = MaterialTheme.typography.labelMedium)
            OutlinedTextField(block.translatedText, vm::updateTranslation, modifier = Modifier.fillMaxWidth(), minLines = 3)
            Text("Font size: ${block.style.fontSizeSp.toInt()} sp")
            Slider(block.style.fontSizeSp, vm::updateFontSize, valueRange = 10f..64f)
            Surface(color = AppColors.SurfaceRaised, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Text(block.translatedText, fontSize = block.style.fontSizeSp.sp, modifier = Modifier.padding(20.dp), color = AppColors.Text)
            }
            Button(vm::applyBlock, Modifier.fillMaxWidth().height(52.dp)) { if (block.applied) Icon(Icons.Default.Check, null); Text(if (block.applied) " Applied — apply again after edits" else "Replace original text on page") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(vm::previousBlock, enabled = vm.selectedBlockIndex > 0, modifier = Modifier.weight(1f)) { Text("Previous") }
                OutlinedButton(vm::nextBlock, enabled = vm.selectedBlockIndex < page.blocks.lastIndex, modifier = Modifier.weight(1f)) { Text("Next text") }
            }
        }
    }
}
