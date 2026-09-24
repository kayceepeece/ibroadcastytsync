package ibytsync.core.pipeline

import ibytsync.core.download.DownloadRequest
import ibytsync.core.download.DownloadResult
import ibytsync.core.download.YtDlpEngine
import ibytsync.core.matching.YtCandidate
import ibytsync.core.metadata.CorrectionFetcher
import ibytsync.core.metadata.ITunesCorrection
import ibytsync.core.metadata.SpotifyPageFetcher
import ibytsync.core.metadata.SpotifyScraper
import ibytsync.core.metadata.YtExtract
import org.junit.Assert.*
import org.junit.Test

class MetadataResolverTest {

    private fun trackHtml(): String = """
        <html><head>
        <meta property="og:title" content="Mrs Magic">
        <meta property="og:description" content="Strawberry Guy · Sun Outside My Window · Song · 2019">
        <meta property="og:image" content="https://i.scdn.co/image/xyz">
        <meta name="music:musician_description" content="Strawberry Guy">
        <meta name="music:duration" content="209">
        </head><body></body></html>
    """.trimIndent()

    private fun albumHtml(): String = """
        <html><head>
        <meta property="og:title" content="After Hours - Album by The Weeknd | Spotify">
        <meta property="og:description" content="The Weeknd · album · 2020 · 14 songs">
        </head></html>
    """.trimIndent()

    private class FakeEngine(
        var extract: YtExtract? = null
    ) : YtDlpEngine {
        override fun download(request: DownloadRequest): DownloadResult? = null
        override fun searchCandidates(query: String, count: Int): List<YtCandidate> = emptyList()
        override fun describeVideo(url: String): YtExtract? = extract
        override fun activeVariantLog(): List<String> = emptyList()
    }

    private fun itunesMrsMagic(): ITunesCorrection = ITunesCorrection(CorrectionFetcher { _ ->
        """{"resultCount":1,"results":[
          |{"artistName":"Strawberry Guy","trackName":"Mrs Magic","collectionName":"Sun Outside My Window",
          |"artworkUrl100":"https://example.com/100x100bb.jpg","trackTimeMillis":209000}]}""".trimMargin()
    })

    private fun fetchingRow(input: String) = QueueRow(
        id = "r1", sourceInput = input, status = RowStatus.FETCHING_METADATA
    )

    @Test fun invalidUrlSkipsImmediately() {
        val r = MetadataResolver(
            SpotifyScraper(SpotifyPageFetcher { error("must not fetch") }),
            FakeEngine()
        )
        val out = r.resolve(fetchingRow("https://open.spotify.com/episode/abc123XYZ"))
        assertTrue(out is RowOutcome.Ready)
        assertEquals(RowStatus.SKIPPED_BAD_URL, (out as RowOutcome.Ready).row.status)
    }

    @Test fun garbageInputSkipsWithoutNetwork() {
        var fetched = false
        val r = MetadataResolver(
            SpotifyScraper(SpotifyPageFetcher { fetched = true; null }),
            FakeEngine()
        )
        val out = r.resolve(fetchingRow("not a url"))
        assertEquals(RowStatus.SKIPPED_BAD_URL, (out as RowOutcome.Ready).row.status)
        assertFalse(fetched)
    }

    @Test fun spotifyTrackResolvesToReady() {
        val r = MetadataResolver(
            SpotifyScraper(SpotifyPageFetcher { trackHtml() }),
            FakeEngine()
        )
        val out = r.resolve(fetchingRow("https://open.spotify.com/track/0TZejo18HlJ86OrWNsXKnw?autoplay=true"))
        val row = (out as RowOutcome.Ready).row
        assertEquals(RowStatus.METADATA_READY, row.status)
        assertEquals("Mrs Magic", row.title)
        assertEquals("Strawberry Guy", row.artist)
        assertEquals("Sun Outside My Window", row.album)
        assertEquals(209_000L, row.durationMs)
        assertEquals("https://i.scdn.co/image/xyz", row.coverUrl)
    }

    @Test fun spotifyTrackNullFetchFailsMetadata() {
        val r = MetadataResolver(
            SpotifyScraper(SpotifyPageFetcher { null }),
            FakeEngine()
        )
        val out = r.resolve(fetchingRow("https://open.spotify.com/track/0TZejo18HlJ86OrWNsXKnw"))
        assertEquals(RowStatus.FAILED_METADATA, (out as RowOutcome.Ready).row.status)
    }

    @Test fun spotifyThrowingFetcherFailsMetadata() {
        val r = MetadataResolver(
            SpotifyScraper(SpotifyPageFetcher { throw RuntimeException("boom") }),
            FakeEngine()
        )
        val out = r.resolve(fetchingRow("https://open.spotify.com/track/abc123"))
        assertEquals(RowStatus.FAILED_METADATA, (out as RowOutcome.Ready).row.status)
    }

    @Test fun spotifyLocaleUrlResolvesViaCanonicalId() {
        var seen = ""
        val r = MetadataResolver(
            SpotifyScraper(SpotifyPageFetcher { url -> seen = url; trackHtml() }),
            FakeEngine()
        )
        val out = r.resolve(fetchingRow("https://open.spotify.com/intl-de/track/0TZejo18HlJ86OrWNsXKnw"))
        assertEquals(RowStatus.METADATA_READY, (out as RowOutcome.Ready).row.status)
        assertEquals("https://open.spotify.com/track/0TZejo18HlJ86OrWNsXKnw", seen)
    }

