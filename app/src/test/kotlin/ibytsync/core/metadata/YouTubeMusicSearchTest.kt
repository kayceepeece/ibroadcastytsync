package ibytsync.core.metadata

import ibytsync.core.matching.YtCandidate
import ibytsync.core.matching.findBestYtMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures are trimmed from a real WEB_REMIX "songs" shelf response for
 * "Ayra Starr Hot Body", recorded from the device. The album track sits at rank 1 at 2:40
 * while the music video is 2:44 and is not a song result at all.
 */
class YouTubeMusicSearchTest {

    private fun row(
        videoId: String,
        title: String,
        artist: String,
        album: String,
        duration: String,
        videoType: String,
        plays: String = "1M plays"
    ): String = """
        {
          "musicResponsiveListItemRenderer": {
            "flexColumns": [
              { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [ { "text": "$title" } ] } } },
              { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [
                  { "text": "$artist", "navigationEndpoint": { "browseEndpoint": {
                      "browseEndpointContextSupportedConfigs": { "browseEndpointContextMusicConfig": { "pageType": "MUSIC_PAGE_TYPE_ARTIST" } } } } },
                  { "text": " • " },
                  { "text": "$album", "navigationEndpoint": { "browseEndpoint": {
                      "browseEndpointContextSupportedConfigs": { "browseEndpointContextMusicConfig": { "pageType": "MUSIC_PAGE_TYPE_ALBUM" } } } } },
                  { "text": " • " },
                  { "text": "$duration" }
              ] } } },
              { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [ { "text": "$plays" } ] } } }
            ],
            "playlistItemData": { "videoId": "$videoId" },
            "navigationEndpoint": { "watchEndpoint": {
                "videoId": "$videoId",
                "watchEndpointMusicSupportedConfigs": { "watchEndpointMusicConfig": { "musicVideoType": "$videoType" } } } }
          }
        }
    """.trimIndent()

    private fun payload(vararg rows: String): String =
        """{ "contents": { "singleColumnBrowseResultsRenderer": { "tabs": [ { "contents": [${rows.joinToString(",")}] } ] } } }"""

    private val searcher = YouTubeMusicSearch()

    @Test fun parsesSongRowsWithDurationArtistAndAtvFlag() {
        val body = payload(
            row("P-9L0BqQUMg", "Hot Body", "Ayra Starr", "Hot Body", "2:40", "MUSIC_VIDEO_TYPE_ATV", "48M plays"),
            row("4BUNakm87_M", "Hot Body (Remix) (feat. Danny Ocean)", "Ayra Starr", "Starrgirl", "3:02", "MUSIC_VIDEO_TYPE_ATV", "332K plays")
        )
        val out = searcher.parseSongsSearch(body, 10)
        assertEquals(2, out.size)

        val first = out[0]
        assertEquals("P-9L0BqQUMg", first.id)
        assertEquals("Hot Body", first.title)
        assertEquals("Ayra Starr", first.channel)
        assertEquals(160.0, first.duration, 0.01)
        assertEquals(48_000_000L, first.viewCount)
        assertTrue("album track must be flagged", first.isOfficialAudio)

        assertEquals(182.0, out[1].duration, 0.01)
        assertEquals(332_000L, out[1].viewCount)
    }

    @Test fun musicVideoRowsAreNotFlaggedAsAlbumTracks() {
        val body = payload(row("9qoNd60GcIA", "Hot Body", "Ayra Starr", "Hot Body", "2:44", "MUSIC_VIDEO_TYPE_OMV"))
        val out = searcher.parseSongsSearch(body, 10)
        assertEquals(1, out.size)
        assertEquals(164.0, out[0].duration, 0.01)
        assertFalse(out[0].isOfficialAudio)
    }

