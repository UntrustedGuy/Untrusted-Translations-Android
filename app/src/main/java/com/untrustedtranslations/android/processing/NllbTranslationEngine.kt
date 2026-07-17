package com.untrustedtranslations.android.processing

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import nie.translator.rtranslator.voice_translation.neural_networks.translation.Tokenizer
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.nio.LongBuffer

internal object NllbTranslationEngine {
    private val mutex = Mutex()
    private var runtime: Runtime? = null

    suspend fun translate(
        context: Context,
        text: String,
        sourceLanguageTag: String,
        targetLanguageTag: String,
    ): String = withContext(Dispatchers.IO) {
        mutex.withLock {
            require(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }) {
                "The NLLB pack currently requires a 64-bit ARM Android device."
            }
            val pack = ModelPackId.NLLB_TRANSLATION
            require(ModelPackManager.isInstalled(context, pack)) { "The NLLB pack is not installed." }
            val source = languageCode(sourceLanguageTag)
            val target = languageCode(targetLanguageTag)
            val active = runtime ?: Runtime(context, ModelPackManager.directory(context, pack)).also {
                runtime = it
            }
            active.translate(text, source, target)
        }
    }

    suspend fun release() = withContext(Dispatchers.IO) {
        mutex.withLock {
            runtime?.close()
            runtime = null
        }
    }

    private fun languageCode(tag: String): String = mapOf(
        "af" to "afr_Latn", "ar" to "arb_Arab", "be" to "bel_Cyrl", "bg" to "bul_Cyrl",
        "bn" to "ben_Beng", "ca" to "cat_Latn", "cs" to "ces_Latn", "cy" to "cym_Latn",
        "da" to "dan_Latn", "de" to "deu_Latn", "el" to "ell_Grek", "en" to "eng_Latn",
        "eo" to "epo_Latn", "es" to "spa_Latn", "et" to "est_Latn", "fa" to "pes_Arab",
        "fi" to "fin_Latn", "fr" to "fra_Latn", "ga" to "gle_Latn", "gl" to "glg_Latn",
        "gu" to "guj_Gujr", "he" to "heb_Hebr", "hi" to "hin_Deva", "hr" to "hrv_Latn",
        "ht" to "hat_Latn", "hu" to "hun_Latn", "id" to "ind_Latn", "is" to "isl_Latn",
        "it" to "ita_Latn", "ja" to "jpn_Jpan", "ka" to "kat_Geor", "kn" to "kan_Knda",
        "ko" to "kor_Hang", "lt" to "lit_Latn", "lv" to "lvs_Latn", "mk" to "mkd_Cyrl",
        "mr" to "mar_Deva", "ms" to "zsm_Latn", "mt" to "mlt_Latn", "nl" to "nld_Latn",
        "no" to "nob_Latn", "pl" to "pol_Latn", "pt" to "por_Latn", "ro" to "ron_Latn",
        "ru" to "rus_Cyrl", "sk" to "slk_Latn", "sl" to "slv_Latn", "sq" to "als_Latn",
        "sv" to "swe_Latn", "sw" to "swh_Latn", "ta" to "tam_Taml", "te" to "tel_Telu",
        "th" to "tha_Thai", "tl" to "tgl_Latn", "tr" to "tur_Latn", "uk" to "ukr_Cyrl",
        "ur" to "urd_Arab", "vi" to "vie_Latn", "zh" to "zho_Hans",
    )[tag.substringBefore('-').lowercase()] ?: error("NLLB does not support language: $tag")

    private class Runtime(context: Context, directory: java.io.File) : AutoCloseable {
        private val env = OrtEnvironment.getEnvironment()
        private val options = OrtSession.SessionOptions().apply {
            setMemoryPatternOptimization(false)
            setCPUArenaAllocator(false)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.NO_OPT)
        }
        private val encoder = env.createSession(java.io.File(directory, "NLLB_encoder.onnx").absolutePath, options)
        private val decoder = env.createSession(java.io.File(directory, "NLLB_decoder.onnx").absolutePath, options)
        private val cacheInitializer =
            env.createSession(java.io.File(directory, "NLLB_cache_initializer.onnx").absolutePath, options)
        private val embedAndHead =
            env.createSession(java.io.File(directory, "NLLB_embed_and_lm_head.onnx").absolutePath, options)
        private val tokenizer = Tokenizer(
            java.io.File(directory, "sentencepiece_bpe.model").absolutePath,
            Tokenizer.NLLB,
        )

        fun translate(text: String, source: String, target: String): String {
            val tokenized = tokenizer.tokenize(source, target, text.trim())
            val inputIds = tokenized.inputIDs.take(200).toIntArray()
            val mask = tokenized.attentionMask.take(inputIds.size).toIntArray()
            val encoderIds = longTensor(inputIds)
            val attention = longTensor(mask)
            val emptyPreLogits = zeroFloat(longArrayOf(1, 1, 1024))
            val embeddingMode = boolTensor(false)
            val embedRun = embedAndHead.run(
                mapOf(
                    "input_ids" to encoderIds,
                    "pre_logits" to emptyPreLogits,
                    "use_lm_head" to embeddingMode,
                ),
                setOf("embed_matrix"),
            )
            val encoderRun = encoder.run(
                mapOf(
                    "input_ids" to encoderIds,
                    "attention_mask" to attention,
                    "embed_matrix" to (embedRun[0] as OnnxTensor),
                ),
            )
            embedRun.close()
            emptyPreLogits.close()
            embeddingMode.close()
            encoderIds.close()
            val hidden = encoderRun.get("last_hidden_state").get() as OnnxTensor
            val cacheRun = cacheInitializer.run(mapOf("encoder_hidden_states" to hidden))
            val generated = mutableListOf(0)
            val eos = tokenizer.PieceToID("</s>")
            val targetId = tokenizer.getLanguageID(target)
            val decoderMask = attention
            val permanentPreLogits = zeroFloat(longArrayOf(1, 1, 1024))
            val emptyLmIds = zeroLong(longArrayOf(1, 2))
            var previousRun: OrtSession.Result? = null
            var nextId = 2
            val maximumTokens = (inputIds.size * 8).coerceIn(24, 160)
            try {
                repeat(maximumTokens) { step ->
                    val currentIdTensor = longTensor(intArrayOf(nextId))
                    val embedMode = boolTensor(false)
                    val decoderEmbedRun = embedAndHead.run(
                        mapOf(
                            "input_ids" to currentIdTensor,
                            "pre_logits" to permanentPreLogits,
                            "use_lm_head" to embedMode,
                        ),
                        setOf("embed_matrix"),
                    )
                    val decoderInputs = mutableMapOf<String, OnnxTensor>(
                        "input_ids" to currentIdTensor,
                        "encoder_attention_mask" to decoderMask,
                        "embed_matrix" to (decoderEmbedRun[0] as OnnxTensor),
                    )
                    var emptyPast: OnnxTensor? = null
                    if (previousRun == null) {
                        emptyPast = zeroFloat(longArrayOf(1, 16, 0, 64))
                    }
                    for (layer in 0 until 12) {
                        decoderInputs["past_key_values.$layer.decoder.key"] =
                            previousRun?.get("present.$layer.decoder.key")?.get() as? OnnxTensor ?: emptyPast!!
                        decoderInputs["past_key_values.$layer.decoder.value"] =
                            previousRun?.get("present.$layer.decoder.value")?.get() as? OnnxTensor ?: emptyPast!!
                        decoderInputs["past_key_values.$layer.encoder.key"] =
                            cacheRun.get("present.$layer.encoder.key").get() as OnnxTensor
                        decoderInputs["past_key_values.$layer.encoder.value"] =
                            cacheRun.get("present.$layer.encoder.value").get() as OnnxTensor
                    }
                    val currentRun = decoder.run(decoderInputs)
                    decoderEmbedRun.close()
                    embedMode.close()
                    currentIdTensor.close()
                    emptyPast?.close()
                    previousRun?.close()
                    previousRun = currentRun

                    val headMode = boolTensor(true)
                    val headRun = embedAndHead.run(
                        mapOf(
                            "input_ids" to emptyLmIds,
                            "pre_logits" to (currentRun.get("pre_logits").get() as OnnxTensor),
                            "use_lm_head" to headMode,
                        ),
                        setOf("logits"),
                    )
                    @Suppress("UNCHECKED_CAST")
                    val logits = (headRun[0].value as Array<Array<FloatArray>>)[0][0]
                    var best = 0
                    for (index in 1 until logits.size) if (logits[index] > logits[best]) best = index
                    headRun.close()
                    headMode.close()
                    generated += best
                    if (best == eos) return tokenizer.decode(generated.toIntArray()).trim()
                    nextId = if (step == 0) targetId else best
                }
                return tokenizer.decode(generated.toIntArray()).trim()
            } finally {
                previousRun?.close()
                permanentPreLogits.close()
                emptyLmIds.close()
                cacheRun.close()
                encoderRun.close()
                attention.close()
            }
        }

        private fun longTensor(values: IntArray): OnnxTensor {
            val data = LongArray(values.size) { values[it].toLong() }
            return OnnxTensor.createTensor(env, LongBuffer.wrap(data), longArrayOf(1, data.size.toLong()))
        }

        private fun zeroLong(shape: LongArray): OnnxTensor {
            val size = shape.fold(1L) { total, value -> total * value }.toInt()
            return OnnxTensor.createTensor(env, LongBuffer.wrap(LongArray(size)), shape)
        }

        private fun zeroFloat(shape: LongArray): OnnxTensor {
            val size = shape.fold(1L) { total, value -> total * value }.toInt()
            return OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(size)), shape)
        }

        private fun boolTensor(value: Boolean): OnnxTensor =
            OnnxTensor.createTensor(
                env,
                ByteBuffer.wrap(byteArrayOf(if (value) 1 else 0)),
                longArrayOf(1),
                OnnxJavaType.BOOL,
            )

        override fun close() {
            embedAndHead.close()
            cacheInitializer.close()
            decoder.close()
            encoder.close()
            options.close()
        }
    }
}
