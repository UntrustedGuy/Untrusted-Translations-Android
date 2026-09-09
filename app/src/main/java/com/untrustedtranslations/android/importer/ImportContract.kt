package com.untrustedtranslations.android.importer

import android.content.ContentResolver
import android.net.Uri
import com.untrustedtranslations.android.model.ImportFormat

object ImportContract {
    val mimeTypes = arrayOf(
        "image/*", "application/pdf", "application/zip", "application/x-cbz",
        "application/vnd.comicbook+zip", "application/octet-stream",
    )

    fun detectFormat(resolver: ContentResolver, uri: Uri, displayName: String = uri.lastPathSegment.orEmpty()): ImportFormat {
        val type = resolver.getType(uri).orEmpty().lowercase()
        val name = displayName.lowercase()
        return when {
            type == "application/pdf" || name.endsWith(".pdf") -> ImportFormat.PDF
            type.contains("cbz") || name.endsWith(".cbz") -> ImportFormat.CBZ
            type.contains("zip") || name.endsWith(".zip") -> ImportFormat.ZIP
            type.startsWith("image/") || name.substringAfterLast('.', "") in setOf("jpg", "jpeg", "png", "webp") -> ImportFormat.IMAGE
            else -> error("Unsupported file type: $displayName")
        }
    }
}
