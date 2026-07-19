package com.untrustedtranslations.android.persistence

import android.content.Context
import android.net.Uri
import com.untrustedtranslations.android.model.ComicPage
import com.untrustedtranslations.android.model.ComicProject
import com.untrustedtranslations.android.model.FontChoice
import com.untrustedtranslations.android.model.ImportFormat
import com.untrustedtranslations.android.model.RelativeBounds
import com.untrustedtranslations.android.model.SavedProject
import com.untrustedtranslations.android.model.SourceScript
import com.untrustedtranslations.android.model.TextAlignmentChoice
import com.untrustedtranslations.android.model.TextBlock
import com.untrustedtranslations.android.model.TextStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object ProjectStore {
    suspend fun save(
        context: Context,
        project: ComicProject,
        script: SourceScript,
        sourceTag: String,
        targetTag: String,
    ) = withContext(Dispatchers.IO) {
        val root = projectRoot(context, project.id).apply { mkdirs() }
        val json = JSONObject().apply {
            put("version", 4)
            put("id", project.id)
            put("title", project.title)
            put("format", project.format.name)
            put("currentPageIndex", project.currentPageIndex)
            put("updatedAt", System.currentTimeMillis())
            put("sourceScript", script.name)
            put("sourceLanguageTag", sourceTag)
            put("targetLanguageTag", targetTag)
            put("pages", JSONArray().apply { project.pages.forEach { put(pageToJson(it)) } })
        }
        val temporary = File(root, "project.json.tmp")
        temporary.writeText(json.toString())
        val destination = File(root, "project.json")
        if (destination.exists()) destination.delete()
        check(temporary.renameTo(destination)) { "Unable to save project metadata." }
    }

    suspend fun list(context: Context): List<SavedProject> = withContext(Dispatchers.IO) {
        val root = File(context.filesDir, "projects")
        root.listFiles().orEmpty().mapNotNull { directory ->
            runCatching { read(File(directory, "project.json")) }.getOrNull()
        }.sortedByDescending { it.project.updatedAt }
    }

    suspend fun delete(context: Context, projectId: String) = withContext(Dispatchers.IO) {
        val projectsRoot = File(context.filesDir, "projects").canonicalFile
        val root = projectRoot(context, projectId).canonicalFile
        if (root.path.startsWith(projectsRoot.path + File.separator)) root.deleteRecursively()
    }

    /**
     * Every text edit renders a fresh `rendered-*.png`; nothing removes the old ones, so a
     * project grows by one full-page PNG per edit. Safe to run only when undo history is
     * empty (project open), since undo snapshots may still point at older renders.
     */
    suspend fun pruneStaleRenders(project: ComicProject) = withContext(Dispatchers.IO) {
        val referenced = project.pages.mapNotNull { it.renderedSource.path }.toSet()
        project.pages
            .mapNotNull { it.originalSource.path?.let(::File)?.parentFile }
            .distinct()
            .forEach { directory ->
                directory.listFiles().orEmpty().forEach { file ->
                    if (file.name.startsWith("rendered-") && file.path !in referenced) file.delete()
                }
            }
    }

    private fun read(file: File): SavedProject {
        val json = JSONObject(file.readText())
        val pagesJson = json.getJSONArray("pages")
        val pages = (0 until pagesJson.length()).map { pageFromJson(pagesJson.getJSONObject(it)) }
        return SavedProject(
            project = ComicProject(
                id = json.getString("id"),
                title = json.getString("title"),
                format = ImportFormat.valueOf(json.getString("format")),
                pages = pages,
                currentPageIndex = json.optInt("currentPageIndex")
                    .coerceIn(0, pages.lastIndex.coerceAtLeast(0)),
                updatedAt = json.optLong("updatedAt", file.lastModified()),
            ),
            sourceScript = enumValueOrDefault(
                json.optString("sourceScript"),
                SourceScript.JAPANESE,
            ),
            sourceLanguageTag = json.optString(
                "sourceLanguageTag",
                enumValueOrDefault(json.optString("sourceScript"), SourceScript.JAPANESE).languageTag,
            ),
            targetLanguageTag = json.optString("targetLanguageTag", "en"),
        )
    }

    private fun pageToJson(page: ComicPage) = JSONObject().apply {
        put("id", page.id)
        put("displayName", page.displayName)
        put("originalSource", page.originalSource.path)
        put("renderedSource", page.renderedSource.path)
        put("saved", page.saved)
        put("processed", page.processed)
        put("blocks", JSONArray().apply { page.blocks.forEach { put(blockToJson(it)) } })
    }

    private fun pageFromJson(json: JSONObject): ComicPage {
        val blocksJson = json.getJSONArray("blocks")
        return ComicPage(
            id = json.getString("id"),
            displayName = json.getString("displayName"),
            originalSource = Uri.fromFile(File(json.getString("originalSource"))),
            renderedSource = Uri.fromFile(File(json.getString("renderedSource"))),
            blocks = (0 until blocksJson.length()).map { blockFromJson(blocksJson.getJSONObject(it)) },
            saved = json.optBoolean("saved"),
            processed = json.optBoolean("processed"),
        )
    }

    private fun blockToJson(block: TextBlock) = JSONObject().apply {
        put("id", block.id)
        put("originalText", block.originalText)
        put("translatedText", block.translatedText)
        put("left", block.bounds.left)
        put("top", block.bounds.top)
        put("right", block.bounds.right)
        put("bottom", block.bounds.bottom)
        put("eraseBounds", block.eraseBounds?.let { boundsToJson(it) } ?: JSONObject.NULL)
        put("fontSizeSp", block.style.fontSizeSp)
        put("rotationDegrees", block.style.rotationDegrees)
        put("font", block.style.font.name)
        put("alignment", block.style.alignment.name)
        put("bold", block.style.bold)
        put("italic", block.style.italic)
        put("vertical", block.style.vertical)
        put("textColorArgb", block.style.textColorArgb)
        put("backgroundColorArgb", block.style.backgroundColorArgb ?: JSONObject.NULL)
        put("applied", block.applied)
    }

    private fun blockFromJson(json: JSONObject): TextBlock {
        val bounds = RelativeBounds(
            json.getDouble("left").toFloat(),
            json.getDouble("top").toFloat(),
            json.getDouble("right").toFloat(),
            json.getDouble("bottom").toFloat(),
        )
        return TextBlock(
            id = json.getString("id"),
            originalText = json.optString("originalText"),
            translatedText = json.optString("translatedText"),
            bounds = bounds,
            eraseBounds = if (!json.has("eraseBounds")) bounds else json.optJSONObject("eraseBounds")?.let(::boundsFromJson),
            style = TextStyle(
                fontSizeSp = json.optDouble("fontSizeSp", 22.0).toFloat(),
                rotationDegrees = json.optDouble("rotationDegrees", 0.0).toFloat(),
                font = enumValueOrDefault(json.optString("font"), FontChoice.AUTO),
                alignment = enumValueOrDefault(json.optString("alignment"), TextAlignmentChoice.CENTER),
                bold = json.optBoolean("bold", true),
                italic = json.optBoolean("italic", false),
                vertical = json.optBoolean("vertical", false),
                textColorArgb = json.optLong("textColorArgb", 0xFF000000),
                backgroundColorArgb = if (json.isNull("backgroundColorArgb")) null else json.optLong("backgroundColorArgb"),
            ),
            applied = json.optBoolean("applied"),
        )
    }

    private fun boundsToJson(bounds: RelativeBounds) = JSONObject().apply {
        put("left", bounds.left)
        put("top", bounds.top)
        put("right", bounds.right)
        put("bottom", bounds.bottom)
    }

    private fun boundsFromJson(json: JSONObject) = RelativeBounds(
        json.getDouble("left").toFloat(),
        json.getDouble("top").toFloat(),
        json.getDouble("right").toFloat(),
        json.getDouble("bottom").toFloat(),
    )

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, default: T): T =
        enumValues<T>().firstOrNull { it.name == value } ?: default

    private fun projectRoot(context: Context, projectId: String) =
        File(File(context.filesDir, "projects"), projectId)
}
