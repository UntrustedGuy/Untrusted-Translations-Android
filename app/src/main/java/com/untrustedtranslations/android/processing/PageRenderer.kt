package com.untrustedtranslations.android.processing

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.untrustedtranslations.android.model.ComicPage
import com.untrustedtranslations.android.model.TextBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object PageRenderer {
    suspend fun apply(context: Context, page: ComicPage, block: TextBlock): Uri = withContext(Dispatchers.IO) {
        val source = requireNotNull(page.renderedSource.path)
        val bitmap = requireNotNull(BitmapFactory.decodeFile(source)?.copy(Bitmap.Config.ARGB_8888, true)) { "Unable to render page." }
        val rect = RectF(
            block.bounds.left * bitmap.width, block.bounds.top * bitmap.height,
            block.bounds.right * bitmap.width, block.bounds.bottom * bitmap.height,
        )
        val canvas = Canvas(bitmap)
        val background = sampledBackground(bitmap, rect)
        canvas.drawRoundRect(RectF(rect.left - 6, rect.top - 6, rect.right + 6, rect.bottom + 6), 8f, 8f, Paint().apply { color = background })
        val padding = (rect.width() * .06f).coerceAtLeast(4f)
        val textWidth = (rect.width() - padding * 2).toInt().coerceAtLeast(20)
        var textSize = block.style.fontSizeSp * context.resources.displayMetrics.scaledDensity
        var layout = layout(block.translatedText, textWidth, textSize)
        while (layout.height > rect.height() - padding * 2 && textSize > 10f) {
            textSize -= 1f
            layout = layout(block.translatedText, textWidth, textSize)
        }
        canvas.save()
        canvas.rotate(block.style.rotationDegrees, rect.centerX(), rect.centerY())
        canvas.translate(rect.left + padding, rect.centerY() - layout.height / 2f)
        layout.draw(canvas)
        canvas.restore()
        val output = File(requireNotNull(File(source).parentFile), "rendered-${page.id}.png")
        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        Uri.fromFile(output)
    }

    private fun layout(text: String, width: Int, size: Float): StaticLayout {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = size; typeface = Typeface.DEFAULT_BOLD }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build()
    }

    private fun sampledBackground(bitmap: Bitmap, rect: RectF): Int {
        val points = listOf(rect.left to rect.top, rect.right to rect.top, rect.left to rect.bottom, rect.right to rect.bottom)
        var red = 0; var green = 0; var blue = 0
        points.forEach { (x, y) ->
            val color = bitmap.getPixel(x.toInt().coerceIn(0, bitmap.width - 1), y.toInt().coerceIn(0, bitmap.height - 1))
            red += Color.red(color); green += Color.green(color); blue += Color.blue(color)
        }
        return Color.rgb(red / 4, green / 4, blue / 4)
    }
}
