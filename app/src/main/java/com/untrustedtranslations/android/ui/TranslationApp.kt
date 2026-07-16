package com.untrustedtranslations.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable fun TranslationApp(vm: TranslationViewModel = viewModel()) = when (vm.screen) {
    AppScreen.IMPORT -> ImportScreen(vm::loadDemoProject)
    AppScreen.PAGE -> PageScreen(vm)
    AppScreen.EDITOR -> EditorScreen(vm)
}

@Composable private fun ImportScreen(onImport: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Icon(Icons.Default.UploadFile, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(20.dp))
        Text("Translate an entire comic", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp)); Text("Import images, PDF, CBZ or ZIP. Every page stays under your control.", color = Color.Gray)
        Spacer(Modifier.height(28.dp)); Button(onClick = onImport, Modifier.fillMaxWidth()) { Text("Import comic") }
        Spacer(Modifier.height(10.dp)); Text("Prototype opens a six-page sample project", style = MaterialTheme.typography.bodySmall)
    }
}

@OptIn(ExperimentalMaterial3Api::class) @Composable private fun PageScreen(vm: TranslationViewModel) {
    val project = vm.project ?: return; val page = vm.currentPage ?: return
    Scaffold(topBar = { TopAppBar(
        title = { Column { Text(project.title); Text("${page.displayName} · ${project.currentPageIndex + 1}/${project.pages.size}", style = MaterialTheme.typography.labelMedium) } },
        actions = { TextButton(onClick = vm::save) { Text(if (page.saved) "Saved" else "Save") }; Button(onClick = vm::saveAndNext, enabled = project.currentPageIndex < project.pages.lastIndex) { Text("Save & Next") }; Spacer(Modifier.width(8.dp)) }
    ) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth().background(Color(0xFFE9E6E1), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) { Text("Manga page preview\n\n${page.blocks.size} text regions detected", style = MaterialTheme.typography.titleLarge) }
            Spacer(Modifier.height(16.dp)); Button(onClick = vm::openEditor, Modifier.fillMaxWidth().height(54.dp)) { Text("Open Editor") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class) @Composable private fun EditorScreen(vm: TranslationViewModel) {
    val page = vm.currentPage ?: return; val block = vm.currentBlock ?: return
    Scaffold(topBar = { TopAppBar(
        navigationIcon = { IconButton(onClick = vm::closeEditor) { Icon(Icons.Default.ArrowBack, "Back") } },
        title = { Text("Text ${vm.selectedBlockIndex + 1} of ${page.blocks.size}") },
        actions = { TextButton(onClick = vm::save) { Text(if (page.saved) "Saved" else "Save") } }
    ) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Original", fontWeight = FontWeight.SemiBold); OutlinedTextField(block.originalText, {}, readOnly = true, modifier = Modifier.fillMaxWidth())
            Text("Translation", fontWeight = FontWeight.SemiBold); OutlinedTextField(block.translatedText, vm::updateTranslation, modifier = Modifier.fillMaxWidth(), minLines = 3)
            Text("Font size: ${block.style.fontSizeSp.toInt()} sp"); Slider(block.style.fontSizeSp, vm::updateFontSize, valueRange = 10f..64f)
            Box(Modifier.fillMaxWidth().border(1.dp, Color.LightGray, RoundedCornerShape(12.dp)).padding(20.dp), contentAlignment = Alignment.Center) { Text(block.translatedText, fontSize = block.style.fontSizeSp.sp) }
            Button(onClick = vm::applyBlock, modifier = Modifier.fillMaxWidth()) { if (block.applied) Icon(Icons.Default.Check, null); Text(if (block.applied) " Applied to page" else "Apply to page") }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(vm::previousBlock, enabled = vm.selectedBlockIndex > 0, modifier = Modifier.weight(1f)) { Text("Previous") }
                OutlinedButton(vm::nextBlock, enabled = vm.selectedBlockIndex < page.blocks.lastIndex, modifier = Modifier.weight(1f)) { Text("Next text") }
            }
            Text("Pages never advance automatically. Use Save & Next from the page screen.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
    }
}
