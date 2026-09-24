package ibytsync.core.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File

object FolderStore {
    private const val KEY_TREE = "saf_tree_uri"

    fun saveTree(context: Context, uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri,
            android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        context.getSharedPreferences("ibytsync_folders", Context.MODE_PRIVATE)
            .edit().putString(KEY_TREE, uri.toString()).apply()
    }

    fun savedTree(context: Context): Uri? {
        val s = context.getSharedPreferences("ibytsync_folders", Context.MODE_PRIVATE)
            .getString(KEY_TREE, null) ?: return null
        return Uri.parse(s)
    }

    sealed interface SaveOutcome {
        data class Saved(val displayName: String) : SaveOutcome
        data class Skipped(val reason: String) : SaveOutcome
        data class Failed(val reason: String) : SaveOutcome
    }

    fun mimeFor(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "m4a", "mp4", "aac" -> "audio/mp4"
            "opus", "ogg", "oga" -> "audio/ogg"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            else -> "audio/mpeg"
        }
    }

    fun hasPersistedPermission(context: Context, tree: Uri): Boolean {
        return try {
            context.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }
        } catch (_: Exception) {
            false
        }
    }

    fun save(
        context: Context,
        tree: Uri,
        tmpFile: File,
        onConfirm: Boolean
    ): SaveOutcome {
        if (!onConfirm) {
            tmpFile.delete()
            return SaveOutcome.Skipped("user skipped")
        }
        if (!hasPersistedPermission(context, tree)) {
            return SaveOutcome.Failed("folder permission expired")
        }
        return try {
            val dir = DocumentFile.fromTreeUri(context, tree)
                ?: return SaveOutcome.Failed("bad tree uri")
            val existing = dir.findFile(tmpFile.name)
            existing?.delete()
            val mime = mimeFor(tmpFile.name)
            val dest = dir.createFile(mime, tmpFile.name)
                ?: return SaveOutcome.Failed("create failed")
            context.contentResolver.openOutputStream(dest.uri)?.use { out ->
                tmpFile.inputStream().use { it.copyTo(out) }
            } ?: return SaveOutcome.Failed("open stream failed")
            SaveOutcome.Saved(tmpFile.name)
        } catch (e: Exception) {
            SaveOutcome.Failed(e.javaClass.simpleName)
        }
    }
}
