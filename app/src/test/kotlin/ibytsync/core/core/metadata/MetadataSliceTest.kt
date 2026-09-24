package ibytsync.core.metadata

import org.junit.Test

/**
 * Metadata-slice tests.
 *
 * Covers URL classification, Spotify track/album/JSON-LD/duration/cover
 * parsing, failure-to-Failed-metadata semantics, and the loopback-HTTP
 * proof of the MockWebServer seam (injectable fetcher + baseUrl override).
 *
 * NOTE on runner: these are plain JUnit4 `@Test` cases (stdlib `check()`
 * assertions; converting to Assert-style is unnecessary). Behavior is
 * additionally pinned by `tests/metadata_slice_parity.py`, which is green.
 */
class MetadataSliceTest {

    private fun eq(expected: Any?, actual: Any?, name: String) {
        check(expected == actual) { "$name: expected <$expected> got <$actual>" }
    }

    // ---- URL classification ----

    @Test fun `spotify track plain url classifies`() {
        eq(
            UrlKind.SpotifyTrack("4uLU6hMCjMI75M1A2tKUQ"),
            UrlClassifier.classify("https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQ"),
            "track-plain"
        )
    }

    @Test fun `spotify track with query and trailing slash classifies`() {
        eq(
            UrlKind.SpotifyTrack("4uLU6hMCjMI75M1A2tKUQ"),
            UrlClassifier.classify("https://open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQ?si=abc123/"),
            "track-query"
        )
    }

    @Test fun `spotify locale prefix is stripped`() {
        eq(
            UrlKind.SpotifyTrack("4uLU6hMCjMI75M1A2tKUQ"),
            UrlClassifier.classify("https://open.spotify.com/intl-de/track/4uLU6hMCjMI75M1A2tKUQ"),
            "track-locale"
        )
    }

    @Test fun `spotify album classifies`() {
        eq(
            UrlKind.SpotifyAlbum("1ABCdefGhijK2lmnOPqrst"),
            UrlClassifier.classify("https://open.spotify.com/album/1ABCdefGhijK2lmnOPqrst"),
            "album"
        )
    }

    @Test fun `spotify playlist classifies as SpotifyPlaylist collection`() {
        val kind = UrlClassifier.classify("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M")
        check(kind is UrlKind.SpotifyPlaylist) { "playlist must be SpotifyPlaylist" }
        check((kind as UrlKind.SpotifyPlaylist).id == "37i9dQZF1DXcBWIGoYBM5M") { "playlist id mismatch" }
        check(UrlClassifier.isCollection(kind)) { "playlist must be a collection" }
    }

    @Test fun `spotify episode is invalid`() {
        check(UrlClassifier.classify("https://open.spotify.com/episode/abc123XYZ") is UrlKind.Invalid) {
            "episode must be Invalid"
        }
    }

    @Test fun `youtube watch url classifies`() {
        check(UrlClassifier.classify("https://www.youtube.com/watch?v=m4SyfrcsE0Q") is UrlKind.YouTube) {
            "watch must be YouTube"
        }
    }

    @Test fun `youtu be short link classifies`() {
        check(UrlClassifier.classify("https://youtu.be/m4SyfrcsE0Q") is UrlKind.YouTube) {
            "youtu.be must be YouTube"
        }
    }

    @Test fun `youtube shorts classifies`() {
        check(UrlClassifier.classify("https://www.youtube.com/shorts/m4SyfrcsE0Q") is UrlKind.YouTube) {
            "shorts must be YouTube"
        }
    }

    @Test fun `youtube music watch classifies`() {
        check(UrlClassifier.classify("https://music.youtube.com/watch?v=m4SyfrcsE0Q") is UrlKind.YouTube) {
            "music watch must be YouTube"
        }
    }

    @Test fun `garbage empty and non-url inputs are invalid`() {
        check(UrlClassifier.classify("not a url") is UrlKind.Invalid) { "garbage" }
        check(UrlClassifier.classify("") is UrlKind.Invalid) { "empty" }
        check(UrlClassifier.classify(null) is UrlKind.Invalid) { "null" }
        check(UrlClassifier.classify("ftp://example.com/x") is UrlKind.Invalid) { "ftp" }
        check(UrlClassifier.classify("https://example.com/track/abc") is UrlKind.Invalid) { "other host" }
    }

    @Test fun `watch without video id is invalid`() {
        check(UrlClassifier.classify("https://www.youtube.com/watch") is UrlKind.Invalid) {
            "bare watch must be Invalid"
        }
    }

    // ---- Spotify track parsing ----

