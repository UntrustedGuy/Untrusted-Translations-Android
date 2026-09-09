package untrusted.manhwa.gen.domain

import org.json.JSONArray
import org.json.JSONObject
import untrusted.manhwa.gen.data.AppDb
import untrusted.manhwa.gen.data.ChapterEntityRef
import untrusted.manhwa.gen.data.EntityType
import untrusted.manhwa.gen.data.StoryEntity
import untrusted.manhwa.gen.engine.EngineManager

/**
 * Chapter text -> entities & scenes via the local LLM, in small structured
 * passes (a 3B model is unreliable with one giant prompt). All parsing is
 * defensive: the model's JSON is validated field by field.
 */
class ChapterProcessor(private val db: AppDb) {

    /** Strips markdown fences and finds the outermost JSON object/array. */
    private fun cleanJson(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("```")) {
            s = s.removePrefix("```json").removePrefix("```").trim()
            if (s.endsWith("```")) s = s.removeSuffix("```").trim()
        }
        val start = s.indexOfFirst { it == '{' || it == '[' }
        if (start > 0) s = s.substring(start)
        // Truncate at reasonable bound to avoid feeding garbage back
        val closeBrace = when {
            s.startsWith("{") -> s.indexOfLast { it == '}' }
            s.startsWith("[") -> s.indexOfLast { it == ']' }
            else -> -1
        }
        if (closeBrace > 0) s = s.substring(0, closeBrace + 1)
        return s
    }

    private fun chunk(text: String, maxChars: Int = 5000): List<String> {
        if (text.length <= maxChars) return listOf(text)
        val chunks = mutableListOf<String>()
        var i = 0
        while (i < text.length) {
            var end = (i + maxChars).coerceAtMost(text.length)
            if (end < text.length) {
                // break on a paragraph boundary where possible
                val nl = text.lastIndexOf("\n\n", end)
                if (nl > i + maxChars / 2) end = nl
            }
            chunks.add(text.substring(i, end))
            i = end
        }
        return chunks
    }

    /**
     * Pass 1: entity extraction, merged into the project (dedup by name).
     * Returns the number of new entities found.
     */
    suspend fun extractEntities(
        projectId: Long,
        chapterId: Long,
        text: String,
        onStatus: (String) -> Unit = {},
    ): Int {
        var newCount = 0
        val parts = chunk(text)
        if (parts.isEmpty()) return 0

        for ((idx, part) in parts.withIndex()) {
            val statusMsg = if (parts.size > 1) {
                "Finding entities… chunk ${idx + 1} of ${parts.size}"
            } else {
                "Finding characters, locations, items…"
            }
            onStatus(statusMsg)

            val out = runCatching {
                EngineManager.complete(ENTITY_SYSTEM, part, maxTokens = 1500)
            }.getOrNull() ?: continue

            val json = runCatching { JSONObject(cleanJson(out)) }.getOrNull() ?: continue

            suspend fun handle(arrayKey: String, type: String, descKey: String) {
                val arr = json.optJSONArray(arrayKey) ?: return
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val name = o.optString("name").trim()
                    if (name.isEmpty() || name.length > 60) continue
                    val desc = o.optString(descKey).trim()
                    val existing = db.entities().byName(projectId, name, type)
                    val id = if (existing == null) {
                        newCount++
                        db.entities().insert(
                            StoryEntity(projectId = projectId, type = type, name = name, description = desc)
                        )
                    } else {
                        // enrich an empty description, never overwrite user edits
                        if (existing.description.isBlank() && desc.isNotBlank() && !existing.descriptionConfirmed) {
                            db.entities().update(existing.copy(description = desc))
                        }
                        existing.id
                    }
                    db.entities().link(ChapterEntityRef(chapterId, id))
                }
            }
            handle("characters", EntityType.CHARACTER, "appearance")
            handle("locations", EntityType.LOCATION, "description")
            handle("items", EntityType.ITEM, "description")
        }
        return newCount
    }

    /** Pass 2: scene breakdown. Replaces existing scenes for the chapter. */
    suspend fun extractScenes(
        chapterId: Long,
        text: String,
        onStatus: (String) -> Unit = {},
    ): Int {
        db.scenes().deleteForChapter(chapterId)
        var order = 0
        val parts = chunk(text, 6000)
        if (parts.isEmpty()) return 0

        for ((idx, part) in parts.withIndex()) {
            val statusMsg = if (parts.size > 1) {
                "Splitting scenes… chunk ${idx + 1} of ${parts.size}"
            } else {
                "Splitting into visual scenes…"
            }
            onStatus(statusMsg)

            val out = runCatching {
                EngineManager.complete(SCENE_SYSTEM, part, maxTokens = 1200)
            }.getOrNull() ?: continue

            val arr = runCatching { JSONArray(cleanJson(out)) }.getOrNull() ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val title = o.optString("title").trim()
                val summary = o.optString("summary").trim()
                if (title.isEmpty() && summary.isEmpty()) continue
                db.scenes().insert(
                    untrusted.manhwa.gen.data.Scene(
                        chapterId = chapterId,
                        orderIdx = order++,
                        title = title.ifEmpty { "Scene ${order}" },
                        summary = summary,
                    )
                )
            }
        }
        return order
    }

    /**
     * Pass 3 (Phase 6): panels for one scene. Tagged character names are
     * passed to the model so it can reference them; @tags are then injected
     * by name-matching (deterministic code, not the LLM).
     */
    suspend fun breakdownScene(
        scene: untrusted.manhwa.gen.data.Scene,
        taggedCharacters: List<StoryEntity>,
    ): Int {
        db.panels().deleteForScene(scene.id)
        val names = taggedCharacters.mapNotNull { e ->
            e.tag?.let { "${e.name} -> @$it" }
        }.joinToString("\n")
        val user = "Characters and their tags:\n$names\n\nScene: ${scene.title}\n${scene.summary}"
        val out = EngineManager.complete(PANEL_SYSTEM, user, maxTokens = 1800) ?: return 0
        val arr = runCatching { JSONArray(cleanJson(out)) }.getOrNull() ?: return 0
        var order = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            var prompt = o.optString("prompt").trim()
            if (prompt.isEmpty()) continue
            // Deterministic tag injection: replace known character names with @tags
            for (c in taggedCharacters) {
                val tag = c.tag ?: continue
                if (!prompt.contains("@$tag") && prompt.contains(c.name, ignoreCase = true)) {
                    prompt = prompt.replace(Regex(Regex.escape(c.name), RegexOption.IGNORE_CASE), "@$tag")
                }
            }
            db.panels().insert(
                untrusted.manhwa.gen.data.Panel(
                    sceneId = scene.id,
                    chapterId = scene.chapterId,
                    orderIdx = order++,
                    prompt = prompt,
                    dialogue = o.optString("dialogue").trim(),
                    narration = o.optString("narration").trim(),
                )
            )
        }
        return order
    }

    companion object {
        const val ENTITY_SYSTEM =
            "You extract structured data from novel chapters. Reply with ONLY valid JSON, no " +
                "markdown fences, no commentary. Schema: {\"characters\":[{\"name\":string," +
                "\"appearance\":string}],\"locations\":[{\"name\":string,\"description\":string}]," +
                "\"items\":[{\"name\":string,\"description\":string}]}. Extract every named " +
                "character, location, and significant item. For appearance, include only visual " +
                "details stated in the text (hair, eyes, build, clothing). Empty string if none."

        const val SCENE_SYSTEM =
            "You split novel text into visual scenes for a comic. Reply with ONLY a valid JSON " +
                "array, no fences, no commentary: [{\"title\":string,\"summary\":string}]. A scene " +
                "is a continuous piece of action in one place. The summary must describe what " +
                "VISUALLY happens (who is present, where, doing what) in 2-4 sentences."

        const val PANEL_SYSTEM =
            "You turn a comic scene into 3-8 panels. Reply with ONLY a valid JSON array, no " +
                "fences: [{\"prompt\":string,\"dialogue\":string,\"narration\":string}]. Each prompt " +
                "describes ONE image: characters present (use their names), action, camera shot " +
                "(close-up/medium/wide), mood, background. dialogue = spoken words only, empty if " +
                "silent. narration = caption text, usually empty. Never mention speech bubbles in " +
                "the prompt."
    }
}
