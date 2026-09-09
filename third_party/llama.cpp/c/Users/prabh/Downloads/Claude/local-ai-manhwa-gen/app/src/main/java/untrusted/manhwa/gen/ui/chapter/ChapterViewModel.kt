package untrusted.manhwa.gen.ui.chapter

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import untrusted.manhwa.gen.data.AppDb
import untrusted.manhwa.gen.data.Chapter
import untrusted.manhwa.gen.data.ChapterStatus
import untrusted.manhwa.gen.data.Scene
import untrusted.manhwa.gen.data.StoryEntity
import untrusted.manhwa.gen.domain.ChapterProcessService
import untrusted.manhwa.gen.domain.ChapterProcessState
import untrusted.manhwa.gen.domain.ProcessStep

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ChapterViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDb.get(app)

    private val chapterIdFlow = MutableStateFlow(-1L)
    private var projectId = -1L

    val chapter: Flow<Chapter?> = chapterIdFlow.flatMapLatest {
        if (it <= 0) flowOf(null) else db.chapters().byIdFlow(it)
    }
    val entities: Flow<List<StoryEntity>> = chapterIdFlow.flatMapLatest {
        if (it <= 0) flowOf(emptyList()) else db.entities().byChapter(it)
    }
    val scenes: Flow<List<Scene>> = chapterIdFlow.flatMapLatest {
        if (it <= 0) flowOf(emptyList()) else db.scenes().byChapter(it)
    }

    /** Processing runs in ChapterProcessService; the UI observes its state. */
    val processing: Boolean
        get() = ChapterProcessState.running && ChapterProcessState.chapterId == chapterIdFlow.value
    val processingStatus: String
        get() = ChapterProcessState.status

    /** Detailed step-by-step progress of the processing pipeline. */
    val processingSteps: List<ProcessStep>
        get() = ChapterProcessState.steps

    fun openChapter(projectId: Long, chapterId: Long) {
        this.projectId = projectId
        if (chapterIdFlow.value == chapterId) return
        chapterIdFlow.value = chapterId
        viewModelScope.launch {
            val c = db.chapters().byId(chapterId) ?: return@launch
            if (c.status == ChapterStatus.UPLOADED && !ChapterProcessState.running) {
                ChapterProcessService.start(getApplication(), projectId, chapterId)
            }
        }
    }

    fun reprocess() {
        if (ChapterProcessState.running) return
        ChapterProcessService.start(getApplication(), projectId, chapterIdFlow.value)
    }

    fun cancelProcessing() {
        ChapterProcessService.stop(getApplication())
    }
}
