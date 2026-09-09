package untrusted.manhwa.gen.ui.phase0

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import untrusted.manhwa.gen.engine.DownloadInfo
import untrusted.manhwa.gen.engine.ModelDownloader
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Phase0TestScreen(vm: Phase0ViewModel = viewModel()) {
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Model Setup") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ModelPickers(vm)

            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Image (SD)") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Extract (LLM)") })
            }

            when (tab) {
                0 -> ImageTab(vm)
                1 -> ExtractTab(vm)
            }

            StatusPanel(vm)
        }
    }
}

@Composable
private fun ModelPickers(vm: Phase0ViewModel) {
    val pickSd = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let { uri -> vm.importModel(uri, Phase0ViewModel.ModelKind.SD) }
    }
    val pickVae = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let { uri -> vm.importModel(uri, Phase0ViewModel.ModelKind.VAE) }
    }
    val pickLora = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let { uri -> vm.importModel(uri, Phase0ViewModel.ModelKind.LORA) }
    }
    val pickLlm = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let { uri -> vm.importModel(uri, Phase0ViewModel.ModelKind.LLM) }
    }
    val any = arrayOf("*/*")

    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Downloadable models: one-tap fetch straight from HuggingFace
            ModelRow(
                label = "SD checkpoint (AOM3) · 2.1 GB",
                path = vm.sdModelPath,
                busy = vm.busy,
                downloadInfo = vm.downloadInfo["sd"],
                onDownload = { vm.downloadModel(ModelDownloader.SPECS.first { it.id == "sd" }) },
                onPick = { pickSd.launch(any) },
            )
            ModelRow(
                label = "VAE (OrangeMix) · 823 MB",
                path = vm.vaePath,
                busy = vm.busy,
                downloadInfo = vm.downloadInfo["vae"],
                onDownload = { vm.downloadModel(ModelDownloader.SPECS.first { it.id == "vae" }) },
                onPick = { pickVae.launch(any) },
            )
            ModelRow(
                label = "LLM (Qwen2.5-3B) · 2.1 GB",
                path = vm.llmModelPath,
                busy = vm.busy,
                downloadInfo = vm.downloadInfo["llm"],
                onDownload = { vm.downloadModel(ModelDownloader.SPECS.first { it.id == "llm" }) },
                onPick = { pickLlm.launch(any) },
            )
            // Shakker.ai needs a login — no stable direct URL, import manually
            ModelRow(
                label = "Style LoRA (import file)",
                path = vm.loraPath,
                busy = vm.busy,
                downloadInfo = null,
                onDownload = null,
                onPick = { pickLora.launch(any) },
            )
        }
    }
}

@Composable
private fun ModelRow(
    label: String,
    path: String?,
    busy: Boolean,
    downloadInfo: DownloadInfo?,
    onDownload: (() -> Unit)?,
    onPick: () -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelLarge)
                Text(
                    when {
                        downloadInfo != null -> downloadInfo.summary
                        path != null -> {
                            val f = File(path)
                            val size = if (f.exists()) " (%,d MB)".format(f.length() / 1_000_000) else ""
                            f.name + size
                        }
                        else -> "not set — tap Get to download or Pick to choose file"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (downloadInfo != null) {
                // Show an animated circular progress while downloading
                val animatedProgress by animateFloatAsState(
                    targetValue = downloadInfo.fraction,
                    label = "downloadProgress"
                )
                CircularProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier.size(36.dp),
                    strokeWidth = 3.dp,
                )
            } else {
                if (onDownload != null && path == null) {
                    OutlinedButton(
                        onClick = onDownload,
                        enabled = !busy,
                    ) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Get")
                    }
                    Spacer(Modifier.width(4.dp))
                }
                OutlinedButton(
                    onClick = onPick,
                    enabled = !busy,
                ) {
                    Text(if (path != null) "Change" else "Pick")
                }
            }
        }
        downloadInfo?.let {
            LinearProgressIndicator(
                progress = { it.fraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun ImageTab(vm: Phase0ViewModel) {
    OutlinedTextField(
        value = vm.prompt,
        onValueChange = { vm.prompt = it },
        label = { Text("Prompt") },
        modifier = Modifier.fillMaxWidth().height(140.dp),
    )
    OutlinedTextField(
        value = vm.negativePrompt,
        onValueChange = { vm.negativePrompt = it },
        label = { Text("Negative prompt") },
        modifier = Modifier.fillMaxWidth().height(100.dp),
    )
    Text("LoRA strength: %.2f".format(vm.loraStrength), style = MaterialTheme.typography.labelMedium)
    Slider(value = vm.loraStrength, onValueChange = { vm.loraStrength = it }, valueRange = 0f..1f)

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = vm::generateImage,
            enabled = !vm.busy && vm.sdModelPath != null,
            modifier = Modifier.weight(1f),
        ) { Text("Generate 384×576") }
        if (vm.busy) OutlinedButton(onClick = vm::cancel) { Text("Cancel") }
    }

    vm.resultImage?.let { bmp ->
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = "Generated panel",
            modifier = Modifier.fillMaxWidth().aspectRatio(bmp.width.toFloat() / bmp.height),
        )
    }
}

@Composable
private fun ExtractTab(vm: Phase0ViewModel) {
    OutlinedTextField(
        value = vm.extractionInput,
        onValueChange = { vm.extractionInput = it },
        label = { Text("Paste a chapter excerpt") },
        modifier = Modifier.fillMaxWidth().height(180.dp),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = vm::runExtraction,
            enabled = !vm.busy && vm.llmModelPath != null && vm.extractionInput.isNotBlank(),
            modifier = Modifier.weight(1f),
        ) { Text("Extract entities") }
        if (vm.busy) OutlinedButton(onClick = vm::cancel) { Text("Cancel") }
    }
    vm.resultText?.let {
        Card {
            Text(
                it,
                Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

@Composable
private fun StatusPanel(vm: Phase0ViewModel) {
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (vm.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(vm.status, style = MaterialTheme.typography.bodyMedium)
            }
            vm.progress?.let { (step, steps) ->
                if (steps > 0) {
                    val animated by animateFloatAsState(
                        targetValue = step.toFloat() / steps,
                        label = "genProgress"
                    )
                    LinearProgressIndicator(
                        progress = { animated },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val eta = GenProgress.etaSec
                    val etaText = if (eta != null) " · ETA ${eta}s" else ""
                    Text("Step $step / $steps$etaText", style = MaterialTheme.typography.labelSmall)
                }
            }
            vm.lastTimingMs?.let {
                Text("Last run: ${it / 1000}s", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
    Spacer(Modifier.height(24.dp))
}
