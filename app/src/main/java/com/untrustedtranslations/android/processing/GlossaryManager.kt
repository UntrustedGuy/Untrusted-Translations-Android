package com.untrustedtranslations.android.processing

import android.content.Context
import com.untrustedtranslations.android.model.GlossaryEntry
import com.untrustedtranslations.android.model.TranslationMemory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

object GlossaryManager {
    private fun glossaryFile(context: Context, projectId: String): File =
        File(File(context.filesDir, "projects"), "$projectId/glossary.json")

    private fun tmFile(context: Context, projectId: String): File =
        File(File(context.filesDir, "projects"), "$projectId/translation_memory.json")

    suspend fun loadGlossary(context: Context, projectId: String): List<GlossaryEntry> =
        withContext(Dispatchers.IO) {
            val file = glossaryFile(context, projectId)
            if (!file.exists()) return@withContext emptyList()
            runCatching {
                val json = JSONArray(file.readText())
                (0 until json.length()).map { entryFromJson(json.getJSONObject(it)) }
            }.getOrDefault(emptyList())
        }

    suspend fun saveGlossary(context: Context, projectId: String, entries: List<GlossaryEntry>) =
        withContext(Dispatchers.IO) {
            val file = glossaryFile(context, projectId)
            file.parentFile?.mkdirs()
            val json = JSONArray().apply { entries.forEach { put(entryToJson(it)) } }
            file.writeText(json.toString())
        }

    suspend fun loadTranslationMemory(
        context: Context, projectId: String, sourceLang: String, targetLang: String,
    ): List<TranslationMemory> = withContext(Dispatchers.IO) {
        val file = tmFile(context, projectId)
        if (!file.exists()) return@withContext emptyList()
        runCatching {
            val json = JSONArray(file.readText())
            (0 until json.length()).map { tmFromJson(it, json.getJSONObject(it)) }
                .filter { it.sourceLanguage == sourceLang && it.targetLanguage == targetLang }
        }.getOrDefault(emptyList())
    }

    suspend fun recordTranslation(
        context: Context, projectId: String,
        sourceText: String, targetText: String,
        sourceLang: String, targetLang: String,
    ) = withContext(Dispatchers.IO) {
        if (sourceText.isBlank() || targetText.isBlank()) return@withContext
        val file = tmFile(context, projectId)
        file.parentFile?.mkdirs()
        val existing = if (file.exists()) {
            runCatching { JSONArray(file.readText()) }.getOrDefault(JSONArray())
        } else JSONArray()
        // Find existing entry
        for (i in 0 until existing.length()) {
            val obj = existing.getJSONObject(i)
            if (obj.optString("sourceText", "") == sourceText &&
                obj.optString("targetText", "") == targetText
            ) {
                obj.put("usageCount", obj.optInt("usageCount", 1) + 1)
                file.writeText(existing.toString())
                return@withContext
            }
        }
        existing.put(JSONObject().apply {
            put("sourceText", sourceText)
            put("targetText", targetText)
            put("sourceLanguage", sourceLang)
            put("targetLanguage", targetLang)
            put("usageCount", 1)
        })
        file.writeText(existing.toString())
    }

    /** Apply glossary terms to translated text: replace known source terms with preferred translations */
    fun applyGlossary(text: String, entries: List<GlossaryEntry>): String {
        var result = text
        for (entry in entries) {
            val flags = if (entry.caseSensitive) 0.toString() else "(?i)".toString()
            // We use simple replace since regex with Unicode can be tricky on Android
            if (entry.caseSensitive) {
                result = result.replace(entry.sourceText, entry.targetText)
            } else {
                result = result.replace(entry.sourceText, entry.targetText, ignoreCase = true)
            }
        }
        return result
    }

    fun findMatchingEntries(text: String, entries: List<GlossaryEntry>): List<GlossaryEntry> =
        entries.filter { entry ->
            if (entry.caseSensitive) text.contains(entry.sourceText)
            else text.contains(entry.sourceText, ignoreCase = true)
        }

    private fun entryToJson(entry: GlossaryEntry) = JSONObject().apply {
        put("id", entry.id)
        put("sourceText", entry.sourceText)
        put("targetText", entry.targetText)
        put("caseSensitive", entry.caseSensitive)
        put("notes", entry.notes)
    }

    private fun entryFromJson(json: JSONObject) = GlossaryEntry(
        id = json.getString("id"),
        sourceText = json.getString("sourceText"),
        targetText = json.getString("targetText"),
        caseSensitive = json.optBoolean("caseSensitive"),
        notes = json.optString("notes"),
    )

    private fun tmFromJson(index: Int, json: JSONObject) = TranslationMemory(
        sourceText = json.optString("sourceText", ""),
        targetText = json.optString("targetText", ""),
        sourceLanguage = json.optString("sourceLanguage", ""),
        targetLanguage = json.optString("targetLanguage", ""),
        usageCount = json.optInt("usageCount", 1),
    )
}
