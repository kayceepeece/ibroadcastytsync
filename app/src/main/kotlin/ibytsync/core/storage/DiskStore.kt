package ibytsync.core.storage

import android.content.Context
import android.util.Log
import ibytsync.core.metadata.ReleaseOption
import ibytsync.core.pipeline.AudioFormatChoice
import ibytsync.core.pipeline.AudioSourcePreference
import ibytsync.core.pipeline.DuplicateMatch
import ibytsync.core.pipeline.QueueRow
import ibytsync.core.pipeline.RowStatus
import ibytsync.core.upload.LibrarySnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

object DiskStore {
    private const val TAG = "DiskStore"
    private const val QUEUE_FILE = "queue_cache.json"
    private const val SYNCED_FILE = "synced_archive.json"
    private const val LIBRARY_FILE = "library_cache.json"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun JsonObject.str(key: String, default: String = ""): String =
        (get(key) as? JsonPrimitive)?.contentOrNull ?: default

    private fun JsonObject.intOrNull(key: String): Int? =
        (get(key) as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

    private fun JsonObject.longOrNull(key: String): Long? =
        (get(key) as? JsonPrimitive)?.longOrNull
            ?: (get(key) as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

    private fun JsonObject.floatOrNull(key: String): Float? =
        (get(key) as? JsonPrimitive)?.doubleOrNull?.toFloat()
            ?: (get(key) as? JsonPrimitive)?.contentOrNull?.toFloatOrNull()

    private fun JsonObject.bool(key: String, default: Boolean = false): Boolean =
        (get(key) as? JsonPrimitive)?.booleanOrNull
            ?: (get(key) as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
            ?: default

    private fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject
    private fun JsonObject.arr(key: String): JsonArray? = get(key) as? JsonArray

    fun rowToJson(row: QueueRow): JsonObject = buildJsonObject {
        put("id", row.id)
        put("sourceInput", row.sourceInput)
        put("status", row.status.name)
        put("title", row.title)
        put("artist", row.artist)
        put("album", row.album)
        row.durationMs?.let { put("durationMs", it) }
        row.coverUrl?.let { put("coverUrl", it) }
        put("audioPreference", row.audioPreference.name)
        put("detail", row.detail)
        row.progress?.let { put("progress", it) }
        row.year?.let { put("year", it) }
        row.genre?.let { put("genre", it) }
        row.trackNumber?.let { put("trackNumber", it) }
        row.trackCount?.let { put("trackCount", it) }
        put("forceUpload", row.forceUpload)
        row.targetPlaylistId?.let { put("targetPlaylistId", it) }
        row.targetPlaylistName?.let { put("targetPlaylistName", it) }
        if (row.targetPlaylistIds.isNotEmpty()) {
            putJsonArray("targetPlaylistIds") {
                row.targetPlaylistIds.forEach { add(JsonPrimitive(it)) }
            }
        }
        if (row.targetPlaylistNames.isNotEmpty()) {
            putJsonArray("targetPlaylistNames") {
                row.targetPlaylistNames.forEach { add(JsonPrimitive(it)) }
            }
        }
        row.audioFormat?.let { put("audioFormat", it.name) }
        row.batchId?.let { put("batchId", it) }
        row.ibroadcastTrackId?.let { put("ibroadcastTrackId", it) }
        row.replacesUploadedTrackId?.let { put("replacesUploadedTrackId", it) }
        put("isEditingInStudio", row.isEditingInStudio)
        put("isUpdated", row.isUpdated)
        row.savedLocalUri?.let { put("savedLocalUri", it) }
        row.officialAudioId?.let { put("officialAudioId", it) }
        row.officialAudioDurationMs?.let { put("officialAudioDurationMs", it) }

        row.duplicateMatch?.let { dup ->
            put("duplicateMatch", buildJsonObject {
                put("isDuplicate", dup.isDuplicate)
                put("matchedTitle", dup.matchedTitle)
                put("matchedArtist", dup.matchedArtist)
                put("durationSec", dup.durationSec)
                put("isExactDuration", dup.isExactDuration)
            })
        }

        row.selectedOption?.let { opt ->
            put("selectedOption", optionToJson(opt))
        }

        if (row.options.isNotEmpty()) {
            putJsonArray("options") {
                for (opt in row.options) {
                    add(optionToJson(opt))
                }
            }
        }
    }

    fun optionToJson(opt: ReleaseOption): JsonObject = buildJsonObject {
        put("artist", opt.artist)
        put("trackTitle", opt.trackTitle)
        put("album", opt.album)
        opt.artworkUrl?.let { put("artworkUrl", it) }
        opt.durationMs?.let { put("durationMs", it) }
        opt.isrc?.let { put("isrc", it) }
        put("sources", buildJsonArray { opt.sources.forEach { add(JsonPrimitive(it)) } })
        put("score", opt.score)
        opt.year?.let { put("year", it) }
        opt.genre?.let { put("genre", it) }
        opt.trackNumber?.let { put("trackNumber", it) }
        opt.trackCount?.let { put("trackCount", it) }
        opt.discNumber?.let { put("discNumber", it) }
        opt.discCount?.let { put("discCount", it) }
    }

    fun jsonToOption(obj: JsonObject): ReleaseOption {
        val srcArray = obj["sources"]?.let { el ->
            if (el is JsonArray) el.mapNotNull { (it as? JsonPrimitive)?.content } else null
        } ?: emptyList()
        return ReleaseOption(
            artist = obj.str("artist"),
            trackTitle = obj.str("trackTitle"),
            album = obj.str("album"),
            artworkUrl = obj.str("artworkUrl").takeIf { it.isNotEmpty() },
            durationMs = obj.longOrNull("durationMs"),
            isrc = obj.str("isrc").takeIf { it.isNotEmpty() },
            sources = srcArray,
            score = obj.intOrNull("score") ?: 0,
            year = obj.str("year").takeIf { it.isNotEmpty() },
            genre = obj.str("genre").takeIf { it.isNotEmpty() },
            trackNumber = obj.intOrNull("trackNumber"),
            trackCount = obj.intOrNull("trackCount"),
            discNumber = obj.intOrNull("discNumber"),
            discCount = obj.intOrNull("discCount")
        )
    }

    fun jsonToRow(obj: JsonObject): QueueRow {
        val statusName = obj.str("status", RowStatus.QUEUED.name)
        val status = try { RowStatus.valueOf(statusName) } catch (_: Exception) { RowStatus.QUEUED }

        val audioPrefName = obj.str("audioPreference", AudioSourcePreference.ORIGINAL_VIDEO.name)
        val audioPref = try { AudioSourcePreference.valueOf(audioPrefName) } catch (_: Exception) { AudioSourcePreference.ORIGINAL_VIDEO }

        val formatName = obj.str("audioFormat").takeIf { it.isNotEmpty() }
        val audioFormat = formatName?.let { try { AudioFormatChoice.valueOf(it) } catch (_: Exception) { null } }

        val dupObj = obj.obj("duplicateMatch")
        val duplicateMatch = dupObj?.let {
            DuplicateMatch(
                isDuplicate = it.bool("isDuplicate", true),
                matchedTitle = it.str("matchedTitle"),
                matchedArtist = it.str("matchedArtist"),
                durationSec = it.intOrNull("durationSec") ?: 0,
                isExactDuration = it.bool("isExactDuration", true)
            )
        }

        val selectedOpt = obj.obj("selectedOption")?.let { jsonToOption(it) }
        val optionsList = obj.arr("options")?.mapNotNull { el ->
            (el as? JsonObject)?.let { jsonToOption(it) }
        } ?: emptyList()

        val restoredStatus = if (status in ibytsync.core.pipeline.PhaseDetails.TRANSIENT_STATUSES) {
            ibytsync.core.pipeline.RowStatus.METADATA_READY
        } else status
        val restoredProgress = if (status in ibytsync.core.pipeline.PhaseDetails.TRANSIENT_STATUSES) {
            null
        } else {
            obj.floatOrNull("progress")
        }
        val restoredDetail = if (status in ibytsync.core.pipeline.PhaseDetails.TRANSIENT_STATUSES) {
            ibytsync.core.pipeline.PhaseDetails.INTERRUPTED
        } else {
            obj.str("detail")
        }

        // Legacy rows could carry a "no tags" detail while the structural flag was absent,
        // leaving the detail string as the only thing that stopped tagFile writing empty tags.
        val legacyUntagged = restoredDetail.contains("Untagged", ignoreCase = true) ||
            restoredDetail.contains("No metadata", ignoreCase = true)
        val restoredOption = if (selectedOpt == null && legacyUntagged) {
            ReleaseOption.noMetadata(obj.str("title"), obj.longOrNull("durationMs"))
        } else {
            selectedOpt
        }

        return QueueRow(
            id = obj.str("id"),
            sourceInput = obj.str("sourceInput"),
            status = restoredStatus,
            title = obj.str("title"),
            artist = obj.str("artist"),
            album = obj.str("album"),
            durationMs = obj.longOrNull("durationMs"),
            coverUrl = obj.str("coverUrl").takeIf { it.isNotEmpty() },
            audioPreference = audioPref,
            options = optionsList,
            selectedOption = restoredOption,
            detail = restoredDetail,
            progress = restoredProgress,
            year = obj.str("year").takeIf { it.isNotEmpty() },
            genre = obj.str("genre").takeIf { it.isNotEmpty() },
            trackNumber = obj.intOrNull("trackNumber"),
            trackCount = obj.intOrNull("trackCount"),
            duplicateMatch = duplicateMatch,
            forceUpload = obj.bool("forceUpload"),
            targetPlaylistId = obj.str("targetPlaylistId").takeIf { it.isNotEmpty() },
            targetPlaylistName = obj.str("targetPlaylistName").takeIf { it.isNotEmpty() },
            targetPlaylistIds = obj["targetPlaylistIds"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?: listOfNotNull(obj.str("targetPlaylistId").takeIf { it.isNotEmpty() }),
            targetPlaylistNames = obj["targetPlaylistNames"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?: listOfNotNull(obj.str("targetPlaylistName").takeIf { it.isNotEmpty() }),
            audioFormat = audioFormat,
            batchId = obj.str("batchId").takeIf { it.isNotEmpty() },
            ibroadcastTrackId = obj.str("ibroadcastTrackId").takeIf { it.isNotEmpty() },
            replacesUploadedTrackId = obj.str("replacesUploadedTrackId").takeIf { it.isNotEmpty() },
            isEditingInStudio = obj.bool("isEditingInStudio"),
            isUpdated = obj.bool("isUpdated"),
            savedLocalUri = obj.str("savedLocalUri").takeIf { it.isNotEmpty() },
            officialAudioId = obj.str("officialAudioId").takeIf { it.isNotEmpty() },
            officialAudioDurationMs = obj.longOrNull("officialAudioDurationMs")?.takeIf { it > 0L }
        )
    }

    private fun writeRows(file: File, rows: List<QueueRow>) {
        try {
            val arr = buildJsonArray {
                for (r in rows) {
                    val sanitized = if (r.status in ibytsync.core.pipeline.PhaseDetails.TRANSIENT_STATUSES) {
                        r.copy(progress = null)
                    } else r
                    add(rowToJson(sanitized))
                }
            }
            val parent = file.parentFile ?: return
            if (!parent.exists()) parent.mkdirs()
            val tempFile = File(parent, "${file.name}.tmp")
            tempFile.writeText(arr.toString())
            if (tempFile.exists()) {
                if (file.exists()) file.delete()
                tempFile.renameTo(file)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write rows to ${file.name}: ${e.message}", e)
        }
    }

    private fun readRows(file: File): List<QueueRow> {
        if (!file.exists()) return emptyList()
        return try {
            val content = file.readText()
            if (content.isBlank()) return emptyList()
            val parsed = json.parseToJsonElement(content).jsonArray
            parsed.mapNotNull { el ->
                (el as? JsonObject)?.let { jsonToRow(it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read rows from ${file.name}: ${e.message}", e)
            emptyList()
        }
    }

    fun saveQueue(context: Context, rows: List<QueueRow>) {
        val file = File(context.filesDir, QUEUE_FILE)
        writeRows(file, rows)
    }

    fun loadQueue(context: Context): List<QueueRow> {
        val file = File(context.filesDir, QUEUE_FILE)
        return readRows(file)
    }

    fun clearQueue(context: Context) {
        val file = File(context.filesDir, QUEUE_FILE)
        if (file.exists()) file.delete()
    }

    fun saveSynced(context: Context, rows: List<QueueRow>) {
        val file = File(context.filesDir, SYNCED_FILE)
        writeRows(file, rows)
    }

    fun loadSynced(context: Context): List<QueueRow> {
        val file = File(context.filesDir, SYNCED_FILE)
        return readRows(file)
    }

    fun appendSynced(context: Context, rows: List<QueueRow>) {
        if (rows.isEmpty()) return
        val current = loadSynced(context).toMutableList()
        val existingIds = current.map { it.id }.toSet()
        val newRows = rows.filterNot { existingIds.contains(it.id) }
        current.addAll(0, newRows) // Most recently synced at top
        saveSynced(context, current)
    }

    fun clearSynced(context: Context) {
        val file = File(context.filesDir, SYNCED_FILE)
        if (file.exists()) file.delete()
    }

    fun saveLibrary(context: Context, snapshot: LibrarySnapshot) {
        val obj = buildJsonObject {
            put("lastModified", snapshot.lastModified ?: "")
            putJsonArray("checksums") { snapshot.checksums.forEach { add(JsonPrimitive(it)) } }
            putJsonObject("pairs") {
                snapshot.pairs.forEach { (id, pair) ->
                    put(id, buildJsonArray { add(JsonPrimitive(pair.first)); add(JsonPrimitive(pair.second)) })
                }
            }
            putJsonObject("playlists") {
                snapshot.playlists.forEach { (id, pl) -> put(id, JsonPrimitive(pl.name)) }
            }
            putJsonObject("tracks") {
                snapshot.tracks.forEach { (id, t) ->
                    put(id, buildJsonObject {
                        put("title", t.title)
                        put("artist", t.artist)
                        put("lengthSec", t.lengthSec)
                        put("md5", t.md5)
                    })
                }
            }
        }
        try {
            File(context.filesDir, LIBRARY_FILE).writeText(obj.toString())
        } catch (e: Exception) {
            Log.e(TAG, "saveLibrary failed: ${e.message}")
        }
    }

    fun loadLibrary(context: Context): LibrarySnapshot? {
        val file = File(context.filesDir, LIBRARY_FILE)
        if (!file.exists()) return null
        return try {
            val o = json.parseToJsonElement(file.readText()).jsonObject
            val pairs = mutableMapOf<String, Pair<String, String>>()
            o.obj("pairs")?.forEach { (id, el) ->
                val arr = el as? JsonArray ?: return@forEach
                val title = (arr.getOrNull(0) as? JsonPrimitive)?.contentOrNull ?: ""
                val artist = (arr.getOrNull(1) as? JsonPrimitive)?.contentOrNull ?: ""
                pairs[id] = title to artist
            }
            val checksums = o.arr("checksums")
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.toSet() ?: emptySet()
            val playlists = mutableMapOf<String, ibytsync.core.upload.Playlist>()
            o.obj("playlists")?.forEach { (id, el) ->
                playlists[id] = ibytsync.core.upload.Playlist(id, (el as? JsonPrimitive)?.contentOrNull ?: id)
            }
            val tracks = mutableMapOf<String, ibytsync.core.upload.LibraryTrackInfo>()
            o.obj("tracks")?.forEach { (id, el) ->
                val t = el as? JsonObject ?: return@forEach
                tracks[id] = ibytsync.core.upload.LibraryTrackInfo(
                    title = t.str("title"),
                    artist = t.str("artist"),
                    lengthSec = t.intOrNull("lengthSec") ?: 0,
                    md5 = t.str("md5")
                )
            }
            LibrarySnapshot(
                lastModified = o.str("lastModified").takeIf { it.isNotEmpty() },
                pairs = pairs,
                checksums = checksums,
                playlists = playlists,
                tracks = tracks
            )
        } catch (e: Exception) {
            Log.e(TAG, "loadLibrary failed: ${e.message}")
            null
        }
    }

    fun clearLibrary(context: Context) {
        val file = File(context.filesDir, LIBRARY_FILE)
        if (file.exists()) file.delete()
    }
}
