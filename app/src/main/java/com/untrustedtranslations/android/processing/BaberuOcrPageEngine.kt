package com.untrustedtranslations.android.processing

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import com.untrustedtranslations.android.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.UUID
import kotlin.math.*

@Deprecated("Recognition not wired — kept for future use")
internal object BaberuOcrPageEngine {
    private data class Det(val rect: Rect, val score: Float)

    suspend fun process(ctx: Context, page: ComicPage, script: SourceScript, pack: ModelPackId): List<TextBlock> = withContext(Dispatchers.IO) {
        val dir = ModelPackManager.directory(ctx, pack)
        require(ModelPackManager.isInstalled(ctx, pack)) { "Manga-OCR pack not installed" }
        val bmp = ctx.contentResolver.openInputStream(page.originalSource)?.use(BitmapFactory::decodeStream) ?: error("Cannot open page")
        val dets = findText(ctx, bmp)
        dets.mapNotNull { d ->
            val b = RelativeBounds(d.rect.left.toFloat() / bmp.width, d.rect.top.toFloat() / bmp.height, d.rect.right.toFloat() / bmp.width, d.rect.bottom.toFloat() / bmp.height)
            TextBlock(id = UUID.randomUUID().toString(), originalText = "", translatedText = "", bounds = b, eraseBounds = b, style = LetteringStyleEstimator.estimate(ctx, bmp, d.rect, "", script, null))
        }.sortedWith { a, b -> val r = a.bounds.top.compareTo(b.bounds.top); if (r != 0) r else a.bounds.left.compareTo(b.bounds.left) }
    }

    private fun findText(ctx: Context, bmp: Bitmap): List<Det> {
        val ids = listOf(ModelPackId.RAPID_OCR_JAPANESE, ModelPackId.RAPID_OCR_KOREAN, ModelPackId.RAPID_OCR_CHINESE, ModelPackId.RAPID_OCR_LATIN, ModelPackId.RAPID_OCR_V5_JAPANESE, ModelPackId.RAPID_OCR_V5_KOREAN, ModelPackId.RAPID_OCR_V5_CHINESE, ModelPackId.RAPID_OCR_V5_LATIN)
        for (rid in ids) {
            val f = File(ModelPackManager.directory(ctx, rid), "det.onnx")
            if (f.isFile) return dbnetDetect(f, bmp)
        }
        return edgeDetect(bmp)
    }

    private fun dbnetDetect(f: File, bmp: Bitmap): List<Det> = try {
        val env = OrtEnvironment.getEnvironment(); val sess = env.createSession(f.absolutePath)
        val sc = min(1f, 1280f / max(bmp.width, bmp.height)); val w = ceil(bmp.width * sc / 32f).toInt().coerceAtLeast(32) * 32; val h = ceil(bmp.height * sc / 32f).toInt().coerceAtLeast(32) * 32
        val r = Bitmap.createScaledBitmap(bmp, w, h, true); val px = IntArray(w * h); r.getPixels(px, 0, w, 0, 0, w, h); val d = FloatArray(w * h * 3)
        for (i in px.indices) { d[i] = (px[i] and 255) / 255f; d[w * h + i] = (px[i] shr 8 and 255) / 255f; d[2 * w * h + i] = (px[i] shr 16 and 255) / 255f }
        OnnxTensor.createTensor(env, FloatBuffer.wrap(d), longArrayOf(1, 3, h.toLong(), w.toLong())).use { inp -> sess.run(mapOf(sess.inputNames.first() to inp)).use { res ->
            val o = res[0].value as Array<Array<Array<FloatArray>>>; return components(o[0][0], bmp.width, bmp.height)
        }}
    } catch (_: Exception) { edgeDetect(bmp) }

