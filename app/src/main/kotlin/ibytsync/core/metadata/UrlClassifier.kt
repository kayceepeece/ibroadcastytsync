package ibytsync.core.metadata

/**
 * URL classification.
 *
 * - Spotify track/album URLs (locale prefix stripped) -> [UrlKind.SpotifyTrack]/[UrlKind.SpotifyAlbum]
 * - YouTube watch / shorts / youtu.be / music.youtube.com -> [UrlKind.YouTube]
 * - Anything else -> [UrlKind.Invalid], which the queue must mark
 *   `Skipped — bad URL` immediately (one bad line never blocks the batch).
 *
 * Pure Kotlin/JVM, no Android dependencies.
 */
sealed class UrlKind {
    data class SpotifyTrack(val id: String) : UrlKind()
    data class SpotifyAlbum(val id: String) : UrlKind()
    data class SpotifyPlaylist(val id: String) : UrlKind()
    data class YouTube(val rawUrl: String) : UrlKind()
    data class YouTubePlaylist(val playlistId: String, val rawUrl: String) : UrlKind()
    data class YouTubeWatchWithPlaylist(val videoId: String, val playlistId: String, val rawUrl: String) : UrlKind()
    data class Invalid(val reason: String = "bad URL") : UrlKind()
}

object UrlClassifier {

    private val SPOTIFY_HOST = "open.spotify.com"
    private val SPOTIFY_ID = Regex("^[A-Za-z0-9]+$")
    private val LOCALE_PREFIX = Regex("^intl(-[A-Za-z-]+)?$", RegexOption.IGNORE_CASE)

    private val YOUTUBE_HOSTS = setOf(
        "youtube.com",
        "www.youtube.com",
        "m.youtube.com",
        "music.youtube.com",
        "youtu.be",
        "www.youtu.be"
    )

    /**
     * Classify a pasted line. Never throws: unparseable input yields [UrlKind.Invalid].
     */
    fun classify(raw: String?): UrlKind {
        if (raw.isNullOrBlank()) return UrlKind.Invalid("empty")
        val trimmed = raw.trim()
        var candidate = trimmed
        if (!candidate.startsWith("http://", ignoreCase = true) && !candidate.startsWith("https://", ignoreCase = true)) {
            val match = Regex("""https?://[^\s]+""").find(candidate)
            if (match != null) {
                candidate = match.value
            }
        }
        val uri = try {
            java.net.URI(candidate)
        } catch (_: Exception) {
            return UrlKind.Invalid("bad URL")
        }
        val scheme = (uri.scheme ?: "").lowercase()
        if (scheme != "http" && scheme != "https") return UrlKind.Invalid("bad URL")
        val host = (uri.host ?: "").lowercase()
        if (host.isEmpty()) return UrlKind.Invalid("bad URL")

        if (host == SPOTIFY_HOST || host == "www.$SPOTIFY_HOST") {
            return classifySpotify(uri)
        }
        if (host in YOUTUBE_HOSTS) {
            return classifyYouTube(candidate, uri)
        }
        return UrlKind.Invalid("bad URL")
    }

    /** True when the row must skip immediately without any network fetch. */
    fun isSkippedImmediately(kind: UrlKind): Boolean = kind is UrlKind.Invalid

    /** True when the URL represents a collection of tracks that should be expanded. */
    fun isCollection(kind: UrlKind): Boolean =
        kind is UrlKind.SpotifyAlbum || kind is UrlKind.SpotifyPlaylist || kind is UrlKind.YouTubePlaylist

    private fun classifySpotify(uri: java.net.URI): UrlKind {
        val segs = uri.path.split("/").filter { it.isNotEmpty() }.toMutableList()
        // Strip locale prefix such as /intl-de/track/<id>.
        while (segs.size > 2 && LOCALE_PREFIX.matches(segs[0])) {
            segs.removeAt(0)
        }
        // Tolerate /embed/track/<id> embeds as the underlying track/album.
        if (segs.size >= 3 && segs[0].equals("embed", ignoreCase = true)) {
            segs.removeAt(0)
        }
        if (segs.size < 2) return UrlKind.Invalid("bad URL")
        val type = segs[0].lowercase()
        val id = segs[1]
        if (!SPOTIFY_ID.matches(id)) return UrlKind.Invalid("bad URL")
        return when (type) {
            "track" -> UrlKind.SpotifyTrack(id)
            "album" -> UrlKind.SpotifyAlbum(id)
            "playlist" -> UrlKind.SpotifyPlaylist(id)
            else -> UrlKind.Invalid("unsupported spotify type")
        }
    }

    private fun classifyYouTube(rawTrimmed: String, uri: java.net.URI): UrlKind {
        val host = (uri.host ?: "").lowercase()
        val path = uri.path ?: ""
        val segs = path.split("/").filter { it.isNotEmpty() }
        val query = uri.query ?: uri.rawQuery ?: ""
        val queryParams = query.split("&").mapNotNull {
            val kv = it.split("=", limit = 2)
            if (kv.size == 2) kv[0].lowercase() to kv[1] else null
        }.toMap()

        // youtu.be/<id>
        if (host == "youtu.be" || host == "www.youtu.be") {
            if (segs.isNotEmpty() && segs[0].isNotBlank()) {
                val videoId = segs[0]
                val playlistId = queryParams["list"]?.takeIf { it.isNotBlank() }
                return if (playlistId != null) {
                    UrlKind.YouTubeWatchWithPlaylist(videoId, playlistId, rawTrimmed)
                } else {
                    UrlKind.YouTube(rawTrimmed)
                }
            }
            return UrlKind.Invalid("bad URL")
        }

        // .../playlist?list=<id>
        if (segs.size == 1 && segs[0].equals("playlist", ignoreCase = true)) {
            val playlistId = queryParams["list"]?.takeIf { it.isNotBlank() }
            if (playlistId != null) return UrlKind.YouTubePlaylist(playlistId, rawTrimmed)
            return UrlKind.Invalid("bad URL")
        }

        // .../watch?v=<id> (youtube.com and music.youtube.com)
        if (segs.size == 1 && segs[0].equals("watch", ignoreCase = true)) {
            val videoId = queryParams["v"]?.takeIf { it.isNotBlank() }
            val playlistId = queryParams["list"]?.takeIf { it.isNotBlank() }
            if (videoId != null && playlistId != null) {
                return UrlKind.YouTubeWatchWithPlaylist(videoId, playlistId, rawTrimmed)
            }
            if (videoId != null) return UrlKind.YouTube(rawTrimmed)
            return UrlKind.Invalid("bad URL")
        }

        // .../shorts/<id>, .../embed/<id>, .../live/<id>, .../v/<id>
        if (segs.size >= 2) {
            val head = segs[0].lowercase()
            if ((head == "shorts" || head == "embed" || head == "live" || head == "v") &&
                segs[1].isNotBlank()
            ) {
                return UrlKind.YouTube(rawTrimmed)
            }
        }
        return UrlKind.Invalid("bad URL")
    }
}
