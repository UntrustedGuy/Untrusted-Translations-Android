package com.untrustedtranslations.android.processing

import ai.onnxruntime.OnnxTensor
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object LaMaInpainter {
    
    suspend fun tryEraseAll(
        context: Context,
        bitmap: Bitmap,
        rects: List<Rect>,
    ): Boolean = withContext(Dispatchers.Default) {
        if (!ModelPackManager.isInstalled(context, ModelPackId.LAMA_INPAINTING)) return@withContext false
        
        val dir = ModelPackManager.directory(context, ModelPackId.LAMA_INPAINTING)
        val lamaFile = File(dir, "lama-manga-dynamic.onnx")
        val ctdFile = File(dir, "comic-text-detector.onnx")
        
        if (!lamaFile.isFile || !ctdFile.isFile) return@withContext false

        try {
            val lamaSession = OnnxSessionCache.getOrCreate("lama", lamaFile)
            val ctdSession = OnnxSessionCache.getOrCreate("ctd", ctdFile)
            val env = OnnxSessionCache.environment

            if (rects.isEmpty()) return@withContext true
            val cropSize = 1024
            val clusters = mutableListOf<MutableList<Rect>>()
            for (rect in rects) {
                var added = false
                for (cluster in clusters) {
                    var minX = rect.left
                    var minY = rect.top
                    var maxX = rect.right
                    var maxY = rect.bottom
                    for (r in cluster) {
                        minX = min(minX, r.left)
                        minY = min(minY, r.top)
                        maxX = max(maxX, r.right)
                        maxY = max(maxY, r.bottom)
                    }
                    if (maxX - minX <= cropSize && maxY - minY <= cropSize) {
                        cluster.add(rect)
                        added = true
                        break
                    }
                }
                if (!added) {
                    clusters.add(mutableListOf(rect))
                }
            }
            
            for (cluster in clusters) {
                var minX = cluster.first().left
                var minY = cluster.first().top
                var maxX = cluster.first().right
                var maxY = cluster.first().bottom
                for (r in cluster) {
                    minX = min(minX, r.left)
                    minY = min(minY, r.top)
                    maxX = max(maxX, r.right)
                    maxY = max(maxY, r.bottom)
                }
                val cx = (minX + maxX) / 2
                val cy = (minY + maxY) / 2
                
                val half = cropSize / 2
                val cropLeft = cx - half
                val cropTop = cy - half
                val cropRight = cx + half
                val cropBottom = cy + half
                
                val paddedCrop = Bitmap.createBitmap(cropSize, cropSize, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(paddedCrop)
                canvas.drawColor(Color.WHITE)
                
                val srcRect = Rect(
                    max(0, cropLeft), max(0, cropTop),
                    min(bitmap.width, cropRight), min(bitmap.height, cropBottom)
                )
                val dstRect = Rect(
                    if (cropLeft < 0) -cropLeft else 0,
                    if (cropTop < 0) -cropTop else 0,
                    (if (cropLeft < 0) -cropLeft else 0) + srcRect.width(),
                    (if (cropTop < 0) -cropTop else 0) + srcRect.height()
                )
                
                canvas.drawBitmap(bitmap, srcRect, dstRect, null)
                
                val pixels = IntArray(cropSize * cropSize)
                paddedCrop.getPixels(pixels, 0, cropSize, 0, 0, cropSize, cropSize)
                
                val ctdData = FloatArray(3 * cropSize * cropSize)
                val lamaData = FloatArray(3 * cropSize * cropSize)
                
                val meanR = 0.485f * 255f
                val meanG = 0.456f * 255f
                val meanB = 0.406f * 255f
                val stdR = 0.229f * 255f
                val stdG = 0.224f * 255f
                val stdB = 0.225f * 255f
                
                for (i in pixels.indices) {
                    val p = pixels[i]
                    val r = ((p shr 16) and 0xFF).toFloat()
                    val g = ((p shr 8) and 0xFF).toFloat()
                    val b = (p and 0xFF).toFloat()
                    
                    ctdData[i] = (r - meanR) / stdR
                    ctdData[cropSize * cropSize + i] = (g - meanG) / stdG
                    ctdData[2 * cropSize * cropSize + i] = (b - meanB) / stdB
                    
                    lamaData[i] = r / 255f
                    lamaData[cropSize * cropSize + i] = g / 255f
                    lamaData[2 * cropSize * cropSize + i] = b / 255f
                }
                
                val segMaskData = FloatArray(cropSize * cropSize)
                OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(ctdData), longArrayOf(1, 3, cropSize.toLong(), cropSize.toLong())).use { input ->
                    ctdSession.run(mapOf("images" to input)).use { result ->
                        @Suppress("UNCHECKED_CAST")
                        val segOutput = result[1].value as Array<Array<Array<FloatArray>>>
                        val segMask = segOutput[0][0]
                        for (y in 0 until cropSize) {
                            for (x in 0 until cropSize) {
                                val globalX = cropLeft + x
                                val globalY = cropTop + y
                                val inBox = cluster.any { globalX in it.left..it.right && globalY in it.top..it.bottom }
                                val prob = segMask[y][x]
                                segMaskData[y * cropSize + x] = if (inBox && prob > 0.5f) 1f else 0f
                            }
                        }
                    }
                }

                // Grow the raw segmentation mask a little so anti-aliased glyph edges and thin
                // ink bleed just outside the detector's confident pixels are still covered by
                // LaMa's input mask (otherwise faint text fragments survive around the border).
                val dilatedMask = dilate(segMaskData, cropSize, cropSize, radius = 3)
                // A softer, wider version of the same mask is used only for the *output*
                // blend below, feathered so the seam between generated and original pixels
                // isn't a hard rectangle -- that hard edge, previously drawn at the full
                // detection-box boundary, is what produced visible seams/artifacts in the
                // surrounding art.
                val blendAlpha = boxBlurAlpha(dilate(segMaskData, cropSize, cropSize, radius = 5), cropSize, cropSize, radius = 3)

                OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(lamaData), longArrayOf(1, 3, cropSize.toLong(), cropSize.toLong())).use { imageInput ->
                    OnnxTensor.createTensor(env, java.nio.FloatBuffer.wrap(dilatedMask), longArrayOf(1, 1, cropSize.toLong(), cropSize.toLong())).use { maskInput ->
                        lamaSession.run(mapOf("image" to imageInput, "mask" to maskInput)).use { result ->
                            @Suppress("UNCHECKED_CAST")
                            val outData = result[0].value as Array<Array<Array<FloatArray>>>
                            val outR = outData[0][0]
                            val outG = outData[0][1]
                            val outB = outData[0][2]

                            for (y in 0 until cropSize) {
                                for (x in 0 until cropSize) {
                                    val index = y * cropSize + x
                                    val alpha = blendAlpha[index]
                                    if (alpha <= 0f) continue
                                    val modelR = (outR[y][x] * 255f).coerceIn(0f, 255f)
                                    val modelG = (outG[y][x] * 255f).coerceIn(0f, 255f)
                                    val modelB = (outB[y][x] * 255f).coerceIn(0f, 255f)
                                    val original = pixels[index]
                                    val originalR = (original shr 16) and 0xFF
                                    val originalG = (original shr 8) and 0xFF
                                    val originalB = original and 0xFF
                                    val r = (modelR * alpha + originalR * (1f - alpha)).roundToInt().coerceIn(0, 255)
                                    val g = (modelG * alpha + originalG * (1f - alpha)).roundToInt().coerceIn(0, 255)
                                    val b = (modelB * alpha + originalB * (1f - alpha)).roundToInt().coerceIn(0, 255)
                                    pixels[index] = Color.rgb(r, g, b)
                                }
                            }
                        }
                    }
                }
                
                paddedCrop.setPixels(pixels, 0, cropSize, 0, 0, cropSize, cropSize)
                canvas.drawBitmap(paddedCrop, 0f, 0f, null)
                
                val uncropRect = Rect(
                    max(0, -cropLeft), max(0, -cropTop),
                    min(cropSize, bitmap.width - cropLeft), min(cropSize, bitmap.height - cropTop)
                )
                
                val outCanvas = android.graphics.Canvas(bitmap)
                outCanvas.drawBitmap(paddedCrop, uncropRect, srcRect, null)
                paddedCrop.recycle()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Batched version of [computeTextMask]: clusters all requested regions the same way
     * [tryEraseAll] clusters its erase rects (so nearby blocks share a single 1024x1024 CTD
     * crop and inference call instead of each block paying for its own), and runs only the
     * segmentation model -- not the much heavier LaMa inpainting network, which the single-
     * block version above never actually needed either, since it only wants the mask, not a
     * cleaned image. Ten blocks used to mean ten full crops-and-inferences; a typical page
     * layout now costs a handful of clustered calls instead, which is most of why marking
     * detected text was taking on the order of minutes.
     * Returns a map from each input id to its highlight bitmap (same translucent-violet
     * ARGB_8888 format as [computeTextMask]); ids whose mask couldn't be computed are omitted.
     */
    suspend fun computeTextMasks(
        context: Context,
        bitmap: Bitmap,
        regions: List<Pair<String, Rect>>,
    ): Map<String, Bitmap> = withContext(Dispatchers.Default) {
        val valid = regions.filter { it.second.width() >= 2 && it.second.height() >= 2 }
        if (valid.isEmpty()) return@withContext emptyMap()
        if (!ModelPackManager.isInstalled(context, ModelPackId.LAMA_INPAINTING)) return@withContext emptyMap()
        val dir = ModelPackManager.directory(context, ModelPackId.LAMA_INPAINTING)
        val ctdFile = File(dir, "comic-text-detector.onnx")
        if (!ctdFile.isFile) return@withContext emptyMap()

        try {
            val ctdSession = OnnxSessionCache.getOrCreate("ctd", ctdFile)
            val env = OnnxSessionCache.environment
            val cropSize = 1024

            val clusters = mutableListOf<MutableList<Pair<String, Rect>>>()
            for (item in valid) {
                var added = false
                for (cluster in clusters) {
                    var minX = item.second.left; var minY = item.second.top
                    var maxX = item.second.right; var maxY = item.second.bottom
                    for ((_, r) in cluster) {
                        minX = min(minX, r.left); minY = min(minY, r.top)
                        maxX = max(maxX, r.right); maxY = max(maxY, r.bottom)
                    }
                    if (maxX - minX <= cropSize && maxY - minY <= cropSize) {
                        cluster.add(item)
                        added = true
                        break
                    }
                }
                if (!added) clusters.add(mutableListOf(item))
            }

            val out = mutableMapOf<String, Bitmap>()
            for (cluster in clusters) {
                var minX = cluster.first().second.left; var minY = cluster.first().second.top
                var maxX = cluster.first().second.right; var maxY = cluster.first().second.bottom
                for ((_, r) in cluster) {
                    minX = min(minX, r.left); minY = min(minY, r.top)
                    maxX = max(maxX, r.right); maxY = max(maxY, r.bottom)
                }
                val cx = (minX + maxX) / 2
                val cy = (minY + maxY) / 2
                val half = cropSize / 2
                val cropLeft = cx - half
                val cropTop = cy - half

                val paddedCrop = Bitmap.createBitmap(cropSize, cropSize, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(paddedCrop)
                canvas.drawColor(Color.WHITE)
                val srcRect = Rect(
                    max(0, cropLeft), max(0, cropTop),
                    min(bitmap.width, cropLeft + cropSize), min(bitmap.height, cropTop + cropSize),
                )
                val dstRect = Rect(
                    if (cropLeft < 0) -cropLeft else 0,
                    if (cropTop < 0) -cropTop else 0,
                    (if (cropLeft < 0) -cropLeft else 0) + srcRect.width(),
                    (if (cropTop < 0) -cropTop else 0) + srcRect.height(),
                )
                canvas.drawBitmap(bitmap, srcRect, dstRect, null)

                val pixels = IntArray(cropSize * cropSize)
                paddedCrop.getPixels(pixels, 0, cropSize, 0, 0, cropSize, cropSize)
                val ctdData = FloatArray(3 * cropSize * cropSize)
                val meanR = 0.485f * 255f; val meanG = 0.456f * 255f; val meanB = 0.406f * 255f
                val stdR = 0.229f * 255f; val stdG = 0.224f * 255f; val stdB = 0.225f * 255f
                for (i in pixels.indices) {
                    val p = pixels[i]
                    ctdData[i] = (((p shr 16) and 0xFF).toFloat() - meanR) / stdR
                    ctdData[cropSize * cropSize + i] = (((p shr 8) and 0xFF).toFloat() - meanG) / stdG
                    ctdData[2 * cropSize * cropSize + i] = ((p and 0xFF).toFloat() - meanB) / stdB
                }

                OnnxTensor.createTensor(
                    env, java.nio.FloatBuffer.wrap(ctdData), longArrayOf(1, 3, cropSize.toLong(), cropSize.toLong()),
                ).use { input ->
                    ctdSession.run(mapOf("images" to input)).use { result ->
                        @Suppress("UNCHECKED_CAST")
                        val segOutput = result[1].value as Array<Array<Array<FloatArray>>>
                        val segMask = segOutput[0][0]
                        // The raw per-pixel segmentation is noisy at this resolution -- lots of
                        // small holes scattered through what should be a solid glyph area, not
                        // a clean blob. Tracing "boundary" directly against that raw mask means
                        // almost every pixel sits next to a hole within a couple pixels, so
                        // nearly the whole area reads as "edge" -- which is exactly why the
                        // previous attempt still rendered as a solid scribble instead of a thin
                        // outline. A morphological close (dilate then erode) fills those small
                        // holes first, so the boundary trace afterward is actually thin.
                        val closeRadius = 3
                        val rawFlat = FloatArray(cropSize * cropSize) { i ->
                            if (segMask[i / cropSize][i % cropSize] > 0.5f) 1f else 0f
                        }
                        val closedFlat = erode(dilate(rawFlat, cropSize, cropSize, closeRadius), cropSize, cropSize, closeRadius)
                        for ((id, rect) in cluster) {
                            // A solid translucent fill over every glyph pixel reads as a thick
                            // highlighter scribble once several characters sit close together --
                            // trace only the boundary of the mask instead, which is what an
                            // actual "outline around the text" means: a thin band a couple
                            // pixels wide hugging the transition between glyph and background,
                            // fully transparent everywhere else (including glyph interiors).
                            val maskGrid = Array(rect.height()) { y ->
                                BooleanArray(rect.width()) { x ->
                                    val cropY = (rect.top + y) - cropTop
                                    val cropX = (rect.left + x) - cropLeft
                                    cropY in 0 until cropSize && cropX in 0 until cropSize &&
                                        closedFlat[cropY * cropSize + cropX] > 0f
                                }
                            }
                            val strokeRadius = 2
                            val outlineColor = Color.argb(235, 0xA5, 0x8A, 0xFF)
                            val maskBitmap = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ARGB_8888)
                            val row = IntArray(rect.width())
                            for (y in 0 until rect.height()) {
                                for (x in 0 until rect.width()) {
                                    var edge = false
                                    if (maskGrid[y][x]) {
                                        outer@ for (dy in -strokeRadius..strokeRadius) {
                                            for (dx in -strokeRadius..strokeRadius) {
                                                val ny = y + dy
                                                val nx = x + dx
                                                if (ny !in 0 until rect.height() || nx !in 0 until rect.width() ||
                                                    !maskGrid[ny][nx]
                                                ) {
                                                    edge = true
                                                    break@outer
                                                }
                                            }
                                        }
                                    }
                                    row[x] = if (edge) outlineColor else 0
                                }
                                maskBitmap.setPixels(row, 0, rect.width(), 0, y, rect.width(), 1)
                            }
                            out[id] = maskBitmap
                        }
                    }
                }
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun dilate(mask: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        if (radius <= 0) return mask
        val horizontal = FloatArray(mask.size)
        for (y in 0 until height) {
            val rowStart = y * width
            for (x in 0 until width) {
                var hit = false
                var k = -radius
                while (k <= radius && !hit) {
                    val xx = x + k
                    if (xx in 0 until width && mask[rowStart + xx] > 0f) hit = true
                    k++
                }
                horizontal[rowStart + x] = if (hit) 1f else 0f
            }
        }
        val result = FloatArray(mask.size)
        for (x in 0 until width) {
            for (y in 0 until height) {
                var hit = false
                var k = -radius
                while (k <= radius && !hit) {
                    val yy = y + k
                    if (yy in 0 until height && horizontal[yy * width + x] > 0f) hit = true
                    k++
                }
                result[y * width + x] = if (hit) 1f else 0f
            }
        }
        return result
    }

    /** Binary erosion: inverse of [dilate] -- a pixel survives only if its whole neighborhood is set. */
    private fun erode(mask: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        if (radius <= 0) return mask
        val horizontal = FloatArray(mask.size)
        for (y in 0 until height) {
            val rowStart = y * width
            for (x in 0 until width) {
                var all = true
                var k = -radius
                while (k <= radius && all) {
                    val xx = x + k
                    if (xx !in 0 until width || mask[rowStart + xx] <= 0f) all = false
                    k++
                }
                horizontal[rowStart + x] = if (all) 1f else 0f
            }
        }
        val result = FloatArray(mask.size)
        for (x in 0 until width) {
            for (y in 0 until height) {
                var all = true
                var k = -radius
                while (k <= radius && all) {
                    val yy = y + k
                    if (yy !in 0 until height || horizontal[yy * width + x] <= 0f) all = false
                    k++
                }
                result[y * width + x] = if (all) 1f else 0f
            }
        }
        return result
    }

    /** Separable box blur turning a binary mask into a soft 0..1 alpha for feathered blending. */
    private fun boxBlurAlpha(mask: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        if (radius <= 0) return mask
        val horizontal = FloatArray(mask.size)
        val norm = 2 * radius + 1
        for (y in 0 until height) {
            val rowStart = y * width
            for (x in 0 until width) {
                var sum = 0f
                for (k in -radius..radius) {
                    val xx = (x + k).coerceIn(0, width - 1)
                    sum += mask[rowStart + xx]
                }
                horizontal[rowStart + x] = sum / norm
            }
        }
        val result = FloatArray(mask.size)
        for (x in 0 until width) {
            for (y in 0 until height) {
                var sum = 0f
                for (k in -radius..radius) {
                    val yy = (y + k).coerceIn(0, height - 1)
                    sum += horizontal[yy * width + x]
                }
                result[y * width + x] = (sum / norm).coerceIn(0f, 1f)
            }
        }
        return result
    }
}