    @Test fun spotifyAlbumStripsSuffixAndCarriesNoDurationOrCover() {
        val r = MetadataResolver(
            SpotifyScraper(SpotifyPageFetcher { albumHtml() }),
            FakeEngine()
        )
        val out = r.resolve(fetchingRow("https://open.spotify.com/album/4yP0hdKOZPNshxUOjY0cZj"))
        val row = (out as RowOutcome.Ready).row
        assertEquals(RowStatus.METADATA_READY, row.status)
        assertEquals("After Hours", row.album)
        assertEquals("The Weeknd", row.artist)
        assertNull(row.durationMs)
        assertNull(row.coverUrl)
    }

    @Test fun youtubeDescribeNullFailsMetadata() {
        val r = MetadataResolver(
            SpotifyScraper(SpotifyPageFetcher { error("must not fetch") }),
            FakeEngine(extract = null)
        )
        val out = r.resolve(fetchingRow("https://www.youtube.com/watch?v=m4SyfrcsE0Q"))
        assertEquals(RowStatus.FAILED_METADATA, (out as RowOutcome.Ready).row.status)
    }

    @Test fun youtubeExtractParksAwaitingAccept() {
        val r = MetadataResolver(
            SpotifyScraper(SpotifyPageFetcher { error("must not fetch") }),
            FakeEngine(extract = YtExtract("Strawberry Guy - Mrs Magic", "SomeChannel", 209_000L))
        )
        val out = r.resolve(fetchingRow("https://youtu.be/m4SyfrcsE0Q"))
        assertTrue(out is RowOutcome.AwaitingPick)
        val awaiting = out as RowOutcome.AwaitingPick
        assertEquals(RowStatus.AWAITING_ACCEPT, awaiting.row.status)
        assertEquals("Mrs Magic", awaiting.suggestion.titleGuess)
    }

    @Test fun youtubeExtractWithCorrectionCarriesItunesPick() {
        val r = MetadataResolver(
            SpotifyScraper(SpotifyPageFetcher { error("must not fetch") }),
            FakeEngine(extract = YtExtract("Strawberry Guy - Mrs Magic (Official Video)", "SomeChannel", 209_000L)),
            listOf(itunesMrsMagic())
        )
        val out = r.resolve(fetchingRow("https://www.youtube.com/watch?v=m4SyfrcsE0Q"))
        assertTrue(out is RowOutcome.AwaitingPick)
        val correction = (out as RowOutcome.AwaitingPick).suggestion.correction
        assertNotNull(correction)
        assertEquals("itunes", correction!!.source)
        assertEquals("Sun Outside My Window", correction.album)
    }

    @Test fun nonFetchingRowPassesThroughUntouched() {
        val row = QueueRow(id = "r9", sourceInput = "https://open.spotify.com/track/x",
            status = RowStatus.METADATA_READY, title = "T")
        val r = MetadataResolver(
            SpotifyScraper(SpotifyPageFetcher { error("must not fetch") }),
            FakeEngine()
        )
        val out = r.resolve(row)
        assertEquals(row, (out as RowOutcome.Ready).row)
    }

    @Test fun localFileWithoutStoreMatchesLandsReadyWithOriginalTags() {
        val r = MetadataResolver(
            SpotifyScraper(SpotifyPageFetcher { error("no spotify fetch") }),
            FakeEngine(),
            correctionClients = emptyList()
        )
        val row = QueueRow(
            id = "loc1",
            sourceInput = "content://media/external/audio/media/101",
            status = RowStatus.FETCHING_METADATA,
            title = "My Voice Memo",
            artist = "Me",
            album = "Recordings"
        )
        val out = r.resolve(row)
        assertTrue(out is RowOutcome.Ready)
        val ready = (out as RowOutcome.Ready).row
        assertEquals(RowStatus.METADATA_READY, ready.status)
        assertEquals("My Voice Memo", ready.title)
        assertEquals("Me", ready.artist)
        assertTrue("Must provide options including original tags and no-metadata", ready.options.size >= 2)
        assertTrue(ready.options.any { it.isNoMetadata })
        assertTrue(ready.options.any { it.isOriginalSource })
    }

    @Test fun localFileWithStoreMatchesLandsAwaitingAccept() {
        val r = MetadataResolver(
            SpotifyScraper(SpotifyPageFetcher { error("no spotify fetch") }),
            FakeEngine(),
            correctionClients = listOf(itunesMrsMagic())
        )
        val row = QueueRow(
            id = "loc2",
            sourceInput = "file:///sdcard/Music/Mrs Magic.mp3",
            status = RowStatus.FETCHING_METADATA,
            title = "Mrs Magic",
            artist = "Strawberry Guy",
            album = "",
            durationMs = 209_000L
        )
        val out = r.resolve(row)
        assertTrue(out is RowOutcome.Ready)
        val awaiting = (out as RowOutcome.Ready).row
        assertEquals(RowStatus.AWAITING_ACCEPT, awaiting.status)
        assertEquals("Sun Outside My Window", awaiting.album)
        assertTrue("Must provide official store options, original tags, and no-metadata", awaiting.options.size >= 3)
        assertTrue(awaiting.options.any { it.isNoMetadata })
        assertTrue(awaiting.options.any { it.isOriginalSource })
    }

    @Test fun spotifyTrackWithCorrectionClientsMergesSources() {
        val r = MetadataResolver(
            scraper = SpotifyScraper(SpotifyPageFetcher { trackHtml() }),
            downloader = FakeEngine(),
            correctionClients = listOf(itunesMrsMagic())
        )
        val out = r.resolve(fetchingRow("https://open.spotify.com/track/0TZejo18HlJ86OrWNsXKnw"))
        assertTrue(out is RowOutcome.Ready)
        val ready = (out as RowOutcome.Ready).row
        assertEquals(RowStatus.METADATA_READY, ready.status)
        assertEquals("Mrs Magic", ready.title)
        assertNotNull(ready.selectedOption)
        assertEquals(listOf("spotify", "itunes"), ready.selectedOption!!.sources)
    }
}
