package dev.whispr.android.files

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.io.OutputStream

/**
 * Files the user keeps outside the app: saved attachments and chat backups.
 * They go to Downloads/Whispr, or to a folder the user picked in Settings
 * ([folder], a document tree URI). Both outlive uninstalling the app.
 */
object PublicFiles {
    const val FOLDER = "Whispr"

    sealed interface Result {
        data class Saved(val where: String) : Result

        /** Android 9 and older can only save to a folder picked in Settings. */
        data object NeedsFolder : Result

        data object Failed : Result
    }

    /** Copies [source] out as [name]. */
    fun save(context: Context, source: File, name: String, mime: String, folder: String?): Result =
        write(context, name, mime, folder) { out -> source.inputStream().use { it.copyTo(out) } }

    fun write(context: Context, name: String, mime: String, folder: String?, body: (OutputStream) -> Unit): Result =
        try {
            val target = when {
                folder != null -> inTree(context, Uri.parse(folder), name, mime)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> inDownloads(context, name, mime)
                else -> return Result.NeedsFolder
            } ?: return Result.Failed
            val out = context.contentResolver.openOutputStream(target, "w") ?: return Result.Failed
            out.use(body)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && folder == null) {
                context.contentResolver.update(
                    target,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                )
            }
            Result.Saved(if (folder == null) "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER" else folderLabel(folder))
        } catch (_: IOException) {
            Result.Failed
        } catch (_: SecurityException) {
            Result.Failed // the picked folder's permission was revoked
        } catch (_: IllegalArgumentException) {
            Result.Failed
        }

    /** Deletes all but the [keep] newest of our files in Downloads/Whispr named [prefix]... */
    fun prune(context: Context, prefix: String, keep: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val ids = mutableListOf<Long>()
        try {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH} = ? AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                arrayOf("${Environment.DIRECTORY_DOWNLOADS}/$FOLDER/", "$prefix%"),
                "${MediaStore.MediaColumns.DATE_ADDED} DESC",
            )?.use { c -> while (c.moveToNext()) ids += c.getLong(0) }
            ids.drop(keep).forEach { context.contentResolver.delete(ContentUris.withAppendedId(uri, it), null, null) }
        } catch (_: SecurityException) {
            // Not ours (e.g. left by an earlier install): leave it.
        }
    }

    /** A readable name for a picked folder ("Documents/Whispr"). */
    fun folderLabel(folder: String): String =
        runCatching { DocumentsContract.getTreeDocumentId(Uri.parse(folder)).substringAfter(':') }
            .getOrNull()?.ifBlank { null } ?: folder

    private fun inDownloads(context: Context, name: String, mime: String): Uri? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    }

    private fun inTree(context: Context, tree: Uri, name: String, mime: String): Uri? {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return DocumentsContract.createDocument(context.contentResolver, parent, mime, name)
    }
}
