package com.untrustedtranslations.android.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import com.untrustedtranslations.android.model.ComicPage
import com.untrustedtranslations.android.model.FontChoice
import com.untrustedtranslations.android.model.TextAlignmentChoice
import com.untrustedtranslations.android.model.TextBlock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

object PageRenderer {
    suspend fun apply(context: Context, page: ComicPage, blocks: List<TextBlock>): Uri =
        withContext(Dispatchers.IO) {
            val source = requireNotNull(page.originalSource.path)
            val bitmap = requireNotNull(
                BitmapFactory.decodeFile(source)?.copy(Bitmap.Config.ARGB_8888, true),
            ) { "Unable to render page." }
            val canvas = Canvas(bitmap)
            blocks.filter { it.applied && it.translatedText.isNotBlank() }.forEach { block ->
                drawBlock(context, bitmap, canvas, block)
            }
            val output = File(
                requireNotNull(File(source).parentFile),
                "rendered-${page.id}-${UUID.randomUUID()}.png",
            )
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            Uri.fromFile(output)
        }

    private fun drawBlock(context: Context, bitmap: Bitmap, canvas: Canvas, block: TextBlock) {
        val rect = RectF(
            block.bounds.left * bitmap.width,
            block.bounds.top * bitmap.height,
            block.bounds.right * bitmap.width,
            block.bounds.bottom * bitmap.height,
        )
        if (rect.width() < 2f || rect.height() < 2f) return

        val expansion = (rect.width().coerceAtMost(rect.height()) * .03f).coerceIn(3f, 14f)
        canvas.drawRoundRect(
            RectF(rect.left - expansion, rect.top - expansion, rect.right + expansion, rect.bottom + expansion),
            expansion,
            expansion,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = sampledBackground(bitmap, rect) },
        )

        val padding = (rect.width() * .06f).coerceAtLeast(4f)
        val textWidth = (rect.width() - padding * 2).toInt().coerceAtLeast(20)
        val renderText = if (block.style.vertical) verticalize(block.translatedText) else block.translatedText
        var textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            block.style.fontSizeSp,
            context.resources.displayMetrics,
        )
        var textLayout = layout(renderText, textWidth, textSize, block)
        while (textLayout.height > rect.height() - padding * 2 && textSize > 10f) {
            textSize -= 1f
            textLayout = layout(renderText, textWidth, textSize, block)
        }
        canvas.save()
        canvas.rotate(block.style.rotationDegrees, rect.centerX(), rect.centerY())
        canvas.translate(rect.left + padding, rect.centerY() - textLayout.height / 2f)
        textLayout.draw(canvas)
        canvas.restore()
    }

    private fun layout(text: String, width: Int, size: Float, block: TextBlock): StaticLayout {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = block.style.textColorArgb.toInt()
            textSize = size
            typeface = typeface(block)
        }
        val alignment = when (block.style.alignment) {
            TextAlignmentChoice.START -> Layout.Alignment.ALIGN_NORMAL
            TextAlignmentChoice.CENTER -> Layout.Alignment.ALIGN_CENTER
            TextAlignmentChoice.END -> Layout.Alignment.ALIGN_OPPOSITE
        }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(alignment)
            .setIncludePad(false)
            .setLineSpacing(0f, .92f)
            .build()
    }

    private fun typeface(block: TextBlock): Typeface {
        val family = when (block.style.font) {
            FontChoice.AUTO -> Typeface.DEFAULT
            FontChoice.SANS -> Typeface.SANS_SERIF
            FontChoice.SERIF -> Typeface.SERIF
            FontChoice.CONDENSED -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
            FontChoice.MONOSPACE -> Typeface.MONOSPACE
            FontChoice.CASUAL -> Typeface.create("casual", Typeface.NORMAL)
        }
        val style = when {
            block.style.bold && block.style.italic -> Typeface.BOLD_ITALIC
            block.style.bold -> Typeface.BOLD
            block.style.italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        return Typeface.create(family, style)
    }

    private fun verticalize(text: String): String = text.lines().joinToString("\n") { line ->
        line.trim().toCharArray().joinToString("\n")
    }

    private fun sampledBackground(bitmap: Bitmap, rect: RectF): Int {
        val inset = 2f
        val points = listOf(
            rect.left + inset to rect.top + inset,
            rect.centerX() to rect.top + inset,
            rect.right - inset to rect.top + inset,
            rect.left + inset to rect.centerY(),
            rect.right - inset to rect.centerY(),
            rect.left + inset to rect.bottom - inset,
            rect.centerX() to rect.bottom - inset,
            rect.right - inset to rect.bottom - inset,
        )
        val colors = points.map { (x, y) ->
            bitmap.getPixel(
                x.toInt().coerceIn(0, bitmap.width - 1),
                y.toInt().coerceIn(0, bitmap.height - 1),
            )
        }
        return Color.rgb(
            colors.map(Color::red).sorted()[colors.size / 2],
            colors.map(Color::green).sorted()[colors.size / 2],
            colors.map(Color::blue).sorted()[colors.size / 2],
        )
    }
}
