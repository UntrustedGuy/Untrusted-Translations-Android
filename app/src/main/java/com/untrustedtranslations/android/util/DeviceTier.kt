package com.untrustedtranslations.android.util

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import kotlin.math.roundToInt

enum class AiTier { LOW, MID, HIGH }

data class DeviceProfile(
    val totalRamGb: Float,
    val primaryAbi: String,
    val recommendedTier: AiTier,
) {
    val summary: String
        get() = "${(totalRamGb * 10).roundToInt() / 10f} GB RAM - $primaryAbi - recommended: ${recommendedTier.name}"
}

object DeviceTierDetector {
    fun profile(context: Context): DeviceProfile {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memory = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
        val ramGb = memory.totalMem / (1024f * 1024f * 1024f)
        val tier = when {
            ramGb < 4f -> AiTier.LOW
            ramGb < 6.5f -> AiTier.MID
            else -> AiTier.HIGH
        }
        return DeviceProfile(
            totalRamGb = ramGb,
            primaryAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown ABI",
            recommendedTier = tier,
        )
    }
}
