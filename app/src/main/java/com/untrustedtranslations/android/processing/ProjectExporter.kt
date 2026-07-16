package com.untrustedtranslations.android.processing

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.untrustedtranslations.android.model.ComicProject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ProjectExporter {
    suspend fun export(context: Context, project: ComicProject): Uri = withContext(Dispatchers.IO) {
        val safeTitle = project.title.replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { "translated-comic" }
        val name = "$safeTitle-translated.cbz"
        val uri: Uri
        val output = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "application/vnd.comicbook+zip")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Untrusted Translations")
            }
            uri = requireNotNull(context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
            requireNotNull(context.contentResolver.openOutputStream(uri))
        } else {
            val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Untrusted Translations").apply { mkdirs() }
            val file = File(directory, name)
            uri = Uri.fromFile(file)
            file.outputStream()
        }
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
        uri
    }
}
