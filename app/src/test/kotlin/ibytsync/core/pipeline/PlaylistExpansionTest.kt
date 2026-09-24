package ibytsync.core.pipeline

import ibytsync.core.download.DownloadRequest
import ibytsync.core.download.DownloadResult
import ibytsync.core.download.YtDlpEngine
import ibytsync.core.matching.YtCandidate
import ibytsync.core.metadata.SpotifyEmbedFetcher
import ibytsync.core.metadata.SpotifyEmbedScraper
import ibytsync.core.metadata.UrlClassifier
import ibytsync.core.metadata.UrlKind
import ibytsync.core.metadata.YtExtract
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistExpansionTest {

    @Test
    fun urlClassifierIdentifiesPlaylistTypesAndWatchWithPlaylist() {
        val ytPlaylist = UrlClassifier.classify("https://www.youtube.com/playlist?list=PL12345")
        assertTrue(ytPlaylist is UrlKind.YouTubePlaylist)
        assertEquals("PL12345", (ytPlaylist as UrlKind.YouTubePlaylist).playlistId)
        assertTrue(UrlClassifier.isCollection(ytPlaylist))

        val ytWatchWithPl = UrlClassifier.classify("https://www.youtube.com/watch?v=m4SyfrcsE0Q&list=PL12345")
        assertTrue(ytWatchWithPl is UrlKind.YouTubeWatchWithPlaylist)
        assertEquals("m4SyfrcsE0Q", (ytWatchWithPl as UrlKind.YouTubeWatchWithPlaylist).videoId)
        assertEquals("PL12345", ytWatchWithPl.playlistId)

        val spotifyPl = UrlClassifier.classify("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M")
        assertTrue(spotifyPl is UrlKind.SpotifyPlaylist)
        assertTrue(UrlClassifier.isCollection(spotifyPl))

        val spotifyAlbum = UrlClassifier.classify("https://open.spotify.com/album/4aawyAB9vmqN3uQ7FjRGTy")
        assertTrue(spotifyAlbum is UrlKind.SpotifyAlbum)
        assertTrue(UrlClassifier.isCollection(spotifyAlbum))
    }

    @Test
    fun spotifyEmbedScraperParsesTracklistCleanly() {
        val sampleHtml = """
            <!DOCTYPE html>
            <html>
            <head>
            <script id="__NEXT_DATA__" type="application/json">
            {
                "props": {
                    "pageProps": {
                        "state": {
                            "data": {
                                "entity": {
                                    "name": "After Hours",
                                    "type": "album",
                                    "coverArt": {
                                        "sources": [{"url": "https://i.scdn.co/image/ab67616d0000b2738863bc11d2aa12b54f5aeb36"}]
                                    },
                                    "trackList": [
                                        {
                                            "title": "Alone Again",
                                            "subtitle": "The Weeknd",
                                            "duration": 250000,
                                            "uri": "spotify:track:6bAlKq89kHhW7d1JbN2u7A",
                                            "isPlayable": true
                                        },
                                        {
                                            "title": "Too Late",
                                            "subtitle": "The Weeknd",
                                            "duration": 239000,
                                            "uri": "spotify:track:4aaw78901234567890abcd",
                                            "isPlayable": true
                                        }
                                    ]
                                }
                            }
                        }
                    }
                }
            }
            </script>
            </head>
            </html>
        """.trimIndent()

        val scraper = SpotifyEmbedScraper(fetcher = SpotifyEmbedFetcher { sampleHtml })
        val res = scraper.fetchCollection(UrlKind.SpotifyAlbum("test_id"))
        assertNotNull(res)
        assertEquals("After Hours", res!!.title)
        assertEquals("album", res.type)
        assertEquals("https://i.scdn.co/image/ab67616d0000b2738863bc11d2aa12b54f5aeb36", res.coverUrl)
        assertEquals(2, res.tracks.size)

        val t1 = res.tracks[0]
        assertEquals("Alone Again", t1.title)
        assertEquals("The Weeknd", t1.artist)
        assertEquals("After Hours", t1.album)
        assertEquals(250000L, t1.durationMs)
        assertEquals("6bAlKq89kHhW7d1JbN2u7A", t1.trackId)
    }

    @Test
    fun batchViewModelUnrollsSpotifyAlbumIntoIndividualRows() = runBlocking {
        val sampleHtml = """
            <html><script id="__NEXT_DATA__" type="application/json">
            {
                "props": {
                    "pageProps": {
                        "state": {
                            "data": {
                                "entity": {
                                    "name": "After Hours",
                                    "type": "album",
                                    "coverArt": { "sources": [{"url": "https://example.com/cover.jpg"}] },
                                    "trackList": [
                                        { "title": "Track 1", "subtitle": "Artist A", "duration": 180000, "uri": "spotify:track:id1" },
                                        { "title": "Track 2", "subtitle": "Artist A", "duration": 200000, "uri": "spotify:track:id2" }
                                    ]
                                }
                            }
                        }
                    }
                }
            }
            </script></html>
        """.trimIndent()

        val scraper = SpotifyEmbedScraper(fetcher = SpotifyEmbedFetcher { sampleHtml })
        val vm = BatchViewModel(
            spotifyEmbedScraper = scraper
        )

        vm.addInput("https://open.spotify.com/album/4aawyAB9vmqN3uQ7FjRGTy")
        // allow background IO coroutine to execute
        var attempts = 0
        while (vm.rows.value.size < 2 && attempts < 50) {
            delay(20)
            attempts++
        }

        val rows = vm.rows.value
        assertEquals(2, rows.size)
        assertEquals("Track 1", rows[0].title)
        assertEquals("Artist A", rows[0].artist)
        assertEquals("After Hours", rows[0].album)
        assertEquals("After Hours", rows[0].targetPlaylistName)
        assertEquals(RowStatus.METADATA_READY, rows[0].status)
        assertEquals("https://open.spotify.com/track/id1", rows[0].sourceInput)

        assertEquals("Track 2", rows[1].title)
        assertEquals("After Hours", rows[1].targetPlaylistName)
    }

    @Test
    fun youtubeWatchWithPlaylistTriggersPromptAndCanImportSingleOrEntirePlaylist() = runBlocking {
        val fakeDl = object : YtDlpEngine {
            override fun download(request: DownloadRequest): DownloadResult? = null
            override fun searchCandidates(query: String, count: Int): List<YtCandidate> = emptyList()
            override fun describeVideo(url: String): YtExtract? = null
            override fun extractPlaylist(playlistUrl: String): List<YtCandidate> = listOf(
                YtCandidate("v1", "Pl Track 1", "Channel 1", 180.0, 1000L),
                YtCandidate("v2", "Pl Track 2", "Channel 1", 200.0, 2000L)
            )
            override fun activeVariantLog(): List<String> = emptyList()
        }

        val vm = BatchViewModel(
            downloaderProvider = { fakeDl }
        )

        vm.addInput("https://www.youtube.com/watch?v=abc12345678&list=PL12345")

        val prompt = vm.pendingPlaylistPrompt.value
        assertNotNull(prompt)
        assertEquals("https://www.youtube.com/watch?v=abc12345678", prompt!!.videoUrl)
        assertEquals("https://www.youtube.com/playlist?list=PL12345", prompt.playlistUrl)

        vm.acceptPlaylistPrompt(importEntirePlaylist = true)
        var attempts = 0
        while (vm.rows.value.size < 2 && attempts < 50) {
            delay(20)
            attempts++
        }

        val rows = vm.rows.value
        assertEquals(2, rows.size)
        assertEquals("Pl Track 1", rows[0].title)
        assertEquals("Channel 1", rows[0].artist)
        assertEquals("YouTube Playlist", rows[0].targetPlaylistName)
    }
}
