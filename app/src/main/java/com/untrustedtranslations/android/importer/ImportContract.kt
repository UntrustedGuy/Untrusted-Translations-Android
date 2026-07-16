package com.untrustedtranslations.android.importer

import android.content.ContentResolver
import android.net.Uri
import com.untrustedtranslations.android.model.ImportFormat

object ImportContract {
    val mimeTypes = arrayOf("image/*", "application/pdf", "application/zip", "application/x-cbz", "application/vnd.comicbook+zip")

    fun detectFormat(resolver: ContentResolver, uri: Uri): ImportFormat {
        val type = resolver.getType(uri).orEmpty().lowercase()
        val name = uri.lastPathSegment.orEmpty().lowercase()
        return when {
            type == "application/pdf" || name.endsWith(".pdf") -> ImportFormat.PDF
            type.contains("cbz") || name.endsWith(".cbz") -> ImportFormat.CBZ
            type.contains("zip") || name.endsWith(".zip") -> ImportFormat.ZIP
            else -> ImportFormat.IMAGE
        }
    }
}
