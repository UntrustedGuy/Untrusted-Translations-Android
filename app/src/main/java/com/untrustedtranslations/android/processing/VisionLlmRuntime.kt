package com.untrustedtranslations.android.processing

import android.content.Context
import android.graphics.Bitmap
import com.untrustedtranslations.android.model.SourceScript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Local multimodal OCR backed by llama.cpp libmtmd and Qwen2-VL. */
internal object VisionLlmRuntime {
    private val mutex = Mutex()
    private var libraryLoaded = false
    private var loadedPack: ModelPackId? = null

    private external fun nativeLoad(modelPath: String, projectorPath: String, nativeLibraryDir: String): Int
    private external fun nativeRead(rgb: ByteArray, width: Int, height: Int, prompt: String): String?
    private external fun nativeUnload()

    suspend fun recognize(
        context: Context,
        pack: ModelPackId,
        bitmap: Bitmap,
        script: SourceScript,
    ): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            ensureLoaded(context, pack)
            val scaled = scaled(bitmap, 768)
            try {
                val prompt = "Read the ${script.label} dialogue text in this comic speech bubble. " +
                    "Return the exact text only. Join its lines in natural reading order. " +
                    "Do not translate, describe the image, or include sound effects."
                clean(nativeRead(rgb(scaled), scaled.width, scaled.height, prompt).orEmpty())
            } finally {
                if (scaled !== bitmap) scaled.recycle()
            }
        }
    }

    suspend fun release() = mutex.withLock {
        if (loadedPack != null) withContext(Dispatchers.IO) { nativeUnload() }
        loadedPack = null
    }

    private fun ensureLoaded(context: Context, pack: ModelPackId) {
        if (!libraryLoaded) {
            System.loadLibrary("vision-chat")
            libraryLoaded = true
        }
        if (loadedPack == pack) return
        if (loadedPack != null) nativeUnload()
        val directory = ModelPackManager.directory(context, pack)
        val result = nativeLoad(
            directory.resolve("model.gguf").absolutePath,
            directory.resolve("mmproj.gguf").absolutePath,
            context.applicationInfo.nativeLibraryDir,
        )
        check(result == 0) { "Could not load the local vision model (error $result)." }
        loadedPack = pack
    }

    private fun scaled(source: Bitmap, maximum: Int): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= maximum) return source
        val scale = maximum.toFloat() / longest
        return Bitmap.createScaledBitmap(
            source,
            (source.width * scale).roundToInt().coerceAtLeast(1),
            (source.height * scale).roundToInt().coerceAtLeast(1),
            true,
        )
    }

    private fun rgb(bitmap: Bitmap): ByteArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val output = ByteArray(pixels.size * 3)
        pixels.forEachIndexed { index, color ->
            output[index * 3] = (color ushr 16 and 255).toByte()
            output[index * 3 + 1] = (color ushr 8 and 255).toByte()
            output[index * 3 + 2] = (color and 255).toByte()
        }
        return output
    }

    private fun clean(raw: String): String = raw.trim()
        .removePrefix("Text:")
        .removePrefix("Dialogue:")
        .trim()
        .trim('"', '\'', '`')
}