    @Test fun anUnknownShelfShapeYieldsNoResultsRatherThanGarbage() {
        assertEquals(0, searcher.parseSongsSearch("""{ "contents": {} }""", 10).size)
        assertEquals(0, searcher.parseSongsSearch("not json at all", 10).size)
        assertEquals(0, searcher.parseSongsSearch("", 10).size)
    }

    @Test fun durationIsTheLastTimeShapedTokenSoAlbumDigitsCannotBeMistaken() {
        // An album that itself looks like a time must not be read as the track length.
        assertEquals(160.0, searcher.parseTrailingDuration("Ayra Starr • 11:11 • 2:40"), 0.01)
        assertEquals(0.0, searcher.parseTrailingDuration("Ayra Starr • Hot Body"), 0.01)
        assertEquals(3_723.0, searcher.parseTrailingDuration("Ayra Starr • Album • 1:02:03"), 0.01)
    }

    @Test fun pickUsesTheAtvSignalWhenWordingAndDurationDisagree() {
        // Taken from the real pool: the 2:44 music video matches the row's own duration exactly
        // and says nothing about being a video, while the album track is 4s shorter. Only the
        // ATV flag separates them.
        val cands = listOf(
            YtCandidate("9qoNd60GcIA", "Ayra Starr - Hot Body", "Ayra Starr", 164.0, 31_907_252),
            YtCandidate("P-9L0BqQUMg", "Hot Body", "Ayra Starr", 160.0, 48_000_000, isOfficialAudio = true)
        )
        val picked = findBestYtMatch("Hot Body", "Ayra Starr", 160_000L, cands)
        assertEquals("https://music.youtube.com/watch?v=P-9L0BqQUMg", picked)
    }

    @Test fun candidatesFromAnUnrelatedArtistAreRejected() {
        // "HIBOY — Hot Body (Ayra Starr)" is a re-recording by a different act. It is close in
        // length and names Ayra Starr, so without an artist check it competes for the win.
        val rival = YtCandidate("w1EDjv4Iiq4", "Hot Body (Ayra Starr)", "HIBOY", 167.0, 5_000_000L, isOfficialAudio = true)
        assertNull(findBestYtMatch("Hot Body", "Ayra Starr", 160_000L, listOf(rival)))
    }

    @Test fun thirdPartyUploadsSurviveWhenTheyNameTheArtist() {
        // Lyrics/topic uploads routinely sit on non-artist channels but name the act in the
        // title, so they must still be usable.
        val thirdParty = YtCandidate("7clouds", "Ayra Starr - Hot Body (Lyrics)", "7clouds Afrobeats", 161.0, 27_721)
        assertEquals("https://music.youtube.com/watch?v=7clouds", findBestYtMatch("Hot Body", "Ayra Starr", 160_000L, listOf(thirdParty)))
    }

    @Test fun uploadsThatNeverNameTheArtistAreRejected() {
        // Documented tradeoff of the hard reject: a bare-titled upload on an unrelated channel
        // gives us nothing to verify against. A wrong track is worse than no match, and the
        // YouTube Music songs shelf covers these tracks properly in the primary tier.
        val bare = YtCandidate("bare1", "Hot Body", "Mavin Records", 160.0, 1_000)
        assertNull(findBestYtMatch("Hot Body", "Ayra Starr", 160_000L, listOf(bare)))
    }

    @Test fun missingArtistMetadataNeverDiscardsEveryCandidate() {
        val cand = YtCandidate("id1", "Hot Body", "", 160.0, 10)
        assertEquals("https://music.youtube.com/watch?v=id1", findBestYtMatch("Hot Body", "", 160_000L, listOf(cand)))
    }

    @Test fun playCountParsesShelfWording() {
        assertEquals(48_000_000L, searcher.parsePlayCount("48M plays"))
        assertEquals(332_000L, searcher.parsePlayCount("332K plays"))
        assertEquals(1_300_000L, searcher.parsePlayCount("1.3M plays"))
        assertEquals(0L, searcher.parsePlayCount(""))
    }
}
