package com.untrustedtranslations.android.processing

import ai.onnxruntime.OnnxTensor
import android.graphics.Bitmap
import android.graphics.Rect
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * Detects speech-bubble dialogue and free text (captions/thought boxes), excluding empty
 * bubble outlines. Sound effects live inside the same "free text" class as captions/thought
 * boxes in the underlying model, so they are filtered separately and heuristically via
 * [looksLikeSoundEffect] once OCR text is available, rather than at detection time.
 */
internal object ComicDialogueDetector {
    /**
     * Global on/off switch for [looksLikeSoundEffect], mirrored from the user's persisted
     * AiSettings.sfxFilterEnabled and set once per processing run by the ViewModel before OCR
     * starts. Defaults to false -- see the doc comment on AiSettings.sfxFilterEnabled for why:
     * this matches the reference tool's own default of leaving SFX filtering off until a user
     * opts in, rather than risking real dialogue/narration being silently dropped by default.
     */
    var sfxFilteringEnabled: Boolean = false

    private const val INPUT_SIZE = 640
    // ogkalu/comic-text-and-bubble-detector classes: 0 = bubble (empty outline, not text),
    // 1 = text_bubble (dialogue inside a bubble), 2 = text_free (free text: captions,
    // narration, thought boxes, AND sound effects -- the model doesn't separate those).
    private const val BUBBLE_LABEL = 0L
    private const val TEXT_BUBBLE_LABEL = 1L
    private const val FREE_TEXT_LABEL = 2L

    /**
     * isText marks label 1/2 (actual glyph regions) as opposed to the excluded bubble-outline
     * label. isFreeText marks label 2 specifically, since free text is where SFX and thought
     * captions both land -- callers that want to drop SFX but keep thought/caption text need
     * to apply [looksLikeSoundEffect] to isFreeText regions, because the detector itself
     * cannot tell the two apart.
     */
    data class Region(
        val rect: Rect,
        val confidence: Float,
        val isText: Boolean = false,
        val isFreeText: Boolean = false,
    )

    private data class CachedResult(
        val cacheKey: String,
        val pageKey: String,
        val width: Int,
        val height: Int,
        val minScore: Float,
        val regions: List<Region>,
    )
    @Volatile private var lastResult: CachedResult? = null

    fun detect(
        cacheKey: String,
        model: File,
        bitmap: Bitmap,
        minimumScore: Float = .35f,
        pageKey: String? = null,
    ): List<Region> {
        val resolvedPageKey = pageKey ?:
            "bitmap:${System.identityHashCode(bitmap)}:${bitmap.generationId}"
        lastResult?.let { cached ->
            if (cached.cacheKey == cacheKey && cached.pageKey == resolvedPageKey &&
                cached.width == bitmap.width && cached.height == bitmap.height &&
                cached.minScore == minimumScore
            ) {
                return cached.regions
            }
        }
        val environment = OnnxSessionCache.environment
        val session = OnnxSessionCache.getOrCreate(cacheKey, model)
        require("images" in session.inputNames && "orig_target_sizes" in session.inputNames) {
            "Unsupported comic dialogue detector inputs."
        }
        val regions = tilesFor(bitmap.width, bitmap.height)
            .flatMap { tile -> detectTile(environment, session, bitmap, tile, minimumScore) }
            .suppressOverlaps()
        lastResult = CachedResult(
            cacheKey, resolvedPageKey, bitmap.width, bitmap.height, minimumScore, regions,
        )
        return regions
    }

