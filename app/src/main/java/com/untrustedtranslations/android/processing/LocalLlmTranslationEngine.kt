package com.untrustedtranslations.android.processing

import android.content.Context
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import com.arm.aichat.isModelLoaded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale

/** On-device, context-aware manga translation powered by the official llama.cpp Android binding. */
object LocalLlmTranslationEngine {
    private val mutex = Mutex()
    private var loadedPack: ModelPackId? = null

    suspend fun translate(
        context: Context,
        pack: ModelPackId,
        text: String,
        sourceTag: String,
        targetTag: String,
    ): String = mutex.withLock {
        require(pack in localLlmPacks) { "Selected pack is not a local translation model." }
        if (text.isBlank() || sourceTag == targetTag) return@withLock text
        val engine = AiChat.getInferenceEngine(context.applicationContext)
        prepare(engine, context, pack)
        val source = languageName(sourceTag)
        val target = languageName(targetTag)
        val prompt = buildString {
            appendLine("Translate this manga dialogue from $source to $target.")
            appendLine("Preserve character voice, emotion, names, honorifics, and implied context.")
            appendLine("Return only the translated dialogue. Do not explain or add quotation marks.")
            appendLine("Dialogue: $text")
            append("/no_think")
        }
        val raw = engine.sendUserPrompt(prompt, predictLength = 256).toList().joinToString("")
        clean(raw).ifBlank { error("The local model returned an empty translation.") }
    }

    suspend fun release() = mutex.withLock {
        loadedPack = null
        val engine = runCatching { currentEngine }.getOrNull() ?: return@withLock
        if (engine.state.value.isModelLoaded || engine.state.value is InferenceEngine.State.Error) {
            withContext(Dispatchers.IO) { engine.cleanUp() }
        }
        currentEngine = null
    }

    private var currentEngine: InferenceEngine? = null

    private suspend fun prepare(engine: InferenceEngine, context: Context, pack: ModelPackId) {
        currentEngine = engine
        if (loadedPack == pack && engine.state.value is InferenceEngine.State.ModelReady) return
        if (engine.state.value.isModelLoaded || engine.state.value is InferenceEngine.State.Error) {
            withContext(Dispatchers.IO) { engine.cleanUp() }
        }
        val readyState = engine.state.first {
            it is InferenceEngine.State.Initialized || it is InferenceEngine.State.Error
        }
        if (readyState is InferenceEngine.State.Error) throw readyState.exception
        val model = ModelPackManager.directory(context, pack).resolve("model.gguf")
        require(model.isFile) { "Download ${ModelPackManager.info(pack).title} first." }
        engine.loadModel(model.absolutePath)
        engine.setSystemPrompt(
            "You are a professional manga translator. Translate dialogue only, never sound effects. " +
                "Use natural target-language sentences and output only the requested translation.",
        )
        loadedPack = pack
    }

    private fun clean(value: String): String {
        var result = value.trim()
        if ("</think>" in result) result = result.substringAfterLast("</think>").trim()
        result = result.removePrefix("Translation:").removePrefix("Translated dialogue:").trim()
        if (result.length >= 2 && result.first() == '"' && result.last() == '"') {
            result = result.substring(1, result.lastIndex).trim()
        }
        return result
    }

    private fun languageName(tag: String): String =
        Locale.forLanguageTag(tag).getDisplayLanguage(Locale.ENGLISH).ifBlank { tag }

    val localLlmPacks = setOf(
        ModelPackId.LOCAL_LLM_LOW,
        ModelPackId.LOCAL_LLM_MID,
        ModelPackId.LOCAL_LLM_HIGH,
    )
}
