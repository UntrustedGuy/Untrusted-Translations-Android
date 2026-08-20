package com.untrustedtranslations.android.processing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
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

    /** Returns the exact fitted size used by the bitmap renderer so gesture previews do not jump. */
    fun fittedFontSizeSp(
        context: Context,
        imageWidth: Int,
        imageHeight: Int,
        block: TextBlock,
    ): Float {
        val rect = RectF(
            block.bounds.left * imageWidth,
            block.bounds.top * imageHeight,
            block.bounds.right * imageWidth,
            block.bounds.bottom * imageHeight,
        )
        if (rect.width() < 2f || rect.height() < 2f) return block.style.fontSizeSp
        val padPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            block.style.backgroundPaddingDp,
            context.resources.displayMetrics,
        ).coerceAtLeast(4f)
        val textWidth = (rect.width() - padPx * 2).toInt().coerceAtLeast(20)
        val renderText = if (block.style.vertical) verticalize(block.translatedText) else block.translatedText
        var textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            block.style.fontSizeSp,
            context.resources.displayMetrics,
        )
        var textLayout = layout(context, renderText, textWidth, textSize, block)
        while (textLayout.height > rect.height() - padPx * 2 && textSize > 10f) {
            textSize -= 1f
            textLayout = layout(context, renderText, textWidth, textSize, block)
        }
        return textSize / context.resources.displayMetrics.scaledDensity.coerceAtLeast(.01f)
    }

    private fun drawBlock(context: Context, bitmap: Bitmap, canvas: Canvas, block: TextBlock) {
        val rect = RectF(
            block.bounds.left * bitmap.width,
            block.bounds.top * bitmap.height,
            block.bounds.right * bitmap.width,
            block.bounds.bottom * bitmap.height,
        )
        if (rect.width() < 2f || rect.height() < 2f) return

        block.eraseBounds?.let { erase ->
            try {
                val eraseRect = RectF(
                    erase.left * bitmap.width,
                    erase.top * bitmap.height,
                    erase.right * bitmap.width,
                    erase.bottom * bitmap.height,
                )
                if (eraseRect.width() >= 3f && eraseRect.height() >= 3f) {
                    TextInpainter.erase(bitmap, eraseRect)
                }
            } catch (_: Exception) { }
        }

        val padPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            block.style.backgroundPaddingDp,
            context.resources.displayMetrics,
        ).coerceAtLeast(2f)
        val radiusPx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            block.style.backgroundCornerRadiusDp,
            context.resources.displayMetrics,
        ).coerceAtLeast(0f)

        val contentHeight = rect.height() - padPx * 2
        if (contentHeight < 4f) return
        val textWidth = (rect.width() - padPx * 2).toInt().coerceAtLeast(20)
        val renderText = if (block.style.vertical) verticalize(block.translatedText) else block.translatedText
        var textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            block.style.fontSizeSp,
            context.resources.displayMetrics,
        )
        var textLayout = layout(context, renderText, textWidth, textSize, block)
        while (textLayout.height > rect.height() - padPx * 2 && textSize > 10f) {
            textSize -= 1f
            textLayout = layout(context, renderText, textWidth, textSize, block)
        }

        canvas.save()
        canvas.rotate(block.style.rotationDegrees, rect.centerX(), rect.centerY())

        // Background rounded box / speech bubble background
        block.style.backgroundColorArgb?.let { background ->
            val bgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = applyOpacity(background, block.style.backgroundOpacity)
            }
            canvas.drawRoundRect(rect, radiusPx, radiusPx, bgPaint)
        }

        val textY = rect.centerY() - textLayout.height / 2f
        canvas.translate(rect.left + padPx, textY)

        // Render curved text or multi-line block text
        if (block.style.curveSweepAngle != 0f && !block.style.vertical) {
            val sweep = block.style.curveSweepAngle.coerceIn(-180f, 180f)
            val radius = (rect.width() * 0.9f).coerceAtLeast(30f)
            val path = Path().apply {
                val oval = RectF(-radius + rect.width() / 2f - padPx, -radius + textLayout.height / 2f, radius + rect.width() / 2f - padPx, radius + textLayout.height / 2f)
                val startAngle = if (sweep >= 0) 270f - sweep / 2f else 90f + sweep / 2f
                addArc(oval, startAngle, sweep)
            }
            if (block.style.strokeWidthSp > 0f) {
                val strokePaint = createPaint(context, block, textSize, isStroke = true)
                try { canvas.drawTextOnPath(renderText, path, 0f, 0f, strokePaint) } catch (_: Exception) { }
            }
            val fillPaint = createPaint(context, block, textSize, isStroke = false)
            try { canvas.drawTextOnPath(renderText, path, 0f, 0f, fillPaint) } catch (_: Exception) { }
        } else {
            // Standard multi-line StaticLayout rendering
            if (block.style.strokeWidthSp > 0f) {
                val strokeLayout = layout(context, renderText, textWidth, textSize, block, isStroke = true)
                try { strokeLayout.draw(canvas) } catch (_: Exception) { }
            }
            try { textLayout.draw(canvas) } catch (_: Exception) { }
        }

        canvas.restore()
    }

    private fun createPaint(
        context: Context,
        block: TextBlock,
        size: Float,
        isStroke: Boolean = false,
    ): TextPaint {
        val paint = TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            typeface = typeface(context, block)
            letterSpacing = block.style.letterSpacingEm
        }
        if (isStroke) {
            paint.style = android.graphics.Paint.Style.STROKE
            val strokePx = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                block.style.strokeWidthSp,
                context.resources.displayMetrics,
            )
            paint.strokeWidth = strokePx
            paint.strokeJoin = android.graphics.Paint.Join.ROUND
            paint.strokeCap = android.graphics.Paint.Cap.ROUND
            paint.color = applyOpacity(block.style.strokeColorArgb, block.style.textOpacity)
        } else {
            paint.style = android.graphics.Paint.Style.FILL
            paint.color = applyOpacity(block.style.textColorArgb, block.style.textOpacity)
            if (block.style.shadowBlurRadiusSp > 0f || block.style.shadowDxSp != 0f || block.style.shadowDySp != 0f) {
                val blurPx = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP,
                    block.style.shadowBlurRadiusSp.coerceAtLeast(0.1f),
                    context.resources.displayMetrics,
                )
                val dxPx = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP,
                    block.style.shadowDxSp,
                    context.resources.displayMetrics,
                )
                val dyPx = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP,
                    block.style.shadowDySp,
                    context.resources.displayMetrics,
                )
                paint.setShadowLayer(blurPx, dxPx, dyPx, block.style.shadowColorArgb.toInt())
            }
        }
        return paint
    }

    private fun layout(
        context: Context,
        text: String,
        width: Int,
        size: Float,
        block: TextBlock,
        isStroke: Boolean = false,
    ): StaticLayout {
        val paint = createPaint(context, block, size, isStroke)
        val alignment = when (block.style.alignment) {
            TextAlignmentChoice.START -> Layout.Alignment.ALIGN_NORMAL
            TextAlignmentChoice.CENTER -> Layout.Alignment.ALIGN_CENTER
            TextAlignmentChoice.END -> Layout.Alignment.ALIGN_OPPOSITE
        }
        return StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(alignment)
            .setIncludePad(false)
            .setLineSpacing(0f, block.style.lineSpacingMultiplier.coerceIn(0.6f, 2.5f))
            .build()
    }

    private fun typeface(context: Context, block: TextBlock): Typeface {
        val family = when (block.style.font) {
            FontChoice.AUTO -> Typeface.DEFAULT
            FontChoice.MANGA -> Typeface.createFromAsset(context.assets, "fonts/comic_neue_bold.ttf")
            FontChoice.ACTION -> Typeface.create("sans-serif-black", Typeface.BOLD)
            FontChoice.SANS -> Typeface.SANS_SERIF
            FontChoice.SERIF -> Typeface.SERIF
            FontChoice.CONDENSED -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
            FontChoice.MONOSPACE -> Typeface.MONOSPACE
            FontChoice.CASUAL -> Typeface.create("casual", Typeface.NORMAL)
            FontChoice.GOTHIC -> Typeface.create("serif", Typeface.BOLD)
            FontChoice.VINTAGE -> Typeface.create("cursive", Typeface.NORMAL)
            FontChoice.CUSTOM -> { val file = File(context.filesDir, "custom_font.ttf"); if (file.exists()) Typeface.createFromFile(file) else Typeface.DEFAULT }
            else -> Typeface.DEFAULT
        }
        val style = when {
            block.style.bold && block.style.italic -> Typeface.BOLD_ITALIC
            block.style.bold -> Typeface.BOLD
            block.style.italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        return Typeface.create(family, style)
    }

    private fun applyOpacity(colorArgb: Long, opacity: Float): Int {
        val base = colorArgb.toInt()
        val alpha = (Color.alpha(base) * opacity.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
        return (base and 0x00FFFFFF) or (alpha shl 24)
    }

    private fun verticalize(text: String): String = text.lines().joinToString("\n") { line ->
        line.trim().toCharArray().joinToString("\n")
    }
}




