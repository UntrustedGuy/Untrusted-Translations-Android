package com.untrustedtranslations.android.persistence

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.untrustedtranslations.android.model.OcrProvider
import com.untrustedtranslations.android.model.TranslationProvider
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class AiSettings(
    val ocrProvider: OcrProvider = OcrProvider.ML_KIT,
    val translationProvider: TranslationProvider = TranslationProvider.ML_KIT,
    val localTranslationPackName: String = "",
    val geminiApiKey: String = "",
    val openAiApiKey: String = "",
    val anthropicApiKey: String = "",
    val compatibleApiKey: String = "",
    val compatibleBaseUrl: String = "",
    val compatibleModel: String = "",
)

object SecureAiSettings {
    private const val PREFS = "private_ai_settings"
    private const val KEY_OCR = "ocr_provider"
    private const val KEY_TRANSLATION = "translation_provider"
    private const val KEY_LOCAL_TRANSLATION_PACK = "local_translation_pack"
    private const val KEY_GEMINI = "gemini_key_ciphertext"
    private const val KEY_OPENAI = "openai_key_ciphertext"
    private const val KEY_ANTHROPIC = "anthropic_key_ciphertext"
    private const val KEY_COMPATIBLE = "compatible_key_ciphertext"
    private const val KEY_COMPATIBLE_BASE = "compatible_base_url"
    private const val KEY_COMPATIBLE_MODEL = "compatible_model"
    private const val KEY_ALIAS = "untrusted_translations_ai_key"

    fun load(context: Context): AiSettings {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val legacyGemini = prefs.getString("processing_mode", null) == "GEMINI_FREE"
        val ocr = runCatching {
            OcrProvider.valueOf(
                prefs.getString(
                    KEY_OCR,
                    if (legacyGemini) OcrProvider.GEMINI_FREE.name else OcrProvider.ML_KIT.name,
                ).orEmpty(),
            )
        }.getOrDefault(OcrProvider.ML_KIT)
        val translation = runCatching {
            TranslationProvider.valueOf(
                prefs.getString(
                    KEY_TRANSLATION,
                    if (legacyGemini) TranslationProvider.GEMINI_FREE.name
                    else TranslationProvider.ML_KIT.name,
                ).orEmpty(),
            )
        }.getOrDefault(TranslationProvider.ML_KIT)
        return AiSettings(
            ocrProvider = ocr,
            translationProvider = translation,
            localTranslationPackName = prefs.getString(KEY_LOCAL_TRANSLATION_PACK, "").orEmpty(),
            geminiApiKey = readSecret(prefs, KEY_GEMINI),
            openAiApiKey = readSecret(prefs, KEY_OPENAI),
            anthropicApiKey = readSecret(prefs, KEY_ANTHROPIC),
            compatibleApiKey = readSecret(prefs, KEY_COMPATIBLE),
            compatibleBaseUrl = prefs.getString(KEY_COMPATIBLE_BASE, "").orEmpty(),
            compatibleModel = prefs.getString(KEY_COMPATIBLE_MODEL, "").orEmpty(),
        )
    }

    fun save(context: Context, settings: AiSettings) {
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_OCR, settings.ocrProvider.name)
            .putString(KEY_TRANSLATION, settings.translationProvider.name)
            .putString(KEY_LOCAL_TRANSLATION_PACK, settings.localTranslationPackName)
            .putString(KEY_COMPATIBLE_BASE, settings.compatibleBaseUrl.trim())
            .putString(KEY_COMPATIBLE_MODEL, settings.compatibleModel.trim())
            .remove("processing_mode")
        writeSecret(editor, KEY_GEMINI, settings.geminiApiKey)
        writeSecret(editor, KEY_OPENAI, settings.openAiApiKey)
        writeSecret(editor, KEY_ANTHROPIC, settings.anthropicApiKey)
        writeSecret(editor, KEY_COMPATIBLE, settings.compatibleApiKey)
        editor.apply()
    }

    private fun readSecret(prefs: android.content.SharedPreferences, name: String) =
        prefs.getString(name, null)?.let { runCatching { decrypt(it) }.getOrDefault("") }.orEmpty()

    private fun writeSecret(editor: android.content.SharedPreferences.Editor, name: String, value: String) {

        if (value.isBlank()) editor.remove(name) else editor.putString(name, encrypt(value.trim()))
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val combined = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val combined = Base64.decode(value, Base64.NO_WRAP)
        require(combined.size > 12) { "Invalid encrypted setting." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, combined.copyOfRange(0, 12)))
        return String(cipher.doFinal(combined.copyOfRange(12, combined.size)), Charsets.UTF_8)
    }
}
