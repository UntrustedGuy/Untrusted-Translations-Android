package com.untrustedtranslations.android.ui

import android.graphics.BitmapFactory
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.untrustedtranslations.android.model.ComicPage
import com.untrustedtranslations.android.model.FontChoice
import com.untrustedtranslations.android.model.RelativeBounds
import com.untrustedtranslations.android.processing.PageRenderer
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.roundToInt

enum class PageTransformMode { RESIZE, ROTATE }

private enum class PageDragHandle { NONE, MOVE, LEFT, TOP, RIGHT, BOTTOM, ROTATE }

@Composable
fun ManipulablePagePreview(
    page: ComicPage,
    selectedBlockIndex: Int,
    mode: PageTransformMode,
    onSelectBlock: (Int) -> Unit,
    onTransformCommitted: (Int, RelativeBounds, Float, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var bitmap by remember(page.renderedSource) { mutableStateOf<android.graphics.Bitmap?>(null) }
    val context = LocalContext.current
    LaunchedEffect(page.renderedSource) {
        withContext(Dispatchers.IO) {
            val bmp = page.renderedSource.path?.let(BitmapFactory::decodeFile); bitmap = bmp
        }
    }
    val mangaFont = remember {
        FontFamily(Typeface.createFromAsset(context.assets, "fonts/comic_neue_bold.ttf"))
    }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    var activeIndex by remember(page.id, selectedBlockIndex) { mutableIntStateOf(selectedBlockIndex) }
    val selected = page.blocks.getOrNull(selectedBlockIndex)
    var draftBounds by remember(page.id, selected?.id, selected?.bounds) {
        mutableStateOf(selected?.bounds ?: RelativeBounds(.25f, .4f, .75f, .6f))
    }
    var draftRotation by remember(page.id, selected?.id, selected?.style?.rotationDegrees) {
        mutableStateOf(selected?.style?.rotationDegrees ?: 0f)
    }
    var sourceBounds by remember(page.id, selected?.id) {
        mutableStateOf(selected?.bounds ?: RelativeBounds(.25f, .4f, .75f, .6f))
    }
    var sourceFontSizeSp by remember(page.id, selected?.id) {
        mutableStateOf(selected?.style?.fontSizeSp ?: 22f)
    }
    var sourceRotation by remember(page.id, selected?.id) {
        mutableStateOf(selected?.style?.rotationDegrees ?: 0f)
    }
    var dragHandle by remember { mutableStateOf(PageDragHandle.NONE) }
    var previewPending by remember(page.id) { mutableStateOf(false) }
    LaunchedEffect(page.renderedSource) {
        previewPending = false
    }
    val shape = RoundedCornerShape(18.dp)

    Box(
        modifier
            .background(AppColors.Surface, shape)
            .border(1.dp, Color(0xFF343240), shape),
    ) {
        if (bitmap != null) {
            val bmp = bitmap!!
            Box(
                Modifier.fillMaxSize().padding(6.dp)
                    .onSizeChanged { viewport = it }
                    .pointerInput(page.blocks, selectedBlockIndex, mode, viewport) {
                        detectDragGestures(
                            onDragStart = { pointer ->
                                val geometry = pageImageGeometry(viewport, bmp.width, bmp.height)
                                val currentBlock = page.blocks.getOrNull(selectedBlockIndex)
                                val currentRect = currentBlock?.bounds?.toPageRect(geometry)
                                val handleRadius = 32.dp.toPx()
                                val currentHandle = if (currentBlock?.applied == true && currentRect != null) {
                                    hitHandle(pointer, currentRect, mode, handleRadius)
                                } else {
                                    PageDragHandle.NONE
                                }
                                if (currentHandle != PageDragHandle.NONE) {
                                    val activeBlock = requireNotNull(currentBlock)
                                    activeIndex = selectedBlockIndex
                                    draftBounds = activeBlock.bounds
                                    draftRotation = activeBlock.style.rotationDegrees
                                    sourceBounds = activeBlock.bounds
                                    sourceFontSizeSp = activeBlock.style.fontSizeSp
                                    sourceRotation = activeBlock.style.rotationDegrees
                                    previewPending = true
                                    dragHandle = currentHandle
                                } else {
                                    val hit = page.blocks.indices.reversed().firstOrNull { index ->
                                        page.blocks[index].applied &&
                                            page.blocks[index].bounds.toPageRect(geometry).contains(pointer)
                                    }
                                    if (hit != null) {
                                        val block = page.blocks[hit]
                                        activeIndex = hit
                                        draftBounds = block.bounds
                                        draftRotation = block.style.rotationDegrees
                                        sourceBounds = block.bounds
                                        sourceFontSizeSp = block.style.fontSizeSp
                                        sourceRotation = block.style.rotationDegrees
                                        previewPending = true
                                        onSelectBlock(hit)
                                        dragHandle = PageDragHandle.MOVE
                                    } else {
                                        dragHandle = PageDragHandle.NONE
                                    }
                                }
                            },
                            onDrag = { change, amount ->
                                if (dragHandle == PageDragHandle.NONE) return@detectDragGestures
                                change.consume()
                                val geometry = pageImageGeometry(viewport, bmp.width, bmp.height)
                                val dx = amount.x / geometry.width.coerceAtLeast(1f)
                                val dy = amount.y / geometry.height.coerceAtLeast(1f)
                                draftBounds = when (dragHandle) {
                                    PageDragHandle.MOVE -> draftBounds.moveBy(dx, dy)
                                    PageDragHandle.LEFT,
                                    PageDragHandle.TOP,
                                    PageDragHandle.RIGHT,
                                    PageDragHandle.BOTTOM -> draftBounds.resizeFrom(dragHandle, dx, dy)
                                    else -> draftBounds
                                }
                                if (dragHandle == PageDragHandle.ROTATE) {
                                    val rect = draftBounds.toPageRect(geometry)
                                    val degrees = Math.toDegrees(
                                        atan2(
                                            change.position.y - rect.center.y,
                                            change.position.x - rect.center.x,
                                        ).toDouble(),
                                    ).toFloat() + 90f
                                    draftRotation = normalizeDegrees(degrees)
                                }
                            },
                            onDragCancel = {
                                dragHandle = PageDragHandle.NONE
                                previewPending = false
                                activeIndex = selectedBlockIndex
                            },
                            onDragEnd = {
                                if (dragHandle != PageDragHandle.NONE && activeIndex in page.blocks.indices) {
                                    val resized = dragHandle == PageDragHandle.LEFT ||
                                        dragHandle == PageDragHandle.TOP ||
                                        dragHandle == PageDragHandle.RIGHT ||
                                        dragHandle == PageDragHandle.BOTTOM
                                    onTransformCommitted(activeIndex, draftBounds, draftRotation, resized)
                                }
                                dragHandle = PageDragHandle.NONE
                            },
                        )
                    },
            ) {
                Image(
                    bmp.asImageBitmap(),
                    contentDescription = "Manga page with movable text",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
                val liveBlock = page.blocks.getOrNull(activeIndex)
                if (previewPending && liveBlock?.applied == true && viewport != IntSize.Zero) {
                    val geometry = pageImageGeometry(viewport, bmp.width, bmp.height)
                    val oldRect = sourceBounds.toPageRect(geometry)
                    val liveRect = draftBounds.toPageRect(geometry)
                    val density = LocalDensity.current
                    val liveWidth = with(density) { liveRect.width.toDp() }
                    val liveHeight = with(density) { liveRect.height.toDp() }
                    val sourceHeight = (sourceBounds.bottom - sourceBounds.top).coerceAtLeast(.001f)
                    val draftHeight = (draftBounds.bottom - draftBounds.top).coerceAtLeast(.001f)
                    val requestedFontSize = (sourceFontSizeSp * draftHeight / sourceHeight).coerceIn(8f, 160f)
                    val draftWidth = draftBounds.right - draftBounds.left
                    val draftPixelWidth = (draftWidth * bmp.width).roundToInt()
                    val draftPixelHeight = (draftHeight * bmp.height).roundToInt()
                    val requestedFontStep = (requestedFontSize * 10f).roundToInt()
                    val fittedFontSize = remember(
                        liveBlock.id,
                        liveBlock.translatedText,
                        liveBlock.style,
                        draftPixelWidth,
                        draftPixelHeight,
                        requestedFontStep,
                    ) {
                        PageRenderer.fittedFontSizeSp(
                            context,
                            bmp.width,
                            bmp.height,
                            liveBlock.copy(
                                bounds = draftBounds,
                                style = liveBlock.style.copy(fontSizeSp = requestedFontSize),
                            ),
                        )
                    }
                    val displayScale = geometry.width / bmp.width.coerceAtLeast(1)
                    val liveFontSizeSp = fittedFontSize * displayScale
                    if (oldRect != liveRect || draftRotation != sourceRotation) {
                        Box(
                            Modifier
                                .offset {
                                    IntOffset(oldRect.left.roundToInt(), oldRect.top.roundToInt())
                                }
                                .size(
                                    with(density) { oldRect.width.toDp() },
                                    with(density) { oldRect.height.toDp() },
                                )
                                .background(Color(0xB3FFFFFF)),
                        )
                    }
                    Text(
                        text = liveBlock.translatedText,
                        color = Color(liveBlock.style.textColorArgb.toInt()),
                        fontSize = liveFontSizeSp.coerceIn(4f, 160f).sp,
                        fontFamily = when (liveBlock.style.font) {
                            FontChoice.AUTO, FontChoice.SANS -> FontFamily.Default
                            FontChoice.SERIF -> FontFamily.Serif
                            FontChoice.CONDENSED -> FontFamily(Typeface.create("sans-serif-condensed", Typeface.NORMAL))
                            FontChoice.MONOSPACE -> FontFamily.Monospace
                            FontChoice.CASUAL -> FontFamily(Typeface.create("casual", Typeface.NORMAL))
                            FontChoice.MANGA -> mangaFont
                        },
                        fontWeight = if (liveBlock.style.bold) FontWeight.Bold else FontWeight.Normal,
                        fontStyle = if (liveBlock.style.italic) FontStyle.Italic else FontStyle.Normal,
                        textAlign = when (liveBlock.style.alignment) {
                            com.untrustedtranslations.android.model.TextAlignmentChoice.START -> TextAlign.Start
                            com.untrustedtranslations.android.model.TextAlignmentChoice.CENTER -> TextAlign.Center
                            com.untrustedtranslations.android.model.TextAlignmentChoice.END -> TextAlign.End
                        },
                        modifier = Modifier
                            .offset {
                                IntOffset(liveRect.left.roundToInt(), liveRect.top.roundToInt())
                            }
                            .size(liveWidth, liveHeight)
                            .graphicsLayer { rotationZ = draftRotation }
                            .background(
                                liveBlock.style.backgroundColorArgb?.let { Color(it.toInt()) }
                                    ?: Color(0xB3FFFFFF),
                                RoundedCornerShape(6.dp),
                            )
                            .padding(4.dp),
                    )
                }
                Canvas(Modifier.fillMaxSize()) {
                    val geometry = pageImageGeometry(
                        IntSize(size.width.toInt(), size.height.toInt()),
                        bmp.width,
                        bmp.height,
                    )
                    page.blocks.forEachIndexed { index, block ->
                        val isSelected = index == activeIndex && block.applied
                        val bounds = if (isSelected) draftBounds else block.bounds
                        val rect = bounds.toPageRect(geometry)
                        drawRect(
                            color = if (isSelected) AppColors.Cyan else AppColors.Violet,
                            topLeft = rect.topLeft,
                            size = rect.size,
                            style = Stroke((if (isSelected) 3 else 2).dp.toPx()),
                        )
                        if (isSelected) {
                            if (mode == PageTransformMode.RESIZE) {
                                edgeHandles(rect).forEach { handle ->
                                    drawCircle(AppColors.Cyan, 10.dp.toPx(), handle)
                                    drawCircle(AppColors.Void, 4.dp.toPx(), handle)
                                }
                            } else {
                                val stemTop = Offset(rect.center.x, rect.top - 34.dp.toPx())
                                drawLine(AppColors.Cyan, Offset(rect.center.x, rect.top), stemTop, 3.dp.toPx())
                                drawCircle(AppColors.Cyan, 11.dp.toPx(), stemTop)
                                drawCircle(AppColors.Void, 5.dp.toPx(), stemTop)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun hitHandle(pointer: Offset, rect: Rect, mode: PageTransformMode, radius: Float): PageDragHandle {
    if (mode == PageTransformMode.ROTATE) {
        val rotate = Offset(rect.center.x, rect.top - radius * 1.0625f)
        if ((pointer - rotate).getDistance() <= radius) return PageDragHandle.ROTATE
        return if (rect.contains(pointer)) PageDragHandle.MOVE else PageDragHandle.NONE
    }
    val handles = edgeHandles(rect)
    return when {
        (pointer - handles[0]).getDistance() <= radius -> PageDragHandle.LEFT
        (pointer - handles[1]).getDistance() <= radius -> PageDragHandle.TOP
        (pointer - handles[2]).getDistance() <= radius -> PageDragHandle.RIGHT
        (pointer - handles[3]).getDistance() <= radius -> PageDragHandle.BOTTOM
        rect.contains(pointer) -> PageDragHandle.MOVE
        else -> PageDragHandle.NONE
    }
}

private fun edgeHandles(rect: Rect) = listOf(
    Offset(rect.left, rect.center.y),
    Offset(rect.center.x, rect.top),
    Offset(rect.right, rect.center.y),
    Offset(rect.center.x, rect.bottom),
)

private fun pageImageGeometry(viewport: IntSize, imageWidth: Int, imageHeight: Int): Rect {
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

private fun RelativeBounds.toPageRect(image: Rect) = Rect(
    image.left + left * image.width,
    image.top + top * image.height,
    image.left + right * image.width,
    image.top + bottom * image.height,
)

private fun RelativeBounds.moveBy(dx: Float, dy: Float): RelativeBounds {
    val width = right - left
    val height = bottom - top
    val nextLeft = (left + dx).coerceIn(0f, 1f - width)
    val nextTop = (top + dy).coerceIn(0f, 1f - height)
    return RelativeBounds(nextLeft, nextTop, nextLeft + width, nextTop + height)
}

private fun RelativeBounds.resizeFrom(handle: PageDragHandle, dx: Float, dy: Float): RelativeBounds {
    val minimum = .035f
    return when (handle) {
        PageDragHandle.LEFT -> copy(left = (left + dx).coerceIn(0f, right - minimum))
        PageDragHandle.TOP -> copy(top = (top + dy).coerceIn(0f, bottom - minimum))
        PageDragHandle.RIGHT -> copy(right = (right + dx).coerceIn(left + minimum, 1f))
        PageDragHandle.BOTTOM -> copy(bottom = (bottom + dy).coerceIn(top + minimum, 1f))
        else -> this
    }
}

private fun normalizeDegrees(value: Float): Float {
    var normalized = value
    while (normalized > 180f) normalized -= 360f
    while (normalized < -180f) normalized += 360f
    return normalized
}
