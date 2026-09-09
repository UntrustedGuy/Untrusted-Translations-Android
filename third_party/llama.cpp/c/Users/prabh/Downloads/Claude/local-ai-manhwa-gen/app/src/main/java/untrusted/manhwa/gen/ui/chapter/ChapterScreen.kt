package untrusted.manhwa.gen.ui.chapter

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import untrusted.manhwa.gen.data.EntityType
import untrusted.manhwa.gen.data.StoryEntity
import untrusted.manhwa.gen.domain.ProcessStep
import untrusted.manhwa.gen.ui.Routes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterScreen(nav: NavHostController, projectId: Long, chapterId: Long) {
    val vm: ChapterViewModel = viewModel()
    var tab by rememberSaveable { mutableIntStateOf(0) }

    LaunchedEffect(chapterId) { vm.openChapter(projectId, chapterId) }

    val chapter by vm.chapter.collectAsState(initial = null)
    val entities by vm.entities.collectAsState(initial = emptyList())
    val scenes by vm.scenes.collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(chapter?.name ?: "…") },
                navigationIcon = {
                    IconButton(onClick = { nav.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.reprocess() }, enabled = !vm.processing) {
                        Icon(Icons.Filled.Refresh, "Re-process")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (vm.processing) {
                // Processing view with step-by-step progress
                ProcessingView(
                    status = vm.processingStatus,
                    steps = vm.processingSteps,
                    onCancel = { vm.cancelProcessing() },
                )
                return@Scaffold
            }

            // Show error/completion status if any
            AnimatedVisibility(
                visible = vm.processingStatus.isNotBlank(),
                enter = expandVertically(),
                exit = shrinkVertically(),
            ) {
                Text(
                    vm.processingStatus,
                    Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            TabRow(selectedTabIndex = tab) {
                listOf("Characters", "Locations", "Items", "Scenes").forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                }
            }

            when (tab) {
                0 -> EntityList(entities.filter { it.type == EntityType.CHARACTER }) {
                    nav.navigate(Routes.entity(projectId, it.id))
                }
                1 -> EntityList(entities.filter { it.type == EntityType.LOCATION }) {
                    nav.navigate(Routes.entity(projectId, it.id))
                }
                2 -> EntityList(entities.filter { it.type == EntityType.ITEM }) {
                    nav.navigate(Routes.entity(projectId, it.id))
                }
                3 -> Box(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize()) {
                        if (scenes.isEmpty()) {
                            Text(
                                "No scenes extracted.",
                                Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            OutlinedButton(
                                onClick = { nav.navigate(Routes.scenes(projectId, chapterId)) },
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                            ) { Text("Open panel workshop (${scenes.size} scenes)") }
                            LazyColumn(
                                Modifier.fillMaxSize().padding(horizontal = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(scenes, key = { it.id }) { s ->
                                    Card(Modifier.fillMaxWidth()) {
                                        Column(Modifier.padding(12.dp)) {
                                            Text(s.title, style = MaterialTheme.typography.titleSmall)
                                            Text(
                                                s.summary,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProcessingView(
    status: String,
    steps: List<ProcessStep>,
    onCancel: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(16.dp))
        Text(
            status,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(24.dp))

        // Step-by-step progress
        if (steps.isNotEmpty()) {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        "Processing steps:",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    steps.forEach { step ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            when {
                                step.completed -> Icon(
                                    Icons.Filled.CheckCircle,
                                    contentDescription = "Completed",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                                step.current -> CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                                else -> Icon(
                                    Icons.Filled.HourglassTop,
                                    contentDescription = "Pending",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            Text(
                                step.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = when {
                                    step.completed -> MaterialTheme.colorScheme.onSurface
                                    step.current -> MaterialTheme.colorScheme.primary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        Text(
            "First processing of a chapter takes several minutes\n(the AI reads the whole text)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.padding(top = 16.dp),
        ) { Text("Cancel") }
    }
}

@Composable
private fun EntityList(list: List<StoryEntity>, onClick: (StoryEntity) -> Unit) {
    if (list.isEmpty()) {
        Text(
            "Nothing found in this chapter.",
            Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(list, key = { it.id }) { e ->
            Card(Modifier.fillMaxWidth().clickable { onClick(e) }) {
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(e.name, style = MaterialTheme.typography.titleSmall)
                        if (e.description.isNotBlank()) {
                            Text(
                                e.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                            )
                        }
                    }
                    Text(
                        if (e.tag != null) "✓ @${e.tag}" else "untagged",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (e.tag != null) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
