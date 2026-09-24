package ibytsync.core.upload

data class Playlist(val id: String, val name: String)

sealed interface RoutePreference {
    data object LibraryOnly : RoutePreference
    data class Favorite(val playlistId: String, val name: String) : RoutePreference
    data object AskEachTime : RoutePreference
}

data class BatchSummary(var uploaded: Int = 0, var alreadyExisted: Int = 0, var failed: Int = 0, var cancelled: Int = 0) {
    fun message(): String = "Done — $uploaded uploaded, $alreadyExisted duplicates, $failed failed, $cancelled cancelled"
}

data class LibraryTrackInfo(
    val title: String,
    val artist: String,
    val lengthSec: Int = 0,
    val md5: String = ""
)

object DuplicateDetector {
    fun normalize(s: String): String {
        return s.lowercase()
            .replace(Regex("\\b(official|audio|video|lyrics|lyric|remastered|remaster|hd|4k|version)\\b", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\b(feat\\.|ft\\.|featuring)\\s+.*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[\\(\\)\\[\\]\\-_•/|]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun findDuplicate(
        title: String,
        artist: String,
        durationMs: Long?,
        libraryTracks: Collection<LibraryTrackInfo>
    ): ibytsync.core.pipeline.DuplicateMatch? {
        if (title.isBlank()) return null
        val normTitle = normalize(title)
        val normArtist = normalize(artist)
        val durationSec = (durationMs ?: 0L) / 1000L

        for (t in libraryTracks) {
            val libNormTitle = normalize(t.title)
            val libNormArtist = normalize(t.artist)
            val artistMatches = normArtist.isEmpty() || libNormArtist.isEmpty() ||
                    normArtist == libNormArtist || normArtist.contains(libNormArtist) || libNormArtist.contains(normArtist)

            val titleMatches = normTitle == libNormTitle ||
                    normTitle.contains(libNormTitle) || libNormTitle.contains(normTitle)

            if (artistMatches && titleMatches) {
                val isExact = if (durationSec > 0 && t.lengthSec > 0) {
                    kotlin.math.abs(durationSec - t.lengthSec) <= 4
                } else true

                return ibytsync.core.pipeline.DuplicateMatch(
                    isDuplicate = true,
                    matchedTitle = t.title,
                    matchedArtist = t.artist,
                    durationSec = t.lengthSec,
                    isExactDuration = isExact
                )
            }
        }
        return null
    }

    fun isDuplicate(title: String, artist: String, library: Map<String, Pair<String, String>>): Boolean {
        val t = normalize(title)
        val a = normalize(artist)
        return library.values.any { normalize(it.first) == t && normalize(it.second) == a }
    }
}

object UploadRouter {
    fun resolve(
        pref: RoutePreference,
        playlists: Map<String, Playlist>,
        askedId: String? = null
    ): String? {
        return when (pref) {
            is RoutePreference.LibraryOnly -> null
            is RoutePreference.Favorite ->
                if (playlists.containsKey(pref.playlistId)) pref.playlistId else null
            is RoutePreference.AskEachTime -> askedId
        }
    }

    fun staleFavoriteFallback(pref: RoutePreference, playlists: Map<String, Playlist>): Boolean =
        pref is RoutePreference.Favorite && !playlists.containsKey(pref.playlistId)
}

object Checksummer {
    fun md5(file: java.io.File): String {
        val d = java.security.MessageDigest.getInstance("MD5")
        file.inputStream().use { ins ->
            val buf = ByteArray(8192)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                d.update(buf, 0, n)
            }
        }
        return d.digest().joinToString("") { "%02x".format(it) }
    }
}
