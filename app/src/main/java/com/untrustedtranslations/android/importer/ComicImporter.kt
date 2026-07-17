package com.untrustedtranslations.android.importer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.untrustedtranslations.android.model.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.ZipInputStream

object ComicImporter {
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp")

    suspend fun import(context: Context, uri: Uri): ComicProject = withContext(Dispatchers.IO) {
        val displayName = displayName(context, uri)
        val format = ImportContract.detectFormat(context.contentResolver, uri, displayName)
        val projectId = UUID.randomUUID().toString()
        val root = File(context.filesDir, "projects/$projectId").apply { mkdirs() }
        val pages = when (format) {
            ImportFormat.IMAGE -> listOf(importImage(context, uri, root, displayName))
            ImportFormat.PDF -> importPdf(context, uri, root)
            ImportFormat.CBZ, ImportFormat.ZIP -> importArchive(context, uri, root)
            ImportFormat.FOLDER -> error("Use folder import for a directory.")
        }
        require(pages.isNotEmpty()) { "No supported images were found in this file." }
        ComicProject(projectId, displayName.substringBeforeLast('.'), format, pages)
    }

    suspend fun importFolder(context: Context, treeUri: Uri): ComicProject = withContext(Dispatchers.IO) {
        val projectId = UUID.randomUUID().toString()
        val root = File(context.filesDir, "projects/$projectId").apply { mkdirs() }
        val selected = collectFolderImages(context, treeUri)
        require(selected.isNotEmpty()) { "No JPG, PNG, or WebP images were found in this folder." }
        val pages = selected.sortedBy { naturalKey(it.first) }.mapIndexed { index, (name, uri) ->
            val extension = name.substringAfterLast('.', "png").lowercase()
            val file = File(root, "folder-${(index + 1).toString().padStart(4, '0')}.$extension")
            context.contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Unable to open $name." }
                file.outputStream().use(input::copyTo)
            }
            ComicPage("page-${index + 1}", name.substringAfterLast('/'), Uri.fromFile(file))
        }
        val title = DocumentsContract.getTreeDocumentId(treeUri)
            .substringAfterLast(':')
            .substringAfterLast('/')
            .ifBlank { "Image folder" }
        ComicProject(projectId, title, ImportFormat.FOLDER, pages)
    }

    private fun collectFolderImages(context: Context, treeUri: Uri): List<Pair<String, Uri>> {
        data class Entry(val id: String, val name: String, val mimeType: String)

        val resolver = context.contentResolver
        val results = mutableListOf<Pair<String, Uri>>()
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)

        fun walk(directoryId: String, prefix: String) {
            if (results.size >= 1000) return
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, directoryId)
            val entries = mutableListOf<Entry>()
            resolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (cursor.moveToNext()) {
                    entries += Entry(
                        cursor.getString(idColumn),
                        cursor.getString(nameColumn),
                        cursor.getString(mimeColumn),
                    )
                }
            }
            entries.forEach { entry ->
                val relativeName = if (prefix.isBlank()) entry.name else "$prefix/${entry.name}"
                if (entry.mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                    walk(entry.id, relativeName)
                } else if (entry.name.substringAfterLast('.', "").lowercase() in imageExtensions) {
                    results += relativeName to DocumentsContract.buildDocumentUriUsingTree(treeUri, entry.id)
                }
            }
        }

        walk(rootId, "")
        return results
    }


    private fun importImage(context: Context, uri: Uri, root: File, name: String): ComicPage {
        val extension = name.substringAfterLast('.', "png").lowercase().takeIf { it in imageExtensions } ?: "png"
        val file = File(root, "page-0001.$extension")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to open image." }
            file.outputStream().use(input::copyTo)
        }
        return ComicPage("page-1", name, Uri.fromFile(file))
    }

    private fun importArchive(context: Context, uri: Uri, root: File): List<ComicPage> {
        val extracted = mutableListOf<Pair<String, File>>()
        var totalBytes = 0L
        context.contentResolver.openInputStream(uri).use { raw ->
            requireNotNull(raw) { "Unable to open archive." }
            ZipInputStream(raw.buffered()).use { zip ->
                var entry = zip.nextEntry
                while (entry != null && extracted.size < 1000) {
                    val originalName = entry.name.replace('\\', '/')
                    val extension = originalName.substringAfterLast('.', "").lowercase()
                    if (!entry.isDirectory && extension in imageExtensions) {
                        val output = File(root, "archive-${extracted.size.toString().padStart(4, '0')}.$extension")
                        FileOutputStream(output).use { stream ->
                            val buffer = ByteArray(64 * 1024)
                            var pageBytes = 0L
                            while (true) {
                                val count = zip.read(buffer)
                                if (count <= 0) break
                                pageBytes += count
                                totalBytes += count
                                require(pageBytes <= 80L * 1024 * 1024) { "An archive page is unexpectedly large." }
                                require(totalBytes <= 2L * 1024 * 1024 * 1024) { "Archive is too large to import safely." }
                                stream.write(buffer, 0, count)
                            }
                        }
                        extracted += originalName to output
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        return extracted.sortedBy { naturalKey(it.first) }.mapIndexed { index, (name, file) ->
            ComicPage("page-${index + 1}", name.substringAfterLast('/'), Uri.fromFile(file))
        }
    }

    private fun importPdf(context: Context, uri: Uri, root: File): List<ComicPage> {
        val pdf = File(root, "source.pdf")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to open PDF." }
            pdf.outputStream().use(input::copyTo)
        }
        val descriptor = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY)
        return PdfRenderer(descriptor).use { renderer ->
            (0 until renderer.pageCount).map { index ->
                renderer.openPage(index).use { page ->
                    val scale = (2000f / page.width).coerceAtLeast(1f).coerceAtMost(3f)
                    val bitmap = Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                    Canvas(bitmap).drawColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    val output = File(root, "page-${(index + 1).toString().padStart(4, '0')}.png")
                    output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                    ComicPage("page-${index + 1}", "Page ${index + 1}", Uri.fromFile(output))
                }
            }
        }
    }

    private fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return uri.lastPathSegment ?: "Imported comic"
    }

    private fun naturalKey(value: String): String = Regex("\\d+").replace(value.lowercase()) {
        it.value.padStart(12, '0')
    }
}
