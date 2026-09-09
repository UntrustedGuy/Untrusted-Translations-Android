package com.untrustedtranslations.android.processing

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.util.concurrent.ConcurrentHashMap

internal object OnnxSessionCache {
    val environment: OrtEnvironment get() = OrtEnvironment.getEnvironment()

    private val sessions = ConcurrentHashMap<String, OrtSession>()

    @Synchronized
    fun getOrCreate(key: String, model: File): OrtSession =
        sessions.getOrPut(key) {
            val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(2)
            val recognitionHeavy = key.contains("_rec")
            // Manga-OCR decoder runs are now batched instead of four concurrent session.run calls,
            // so non-recognizer sessions can use four intra-op workers without oversubscribing them.
            val threads = minOf(if (recognitionHeavy) 2 else 4, cores)
            val accelerated = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(threads)
                setInterOpNumThreads(1)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                runCatching { addXnnpack(mapOf("intra_op_num_threads" to threads.toString())) }
            }
            try {
                environment.createSession(model.absolutePath, accelerated)
            } catch (acceleratedFailure: Exception) {
                val cpuOnly = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(threads)
                    setInterOpNumThreads(1)
                    setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                }
                try {
                    environment.createSession(model.absolutePath, cpuOnly)
                } catch (cpuFailure: Exception) {
                    cpuFailure.addSuppressed(acceleratedFailure)
                    throw cpuFailure
                } finally {
                    cpuOnly.close()
                }
            } finally {
                accelerated.close()
            }
        }

    @Synchronized
    fun release(prefix: String) {
        val keys = sessions.keys.filter { it.startsWith(prefix) }
        keys.forEach { key -> sessions.remove(key)?.let { runCatching { it.close() } } }
    }

    @Synchronized
    fun releaseAll() {
        sessions.values.forEach { runCatching { it.close() } }
        sessions.clear()
    }
}
