package com.untrustedtranslations.android.processing

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.untrustedtranslations.android.model.ComicProject
import com.untrustedtranslations.android.model.ImportFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ProjectExporter {
    suspend fun export(context: Context, project: ComicProject): Uri = withContext(Dispatchers.IO) {
        val safeTitle = project.title.replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .ifBlank { "translated-comic" }
        when (project.format) {
            ImportFormat.IMAGE -> exportImage(context, project, "$safeTitle-translated.png")
            ImportFormat.PDF -> exportPdf(context, project, "$safeTitle-translated.pdf")
            ImportFormat.ZIP -> exportArchive(context, project, "$safeTitle-translated.zip", "application/zip")
            ImportFormat.CBZ -> exportArchive(
                context,
                project,
                "$safeTitle-translated.cbz",
                "application/vnd.comicbook+zip",
            )
        }
    }

    private fun exportImage(context: Context, project: ComicProject, name: String): Uri {
        val (uri, output) = destination(context, name, "image/png")
        output.use { stream ->
            File(requireNotNull(project.pages.single().renderedSource.path)).inputStream().use {
                it.copyTo(stream)
            }
        }
        return uri
    }

    private fun exportArchive(
        context: Context,
        project: ComicProject,
        name: String,
        mimeType: String,
    ): Uri {
        val (uri, output) = destination(context, name, mimeType)
        output.use { raw ->
            ZipOutputStream(raw.buffered()).use { zip ->
                project.pages.forEachIndexed { index, page ->
                    val file = File(requireNotNull(page.renderedSource.path))
                    zip.putNextEntry(ZipEntry("page-${(index + 1).toString().padStart(4, '0')}.png"))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        return uri
    }

    private fun exportPdf(context: Context, project: ComicProject, name: String): Uri {
        val (uri, output) = destination(context, name, "application/pdf")
        val document = PdfDocument()
        try {
            project.pages.forEachIndexed { index, page ->
                val bitmap = requireNotNull(BitmapFactory.decodeFile(requireNotNull(page.renderedSource.path))) {
                    "Unable to read ${page.displayName} for PDF export."
                }
                val info = PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, index + 1).create()
                val pdfPage = document.startPage(info)
                pdfPage.canvas.drawBitmap(bitmap, 0f, 0f, null)
                document.finishPage(pdfPage)
                bitmap.recycle()
            }
            output.use(document::writeTo)
        } finally {
            document.close()
        }
        return uri
    }

    private fun destination(context: Context, name: String, mimeType: String): Pair<Uri, OutputStream> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, mimeType)
                put(
                    MediaStore.Downloads.RELATIVE_PATH,
                    "${Environment.DIRECTORY_DOWNLOADS}/Untrusted Translations",
                )
            }
            val uri = requireNotNull(
                context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values),
            ) { "Unable to create the exported file." }
            return uri to requireNotNull(context.contentResolver.openOutputStream(uri))
        }
        val directory = File(
            context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
            "Untrusted Translations",
        ).apply { mkdirs() }
        val file = File(directory, name)
        return Uri.fromFile(file) to file.outputStream()
    }
}