    /**
     * The detector's own training data resizes each page to a square 640x640 tile ("tall
     * webtoons were split vertically" per the model's own documentation) rather than squashing
     * a whole page into a square. Doing that squash unconditionally -- as this used to -- badly
     * distorts extremely tall webtoon-style pages and silently drops a chunk of their text.
     * For a normal, roughly page-shaped image this returns a single tile (unchanged behavior,
     * same one detector call, same speed). Only pages tall enough to be webtoon strips get
     * split into overlapping, roughly-square tiles so each one is detected undistorted.
     */
    private fun tilesFor(width: Int, height: Int): List<Rect> {
        if (height <= width * 1.6) return listOf(Rect(0, 0, width, height))
        val tileHeight = (width * 1.3f).toInt().coerceIn(width, height)
        val overlap = (tileHeight * 0.2f).toInt().coerceAtLeast(1)
        val stride = (tileHeight - overlap).coerceAtLeast(1)
        val tiles = mutableListOf<Rect>()
        var top = 0
        while (true) {
            val bottom = (top + tileHeight).coerceAtMost(height)
            tiles += Rect(0, top, width, bottom)
            if (bottom >= height) break
            top += stride
        }
        return tiles
    }

    private fun detectTile(
        environment: ai.onnxruntime.OrtEnvironment,
        session: ai.onnxruntime.OrtSession,
        bitmap: Bitmap,
        tile: Rect,
        minimumScore: Float,
    ): List<Region> {
        val tileBitmap = if (tile.left == 0 && tile.top == 0 && tile.width() == bitmap.width && tile.height() == bitmap.height) {
            bitmap
        } else {
            Bitmap.createBitmap(bitmap, tile.left, tile.top, tile.width(), tile.height())
        }
        val resized = Bitmap.createScaledBitmap(tileBitmap, INPUT_SIZE, INPUT_SIZE, true)
        try {
            val imageValues = rgbTensor(resized)
            // This exported RT-DETR model expects width first and height second.
            val originalSize = longArrayOf(tile.width().toLong(), tile.height().toLong())
            OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(imageValues),
                longArrayOf(1, 3, INPUT_SIZE.toLong(), INPUT_SIZE.toLong()),
            ).use { imageTensor ->
                OnnxTensor.createTensor(
                    environment,
                    LongBuffer.wrap(originalSize),
                    longArrayOf(1, 2),
                ).use { sizeTensor ->
                    session.run(
                        mapOf("images" to imageTensor, "orig_target_sizes" to sizeTensor),
                    ).use { result ->
                        @Suppress("UNCHECKED_CAST")
                        val labels = (result.get("labels").orElse(result[0]) as OnnxTensor).value
                            as Array<LongArray>
                        @Suppress("UNCHECKED_CAST")
                        val boxes = (result.get("boxes").orElse(result[1]) as OnnxTensor).value
                            as Array<Array<FloatArray>>
                        @Suppress("UNCHECKED_CAST")
                        val scores = (result.get("scores").orElse(result[2]) as OnnxTensor).value
                            as Array<FloatArray>
                        return labels[0].indices.mapNotNull { index ->
                            val label = labels[0][index]
                            // Label 0 (bubble outline) carries no glyphs -- OCR-ing it just
                            // produces noise, so it's excluded outright rather than treated
                            // as free text.
                            if ((label != TEXT_BUBBLE_LABEL && label != FREE_TEXT_LABEL) || scores[0][index] < minimumScore) {
                                return@mapNotNull null
                            }
                            val box = boxes[0][index]
                            val left = (box[0].toInt() + tile.left).coerceIn(0, bitmap.width - 1)
                            val top = (box[1].toInt() + tile.top).coerceIn(0, bitmap.height - 1)
                            val right = (box[2].toInt() + tile.left).coerceIn(left + 1, bitmap.width)
                            val bottom = (box[3].toInt() + tile.top).coerceIn(top + 1, bitmap.height)
                            if (right - left < 3 || bottom - top < 3) null
                            else Region(
                                Rect(left, top, right, bottom),
                                scores[0][index],
                                isText = true,
                                isFreeText = label == FREE_TEXT_LABEL,
                            )
                        }
                    }
                }
            }
        } finally {
            if (resized !== tileBitmap) resized.recycle()
            if (tileBitmap !== bitmap) tileBitmap.recycle()
        }
    }

    private fun rgbTensor(bitmap: Bitmap): FloatArray {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val plane = pixels.size
        return FloatArray(plane * 3).also { output ->
            pixels.forEachIndexed { index, color ->
                output[index] = (color ushr 16 and 255) / 255f
                output[plane + index] = (color ushr 8 and 255) / 255f
                output[plane * 2 + index] = (color and 255) / 255f
            }
        }
    }

    private fun List<Region>.suppressOverlaps(): List<Region> {
        val kept = mutableListOf<Region>()
        // Prioritize text boxes (which are tighter) over bubbles
        sortedWith(compareByDescending<Region> { it.isText }.thenByDescending { it.confidence }).forEach { candidate ->
            if (kept.none { 
                val iou = intersectionOverUnion(candidate.rect, it.rect)
                val intersectionArea = intersectionArea(candidate.rect, it.rect)
                val areaA = candidate.rect.width() * candidate.rect.height()
                val areaB = it.rect.width() * it.rect.height()
                // Suppress if IOU is high OR if one is heavily contained inside the other (e.g. text inside bubble)
                iou > .30f || (intersectionArea > 0.8f * minOf(areaA, areaB))
            }) {
                kept += candidate
            }
        }
        return kept
    }

    private fun intersectionArea(a: Rect, b: Rect): Int {
        val w = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0)
        val h = (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0)
        return w * h
    }

    private fun intersectionOverUnion(a: Rect, b: Rect): Float {
        val intersectionWidth = (minOf(a.right, b.right) - maxOf(a.left, b.left)).coerceAtLeast(0)
        val intersectionHeight = (minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)).coerceAtLeast(0)
        val intersection = intersectionWidth.toLong() * intersectionHeight
        val union = a.width().toLong() * a.height() + b.width().toLong() * b.height() - intersection
        return if (union <= 0L) 0f else intersection.toFloat() / union
    }

    /**
     * Best-effort split within the "free text" class (which covers sound effects, captions,
     * and thought/narration boxes alike -- the detector model itself has no SFX label).
     *
     * Errs toward keeping text. The "no lowercase letters" signal only means anything for
     * Latin script -- CJK text has no case at all, so treating "no lowercase" as an SFX signal
     * there would flag *every* short burst of Japanese/Chinese dialogue (a very common dramatic
     * device: big emotional narration splashed across a panel) as a sound effect. So script is
     * checked first, and the size-alone fast path only fires for Latin ALL-CAPS -- ordinary
     * sentence-case text is never flagged this way, and ALL-CAPS is a genuinely rare, reliable
     * "shouted/SFX" signal in Latin script. No CJK script gets an equivalent fast path (an
     * earlier version tried katakana-majority as the Japanese equivalent, since onomatopoeia is
     * conventionally katakana -- but katakana is just as routinely used for ordinary loanwords,
     * emphasis, and stylized titles, so it wasn't a reliable enough signal on its own and kept
     * misflagging real title/dialogue text). All CJK text instead has to clear the stricter
     * isolation+visual-noise bar below, which requires real evidence rather than shortness alone.
     */
    fun looksLikeSoundEffect(
        text: String,
        rect: Rect,
        pageWidth: Int,
        pageHeight: Int,
        bitmap: Bitmap? = null,
        allRegions: List<Region> = emptyList(),
    ): Boolean {
        if (!sfxFilteringEnabled) return false
        if (pageHeight <= 0) return false
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return false
        val letters = trimmed.filter { it.isLetter() }
        if (letters.isEmpty()) return false
        val wordCount = trimmed.split(Regex("\\s+")).count { it.isNotBlank() }
        val short = letters.length in 1..6 && wordCount <= 1
        if (!short) return false

        val hasLatin = letters.any { it in 'A'..'Z' || it in 'a'..'z' }
        val hasLowercaseLatin = letters.any { it in 'a'..'z' }
        // true = script-appropriate "stylized/shouted" signal present, false = definitely
        // ordinary text for this script, null = script gives no reliable signal either way.
        // Katakana majority used to grant Japanese text the same fast path as Latin ALL-CAPS,
        // but that's a much weaker signal than it looks: katakana is routinely used for
        // loanwords, emphasis, and stylized titles in completely ordinary text (a chapter-title
        // banner using a katakana word like "ラブコメ" is not SFX), unlike ALL-CAPS which is
        // genuinely rare outside of shouting/SFX in Latin script. So katakana no longer grants
        // the fast path on its own -- it now has to clear the same isolation+visual-noise bar
        // as any other ambiguous-script text below.
        val stylizedScript: Boolean? = if (hasLatin) !hasLowercaseLatin else null
        if (stylizedScript == false) return false

        val relativeHeight = rect.height().toFloat() / pageHeight.toFloat()
        val relativeWidth = if (pageWidth > 0) rect.width().toFloat() / pageWidth.toFloat() else 0f
        // Either a tall, loosely-drawn SFX glyph, or one stretched wide across a chunk of the
        // page (banner-style "BOOOOM" lettering) counts as oversized.
        val oversized = relativeHeight > 0.09f || relativeWidth > 0.5f
        if (stylizedScript == true && oversized) return true

        // Remaining case: either a script with no case-like signal (Korean/Chinese/Japanese --
        // kana or kanji, katakana included), or a script-flagged-stylized reading that isn't
        // oversized. Require real evidence rather than trusting shortness alone: isolated from
        // any actual dialogue bubble, AND visually styled rather than flat lettering.
        val dialogueMargin = maxOf(rect.width(), rect.height()) / 2
        val isolatedFromDialogue = allRegions.none { other ->
            other.rect != rect && !other.isFreeText &&
                Rect.intersects(
                    Rect(
                        rect.left - dialogueMargin, rect.top - dialogueMargin,
                        rect.right + dialogueMargin, rect.bottom + dialogueMargin,
                    ),
                    other.rect,
                )
        }
        if (!isolatedFromDialogue) return false
        return bitmap?.let { isVisuallyNoisy(it, rect) } ?: false
    }

    /**
     * Coarse proxy for "styled onomatopoeia lettering" vs. flat lettered text: samples the crop
     * and measures per-channel color spread. Ordinary text (even colored) is drawn in one or two
     * flat fill colors over a comparatively even background; stylized SFX lettering commonly has
     * gradients, outlines, or textured fills, which shows up as noticeably higher color variance
     * across the same small region.
     */
    private fun isVisuallyNoisy(bitmap: Bitmap, rect: Rect): Boolean {
        val left = rect.left.coerceIn(0, bitmap.width - 1)
        val top = rect.top.coerceIn(0, bitmap.height - 1)
        val right = rect.right.coerceIn(left + 1, bitmap.width)
        val bottom = rect.bottom.coerceIn(top + 1, bitmap.height)
        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0) return false
        val stepX = maxOf(1, width / 48)
        val stepY = maxOf(1, height / 48)
        var count = 0
        var sumR = 0L; var sumG = 0L; var sumB = 0L
        var sumR2 = 0L; var sumG2 = 0L; var sumB2 = 0L
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val color = bitmap.getPixel(x, y)
                val r = (color ushr 16) and 0xFF
                val g = (color ushr 8) and 0xFF
                val b = color and 0xFF
                sumR += r; sumG += g; sumB += b
                sumR2 += r * r; sumG2 += g * g; sumB2 += b * b
                count++
                x += stepX
            }
            y += stepY
        }
        if (count == 0) return false
        val varR = sumR2.toFloat() / count - (sumR.toFloat() / count).let { it * it }
        val varG = sumG2.toFloat() / count - (sumG.toFloat() / count).let { it * it }
        val varB = sumB2.toFloat() / count - (sumB.toFloat() / count).let { it * it }
        val totalVariance = varR + varG + varB
        return totalVariance > 4500f
    }
}