    private fun trackHtml(
        title: String = "Blinding Lights",
        description: String = "The Weeknd · Song · 2019",
        image: String = "https://i.scdn.co/image/abc123",
        albumJson: String = "\"inAlbum\":{\"@type\":\"MusicAlbum\",\"name\":\"After Hours\"}",
        duration: String = ">3:20</span>"
    ): String = """
        <html><head>
        <meta property="og:title" content="$title">
        <meta property="og:description" content="$description">
        <meta property="og:image" content="$image">
        <script type="application/ld+json">{"@type":"MusicRecording","name":"$title",$albumJson}</script>
        </head><body><span>$duration</span></body></html>
    """.trimIndent()

    @Test fun `track resolves title artist album duration cover`() {
        val meta = checkNotNull(SpotifyScraper.parseTrackHtml(trackHtml()))
        eq("Blinding Lights", meta.trackTitle, "title")
        eq("The Weeknd", meta.artist, "artist")
        eq("After Hours", meta.album, "album")
        eq(200_000L, meta.durationMs, "duration")
        eq("https://i.scdn.co/image/abc123", meta.coverUrl, "cover")
        eq("single", meta.releaseType, "releaseType")
    }

    @Test fun `track artist is description split on middle dot`() {
        val meta = checkNotNull(
            SpotifyScraper.parseTrackHtml(trackHtml(description = "Daft Punk · Album · 2013"))
        )
        eq("Daft Punk", meta.artist, "artist-split")
    }

    @Test fun `track html entities are unescaped`() {
        val meta = checkNotNull(
            SpotifyScraper.parseTrackHtml(
                trackHtml(title = "Rock &amp; Roll", description = "R&amp;B All Stars · Song")
            )
        )
        eq("Rock & Roll", meta.trackTitle, "entity-title")
        eq("R&B All Stars", meta.artist, "entity-artist")
    }

    @Test fun `track duration longer minutes parses`() {
        eq(754_000L, SpotifyScraper.parseDurationMs("<span>12:34</span>"), "long-duration")
    }

    @Test fun `track without duration still resolves with null duration`() {
        val html = trackHtml(duration = "").replace("<span></span>", "")
        val meta = checkNotNull(SpotifyScraper.parseTrackHtml(html))
        eq(null, meta.durationMs, "null-duration")
        eq("Blinding Lights", meta.trackTitle, "title-without-duration")
    }

    @Test fun `track without json-ld album resolves with empty album`() {
        val html = """
            <html><head>
            <meta property="og:title" content="Song">
            <meta property="og:description" content="Artist · Song">
            </head><body><span>3:00</span></body></html>
        """.trimIndent()
        val meta = checkNotNull(SpotifyScraper.parseTrackHtml(html))
        eq("", meta.album, "empty-album")
    }

    @Test fun `track json-ld array form parses album`() {
        val html = """
            <html><head>
            <meta property="og:title" content="Song">
            <meta property="og:description" content="Artist · Song">
            <script type="application/ld+json">[{"@type":"MusicRecording","inAlbum":{"@type":"MusicAlbum","name":"Big Album"}}}]</script>
            </head></html>
        """.trimIndent()
        eq("Big Album", checkNotNull(SpotifyScraper.parseTrackHtml(html)).album, "ld-array")
    }

    @Test fun `track missing title fails to null`() {
        val html = """
            <html><head>
            <meta property="og:description" content="Artist · Song">
            </head></html>
        """.trimIndent()
        eq(null, SpotifyScraper.parseTrackHtml(html), "missing-title")
    }

    @Test fun `track missing artist fails to null`() {
        val html = """
            <html><head>
            <meta property="og:title" content="Song">
            </head></html>
        """.trimIndent()
        eq(null, SpotifyScraper.parseTrackHtml(html), "missing-artist")
    }

    @Test fun `track meta attributes in reverse order parse`() {
        val html = """
            <html><head>
            <meta content="Reversed Song" property="og:title">
            <meta content="Reversed Artist · Song" property="og:description">
            </head></html>
        """.trimIndent()
        val meta = checkNotNull(SpotifyScraper.parseTrackHtml(html))
        eq("Reversed Song", meta.trackTitle, "reversed-title")
        eq("Reversed Artist", meta.artist, "reversed-artist")
    }

    // ---- Live-shape parsing (bot UA page as served 2026-09-08) ----

    private fun liveTrackHtml(): String = """
        <html><head>
        <meta property="og:title" content="Mrs Magic">
        <meta property="og:description" content="Strawberry Guy · Mrs Magic · Song · 2019">
        <meta property="og:image" content="https://i.scdn.co/image/xyz">
        <meta name="music:musician_description" content="Strawberry Guy">
        <meta name="music:duration" content="209">
        <meta name="music:album" content="https://open.spotify.com/album/3Oovjf1PZOryLQSDKwjJzO">
        <script type="application/ld+json">{"@context":"http://schema.googleapis.com/","@type":["CreativeWork","MusicRecording"],"name":"Mrs Magic"}</script>
        </head><body></body></html>
    """.trimIndent()

