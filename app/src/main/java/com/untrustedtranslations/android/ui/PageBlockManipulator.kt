package com.untrustedtranslations.android.ui

import android.graphics.BitmapFactory
import android.graphics.Typeface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.graphics.Brush
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
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.abs
import kotlin.math.PI

private enum class DragTarget { NONE, MOVE, CORNER_TL, CORNER_TR, CORNER_BL, CORNER_BR, ROTATE, PAN }

@Composable
fun ManipulablePagePreview(
    page: ComicPage,
    selectedBlockIndex: Int,
    onSelectBlock: (Int) -> Unit,
    onDeselectAll: () -> Unit,
    onTransformCommitted: (Int, RelativeBounds, Float, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var bitmap by remember(page.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    val context = LocalContext.current
    var previewPending by remember(page.id) { mutableStateOf(false) }
    LaunchedEffect(page.renderedSource) {
        val bmp = withContext(Dispatchers.IO) {
            page.renderedSource.path?.let(BitmapFactory::decodeFile)
        }
        if (bmp != null) bitmap = bmp
        previewPending = false
    }
    val mangaFont = remember {
        FontFamily(Typeface.createFromAsset(context.assets, "fonts/comic_neue_bold.ttf"))
    }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    
    val animatedZoomScale = remember { Animatable(1f) }
    val animatedOffsetX = remember { Animatable(0f) }
    val animatedOffsetY = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()
    
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
    var dragTarget by remember { mutableStateOf(DragTarget.NONE) }
    val shape = RoundedCornerShape(18.dp)

    Box(
        modifier
            .background(AppColors.Surface, shape)
            .border(1.dp, Color(0xFF343240), shape),
    ) {
        if (bitmap != null) {
            val bmp = bitmap!!
            
            suspend fun applyZoom(targetScale: Float, focus: Offset, pan: Offset = Offset.Zero) {
                val newScale = targetScale.coerceIn(1f, 6f)
                val currentScale = animatedZoomScale.value
                val change = if (currentScale == 0f) 1f else newScale / currentScale
                val currentOffset = Offset(animatedOffsetX.value, animatedOffsetY.value)
                val unclamped = focus - (focus - currentOffset) * change + pan
                val width = viewport.width.toFloat()
                val height = viewport.height.toFloat()
                
                animatedZoomScale.snapTo(newScale)
                animatedOffsetX.snapTo(unclamped.x.coerceIn(width - width * newScale, 0f))
                animatedOffsetY.snapTo(unclamped.y.coerceIn(height - height * newScale, 0f))
            }
            
            suspend fun animateZoomTo(targetScale: Float, focusPoint: Offset = Offset(viewport.width / 2f, viewport.height / 2f)) {
                val newScale = targetScale.coerceIn(1f, 6f)
                val currentScale = animatedZoomScale.value
                val change = if (currentScale == 0f) 1f else newScale / currentScale
                val currentOffset = Offset(animatedOffsetX.value, animatedOffsetY.value)
                val unclamped = focusPoint - (focusPoint - currentOffset) * change
                val width = viewport.width.toFloat()
                val height = viewport.height.toFloat()
                
                val targetX = unclamped.x.coerceIn(width - width * newScale, 0f)
                val targetY = unclamped.y.coerceIn(height - height * newScale, 0f)
                
                coroutineScope.launch { animatedZoomScale.animateTo(newScale, spring(dampingRatio = 0.8f)) }
                coroutineScope.launch { animatedOffsetX.animateTo(targetX, spring(dampingRatio = 0.8f)) }
                coroutineScope.launch { animatedOffsetY.animateTo(targetY, spring(dampingRatio = 0.8f)) }
            }

            Box(
                Modifier.fillMaxSize().padding(6.dp)
                    .onSizeChanged { viewport = it }
                    .then(if (animatedZoomScale.value > 1.001f) Modifier.clip(RoundedCornerShape(12.dp)) else Modifier)
                    .pointerInput(page.blocks, selectedBlockIndex, viewport) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            var decided = false
                            var pinchingBlock = false
                            var pinchDecided = false
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                val pressed = event.changes.filter { it.pressed }
                                if (pressed.size >= 2) {
                                    if (!pinchDecided) {
                                        pinchDecided = true
                                        decided = true
                                        val centroid = event.calculateCentroid()
                                        val currentOffset = Offset(animatedOffsetX.value, animatedOffsetY.value)
                                        val local = (centroid - currentOffset) / animatedZoomScale.value
                                        val geometry = pageImageGeometry(viewport, bmp.width, bmp.height)
                                        val block = page.blocks.getOrNull(selectedBlockIndex)?.takeIf { it.applied }
                                        val selectedRect = block?.bounds?.toPageRect(geometry)
                                        pinchingBlock = selectedRect?.inflate(24.dp.toPx())?.contains(local) == true
                                        if (pinchingBlock && block != null && !block.style.locked) {
                                            activeIndex = selectedBlockIndex
                                            draftBounds = block.bounds
                                            draftRotation = block.style.rotationDegrees
                                            sourceBounds = block.bounds
                                            previewPending = true
                                        }
                                    }
                                    if (pinchingBlock) {
                                        val zoom = event.calculateZoom()
                                        val rotation = event.calculateRotation()
                                        if (zoom != 1f) draftBounds = draftBounds.scaledAroundCenter(zoom)
                                        if (rotation != 0f) {
                                            draftRotation = normalizeDegrees(draftRotation + rotation)
                                            draftRotation = snapAngle(draftRotation)
                                        }
                                        event.changes.forEach { it.consume() }
                                    } else {
                                        coroutineScope.launch {
                                            applyZoom(
                                                animatedZoomScale.value * event.calculateZoom(),
                                                event.calculateCentroid(),
                                                event.calculatePan(),
                                            )
                                        }
                                        event.changes.forEach { it.consume() }
                                    }
                                }
                                if (event.changes.none { it.pressed }) {
                                    if (pinchingBlock && activeIndex in page.blocks.indices) {
                                        if (draftBounds != sourceBounds) {
                                            onTransformCommitted(activeIndex, draftBounds, draftRotation, true)
                                        } else {
                                            previewPending = false
                                        }
                                    }
                                    break
                                }
                            }
                        }
                    }
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = animatedZoomScale.value
                        scaleY = animatedZoomScale.value
                        translationX = animatedOffsetX.value
                        translationY = animatedOffsetY.value
                    }
                    .pointerInput(page.blocks, viewport) {
                        detectTapGestures(
                            onTap = { position ->
                                val geometry = pageImageGeometry(viewport, bmp.width, bmp.height)
                                val hit = page.blocks.indices.sortedByDescending { page.blocks[it].style.zIndex }.firstOrNull { index ->
                                    val b = page.blocks[index]
                                    b.applied && b.style.visible && !b.style.locked && b.bounds.toPageRect(geometry).contains(position)
                                }
                                if (hit != null) onSelectBlock(hit) else onDeselectAll()
                            },
                            onDoubleTap = { position ->
                                coroutineScope.launch {
                                    if (animatedZoomScale.value > 1.01f) animateZoomTo(1f)
                                    else animateZoomTo(2.5f, focusPoint = position)
                                }
                            }
                        )
                    }
                    .pointerInput(page.blocks, selectedBlockIndex, viewport) {
                        val velocityTracker = VelocityTracker()
                        detectDragGestures(
                            onDragStart = { pointer ->
                                val geometry = pageImageGeometry(viewport, bmp.width, bmp.height)
                                val currentBlock = page.blocks.getOrNull(selectedBlockIndex)
                                val currentRect = currentBlock?.bounds?.toPageRect(geometry)
                                val handleRadius = 32.dp.toPx()
                                dragTarget = if (currentBlock?.applied == true && currentRect != null && !currentBlock.style.locked) {
                                    hitTest(pointer, currentRect, handleRadius)
                                } else DragTarget.NONE
                                
                                if (dragTarget != DragTarget.NONE) {
                                    val activeBlock = requireNotNull(currentBlock)
                                    activeIndex = selectedBlockIndex
                                    draftBounds = activeBlock.bounds
                                    draftRotation = activeBlock.style.rotationDegrees
                                    sourceBounds = activeBlock.bounds
                                    previewPending = true
                                } else {
                                    val hit = page.blocks.indices.sortedByDescending { page.blocks[it].style.zIndex }.firstOrNull { index ->
                                        val b = page.blocks[index]
                                        b.applied && b.style.visible && !b.style.locked && b.bounds.toPageRect(geometry).contains(pointer)
                                    }
                                    if (hit != null) {
                                        val block = page.blocks[hit]
                                        activeIndex = hit
                                        draftBounds = block.bounds
                                        draftRotation = block.style.rotationDegrees
                                        sourceBounds = block.bounds
                                        previewPending = true
                                        onSelectBlock(hit)
                                        dragTarget = DragTarget.MOVE
                                    } else if (animatedZoomScale.value > 1f) {
                                        dragTarget = DragTarget.PAN
                                    }
                                }
                            },
                            onDrag = { change, amount ->
                                if (dragTarget == DragTarget.NONE) return@detectDragGestures
                                change.consume()
                                velocityTracker.addPosition(change.uptimeMillis, change.position)
                                val geometry = pageImageGeometry(viewport, bmp.width, bmp.height)
                                val dx = amount.x / geometry.width.coerceAtLeast(1f)
                                val dy = amount.y / geometry.height.coerceAtLeast(1f)
                                
                                when (dragTarget) {
                                    DragTarget.MOVE -> draftBounds = draftBounds.moveBy(dx, dy)
                                    DragTarget.CORNER_TL, DragTarget.CORNER_TR,
                                    DragTarget.CORNER_BL, DragTarget.CORNER_BR -> 
                                        draftBounds = draftBounds.resizeFromCorner(dragTarget, dx, dy)
                                    DragTarget.ROTATE -> {
                                        val rect = draftBounds.toPageRect(geometry)
                                        val degrees = Math.toDegrees(
                                            atan2(
                                                change.position.y - rect.center.y,
                                                change.position.x - rect.center.x,
                                            ).toDouble(),
                                        ).toFloat() + 90f
                                        draftRotation = snapAngle(normalizeDegrees(degrees))
                                    }
                                    DragTarget.PAN -> {
                                        coroutineScope.launch {
                                            applyZoom(animatedZoomScale.value, Offset.Zero, amount)
                                        }
                                    }
                                    else -> {}
                                }
                            },
                            onDragCancel = {
                                dragTarget = DragTarget.NONE
                                previewPending = false
                                activeIndex = selectedBlockIndex
                            },
                            onDragEnd = {
                                if (dragTarget == DragTarget.PAN) {
                                    val velocity = velocityTracker.calculateVelocity()
                                    coroutineScope.launch { animatedOffsetX.animateDecay(velocity.x, exponentialDecay()) }
                                    coroutineScope.launch { animatedOffsetY.animateDecay(velocity.y, exponentialDecay()) }
                                } else if (dragTarget != DragTarget.NONE && activeIndex in page.blocks.indices) {
                                    val resized = dragTarget in listOf(DragTarget.CORNER_TL, DragTarget.CORNER_TR, DragTarget.CORNER_BL, DragTarget.CORNER_BR)
                                    onTransformCommitted(activeIndex, draftBounds, draftRotation, resized)
                                }
                                dragTarget = DragTarget.NONE
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
                    val density = LocalDensity.current
                    
                    val fittedSourceSp = remember(
                        liveBlock.id,
                        liveBlock.translatedText,
                        liveBlock.style,
                        sourceBounds,
                    ) {
                        PageRenderer.fittedFontSizeSp(
                            context,
                            bmp.width,
                            bmp.height,
                            liveBlock.copy(bounds = sourceBounds),
                        )
                    }
                    val displayScale = geometry.width / bmp.width.coerceAtLeast(1)
                    val liveFontSizeSp = fittedSourceSp * displayScale
                    
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .offset { IntOffset(oldRect.left.roundToInt(), oldRect.top.roundToInt()) }
                            .size(
                                with(density) { oldRect.width.toDp() },
                                with(density) { oldRect.height.toDp() },
                            )
                            .graphicsLayer {
                                val live = draftBounds.toPageRect(geometry)
                                transformOrigin = TransformOrigin(.5f, .5f)
                                scaleX = live.width / oldRect.width.coerceAtLeast(1f)
                                scaleY = live.height / oldRect.height.coerceAtLeast(1f)
                                translationX = live.center.x - oldRect.center.x
                                translationY = live.center.y - oldRect.center.y
                                rotationZ = draftRotation
                                rotationX = liveBlock.style.perspective3dX
                                rotationY = liveBlock.style.perspective3dY
                                cameraDistance = 12f * density.density
                            }
                            .background(
                                liveBlock.style.backgroundColorArgb?.let {
                                    Color(it.toInt()).copy(alpha = liveBlock.style.backgroundOpacity)
                                } ?: Color.Transparent,
                                RoundedCornerShape(liveBlock.style.backgroundCornerRadiusDp.dp),
                            ),
                    ) {
                        val textBrush = if (liveBlock.style.gradientEnabled) {
                            val angleRad = Math.toRadians(liveBlock.style.gradientAngleDegrees.toDouble())
                            val x0 = 50f - 50f * cos(angleRad).toFloat()
                            val y0 = 50f - 50f * sin(angleRad).toFloat()
                            val x1 = 50f + 50f * cos(angleRad).toFloat()
                            val y1 = 50f + 50f * sin(angleRad).toFloat()
                            Brush.linearGradient(
                                colors = listOf(Color(liveBlock.style.gradientStartColorArgb.toInt()), Color(liveBlock.style.gradientEndColorArgb.toInt())),
                                start = Offset(x0, y0),
                                end = Offset(x1, y1)
                            )
                        } else null
                        
                        var textDeco = TextDecoration.None
                        if (liveBlock.style.underline) textDeco = textDeco + TextDecoration.Underline
                        if (liveBlock.style.strikethrough) textDeco = textDeco + TextDecoration.LineThrough
                        
                        Text(
                            text = if (liveBlock.style.vertical) {
                                liveBlock.translatedText.lines().joinToString("\n") { line ->
                                    line.trim().toCharArray().joinToString("\n")
                                }
                            } else liveBlock.translatedText,
                            color = if (textBrush == null) Color(liveBlock.style.textColorArgb.toInt()).copy(alpha = liveBlock.style.textOpacity) else Color.Unspecified,
                            fontSize = liveFontSizeSp.coerceIn(.5f, 160f).sp,
                            letterSpacing = (liveBlock.style.letterSpacingEm * 14).sp,
                            lineHeight = (liveFontSizeSp * liveBlock.style.lineSpacingMultiplier).sp,
                            fontFamily = when (liveBlock.style.font) {
                                FontChoice.AUTO, FontChoice.SANS -> FontFamily.Default
                                FontChoice.SERIF -> FontFamily.Serif
                                FontChoice.CONDENSED -> FontFamily(Typeface.create("sans-serif-condensed", Typeface.NORMAL))
                                FontChoice.MONOSPACE -> FontFamily.Monospace
                                FontChoice.CASUAL -> FontFamily(Typeface.create("casual", Typeface.NORMAL))
                                FontChoice.MANGA -> mangaFont
                                FontChoice.ACTION -> FontFamily(Typeface.create("sans-serif-black", Typeface.BOLD))
                                FontChoice.GOTHIC -> FontFamily.Serif
                                FontChoice.VINTAGE -> FontFamily(Typeface.create("cursive", Typeface.NORMAL))
                                else -> FontFamily.Default
                            },
                            fontWeight = if (liveBlock.style.bold || liveBlock.style.font == FontChoice.ACTION || liveBlock.style.font == FontChoice.GOTHIC) FontWeight.Bold else FontWeight.Normal,
                            fontStyle = if (liveBlock.style.italic) FontStyle.Italic else FontStyle.Normal,
                            textAlign = when (liveBlock.style.alignment) {
                                com.untrustedtranslations.android.model.TextAlignmentChoice.START -> TextAlign.Start
                                com.untrustedtranslations.android.model.TextAlignmentChoice.CENTER -> TextAlign.Center
                                com.untrustedtranslations.android.model.TextAlignmentChoice.END -> TextAlign.End
                            },
                            textDecoration = textDeco,
                            modifier = Modifier.fillMaxWidth().padding(
                                with(density) { liveBlock.style.backgroundPaddingDp.dp.toPx().toDp() },
                            ),
                            style = androidx.compose.ui.text.TextStyle(
                                brush = textBrush,
                                alpha = liveBlock.style.textOpacity
                            )
                        )
                    }
                }
                Canvas(Modifier.fillMaxSize()) {
                    val geometry = pageImageGeometry(
                        IntSize(size.width.toInt(), size.height.toInt()),
                        bmp.width,
                        bmp.height,
                    )
                    
                    // Draw snap guides if dragging
                    if (dragTarget != DragTarget.NONE) {
                        val activeRect = draftBounds.toPageRect(geometry)
                        val centerX = geometry.left + geometry.width / 2f
                        val centerY = geometry.top + geometry.height / 2f
                        if (abs(activeRect.center.x - centerX) < 4.dp.toPx()) {
                            drawLine(AppColors.Cyan, Offset(centerX, 0f), Offset(centerX, size.height), strokeWidth = 2.dp.toPx())
                        }
                        if (abs(activeRect.center.y - centerY) < 4.dp.toPx()) {
                            drawLine(AppColors.Cyan, Offset(0f, centerY), Offset(size.width, centerY), strokeWidth = 2.dp.toPx())
                        }
                    }

                    // Only draw selection border for the active block
                    val selBlock = page.blocks.getOrNull(activeIndex)
                    if (selBlock?.applied == true && selectedBlockIndex != -1) {
                        val rect = draftBounds.toPageRect(geometry)
                        drawRect(
                            color = AppColors.Cyan,
                            topLeft = rect.topLeft,
                            size = rect.size,
                            style = Stroke(3.dp.toPx()),
                        )
                        
                        cornerHandles(rect).forEach { handle ->
                            drawCircle(AppColors.Cyan, 12.dp.toPx(), handle)
                            drawCircle(AppColors.Void, 6.dp.toPx(), handle)
                        }
                        
                        val rotateHandle = Offset(rect.center.x, rect.top - 40.dp.toPx())
                        drawLine(AppColors.Cyan, Offset(rect.center.x, rect.top), rotateHandle, 2.dp.toPx())
                        drawCircle(AppColors.Cyan, 14.dp.toPx(), rotateHandle)
                        drawCircle(AppColors.Void, 7.dp.toPx(), rotateHandle)
                    }
                }
            }
            Row(
                Modifier.align(Alignment.BottomEnd).padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (animatedZoomScale.value > 1.01f) {
                    Text(
                        "${(animatedZoomScale.value * 100).roundToInt()}%",
                        color = AppColors.Cyan,
                        fontSize = 12.sp,
                        modifier = Modifier
                            .background(Color(0xCC15131D), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                ZoomControlButton(Icons.Default.ZoomOut, "Zoom out") {
                    coroutineScope.launch { animateZoomTo(animatedZoomScale.value / 1.5f) }
                }
                ZoomControlButton(Icons.Default.ZoomIn, "Zoom in") {
                    coroutineScope.launch { animateZoomTo(animatedZoomScale.value * 1.5f) }
                }
                if (animatedZoomScale.value > 1.01f) {
                    ZoomControlButton(Icons.Default.CenterFocusStrong, "Reset zoom") {
                        coroutineScope.launch { animateZoomTo(1f) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoomControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(38.dp).background(Color(0xCC15131D), CircleShape),
    ) {
        Icon(icon, description, tint = AppColors.Cyan, modifier = Modifier.size(22.dp))
    }
}

private fun hitTest(pointer: Offset, rect: Rect, radius: Float): DragTarget {
    val rotateHandle = Offset(rect.center.x, rect.top - 40.dp.value)
    if ((pointer - rotateHandle).getDistance() <= radius) return DragTarget.ROTATE
    cornerHandles(rect).forEachIndexed { i, corner ->
        if ((pointer - corner).getDistance() <= radius)
            return listOf(DragTarget.CORNER_TL, DragTarget.CORNER_TR, DragTarget.CORNER_BL, DragTarget.CORNER_BR)[i]
    }
    if (rect.contains(pointer)) return DragTarget.MOVE
    return DragTarget.NONE
}

private fun cornerHandles(rect: Rect) = listOf(rect.topLeft, rect.topRight, rect.bottomLeft, rect.bottomRight)

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

private fun RelativeBounds.scaledAroundCenter(factor: Float): RelativeBounds {
    val cx = (left + right) / 2f
    val cy = (top + bottom) / 2f
    val halfWidth = ((right - left) / 2f * factor).coerceIn(.0175f, .5f)
    val halfHeight = ((bottom - top) / 2f * factor).coerceIn(.0175f, .5f)
    return RelativeBounds(
        (cx - halfWidth).coerceIn(0f, 1f),
        (cy - halfHeight).coerceIn(0f, 1f),
        (cx + halfWidth).coerceIn(0f, 1f),
        (cy + halfHeight).coerceIn(0f, 1f),
    )
}

private fun RelativeBounds.moveBy(dx: Float, dy: Float): RelativeBounds {
    val width = right - left
    val height = bottom - top
    val nextLeft = (left + dx).coerceIn(0f, 1f - width)
    val nextTop = (top + dy).coerceIn(0f, 1f - height)
    return RelativeBounds(nextLeft, nextTop, nextLeft + width, nextTop + height)
}

private fun RelativeBounds.resizeFromCorner(handle: DragTarget, dx: Float, dy: Float): RelativeBounds {
    val minimum = .035f
    return when (handle) {
        DragTarget.CORNER_TL -> copy(left = (left + dx).coerceIn(0f, right - minimum), top = (top + dy).coerceIn(0f, bottom - minimum))
        DragTarget.CORNER_TR -> copy(right = (right + dx).coerceIn(left + minimum, 1f), top = (top + dy).coerceIn(0f, bottom - minimum))
        DragTarget.CORNER_BL -> copy(left = (left + dx).coerceIn(0f, right - minimum), bottom = (bottom + dy).coerceIn(top + minimum, 1f))
        DragTarget.CORNER_BR -> copy(right = (right + dx).coerceIn(left + minimum, 1f), bottom = (bottom + dy).coerceIn(top + minimum, 1f))
        else -> this
    }
}

private fun normalizeDegrees(value: Float): Float {
    var normalized = value
    while (normalized > 180f) normalized -= 360f
    while (normalized < -180f) normalized += 360f
    return normalized
}

private fun snapAngle(degrees: Float): Float {
    val snaps = listOf(0f, 45f, 90f, 135f, 180f, -45f, -90f, -135f, -180f)
    val threshold = 3f
    return snaps.firstOrNull { abs(degrees - it) < threshold } ?: degrees
}