    private fun components(m: Array<FloatArray>, sw: Int, sh: Int): List<Det> {
        val h = m.size; val w = m.firstOrNull()?.size ?: return emptyList(); val act = BooleanArray(w * h)
        for (y in 1 until h - 1) for (x in 1 until w - 1) if (m[y][x] >= 0.30f && m[y][x] >= m[y - 1][x] && m[y][x] >= m[y + 1][x] && m[y][x] >= m[y][x - 1] && m[y][x] >= m[y][x + 1]) act[y * w + x] = true
        val vis = BooleanArray(act.size); val q = IntArray(act.size); val dets = mutableListOf<Det>()
        for (st in act.indices) { if (!act[st] || vis[st]) continue; var hd = 0; var tl = 0; q[tl++] = st; vis[st] = true
            var mnX = w; var mnY = h; var mxX = 0; var mxY = 0; var px = 0; var ss = 0f
            while (hd < tl) { val idx = q[hd++]; val x = idx % w; val y = idx / w; mnX = min(mnX, x); mxX = max(mxX, x); mnY = min(mnY, y); mxY = max(mxY, y); ss += m[y][x]; px++
                for (n in intArrayOf(idx - 1, idx + 1, idx - w, idx + w)) { if (n in act.indices && !vis[n] && act[n]) { vis[n] = true; q[tl++] = n } } }
            val cw = mxX - mnX + 1; val ch = mxY - mnY + 1; if (px < 10 || cw < 4 || ch < 4) continue
            val ex = (cw * 0.18f).roundToInt().coerceAtLeast(2); val ey = (ch * 0.18f).roundToInt().coerceAtLeast(2)
            val l = ((mnX - ex).coerceAtLeast(0) * sw / w.toFloat()).roundToInt(); val t = ((mnY - ey).coerceAtLeast(0) * sh / h.toFloat()).roundToInt()
            val r = (((mxX + ex + 1).coerceAtMost(w)) * sw / w.toFloat()).roundToInt(); val b = (((mxY + ey + 1).coerceAtMost(h)) * sh / h.toFloat()).roundToInt()
            if (r - l >= 8 && b - t >= 8) dets += Det(Rect(l, t, r, b), ss / px) }
        return mergeDets(dets)
    }

    private fun edgeDetect(bmp: Bitmap): List<Det> {
        val w = bmp.width; val h = bmp.height; val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h)
        val g = IntArray(px.size) { val c = px[it]; ((c and 255) * 0.299 + (c shr 8 and 255) * 0.587 + (c shr 16 and 255) * 0.114).toInt() }; val e = BooleanArray(g.size)
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val gx = g[(y - 1) * w + (x + 1)] + 2 * g[y * w + (x + 1)] + g[(y + 1) * w + (x + 1)] - g[(y - 1) * w + (x - 1)] - 2 * g[y * w + (x - 1)] - g[(y + 1) * w + (x - 1)]
            val gy = g[(y + 1) * w + (x - 1)] + 2 * g[(y + 1) * w + x] + g[(y + 1) * w + (x + 1)] - g[(y - 1) * w + (x - 1)] - 2 * g[(y - 1) * w + x] - g[(y - 1) * w + (x + 1)]
            e[y * w + x] = sqrt((gx * gx + gy * gy).toFloat()) > 40f }
        val vis = BooleanArray(e.size); val q = IntArray(e.size); val dets = mutableListOf<Det>()
        for (st in e.indices) { if (!e[st] || vis[st]) continue; var hd = 0; var tl = 0; q[tl++] = st; vis[st] = true
            var mnX = w; var mnY = h; var mxX = 0; var mxY = 0; var px2 = 0
            while (hd < tl) { val idx = q[hd++]; val x = idx % w; val y = idx / w; mnX = min(mnX, x); mxX = max(mxX, x); mnY = min(mnY, y); mxY = max(mxY, y); px2++
                for (n in intArrayOf(idx - 1, idx + 1, idx - w, idx + w)) { if (n in e.indices && !vis[n] && e[n]) { vis[n] = true; q[tl++] = n } } }
            val cw = mxX - mnX + 1; val ch = mxY - mnY + 1; if (px2 < 20 || cw < 8 || ch < 8 || cw > w * 0.9f || ch > h * 0.9f) continue
            val ex = (cw * 0.15f).roundToInt().coerceAtLeast(4); val ey = (ch * 0.15f).roundToInt().coerceAtLeast(4)
            dets += Det(Rect((mnX - ex).coerceAtLeast(0), (mnY - ey).coerceAtLeast(0), (mxX + ex + 1).coerceAtMost(w), (mxY + ey + 1).coerceAtMost(h)), px2.toFloat()) }
        return mergeDets(dets)
    }

    private fun mergeDets(inp: List<Det>): List<Det> {
        val r = mutableListOf<Det>(); for (c in inp.sortedByDescending { it.score }) {
            val ov = r.indexOfFirst { o -> val i = Rect(); i.setIntersect(c.rect, o.rect) && i.width() * i.height() > min(c.rect.width() * c.rect.height(), o.rect.width() * o.rect.height()) * 0.65f }
            if (ov < 0) r += c }
        return r
    }

    private fun bitmapTensor(bmp: Bitmap, mean: FloatArray, std: FloatArray): FloatArray {
        val px = IntArray(bmp.width * bmp.height); bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height); val pl = px.size
        val out = FloatArray(pl * 3); px.forEachIndexed { idx, c -> val ch = intArrayOf(c and 255, c shr 8 and 255, c shr 16 and 255); for (i in 0..2) out[i * pl + idx] = (ch[i] / 255f - mean[i]) / std[i] }; return out
    }
}
