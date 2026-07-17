package com.untrustedtranslations.android.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.untrustedtranslations.android.model.FontChoice
import com.untrustedtranslations.android.model.SourceScript
import com.untrustedtranslations.android.model.TextStyle
import kotlin.math.atan2

object LetteringStyleEstimator {
    fun estimate(
        context: Context,
        bitmap: Bitmap,
        box: Rect,
        text: String,
        script: SourceScript,
        cornerPoints: Array<android.graphics.Point>?,
    ): TextStyle {
        val safe = Rect(
            box.left.coerceIn(0, bitmap.width - 1),
            box.top.coerceIn(0, bitmap.height - 1),
            box.right.coerceIn(1, bitmap.width),
            box.bottom.coerceIn(1, bitmap.height),
        )
        val lines = text.lines().count { it.isNotBlank() }.coerceAtLeast(1)
        val estimatedSp = (safe.height().toFloat() / lines / context.resources.displayMetrics.scaledDensity)
            .times(.72f)
            .coerceIn(12f, 64f)
        val background = borderMedian(bitmap, safe)
        val inkRatio = inkRatio(bitmap, safe, background)
        val cjk = script == SourceScript.JAPANESE || script == SourceScript.CHINESE || script == SourceScript.KOREAN
        val vertical = cjk && safe.height() > safe.width() * 1.35f
        val visibleCharacters = text.count { !it.isWhitespace() }.coerceAtLeast(1)
        val uppercaseRatio = text.count { it.isUpperCase() }.toFloat() / visibleCharacters
        val font = when {
            uppercaseRatio > .65f && text.any { it == '!' || it == '?' } -> FontChoice.CASUAL
            safe.width().toFloat() / visibleCharacters < safe.height().toFloat() / lines * .52f -> FontChoice.CONDENSED
            else -> FontChoice.SANS
        }
        val rotation = cornerPoints?.takeIf { it.size >= 2 }?.let {
            Math.toDegrees(atan2(
                (it[1].y - it[0].y).toDouble(),
                (it[1].x - it[0].x).toDouble(),
            )).toFloat().coerceIn(-45f, 45f)
        } ?: 0f
        return TextStyle(
            fontSizeSp = estimatedSp,
            rotationDegrees = rotation,
            font = font,
            bold = inkRatio > .11f,
            vertical = vertical,
            textColorArgb = if (luminance(background) > 128f) 0xFF000000 else 0xFFFFFFFF,
        )
    }

    private fun borderMedian(bitmap: Bitmap, rect: Rect): Int {
        val samples = mutableListOf<Int>()
        val step = (rect.width().coerceAtMost(rect.height()) / 20).coerceAtLeast(1)
        for (x in rect.left until rect.right step step) {
            samples += bitmap.getPixel(x, rect.top)
            samples += bitmap.getPixel(x, rect.bottom - 1)
        }
        for (y in rect.top until rect.bottom step step) {
            samples += bitmap.getPixel(rect.left, y)
            samples += bitmap.getPixel(rect.right - 1, y)
        }
        return Color.rgb(
            samples.map(Color::red).sorted()[samples.size / 2],
            samples.map(Color::green).sorted()[samples.size / 2],
            samples.map(Color::blue).sorted()[samples.size / 2],
        )
    }

    private fun inkRatio(bitmap: Bitmap, rect: Rect, background: Int): Float {
        val step = (rect.width().coerceAtMost(rect.height()) / 80).coerceAtLeast(1)
        var different = 0
        var total = 0
        for (y in rect.top until rect.bottom step step) for (x in rect.left until rect.right step step) {
            if (colorDistance(bitmap.getPixel(x, y), background) > 55) different++
            total++
        }
        return different.toFloat() / total.coerceAtLeast(1)
    }

    private fun colorDistance(first: Int, second: Int): Int =
        kotlin.math.abs(Color.red(first) - Color.red(second)) +
            kotlin.math.abs(Color.green(first) - Color.green(second)) +
            kotlin.math.abs(Color.blue(first) - Color.blue(second))

    private fun luminance(color: Int): Float =
        Color.red(color) * .2126f + Color.green(color) * .7152f + Color.blue(color) * .0722f
}
