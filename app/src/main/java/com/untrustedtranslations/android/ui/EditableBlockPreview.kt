package com.untrustedtranslations.android.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.untrustedtranslations.android.model.ComicPage
import com.untrustedtranslations.android.model.RelativeBounds
import com.untrustedtranslations.android.model.TextBlock
import kotlin.math.min

private enum class DragMode { NONE, MOVE, RESIZE }

@Composable
fun EditableBlockPreview(
    page: ComicPage,
    block: TextBlock,
    onBoundsCommitted: (RelativeBounds) -> Unit,
) {
    val bitmap = remember(page.renderedSource) { page.renderedSource.path?.let(BitmapFactory::decodeFile) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var draft by remember(block.id, block.bounds) { mutableStateOf(block.bounds) }
    var dragMode by remember { mutableStateOf(DragMode.NONE) }
    val shape = RoundedCornerShape(16.dp)

    Box(
        Modifier.fillMaxWidth().height(300.dp)
            .background(AppColors.Surface, shape)
            .border(1.dp, Color(0xFF343240), shape)
            .padding(6.dp)
            .onSizeChanged { viewport = it }
            .pointerInput(block.id, bitmap, viewport) {
                detectDragGestures(
                    onDragStart = { pointer ->
                        val geometry = imageGeometry(viewport, bitmap?.width ?: 1, bitmap?.height ?: 1)
                        val box = draft.toDisplayRect(geometry)
                        val handle = Offset(box.right, box.bottom)
                        dragMode = when {
                            (pointer - handle).getDistance() <= 56.dp.toPx() -> DragMode.RESIZE
                            box.contains(pointer) -> DragMode.MOVE
                            else -> DragMode.NONE
                        }
                    },
                    onDrag = { change, amount ->
                        if (dragMode == DragMode.NONE) return@detectDragGestures
                        change.consume()
                        val geometry = imageGeometry(viewport, bitmap?.width ?: 1, bitmap?.height ?: 1)
                        val dx = amount.x / geometry.size.width.coerceAtLeast(1f)
                        val dy = amount.y / geometry.size.height.coerceAtLeast(1f)
                        draft = when (dragMode) {
                            DragMode.MOVE -> draft.moved(dx, dy)
                            DragMode.RESIZE -> draft.resized(dx, dy)
                            DragMode.NONE -> draft
                        }
                    },
                    onDragCancel = { draft = block.bounds; dragMode = DragMode.NONE },
                    onDragEnd = {
                        if (dragMode != DragMode.NONE) onBoundsCommitted(draft)
                        dragMode = DragMode.NONE
                    },
                )
            },
    ) {
        if (bitmap != null) {
            Image(
                bitmap.asImageBitmap(),
                contentDescription = "Drag the selected text box",
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Fit,
            )
            Canvas(Modifier.matchParentSize()) {
                val geometry = imageGeometry(IntSize(size.width.toInt(), size.height.toInt()), bitmap.width, bitmap.height)
                val rect = draft.toDisplayRect(geometry)
                drawRect(
                    color = AppColors.Cyan,
                    topLeft = rect.topLeft,
                    size = rect.size,
                    style = Stroke(3.dp.toPx()),
                )
                drawCircle(
                    color = AppColors.Cyan,
                    radius = 9.dp.toPx(),
                    center = Offset(rect.right, rect.bottom),
                )
                drawCircle(
                    color = AppColors.Void,
                    radius = 4.dp.toPx(),
                    center = Offset(rect.right, rect.bottom),
                )
            }
        }
    }
}

private fun imageGeometry(viewport: IntSize, imageWidth: Int, imageHeight: Int): Rect {
    val width = viewport.width.coerceAtLeast(1).toFloat()
    val height = viewport.height.coerceAtLeast(1).toFloat()
    val scale = min(width / imageWidth.coerceAtLeast(1), height / imageHeight.coerceAtLeast(1))
    val displayedWidth = imageWidth * scale
    val displayedHeight = imageHeight * scale
    return Rect(
        offset = Offset((width - displayedWidth) / 2f, (height - displayedHeight) / 2f),
        size = Size(displayedWidth, displayedHeight),
    )
}

private fun RelativeBounds.toDisplayRect(image: Rect) = Rect(
    left = image.left + left * image.width,
    top = image.top + top * image.height,
    right = image.left + right * image.width,
    bottom = image.top + bottom * image.height,
)

private fun RelativeBounds.moved(dx: Float, dy: Float): RelativeBounds {
    val width = right - left
    val height = bottom - top
    val newLeft = (left + dx).coerceIn(0f, 1f - width)
    val newTop = (top + dy).coerceIn(0f, 1f - height)
    return RelativeBounds(newLeft, newTop, newLeft + width, newTop + height)
}

private fun RelativeBounds.resized(dx: Float, dy: Float) = RelativeBounds(
    left = left,
    top = top,
    right = (right + dx).coerceIn(left + .05f, 1f),
    bottom = (bottom + dy).coerceIn(top + .05f, 1f),
)
