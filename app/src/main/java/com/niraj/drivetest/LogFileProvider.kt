package com.niraj.drivetest

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException

/** Minimal read-only provider so CSV logs can be shared to Excel, Gmail, Drive, WhatsApp… */
class LogFileProvider : ContentProvider() {

    companion object {
        fun dir(ctx: Context): File =
            File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "logs").apply { mkdirs() }

        fun uri(ctx: Context, f: File): Uri =
            Uri.parse("content://${ctx.packageName}.logs/" + Uri.encode(f.name))

        fun list(ctx: Context): List<File> =
            dir(ctx).listFiles { f -> f.isFile && f.name.endsWith(".csv") }
                ?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    override fun onCreate(): Boolean = true

    private fun fileFor(uri: Uri): File? {
        val ctx = context ?: return null
        val name = uri.lastPathSegment ?: return null
        if (name.contains('/') || name.contains("..")) return null
        val f = File(dir(ctx), name)
        return if (f.isFile) f else null
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val f = fileFor(uri) ?: throw FileNotFoundException(uri.toString())
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(
        uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?
    ): Cursor? {
        val f = fileFor(uri) ?: return null
        val cols: Array<String> = projection?.map { it }?.toTypedArray()
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val c = MatrixCursor(cols)
        c.addRow(cols.map {
            when (it) {
                OpenableColumns.DISPLAY_NAME -> f.name
                OpenableColumns.SIZE -> f.length()
                else -> null
            }
        }.toTypedArray())
        return c
    }

    override fun getType(uri: Uri): String = "text/csv"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
