package com.untrustedtranslations.android.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.util.Base64
import com.untrustedtranslations.android.model.ComicPage
import com.untrustedtranslations.android.model.FontChoice
import com.untrustedtranslations.android.model.RelativeBounds
import com.untrustedtranslations.android.model.SourceScript
import com.untrustedtranslations.android.model.TextBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID

object GeminiPageEngine {
    private const val MODEL = "gemini-3.5-flash"
    private const val ENDPOINT =
        "https://generativelanguage.googleapis.com/v1beta/models/$MODEL:generateContent"

    suspend fun process(
        context: Context,
        page: ComicPage,
        script: SourceScript,
        sourceTag: String,
        targetTag: String,
        apiKey: String,
    ): List<TextBlock> = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) {
            "Add a Gemini Free API key in AI settings first. Keep billing disabled for guaranteed zero-cost use."
        }
        val path = requireNotNull(page.originalSource.path)
        val bitmap = requireNotNull(BitmapFactory.decodeFile(path)) { "Unable to inspect this page." }
        try {
            val resultText = request(apiKey, pageRequest(compressedPage(bitmap), sourceTag, targetTag))
            val items = JSONArray(resultText)
            buildList {
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    if (item.optString("kind").equals("sfx", ignoreCase = true)) continue
                    val original = item.optString("original").trim()
                    val translated = item.optString("translated").trim()
                    if (original.length < 2 || translated.isBlank()) continue
                    val rawErase = RelativeBounds(
                        (item.optDouble("left") / 1000.0).toFloat().coerceIn(0f, .98f),
                        (item.optDouble("top") / 1000.0).toFloat().coerceIn(0f, .98f),
                        (item.optDouble("right") / 1000.0).toFloat().coerceIn(.02f, 1f),
                        (item.optDouble("bottom") / 1000.0).toFloat().coerceIn(.02f, 1f),
                    ).normalized()
                    // Shrink by 6% each side to avoid cutting adjacent bubble artwork
                    val insetX = (rawErase.right - rawErase.left) * .12f
                    val insetY = (rawErase.bottom - rawErase.top) * .12f
                    val eraseBounds = RelativeBounds(
                        (rawErase.left + insetX).coerceIn(0f, .99f),
                        (rawErase.top + insetY).coerceIn(0f, .99f),
                        (rawErase.right - insetX).coerceIn(.01f, 1f),
                        (rawErase.bottom - insetY).coerceIn(.01f, 1f),
                    ).normalized()
                    if (eraseBounds.right - eraseBounds.left < .01f ||
                        eraseBounds.bottom - eraseBounds.top < .01f
                    ) continue
                    val pixelBox = Rect(
                        (eraseBounds.left * bitmap.width).toInt(),
                        (eraseBounds.top * bitmap.height).toInt(),
                        (eraseBounds.right * bitmap.width).toInt(),
                        (eraseBounds.bottom * bitmap.height).toInt(),
                    )
                    val estimated = LetteringStyleEstimator.estimate(
                        context, bitmap, pixelBox, original, script, null,
                    )
                    val targetUsesVerticalWriting = targetTag == "ja" || targetTag.startsWith("zh")
                    add(TextBlock(
                        id = UUID.randomUUID().toString(),
                        originalText = original,
                        translatedText = translated,
                        bounds = translatedBounds(
                            eraseBounds, translated, estimated.vertical, targetUsesVerticalWriting,
                        ),
                        eraseBounds = eraseBounds,
                        style = estimated.copy(
                            font = if (targetUsesVerticalWriting) estimated.font else FontChoice.MANGA,
                            vertical = estimated.vertical && targetUsesVerticalWriting,
                        ),
                    ))
                }
            }
        } finally {
            bitmap.recycle()
        }
    }

    suspend fun translateText(
        text: String,
        sourceTag: String,
        targetTag: String,
        apiKey: String,
    ): String = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Add a Gemini Free API key in AI settings first." }
        if (text.isBlank() || sourceTag == targetTag) return@withContext text
        val prompt = """
            Translate this comic dialogue from ${languageName(sourceTag)} to ${languageName(targetTag)}.
            Preserve character voice, names, emotion, implied subjects, and natural sentence flow.
            Return only the translation with no notes or quotation marks.

            $text
        """.trimIndent()
        request(apiKey, simpleTextRequest(prompt)).trim()
    }

    private fun pageRequest(image: ByteArray, sourceTag: String, targetTag: String): JSONObject {
        val prompt = """
            Act as a professional manga, manhwa, and manhua OCR translator.
            Read the entire page using its visual and dialogue context.
            Source language: ${languageName(sourceTag)} ($sourceTag).
            Target language: ${languageName(targetTag)} ($targetTag).

            Return only speech-balloon dialogue and rectangular narration or caption text in reading order.
            Completely exclude sound effects, onomatopoeia, decorative titles, logos, watermarks, credits,
            page numbers, and tiny furigana that merely repeats a nearby base word.
            Do not invent missing text. Join lines that belong to the same balloon.
            Translate naturally for a human comic reader, preserving names, tone, emotion, honorific intent,
            implied subjects, and continuity with nearby balloons.
            Each box coordinate is an integer from 0 to 1000 relative to the whole image:
            left=0 is the left edge, top=0 is the top edge, right=1000 is the right edge,
            and bottom=1000 is the bottom edge. Bounds must tightly cover the original lettering.
        """.trimIndent()

        val coordinate = { JSONObject().put("type", "integer").put("minimum", 0).put("maximum", 1000) }
        val itemSchema = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject()
                .put("original", JSONObject().put("type", "string"))
                .put("translated", JSONObject().put("type", "string"))
                .put("kind", JSONObject().put("type", "string")
                    .put("enum", JSONArray(listOf("dialogue", "caption", "sfx"))))
                .put("left", coordinate())
                .put("top", coordinate())
                .put("right", coordinate())
                .put("bottom", coordinate()))
            .put("required", JSONArray(
                listOf("original", "translated", "kind", "left", "top", "right", "bottom"),
            ))

        return JSONObject()
            .put("contents", JSONArray().put(JSONObject()
                .put("role", "user")
                .put("parts", JSONArray()
                    .put(JSONObject().put("text", prompt))
                    .put(JSONObject().put("inline_data", JSONObject()
                        .put("mime_type", "image/jpeg")
                        .put("data", Base64.encodeToString(image, Base64.NO_WRAP)))))))
            .put("generationConfig", JSONObject()
                .put("temperature", 0.15)
                .put("maxOutputTokens", 8192)
                .put("responseMimeType", "application/json")
                .put("responseSchema", JSONObject().put("type", "array").put("items", itemSchema)))
    }

    private fun simpleTextRequest(prompt: String) = JSONObject()
        .put("contents", JSONArray().put(JSONObject()
            .put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
        .put("generationConfig", JSONObject().put("temperature", 0.2).put("maxOutputTokens", 2048))

    private fun request(apiKey: String, payload: JSONObject): String {
        val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
        return try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20_000
            connection.readTimeout = 120_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("x-goog-api-key", apiKey.trim())
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw IllegalStateException(apiError(status, response))
            val root = JSONObject(response)
            val parts = root.optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts")
                ?: throw IllegalStateException("Gemini returned no readable result.")
            val text = buildString {
                for (index in 0 until parts.length()) {
                    append(parts.optJSONObject(index)?.optString("text").orEmpty())
                }
            }.trim()
            if (text.isBlank()) throw IllegalStateException("Gemini returned an empty result.")
            text.removePrefix("```json").removePrefix("```")
                .removeSuffix("```").trim()
        } finally {
            connection.disconnect()
        }
    }

    private fun apiError(status: Int, response: String): String {
        val detail = runCatching {
            JSONObject(response).optJSONObject("error")?.optString("message")
        }.getOrNull().orEmpty()
        return when (status) {
            400, 401 -> "The Gemini key was rejected. Check the key saved in AI settings."
            403 -> "This Gemini project cannot use the free API in its current region or configuration."
            429 -> "Gemini Free quota is exhausted. Wait for it to reset or use Offline mode; the app never switches to paid usage."
            else -> "Gemini request failed ($status). ${detail.take(180)}".trim()
        }
    }

    private fun compressedPage(source: Bitmap): ByteArray {
        val scale = (1600f / maxOf(source.width, source.height)).coerceAtMost(1f)
        val resized = if (scale < 1f) Bitmap.createScaledBitmap(
            source,
            (source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1),
            true,
        ) else source
        return try {
            ByteArrayOutputStream().use { output ->
                check(resized.compress(Bitmap.CompressFormat.JPEG, 90, output))
                output.toByteArray()
            }
        } finally {
            if (resized !== source) resized.recycle()
        }
    }

    private fun languageName(tag: String): String =
        Locale.forLanguageTag(tag).getDisplayLanguage(Locale.ENGLISH).ifBlank { tag }

    private fun RelativeBounds.normalized(): RelativeBounds =
        RelativeBounds(minOf(left, right), minOf(top, bottom), maxOf(left, right), maxOf(top, bottom))

    private fun translatedBounds(
        source: RelativeBounds,
        translatedText: String,
        sourceWasVertical: Boolean,
        targetUsesVerticalWriting: Boolean,
    ): RelativeBounds {
        if (targetUsesVerticalWriting) return source
        val characters = translatedText.count { !it.isWhitespace() }.coerceAtLeast(1)
        val lines = ((characters + 17) / 18).coerceAtLeast(1)
        val sourceWidth = source.right - source.left
        val sourceHeight = source.bottom - source.top
        val desiredWidth = maxOf(
            sourceWidth * if (sourceWasVertical) 3.2f else 1.4f,
            (.10f + characters * .007f).coerceAtMost(.45f),
        ).coerceIn(.08f, .5f)
        val desiredHeight = maxOf(
            sourceHeight * if (sourceWasVertical) .9f else 1.3f,
            .055f * lines,
        ).coerceIn(.05f, .32f)
        val centerX = (source.left + source.right) / 2f
        val centerY = (source.top + source.bottom) / 2f
        val left = (centerX - desiredWidth / 2f).coerceIn(0f, 1f - desiredWidth)
        val top = (centerY - desiredHeight / 2f).coerceIn(0f, 1f - desiredHeight)
        return RelativeBounds(left, top, left + desiredWidth, top + desiredHeight)
    }
}
