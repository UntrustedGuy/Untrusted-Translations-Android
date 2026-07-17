package com.untrustedtranslations.android.processing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object RemoteTranslationEngines {
    suspend fun unofficialGoogle(text: String, source: String, target: String): String =
        withContext(Dispatchers.IO) {
            val query = URLEncoder.encode(text, Charsets.UTF_8.name())
            val endpoint =
                "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$source&tl=$target&dt=t&q=$query"
            val response = request(endpoint, "GET")
            val root = JSONArray(response)
            val segments = root.optJSONArray(0) ?: error("Unofficial Google Translate returned no text.")
            buildString {
                for (index in 0 until segments.length()) {
                    append(segments.optJSONArray(index)?.optString(0).orEmpty())
                }
            }.ifBlank { error("Unofficial Google Translate returned an empty translation.") }
        }

    suspend fun openAi(
        text: String,
        source: String,
        target: String,
        apiKey: String,
        model: String = "gpt-5-mini",
    ): String = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Enter an OpenAI API key in Translation settings." }
        val body = JSONObject()
            .put("model", model)
            .put("store", false)
            .put(
                "instructions",
                translationInstruction(source, target),
            )
            .put("input", text)
        val response = JSONObject(
            request(
                "https://api.openai.com/v1/responses",
                "POST",
                body.toString(),
                mapOf("Authorization" to "Bearer ${apiKey.trim()}"),
            ),
        )
        extractOpenAiText(response)
    }

    suspend fun anthropic(
        text: String,
        source: String,
        target: String,
        apiKey: String,
        model: String = "claude-sonnet-4-5",
    ): String = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Enter a Claude API key in Translation settings." }
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", 1024)
            .put("system", translationInstruction(source, target))
            .put(
                "messages",
                JSONArray().put(JSONObject().put("role", "user").put("content", text)),
            )
        val response = JSONObject(
            request(
                "https://api.anthropic.com/v1/messages",
                "POST",
                body.toString(),
                mapOf(
                    "x-api-key" to apiKey.trim(),
                    "anthropic-version" to "2023-06-01",
                ),
            ),
        )
        val content = response.optJSONArray("content") ?: error("Claude returned no content.")
        buildString {
            for (index in 0 until content.length()) {
                val item = content.optJSONObject(index)
                if (item?.optString("type") == "text") append(item.optString("text"))
            }
        }.trim().ifBlank { error("Claude returned an empty translation.") }
    }

    suspend fun openAiCompatible(
        text: String,
        source: String,
        target: String,
        apiKey: String,
        baseUrl: String,
        model: String,
    ): String = withContext(Dispatchers.IO) {
        require(baseUrl.startsWith("https://")) { "The custom API URL must start with https://." }
        require(model.isNotBlank()) { "Enter the custom API model name." }
        val endpoint = baseUrl.trimEnd('/') + "/chat/completions"
        val body = JSONObject()
            .put("model", model)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", translationInstruction(source, target)))
                    .put(JSONObject().put("role", "user").put("content", text)),
            )
        val headers = if (apiKey.isBlank()) emptyMap()
        else mapOf("Authorization" to "Bearer ${apiKey.trim()}")
        val response = JSONObject(request(endpoint, "POST", body.toString(), headers))
        response.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?.trim()
            .orEmpty()
            .ifBlank { error("The custom API returned no translation.") }
    }

    private fun translationInstruction(source: String, target: String) =
        "Translate comic dialogue from $source to $target. Preserve names, tone, intent, and line breaks. " +
            "Use natural concise wording that fits a speech bubble. Return only the translation."

    private fun extractOpenAiText(response: JSONObject): String {
        val direct = response.optString("output_text")
        if (direct.isNotBlank()) return direct.trim()
        val output = response.optJSONArray("output") ?: error("OpenAI returned no output.")
        for (i in 0 until output.length()) {
            val content = output.optJSONObject(i)?.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val item = content.optJSONObject(j) ?: continue
                if (item.optString("type") == "output_text" && item.optString("text").isNotBlank()) {
                    return item.optString("text").trim()
                }
            }
        }
        error("OpenAI returned an empty translation.")
    }

    private fun request(
        endpoint: String,
        method: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): String {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 20_000
        connection.readTimeout = 60_000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "Untrusted-Translations-Android")
        headers.forEach(connection::setRequestProperty)
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
        }
        val code = connection.responseCode
        val payload = (if (code in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (code !in 200..299) {
            val apiMessage = runCatching {
                JSONObject(payload).optJSONObject("error")?.optString("message")
            }.getOrNull().orEmpty()
            error(if (apiMessage.isBlank()) "Translation service failed (HTTP $code)." else apiMessage)
        }
        return payload
    }
}
