package untrusted.manhwa.gen.ui.project

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import kotlinx.coroutines.launch
import untrusted.manhwa.gen.data.AppDb
import untrusted.manhwa.gen.data.Chapter
import untrusted.manhwa.gen.data.ChapterStatus
import untrusted.manhwa.gen.domain.ModelStore
import untrusted.manhwa.gen.ui.Routes
import java.io.File

private val SIZE_PRESETS = listOf(320 to 448, 384 to 576, 448 to 640, 512 to 768)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectScreen(nav: NavHostController, projectId: Long) {
    val context = LocalContext.current
    val db = remember { AppDb.get(context) }
    val scope = rememberCoroutineScope()
    val project by db.projects().byIdFlow(projectId).collectAsState(initial = null)
    val chapters by db.chapters().byProject(projectId).collectAsState(initial = emptyList())
    var showSettings by mutableStateOf(false)
    var showDeleteDialog by mutableStateOf(false)

    val pickTxt = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val idx = db.chapters().maxOrder(projectId) + 1
            val dest = File(ModelStore.chaptersDir(context), "p${projectId}_c$idx.txt")
            val copied = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { input.copyTo(it) }
                } != null
            }.getOrDefault(false)
            if (copied && dest.length() > 0) {
                db.chapters().insert(
                    Chapter(projectId = projectId, orderIdx = idx, name = "Chapter $idx", txtPath = dest.absolutePath)
                )
            } else { dest.delete() }
        }
    }

    if (showDeleteDialog && project != null) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete series?") },
            text = { Text("This will delete \"${project!!.name}\" and all its chapters, entities, and scenes. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        db.projects().delete(project!!)
                        showDeleteDialog = false
                        nav.popBackStack()
                    }
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") } },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(project?.name ?: "...") },
                navigationIcon = { IconButton(onClick = { nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    IconButton(onClick = { nav.navigate(Routes.settings(projectId)) }) { Icon(Icons.Filled.Tune, "Generation settings") }
                    IconButton(onClick = { nav.navigate(Routes.MODELS) }) { Icon(Icons.Filled.Settings, "Models") }
                    IconButton(onClick = { showDeleteDialog = true }) { Icon(Icons.Filled.Delete, "Delete series", tint = MaterialTheme.colorScheme.error) }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "settings") {
                val p = project ?: return@item
                Card(
                    onClick = { showSettings = !showSettings },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Tune, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 8.dp))
                            Column {
                                Text("Generation Settings", style = MaterialTheme.typography.titleSmall)
                                Text("${p.genWidth}x${p.genHeight} . ${p.genSteps} steps . CFG ${p.genCfg}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Icon(
                            if (showSettings) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            if (showSettings) "Collapse" else "Expand",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (showSettings) {
                    SettingsContent(p, db, scope)
                }
            }

            item(key = "upload") {
                Button(
                    onClick = { pickTxt.launch(arrayOf("text/plain", "application/octet-stream")) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.UploadFile, null)
                    Text("  Upload chapter (.txt)")
                }
            }

            items(chapters, key = { it.id }) { c ->
                Card(
                    Modifier.fillMaxWidth().clickable { nav.navigate(Routes.chapter(projectId, c.id)) },
                ) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(c.name, style = MaterialTheme.typography.titleMedium)
                        AssistChip(
                            onClick = {},
                            label = { Text(
                                when (c.status) {
                                    ChapterStatus.UPLOADED -> "uploaded"
                                    ChapterStatus.PROCESSED -> "processed"
                                    ChapterStatus.PANELS_GENERATED -> "panels ready"
                                    ChapterStatus.ASSEMBLED -> "assembled"
                                    else -> c.status
                                }
                            ) },
                        )
                    }
                }
            }

            item(key = "bottom") { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun SettingsContent(
    p: untrusted.manhwa.gen.data.Project,
    db: AppDb,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    var width by remember(p.id) { mutableStateOf(p.genWidth) }
    var height by remember(p.id) { mutableStateOf(p.genHeight) }
    var steps by remember(p.id) { mutableStateOf(p.genSteps.toFloat()) }
    var cfg by remember(p.id) { mutableStateOf(p.genCfg) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SIZE_PRESETS.forEach { (w, h) ->
                    FilterChip(
                        selected = width == w && height == h,
                        onClick = { width = w; height = h },
                        label = { Text("${w}x$h", style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
            Text("Steps: ${steps.toInt()}", style = MaterialTheme.typography.labelMedium)
            Slider(value = steps, onValueChange = { steps = it }, valueRange = 8f..30f, steps = 21)
            Text("CFG: %.1f".format(cfg), style = MaterialTheme.typography.labelMedium)
            Slider(value = cfg, onValueChange = { cfg = it }, valueRange = 3f..12f)
            Button(
                onClick = {
                    scope.launch {
                        db.projects().update(p.copy(
                            genWidth = width, genHeight = height,
                            genSteps = steps.toInt(), genCfg = cfg
                        ))
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
        }
    }
}
