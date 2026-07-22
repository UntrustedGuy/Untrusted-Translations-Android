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
            val accelerated = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(minOf(2, Runtime.getRuntime().availableProcessors()))
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                runCatching { addXnnpack(mapOf("intra_op_num_threads" to "2")) }
            }
            try {
                environment.createSession(model.absolutePath, accelerated)
            } catch (acceleratedFailure: Exception) {
                val cpuOnly = OrtSession.SessionOptions().apply {
                    setIntraOpNumThreads(minOf(2, Runtime.getRuntime().availableProcessors()))
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