    @Test fun `live track prefers music tags`() {
        val meta = checkNotNull(SpotifyScraper.parseTrackHtml(liveTrackHtml()))
        eq("Mrs Magic", meta.trackTitle, "live-title")
        eq("Strawberry Guy", meta.artist, "live-artist")
        eq(209_000L, meta.durationMs, "live-duration")
        eq("https://i.scdn.co/image/xyz", meta.coverUrl, "live-cover")
    }

    @Test fun `live track album falls back to description second token`() {
        val html = """
            <html><head>
            <meta property="og:title" content="Blinding Lights">
            <meta property="og:description" content="The Weeknd · After Hours · Song · 2020">
            <meta name="music:duration" content="200">
            </head></html>
        """.trimIndent()
        val meta = checkNotNull(SpotifyScraper.parseTrackHtml(html))
        eq("After Hours", meta.album, "desc-album")
        eq(200_000L, meta.durationMs, "desc-duration")
    }

    @Test fun `live track type-word second token is not an album`() {
        eq(null, SpotifyScraper.descriptionAlbumFallback("Artist · Song"), "block-song")
        eq(null, SpotifyScraper.descriptionAlbumFallback("Artist"), "block-single")
        eq(null, SpotifyScraper.descriptionAlbumFallback("Artist · 2019"), "block-year")
        eq("Real Album", SpotifyScraper.descriptionAlbumFallback("Artist · Real Album · Song · 2020"), "real-album")
    }

    @Test fun `live album strips spotify title suffix`() {
        val html = """
            <html><head>
            <meta property="og:title" content="After Hours - Album by The Weeknd | Spotify">
            <meta property="og:description" content="The Weeknd · album · 2020 · 14 songs">
            </head></html>
        """.trimIndent()
        val album = checkNotNull(SpotifyScraper.parseAlbumHtml(html))
        eq("After Hours", album.album, "suffix-album")
        eq("The Weeknd", album.artist, "suffix-artist")
    }

    @Test fun `live album plain title passes through`() {
        eq("After Hours", SpotifyScraper.stripAlbumTitleSuffix("After Hours"), "plain-album")
        eq("Mrs Magic", SpotifyScraper.stripAlbumTitleSuffix("Mrs Magic - Single by Strawberry Guy"), "single-suffix")
    }

    @Test fun `live default fetcher uses crawler ua`() {
        check(SpotifyScraper.SPOTIFY_USER_AGENT.contains("Googlebot")) { "crawler UA required" }
        check(!SpotifyScraper.SPOTIFY_USER_AGENT.contains("Chrome/126")) { "browser UA must not be used for Spotify" }
    }

    // ---- Scraper failure semantics (never throw, null = Failed-metadata) ----

    @Test fun `network failure returns null instead of throwing`() {
        val scraper = SpotifyScraper(SpotifyPageFetcher { null })
        eq(null, scraper.getTrackMetadata("abc123"), "track-null-fetch")
        eq(null, scraper.getAlbumMetadata("abc123"), "album-null-fetch")
    }

    @Test fun `throwing fetcher returns null instead of throwing`() {
        val scraper = SpotifyScraper(SpotifyPageFetcher { throw RuntimeException("boom") })
        eq(null, scraper.getTrackMetadata("abc123"), "throwing-fetch")
    }

    @Test fun `blank id returns null without fetching`() {
        var called = false
        val scraper = SpotifyScraper(SpotifyPageFetcher { called = true; "" })
        eq(null, scraper.getTrackMetadata("  "), "blank-id")
        check(!called) { "fetcher must not be called for blank id" }
    }

    @Test fun `scraper hits canonical locale-stripped path`() {
        var seen = ""
        val scraper = SpotifyScraper(SpotifyPageFetcher { url -> seen = url; null })
        scraper.getTrackMetadata("TRACKID")
        eq("https://open.spotify.com/track/TRACKID", seen, "track-path")
        scraper.getAlbumMetadata("ALBUMID")
        eq("https://open.spotify.com/album/ALBUMID", seen, "album-path")
    }

