package com.cellularchat.app.files

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.net.URLConnection

class ReceivedFileProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r")
        val file = resolve(uri)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String =
        URLConnection.guessContentTypeFromName(resolve(uri).name) ?: "application/octet-stream"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        val file = resolve(uri)
        val requested = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(requested)
        cursor.addRow(requested.map { column ->
            when (column) {
                OpenableColumns.DISPLAY_NAME -> file.name
                OpenableColumns.SIZE -> file.length()
                else -> null
            }
        })
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private fun resolve(uri: Uri): File {
        require(uri.authority == "${context?.packageName}.files")
        require(uri.pathSegments.size == 2 && uri.pathSegments[0] == "received")
        val root = receivedDirectory(requireNotNull(context)).canonicalFile
        val file = File(root, uri.pathSegments[1]).canonicalFile
        require(file.parentFile == root && file.isFile)
        return file
    }

    companion object {
        fun receivedDirectory(context: android.content.Context): File {
            val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: File(context.filesDir, "downloads")
            return File(base, "CellularChat").apply { mkdirs() }
        }

        fun uriFor(context: android.content.Context, file: File): Uri = Uri.Builder()
            .scheme("content")
            .authority("${context.packageName}.files")
            .appendPath("received")
            .appendPath(file.name)
            .build()
    }
}
