package com.untrustedtranslations.android.processing

import android.content.Context
import com.untrustedtranslations.android.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

data class AppUpdateInfo(
    val version: String,
    val releaseUrl: String,
)

internal data class ModelFileOverride(
    val url: String,
    val sha256: String,
)

/**
 * Small, repository-controlled maintenance manifest.
 *
 * It can move a retired Gemini model ID or a model download to a new HTTPS URL without shipping a
 * new APK. Model overrides are accepted only for approved upstream hosts and only with a SHA-256.
 */
internal object RemoteMaintenance {
    private const val MANIFEST_URL =
        "https://raw.githubusercontent.com/UntrustedGuy/Untrusted-Translations-Android/main/update-config.json"
    private const val PREFS = "remote_maintenance"
    private const val CACHED_JSON = "cached_json"
    private const val DEFAULT_GEMINI_MODEL = "gemini-3.5-flash"
    private val modelNamePattern = Regex("gemini-[a-z0-9.-]+")
    private val hashPattern = Regex("[a-fA-F0-9]{64}")
    private val allowedModelHosts = setOf(
        "github.com",
        "raw.githubusercontent.com",
        "huggingface.co",
        "modelscope.cn",
        "www.modelscope.cn",
    )

    suspend fun refresh(context: Context): Boolean = withContext(Dispatchers.IO) {
        val connection = URL(MANIFEST_URL).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 12_000
            connection.readTimeout = 12_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "Untrusted-Translations-Android/${BuildConfig.VERSION_NAME}")
            if (connection.responseCode !in 200..299) return@withContext false
            val raw = connection.inputStream.bufferedReader().use { it.readText() }
            parse(raw) ?: return@withContext false
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(CACHED_JSON, raw).apply()
            true
        } catch (_: Exception) {
            false
        } finally {
            connection.disconnect()
        }
    }

    fun geminiModel(context: Context): String = cached(context)?.geminiModel ?: DEFAULT_GEMINI_MODEL

    fun availableAppUpdate(context: Context): AppUpdateInfo? {
        val config = cached(context) ?: return null
        return if (isNewer(config.appVersion, BuildConfig.VERSION_NAME)) {
            AppUpdateInfo(config.appVersion, config.releaseUrl)
        } else null
    }

    fun modelFileOverride(context: Context, pack: ModelPackId, fileName: String): ModelFileOverride? =
        cached(context)?.modelFiles?.get("${pack.name}/$fileName")

    private fun cached(context: Context): Config? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(CACHED_JSON, null)
            ?: return null
        return parse(raw)
    }

    private fun parse(raw: String): Config? = runCatching {
        val root = JSONObject(raw)
        require(root.optInt("schemaVersion") == 1)
        val app = root.getJSONObject("app")
        val appVersion = app.getString("version").trim().removePrefix("v")
        require(versionParts(appVersion) != null)
        val releaseUrl = app.getString("releaseUrl").trim()
        require(isAllowedHttps(releaseUrl, setOf("github.com")))
        val geminiModel = root.optString("geminiModel", DEFAULT_GEMINI_MODEL).trim()
        require(modelNamePattern.matches(geminiModel))
        val overrides = buildMap {
            val files = root.optJSONObject("modelFiles") ?: JSONObject()
            files.keys().forEach { key ->
                val item = files.optJSONObject(key) ?: return@forEach
                val url = item.optString("url").trim()
                val sha256 = item.optString("sha256").trim().lowercase()
                if (key.contains('/') && isAllowedHttps(url, allowedModelHosts) && hashPattern.matches(sha256)) {
                    put(key, ModelFileOverride(url, sha256))
                }
            }
        }
        Config(appVersion, releaseUrl, geminiModel, overrides)
    }.getOrNull()

    private fun isAllowedHttps(value: String, hosts: Set<String>): Boolean = runCatching {
        val uri = URI(value)
        uri.scheme.equals("https", ignoreCase = true) && uri.host?.lowercase() in hosts
    }.getOrDefault(false)

    private fun isNewer(candidate: String, current: String): Boolean {
        val left = versionParts(candidate) ?: return false
        val right = versionParts(current) ?: return false
        for (index in left.indices) {
            if (left[index] != right[index]) return left[index] > right[index]
        }
        return false
    }

    private fun versionParts(value: String): IntArray? {
        val normalized = value.trim().removePrefix("v")
        val match = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)(?:-beta(?:\\.(\\d+))?)?$")
            .matchEntire(normalized) ?: return null
        return intArrayOf(
            match.groupValues[1].toInt(),
            match.groupValues[2].toInt(),
            match.groupValues[3].toInt(),
            if ("-beta" in normalized) 0 else 1,
            match.groupValues[4].toIntOrNull() ?: 0,
        )
    }

    private data class Config(
        val appVersion: String,
        val releaseUrl: String,
        val geminiModel: String,
        val modelFiles: Map<String, ModelFileOverride>,
    )
}
