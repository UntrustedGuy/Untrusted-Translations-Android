package com.untrustedtranslations.android.ui
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.SyncAlt

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
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Sync
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.ColorFilter
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

private enum class DragTarget { NONE, MOVE, CORNER_TL, CORNER_TR, CORNER_BL, CORNER_BR, EDGE_LEFT, EDGE_RIGHT, ROTATE, PAN }

@Composable
fun ManipulablePagePreview(
    page: ComicPage,
    selectedBlockIndex: Int,
    onSelectBlock: (Int) -> Unit,
    onDeselectAll: () -> Unit,
    onDeleteBlock: () -> Unit,
    onDuplicateBlock: () -> Unit,
    onTransformCommitted: (Int, RelativeBounds, Float, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var bitmap by remember(page.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var originalBitmap by remember(page.id) { mutableStateOf<android.graphics.Bitmap?>(null) }
    val context = LocalContext.current
    var previewPending by remember(page.id) { mutableStateOf(false) }
    LaunchedEffect(page.renderedSource) {
        val bmp = withContext(Dispatchers.IO) {
            page.renderedSource.path?.let(BitmapFactory::decodeFile)
        }
        val origBmp = withContext(Dispatchers.IO) {
            page.originalSource.path?.let(BitmapFactory::decodeFile)
        }
        if (bmp != null) bitmap = bmp
        
        previewPending = false
    }
    val mangaFont = remember {
        FontFamily(Typeface.createFromAsset(context.assets, "fonts/comic_neue_bold.ttf"))
    }
    var maskBitmaps by remember(page.id) { mutableStateOf<Map<String, android.graphics.Bitmap>>(emptyMap()) }
    LaunchedEffect(page.blocks) {
        val toLoad = page.blocks.filter { it.maskSource != null && it.id !in maskBitmaps }
        if (toLoad.isNotEmpty()) {
            val loaded = withContext(Dispatchers.IO) {
                toLoad.mapNotNull { block ->
                    block.maskSource?.path?.let(BitmapFactory::decodeFile)?.let { block.id to it }
                }
            }
            if (loaded.isNotEmpty()) maskBitmaps = maskBitmaps + loaded
        }
    }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    
    val animatedZoomScale = remember { Animatable(1f) }
    val animatedOffsetX = remember { Animatable(0f) }
    val animatedOffsetY = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()
    
    var activeIndex by remember { mutableIntStateOf(selectedBlockIndex) }
    
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
                    .pointerInput(page.blocks, selectedBlockIndex, viewport) {
                        detectTapGestures(
                            onTap = { position ->
                                val geometry = pageImageGeometry(viewport, bmp.width, bmp.height)
                                val currentOffset = Offset(animatedOffsetX.value, animatedOffsetY.value)
                                val scale = animatedZoomScale.value.coerceAtLeast(0.01f)
                                val localPosition = (position - currentOffset) / scale
                                val currentBlock = page.blocks.getOrNull(selectedBlockIndex)
                                val currentRect = currentBlock?.bounds?.toPageRect(geometry)
                                val zInvTap = 1f / scale
                                val handleRadius = 32.dp.toPx() * zInvTap
                                val rotateOffsetPx = 40.dp.toPx() * zInvTap
                                val iconOffsetPx = 18.dp.toPx() * zInvTap
                                
                                if (currentBlock?.applied == true && currentRect != null && !currentBlock.style.locked) {
                                    val target = hitTest(localPosition, currentRect, handleRadius, rotateOffsetPx, iconOffsetPx)
                                    if (target == DragTarget.CORNER_TL) {
                                        onDeleteBlock()
                                        return@detectTapGestures
                                    } else if (target == DragTarget.CORNER_TR) {
                                        onDuplicateBlock()
                                        return@detectTapGestures
                                    }
                                }
                                
                                val hit = page.blocks.indices.sortedByDescending { page.blocks[it].style.zIndex }.firstOrNull { index ->
                                    val b = page.blocks[index]
                                    b.applied && b.style.visible && !b.style.locked && b.bounds.toPageRect(geometry).contains(localPosition)
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
                                val currentOffset = Offset(animatedOffsetX.value, animatedOffsetY.value)
                                val scale = animatedZoomScale.value.coerceAtLeast(0.01f)
                                val localPointer = (pointer - currentOffset) / scale
                                val currentBlock = page.blocks.getOrNull(selectedBlockIndex)
                                val currentRect = currentBlock?.bounds?.toPageRect(geometry)
                                // Scale hit radii by 1/zoom so they match the visually-scaled icons
                                val zInv = 1f / scale
                                val handleRadius = 32.dp.toPx() * zInv
                                val rotateOffsetPx = 40.dp.toPx() * zInv
                                val iconOffsetPx = 18.dp.toPx() * zInv
                                dragTarget = if (currentBlock?.applied == true && currentRect != null && !currentBlock.style.locked) {
                                    hitTest(localPointer, currentRect, handleRadius, rotateOffsetPx, iconOffsetPx)
                                } else DragTarget.NONE
                                
                                // If they clicked a non-dragging icon (Close, Copy, or Edit), don't start dragging
                                if (dragTarget == DragTarget.CORNER_TL || dragTarget == DragTarget.CORNER_TR || dragTarget == DragTarget.CORNER_BL) {
                                    dragTarget = DragTarget.NONE
                                    return@detectDragGestures
                                }
                                
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
                                        b.applied && b.style.visible && !b.style.locked && b.bounds.toPageRect(geometry).contains(localPointer)
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
                                val currentOffset = Offset(animatedOffsetX.value, animatedOffsetY.value)
                                val scale = animatedZoomScale.value.coerceAtLeast(0.01f)
                                val dx = (amount.x / scale) / geometry.width.coerceAtLeast(1f)
                                val dy = (amount.y / scale) / geometry.height.coerceAtLeast(1f)
                                
                                when (dragTarget) {
                                    DragTarget.MOVE -> draftBounds = draftBounds.moveBy(dx, dy)
                                    DragTarget.CORNER_BR, DragTarget.EDGE_LEFT, DragTarget.EDGE_RIGHT -> 
                                        draftBounds = draftBounds.resizeFromCorner(dragTarget, dx, dy)
                                    DragTarget.ROTATE -> {
                                        val localChangePos = (change.position - currentOffset) / scale
                                        val rect = draftBounds.toPageRect(geometry)
                                        val degrees = Math.toDegrees(
                                            atan2(
                                                localChangePos.y - rect.center.y,
                                                localChangePos.x - rect.center.x,
                                            ).toDouble(),
                                        ).toFloat() + 90f // adjust for top-center handle angle
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
                                    val resized = dragTarget in listOf(DragTarget.CORNER_BR, DragTarget.EDGE_LEFT, DragTarget.EDGE_RIGHT)
                                    onTransformCommitted(activeIndex, draftBounds, draftRotation, resized)
                                }
                                dragTarget = DragTarget.NONE
                            },
                        )
                    },
            ) {
                val iconClose = androidx.compose.ui.graphics.vector.rememberVectorPainter(androidx.compose.material.icons.Icons.Default.Close)
                val iconRotate = androidx.compose.ui.graphics.vector.rememberVectorPainter(androidx.compose.material.icons.Icons.Default.Refresh)
                val iconScale = androidx.compose.ui.graphics.vector.rememberVectorPainter(androidx.compose.material.icons.Icons.Default.OpenInFull)
                val iconStretch = androidx.compose.ui.graphics.vector.rememberVectorPainter(androidx.compose.material.icons.Icons.Default.SyncAlt)
                Canvas(Modifier.fillMaxSize()) {
                    val geometry = pageImageGeometry(IntSize(size.width.toInt(), size.height.toInt()), bmp.width, bmp.height)
                    val destRect = androidx.compose.ui.unit.IntRect(
                        geometry.left.toInt(), geometry.top.toInt(), geometry.right.toInt(), geometry.bottom.toInt()
                    )
                    
                    // Draw the rendered page directly — no punch-through, no ghost
                    drawImage(
                        image = bmp.asImageBitmap(),
                        dstOffset = destRect.topLeft,
                        dstSize = destRect.size
                    )
                    // Highlight every detected block that hasn't been replaced yet, so it's
                    // obvious at a glance whether OCR actually found a given line of text.
                    // This traces the real glyph shapes (from the same segmentation model used
                    // for background cleaning), not a bounding-box rectangle -- the highlight
                    // for a block disappears the moment that specific block is applied; other
                    // still-untouched blocks keep theirs.
                    page.blocks.forEach { block ->
                        if (!block.applied && block.style.visible) {
                            val outlineRect = block.bounds.toPageRect(geometry)
                            val maskBitmap = maskBitmaps[block.id]
                            if (maskBitmap != null) {
                                drawImage(
                                    image = maskBitmap.asImageBitmap(),
                                    dstOffset = androidx.compose.ui.unit.IntOffset(
                                        outlineRect.left.roundToInt(), outlineRect.top.roundToInt(),
                                    ),
                                    dstSize = androidx.compose.ui.unit.IntSize(
                                        outlineRect.width.roundToInt().coerceAtLeast(1),
                                        outlineRect.height.roundToInt().coerceAtLeast(1),
                                    ),
                                )
                            } else {
                                // Fallback for a block whose mask hasn't been computed (e.g. the
                                // cleaning model pack isn't installed) -- still show *something*
                                // rather than silently having no indicator at all.
                                drawRect(
                                    color = AppColors.Violet,
                                    topLeft = outlineRect.topLeft,
                                    size = outlineRect.size,
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
                                )
                            }
                        }
                    }
                }
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
                        // Scale icon/stroke sizes by 1/zoom so they stay constant on screen
                        val zoomInv = 1f / animatedZoomScale.value.coerceAtLeast(0.1f)
                        drawRect(
                            color = AppColors.Cyan,
                            topLeft = rect.topLeft,
                            size = rect.size,
                            style = Stroke(3.dp.toPx() * zoomInv),
                        )
                        
                        fun drawIcon(painter: androidx.compose.ui.graphics.vector.VectorPainter, center: Offset) {
                            val iconSize = 22.dp.toPx() * zoomInv
                            val half = iconSize / 2f
                            
                            drawCircle(Color(0x99000000), radius = half + 4.dp.toPx() * zoomInv, center = center)
                            
                            translate(center.x - half, center.y - half) {
                                with(painter) {
                                    draw(Size(iconSize, iconSize), colorFilter = ColorFilter.tint(Color.White))
                                }
                            }
                        }
                        
                        val offset = 18.dp.toPx() * zoomInv
                        
                        // Top-Center: Rotate Handle
                        val rotateHandleCenter = Offset(rect.center.x, rect.top - 40.dp.toPx() * zoomInv)
                        drawLine(AppColors.Cyan, Offset(rect.center.x, rect.top), rotateHandleCenter, 2.dp.toPx() * zoomInv)
                        drawIcon(iconRotate, rotateHandleCenter)
                        
                        // TL: Close (X)
                        drawIcon(iconClose, Offset(rect.left - offset, rect.top - offset))
                        // BR: Scale
                        drawIcon(iconScale, Offset(rect.right + offset, rect.bottom + offset))
                        
                        // Left Edge: Stretch
                        drawIcon(iconStretch, Offset(rect.left - offset, rect.center.y))
                        
                        // Right Edge: Stretch
                        drawIcon(iconStretch, Offset(rect.right + offset, rect.center.y))
                        
                        // Live Tooltip
                        if (previewPending) {
                            val metricsText = when(dragTarget) {
                                DragTarget.ROTATE -> "${"%.0f".format(draftRotation)}°"
                                DragTarget.CORNER_BR, DragTarget.EDGE_LEFT, DragTarget.EDGE_RIGHT -> {
                                    val scale = (rect.width / sourceBounds.toPageRect(geometry).width) * 100
                                    "${"%.0f".format(scale)}%"
                                }
                                DragTarget.MOVE -> "X: ${"%.0f".format(draftBounds.left * 100)}% Y: ${"%.0f".format(draftBounds.top * 100)}%"
                                else -> ""
                            }
                            if (metricsText.isNotEmpty()) {
                                val paint = android.graphics.Paint().apply { color = android.graphics.Color.WHITE; textSize = 32f * zoomInv; textAlign = android.graphics.Paint.Align.CENTER }
                                val textWidth = paint.measureText(metricsText)
                                val tooltipY = rect.top - 60.dp.toPx() * zoomInv
                                val tooltipRect = androidx.compose.ui.geometry.Rect(rect.center.x - textWidth/2 - 20f * zoomInv, tooltipY - 36f * zoomInv, rect.center.x + textWidth/2 + 20f * zoomInv, tooltipY + 16f * zoomInv)
                                drawRoundRect(AppColors.Void, topLeft = tooltipRect.topLeft, size = tooltipRect.size, cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f * zoomInv, 8f * zoomInv))
                                drawContext.canvas.nativeCanvas.drawText(metricsText, rect.center.x, tooltipY, paint)
                            }
                        }
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

private fun hitTest(pointer: Offset, rect: Rect, radius: Float, rotateOffsetPx: Float, iconOffsetPx: Float): DragTarget {
    val handleRadius = radius * 0.75f

    // Check icon handles FIRST so they take priority over the block body,
    // especially when zoomed and the icon hit-areas overlap the rect edges.
    val rotateHandle = Offset(rect.center.x, rect.top - rotateOffsetPx)
    if ((pointer - rotateHandle).getDistance() <= handleRadius) return DragTarget.ROTATE
    if ((pointer - Offset(rect.left - iconOffsetPx, rect.top - iconOffsetPx)).getDistance() <= handleRadius) return DragTarget.CORNER_TL
    if ((pointer - Offset(rect.right + iconOffsetPx, rect.bottom + iconOffsetPx)).getDistance() <= handleRadius) return DragTarget.CORNER_BR
    if ((pointer - Offset(rect.left - iconOffsetPx, rect.center.y)).getDistance() <= handleRadius) return DragTarget.EDGE_LEFT
    if ((pointer - Offset(rect.right + iconOffsetPx, rect.center.y)).getDistance() <= handleRadius) return DragTarget.EDGE_RIGHT

    // Move the block itself (checked AFTER icons)
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
        DragTarget.EDGE_LEFT -> copy(left = (left + dx).coerceIn(0f, right - minimum))
        DragTarget.EDGE_RIGHT -> copy(right = (right + dx).coerceIn(left + minimum, 1f))
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
