package ibytsync.core.matching

import java.text.Normalizer
import kotlin.math.log10

data class YtCandidate(
    val id: String,
    val title: String,
    val channel: String = "",
    val duration: Double = 0.0,
    val viewCount: Long = 0L,
    /**
     * YouTube Music reports the album track (MUSIC_VIDEO_TYPE_ATV) separately from the
     * music video (MUSIC_VIDEO_TYPE_OMV). We cannot tell these apart from a title, which
     * is why plain YouTube results had to be keyword-guessed before.
     */
    val isOfficialAudio: Boolean = false
)

fun normStr(s: String?): String {
    if (s.isNullOrEmpty()) return ""
    val nfkd = Normalizer.normalize(s, Normalizer.Form.NFKD)
    val ascii = nfkd.replace(Regex("[^\\p{ASCII}]"), "")
    return ascii.lowercase().replace(Regex("\\s+"), " ").trim()
}

private val LYRIC_PENALTIES = listOf(
    "letra", "lyrics", "lyric video", "karaoke",
    "bass boosted", "sped up", "slowed", "nightcore",
    "8d audio", "reverb", "pitched"
)
private val VIDEO_PENALTIES = listOf(
    "official video", "video oficial", "official music video",
    "videoclip", "video clip", "official mv", "(mv)"
)
private val AUDIO_BONUSES = listOf(
    "official audio", "audio oficial", "audio only", "full song",
    "(audio)", "[audio]", "| audio"
)
private val LIVE_KWS = listOf(
    "remix", "live", "en vivo", "en directo", "ao vivo",
    "concert", "concierto", "en concierto", "en gira",
    "behind the scenes"
)
private val SPOTIFY_LIVE_KWS = listOf(
    "remix", "live", "en vivo", "en directo", "ao vivo",
    "concierto", "en concierto"
)

/** "(...)" and "[...]" qualifiers, which credit someone rather than describe the uploader. */
private val BRACKETED_GROUP = Regex("\\([^)]*\\)|\\[[^\\]]*\\]")

/**
 * True when the candidate can plausibly be the requested artist's recording.
 *
 * Guards the case where an unrelated artist uploads a re-recording under the same title:
 * searching "Ayra Starr Hot Body" also returns "HIBOY — Hot Body (Ayra Starr)" at 2:47.
 * Title and duration alone would happily accept it, so the artist is checked before
 * scoring rather than rewarded after.
 *
 * Bracketed qualifiers are stripped before comparing, because a name inside brackets is a
 * reference to someone (a cover crediting the original act, "Song (feat. X)") rather than
 * a statement about who is uploading. That is what separates a re-recording titled
 * "Hot Body (Ayra Starr)" by HIBOY — rejected — from a lyrics channel uploading
 * "Ayra Starr - Hot Body (Lyrics)" — kept, since it carries the real recording.
 *
 * Deliberately permissive otherwise: any one credited artist found in the (unbracketed)
 * channel or title is enough, and a candidate with nothing to compare against is kept so
 * missing metadata can never silently discard every result.
 */
private fun artistMatches(artist: String, c: YtCandidate): Boolean {
    val credited = artist.split(",", "&", " feat.", " ft.", " x ")
        .map { normStr(it) }
        .filter { it.isNotEmpty() }
    if (credited.isEmpty()) return true

    val unbracketedTitle = BRACKETED_GROUP.replace(c.title, " ")
    val haystack = (normStr(c.channel) + " " + normStr(unbracketedTitle)).trim()
    if (haystack.isBlank()) return true

    return credited.any { name ->
        name.isNotEmpty() && Regex("\\b${Regex.escape(name)}\\b").containsMatchIn(haystack)
    }
}

fun scoreCandidate(
    title: String,
    artist: String,
    durationMs: Long,
    c: YtCandidate
): Double {
    var score = 0.0
    val targetS: Double? = if (durationMs > 0) durationMs / 1000.0 else null
    val vidL = c.title.lowercase()
    val titleL = title.lowercase()
    val titleN = normStr(title)
    val artistKey = artist.split(",")[0].trim().lowercase()

    if (targetS != null) {
        val vidDur = c.duration
        val diff = kotlin.math.abs(vidDur - targetS)
        if (diff <= 2) score += 10.0 - diff * 2
        else if (diff <= 5) score += 2.0
        if (vidDur > targetS + 15) score -= 4.0
        if (vidDur > targetS + 30) score -= 4.0
    }

    val featStrip = Regex("\\s*[\\(\\[](feat|ft)\\..*", RegexOption.IGNORE_CASE)
        .replace(titleN, "").trim()
    val versionM = Regex("\\s*\\(([^)]+)\\)\\s*$").find(featStrip)
    val baseTitle = if (versionM != null) featStrip.substring(0, versionM.range.first).trim() else featStrip
    val versionKw = versionM?.groupValues?.get(1)?.let { normStr(it) }
    val vidN = normStr(c.title)

    if (baseTitle.isNotEmpty() && Regex("\\b${Regex.escape(baseTitle)}\\b").containsMatchIn(vidN)) {
        score += 5.0
    }
    if (versionKw != null) {
        if (versionKw in vidN) score += 4.0 else score -= 5.0
    }
    if (artistKey.isNotEmpty() && Regex("\\b${Regex.escape(artistKey)}\\b").containsMatchIn(vidN)) {
        score += 1.0
    }
    if (artistKey.isNotEmpty() && normStr(artistKey) in normStr(c.channel)) {
        score += 3.0
    }
    if (c.viewCount > 0) {
        val cap = if (targetS == null) 3.0 else 5.0
        score += minOf(log10(c.viewCount.toDouble()), cap)
    }
    if (LYRIC_PENALTIES.any { it in vidL }) score -= 3.0
    if (VIDEO_PENALTIES.any { it in vidL }) score -= 4.0
    if (AUDIO_BONUSES.any { it in vidL }) score += 4.0
    // An album track is the released master by definition, so it outranks anything we would
    // otherwise have to infer from wording. This is the only signal that reliably separates
    // "official audio" from a music video that runs long on end credits.
    if (c.isOfficialAudio) score += 6.0
    if (versionKw == null && LIVE_KWS.any { it in vidL } && SPOTIFY_LIVE_KWS.none { it in titleL }) {
        score -= 5.0
    }
    return score
}

/**
 * The confirmed album track for a song, if the search returned one, closest in length to
 * [nearMs].
 *
 * Only candidates YouTube Music actually labels as album tracks are considered, and the artist
 * has to check out. Callers still decide whether the length difference is worth surfacing —
 * this only answers "is there an official album version, and which one".
 */
fun bestOfficialAudio(
    title: String,
    artist: String,
    nearMs: Long,
    candidates: List<YtCandidate>
): YtCandidate? {
    val albumTracks = candidates.filter { it.isOfficialAudio && artistMatches(artist, it) }
    if (albumTracks.isEmpty()) return null
    return albumTracks.maxByOrNull { scoreCandidate(title, artist, nearMs, it) }
}

fun findBestYtMatch(
    title: String,
    artist: String,
    durationMs: Long,
    candidates: List<YtCandidate>
): String? {
    if (candidates.isEmpty()) return null
    val usable = candidates.filter { artistMatches(artist, it) }
    if (usable.isEmpty()) return null
    val scored = usable.map { it to scoreCandidate(title, artist, durationMs, it) }
        .sortedByDescending { it.second }
    val (best, bestScore) = scored[0]
    if (bestScore <= 0) return null
    return "https://music.youtube.com/watch?v=${best.id}"
}