    @Test fun `scraper works against loopback http proving mock-server seam`() {
        val html = trackHtml()
        // Minimal loopback HTTP server on plain java.net.ServerSocket (no extra
        // test deps): proves the fetcher + baseUrl seam a MockWebServer will use.
        val serverSocket = java.net.ServerSocket(0)
        val port = serverSocket.localPort
        val served = java.util.concurrent.CountDownLatch(1)
        val serverThread = Thread {
            try {
                serverSocket.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    var line: String?
                    do {
                        line = input.readLine()
                    } while (line != null && line.isNotEmpty())
                    val bytes = html.toByteArray(Charsets.UTF_8)
                    val out = socket.getOutputStream()
                    val head = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n" +
                        "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                    out.write(head.toByteArray(Charsets.ISO_8859_1))
                    out.write(bytes)
                    out.flush()
                }
            } catch (_: Exception) {
                // Test asserts on the parsed result; transport errors surface there.
            } finally {
                served.countDown()
            }
        }
        serverThread.isDaemon = true
        serverThread.start()
        try {
            val scraper = SpotifyScraper(
                SpotifyScraper.okHttpFetcher(),
                baseUrl = "http://localhost:$port"
            )
            val meta = checkNotNull(scraper.getTrackMetadata("anything"))
            eq("Blinding Lights", meta.trackTitle, "loopback-title")
            eq("The Weeknd", meta.artist, "loopback-artist")
            eq(200_000L, meta.durationMs, "loopback-duration")
            served.await(10, java.util.concurrent.TimeUnit.SECONDS)
        } finally {
            serverSocket.close()
        }
    }

    // ---- Spotify album parsing (no duration/cover) ----

    @Test fun `album resolves artist and album with no duration or cover`() {
        val html = """
            <html><head>
            <meta property="og:title" content="After Hours">
            <meta property="og:description" content="The Weeknd · Album · 2020">
            <meta property="og:image" content="https://i.scdn.co/image/should-be-ignored">
            </head><body><span>14:20</span></body></html>
        """.trimIndent()
        val scraper = SpotifyScraper(SpotifyPageFetcher { html })
        val album = checkNotNull(scraper.getAlbumMetadata("ALBUMID"))
        eq("The Weeknd", album.artist, "album-artist")
        eq("After Hours", album.album, "album-name")
    }

    @Test fun `album missing fields fails to null`() {
        eq(null, SpotifyScraper.parseAlbumHtml("<html></html>"), "album-empty-page")
    }
}

/** JVM entry point so the suite runs without a JUnit runner (see header note). */
object MetadataSliceTestMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val t = MetadataSliceTest()
        val cases = listOf(
            "track-plain" to { t.`spotify track plain url classifies`() },
            "track-query" to { t.`spotify track with query and trailing slash classifies`() },
            "track-locale" to { t.`spotify locale prefix is stripped`() },
            "album-kind" to { t.`spotify album classifies`() },
            "playlist-collection" to { t.`spotify playlist classifies as SpotifyPlaylist collection`() },
            "episode-invalid" to { t.`spotify episode is invalid`() },
            "yt-watch" to { t.`youtube watch url classifies`() },
            "yt-short" to { t.`youtu be short link classifies`() },
            "yt-shorts" to { t.`youtube shorts classifies`() },
            "yt-music" to { t.`youtube music watch classifies`() },
            "invalid-inputs" to { t.`garbage empty and non-url inputs are invalid`() },
            "watch-no-id" to { t.`watch without video id is invalid`() },
            "track-full" to { t.`track resolves title artist album duration cover`() },
            "track-split" to { t.`track artist is description split on middle dot`() },
            "track-entities" to { t.`track html entities are unescaped`() },
            "track-long-dur" to { t.`track duration longer minutes parses`() },
            "track-no-dur" to { t.`track without duration still resolves with null duration`() },
            "track-no-album" to { t.`track without json-ld album resolves with empty album`() },
            "track-ld-array" to { t.`track json-ld array form parses album`() },
            "track-no-title" to { t.`track missing title fails to null`() },
            "track-no-artist" to { t.`track missing artist fails to null`() },
            "track-reversed" to { t.`track meta attributes in reverse order parse`() },
            "live-music-tags" to { t.`live track prefers music tags`() },
            "live-desc-album" to { t.`live track album falls back to description second token`() },
            "live-album-guard" to { t.`live track type-word second token is not an album`() },
            "live-album-suffix" to { t.`live album strips spotify title suffix`() },
            "live-album-plain" to { t.`live album plain title passes through`() },
            "live-crawler-ua" to { t.`live default fetcher uses crawler ua`() },
            "fetch-null" to { t.`network failure returns null instead of throwing`() },
            "fetch-throw" to { t.`throwing fetcher returns null instead of throwing`() },
            "blank-id" to { t.`blank id returns null without fetching`() },
            "canonical-path" to { t.`scraper hits canonical locale-stripped path`() },
            "loopback" to { t.`scraper works against loopback http proving mock-server seam`() },
            "album-full" to { t.`album resolves artist and album with no duration or cover`() },
            "album-missing" to { t.`album missing fields fails to null`() }
        )
        var passed = 0
        for ((name, fn) in cases) {
            try {
                fn()
                passed++
                println("PASS $name")
            } catch (e: Throwable) {
                println("FAIL $name: ${e.message}")
                throw e
            }
        }
        println("METADATA SLICE JVM CHECKS PASSED ($passed/${cases.size})")
    }
}
