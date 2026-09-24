package ibytsync.core.metadata

import ibytsync.core.pipeline.QueueRow
import ibytsync.core.pipeline.RowStates.afterYouTubeDecision
import ibytsync.core.pipeline.RowStates.afterYouTubePick
import ibytsync.core.pipeline.RowStates.afterYouTubeSuggest
import ibytsync.core.pipeline.RowStatus
import org.junit.Assert.*
import org.junit.Test

class YouTubeSuggestTest {

    private fun itunesOk(): ITunesCorrection = ITunesCorrection(CorrectionFetcher { _ ->
        """{"resultCount":2,"results":[
          |{"artistName":"Strawberry Guy","trackName":"Mrs Magic","collectionName":"Sun Outside My Window",
          |"artworkUrl100":"https://example.com/100x100bb.jpg","trackTimeMillis":209000},
          |{"artistName":"Someone Else","trackName":"Mrs Tragic","collectionName":"Other",
          |"artworkUrl100":"https://example.com/x.jpg","trackTimeMillis":200000}]}""".trimMargin()
    })

    private fun deezerErr(): DeezerCorrection = DeezerCorrection(CorrectionFetcher { _ ->
        """{"error":{"type":"Exception","message":"Quota","code":4}}"""
    })

    @Test fun dashSplitArtistTitle() {
        val (a, t) = YtTitleSplitter.split("Strawberry Guy - Mrs Magic (Official Video)", "SomeChannel")
        assertEquals("Strawberry Guy", a)
        assertTrue(t.startsWith("Mrs Magic"))
    }

    @Test fun topicChannelBecomesArtist() {
        val (a, t) = YtTitleSplitter.split("Mrs Magic", "Strawberry Guy - Topic")
        assertEquals("Strawberry Guy", a)
        assertEquals("Mrs Magic", t)
    }

    @Test fun undashableTitleKeepsChannelGuess() {
        val (a, t) = YtTitleSplitter.split("mrs magic strawberry guy", "StrawberryGuy")
        assertEquals("StrawberryGuy", a)
        assertEquals("mrs magic strawberry guy", t)
    }

    @Test fun junkFlagged() {
        assertTrue(YtTitleSplitter.looksJunk("Song (Official Lyric Video)"))
        assertTrue(YtTitleSplitter.looksJunk("Song - Sped Up + reverb"))
        assertFalse(YtTitleSplitter.looksJunk("Strawberry Guy - Mrs Magic"))
    }

    @Test fun ytExtractParsesDumpJson() {
        val ex = YtExtractParser.parseDumpJson(
            """{"title":"Strawberry Guy - Mrs Magic (Official Video)","channel":"Strawberry Guy","duration":209}"""
        )
        assertNotNull(ex)
        assertEquals(209_000L, ex!!.durationMs)
        assertNull(YtExtractParser.parseDumpJson("""{"channel":"x"}"""))
        assertNull(YtExtractParser.parseDumpJson("""not json"""))
    }

    @Test fun itunesSuggestsBestLowConfidenceRejected() {
        val good = itunesOk().suggest("Strawberry Guy", "Mrs Magic")
        assertNotNull(good)
        assertEquals("Strawberry Guy", good!!.artist)
        assertEquals("Mrs Magic", good.trackTitle)
        assertEquals("Sun Outside My Window", good.album)
        assertEquals("https://example.com/600x600bb.jpg", good.artworkUrl)
        assertEquals("itunes", good.source)
        assertNull(itunesOk().suggest("zzzz no such", "qqqq nothing"))
    }

    @Test fun itunesParsesEnrichmentFields() {
        val it = ITunesCorrection(CorrectionFetcher { _ ->
            """{"resultCount":1,"results":[
              |{"artistName":"Strawberry Guy","trackName":"Mrs Magic","collectionName":"Mrs Magic - Single",
              |"artworkUrl100":"https://example.com/100x100bb.jpg","trackTimeMillis":208500,
              |"releaseDate":"2019-07-23T12:00:00Z","primaryGenreName":"Alternative",
              |"trackNumber":1,"trackCount":1,"discNumber":1,"discCount":1}]}""".trimMargin()
        })
        val cand = it.candidates("Strawberry Guy", "Mrs Magic").single()
        assertEquals("2019", cand.year)
        assertEquals("Alternative", cand.genre)
        assertEquals(1, cand.trackNumber)
        assertEquals(1, cand.trackCount)
        assertEquals(1, cand.discNumber)
        assertEquals(1, cand.discCount)
    }

    @Test fun malformedEnrichmentFallsBackClean() {
        val it = ITunesCorrection(CorrectionFetcher { _ ->
            """{"resultCount":1,"results":[
              |{"artistName":"A","trackName":"T","collectionName":"C",
              |"releaseDate":"not-a-date","trackNumber":0,"discNumber":-2}]}""".trimMargin()
        })
        val cand = it.candidates("A", "T").single()
        assertNull(cand.year)
        assertNull(cand.genre)
        assertNull(cand.trackNumber)
        assertNull(cand.discNumber)
    }

    @Test fun deezerPrefersXlCover() {
        val dz = DeezerCorrection(CorrectionFetcher { _ ->
            """{"data":[{"title":"T","artist":{"name":"A"},"duration":200,
              |"album":{"title":"C",
              |"cover":"https://example.com/cover.jpg",
              |"cover_big":"https://example.com/big.jpg",
              |"cover_xl":"https://example.com/xl.jpg"}}]}""".trimMargin()
        })
        assertEquals("https://example.com/xl.jpg", dz.candidates("A", "T").single().artworkUrl)
    }

    @Test fun mergedGroupKeepsBiggestArt() {
        val scored = listOf(
            CorrectionCandidate("A", "T", "C", "https://example.com/600x600bb.jpg", "itunes", 200_000L, "ISRC1") to 90,
            CorrectionCandidate("A", "T", "C", "https://example.com/xl.jpg", "deezer", 200_000L, "isrc1") to 80
        )
        val grouped = groupReleases(scored)
        assertEquals(1, grouped.size)
        assertEquals("https://example.com/xl.jpg", grouped[0].artworkUrl)
        assertEquals(listOf("itunes", "deezer"), grouped[0].sources)
    }

    @Test fun deezerErrorBodyYieldsNull() {
        assertNull(deezerErr().suggest("a", "b"))
    }

    @Test fun deezerSuggestsWithCoverBig() {
        val dz = DeezerCorrection(CorrectionFetcher { _ ->
            """{"data":[{"title":"Mrs Magic","artist":{"name":"Strawberry Guy"},"duration":209,
              |"isrc":"USABC1234567",
              |"album":{"title":"Sun Outside My Window","cover_big":"https://example.com/big.jpg"}}],
              |"total":1}""".trimMargin()
        })
        val cand = dz.suggest("Strawberry Guy", "Mrs Magic")
        assertNotNull(cand)
        assertEquals("deezer", cand!!.source)
        assertEquals("https://example.com/big.jpg", cand.artworkUrl)
        assertEquals(209_000L, cand.durationMs)
        assertEquals("USABC1234567", cand.isrc)
    }

    @Test fun fetchersNeverThrow() {
        val boom = ITunesCorrection(CorrectionFetcher { throw RuntimeException("x") })
        assertNull(boom.suggest("a", "b"))
        assertNull(ITunesCorrection(CorrectionFetcher { null }).suggest("a", "b"))
        assertNull(ITunesCorrection(CorrectionFetcher { "garbage" }).suggest("a", "b"))
        assertNull(itunesOk().suggest("  ", " "))
    }

    @Test fun builderFallsBackToDeezerWhenItunesMisses() {
        val miss = ITunesCorrection(CorrectionFetcher { """{"resultCount":0,"results":[]}""" })
        val hit = DeezerCorrection(CorrectionFetcher { _ ->
            """{"data":[{"title":"Mrs Magic","artist":{"name":"Strawberry Guy"},"duration":209,
              |"album":{"title":"Sun Outside My Window","cover_big":"https://example.com/big.jpg"}}]}""".trimMargin()
        })
        val s = YtSuggestionBuilder.build(YtExtract("Strawberry Guy - Mrs Magic", "chan", 209_000L), miss, hit)
        assertNotNull(s)
        assertEquals("deezer", s!!.correction?.source)
        assertEquals("Strawberry Guy", s.acceptedArtist())
    }

    @Test fun builderWithNoCorrectionKeepsGuess() {
        val s = YtSuggestionBuilder.build(
            YtExtract("Some Channel - My Song", "Some Channel", null),
            ITunesCorrection(CorrectionFetcher { """{"resultCount":0,"results":[]}""" })
        )
        assertNotNull(s)
        assertNull(s!!.correction)
        assertEquals("My Song", s.acceptedTitle())
        assertEquals("My Song", s.acceptedAlbum())
    }

    @Test fun rowAwaitAcceptThenAcceptStamps() {
        val s = YtSuggestionBuilder.build(
            YtExtract("Strawberry Guy - Mrs Magic (Official Video)", "chan", 209_000L), itunesOk()
        )!!
        val awaiting = QueueRow("1", "yt-url").afterYouTubeSuggest(s)
        assertEquals(RowStatus.AWAITING_ACCEPT, awaiting.status)
        assertFalse(ibytsync.core.pipeline.RowStates.canStart(listOf(awaiting.status)))
        val accepted = awaiting.afterYouTubeDecision(s, true)
        assertEquals(RowStatus.METADATA_READY, accepted.status)
        assertEquals("Mrs Magic", accepted.title)
        assertEquals("Strawberry Guy", accepted.artist)
        assertTrue(ibytsync.core.pipeline.RowStates.canStart(listOf(accepted.status)))
    }

    @Test fun rejectMarksFailedRetryOffered() {
        val s = YtSuggestionBuilder.build(YtExtract("A - B", "chan", null))!!
        val awaiting = QueueRow("1", "yt-url").afterYouTubeSuggest(s)
        val rejected = awaiting.afterYouTubeDecision(s, false)
        assertEquals(RowStatus.FAILED_METADATA, rejected.status)
        assertTrue(ibytsync.core.pipeline.RowStates.canRetry(rejected.status))
    }

    @Test fun nullSuggestionFailsDecisionOffPathUntouched() {
        val failed = QueueRow("1", "yt-url").afterYouTubeSuggest(null)
        assertEquals(RowStatus.FAILED_METADATA, failed.status)
        val ready = QueueRow("2", "x", RowStatus.METADATA_READY)
        val s = YtSuggestionBuilder.build(YtExtract("A - B", "chan", null))!!
        assertEquals(RowStatus.METADATA_READY, ready.afterYouTubeDecision(s, true).status)
        assertTrue(ibytsync.core.pipeline.RowStates.canDecide(RowStatus.AWAITING_ACCEPT))
        assertFalse(ibytsync.core.pipeline.RowStates.canDecide(RowStatus.METADATA_READY))
    }

    @Test fun durationPenaltyBands() {
        assertEquals(0, ITunesCorrection.durationPenalty(209_000L, 209_000L))
        assertEquals(0, ITunesCorrection.durationPenalty(212_000L, 209_000L))
        assertEquals(-20, ITunesCorrection.durationPenalty(220_000L, 209_000L))
        assertEquals(-40, ITunesCorrection.durationPenalty(230_000L, 209_000L))
        assertEquals(-1000, ITunesCorrection.durationPenalty(600_000L, 209_000L))
        assertEquals(0, ITunesCorrection.durationPenalty(null, 209_000L))
        assertEquals(0, ITunesCorrection.durationPenalty(209_000L, null))
    }

    @Test fun farDurationSinksBelowThreshold() {
        val long = ITunesCorrection(CorrectionFetcher { _ ->
            """{"resultCount":1,"results":[
              |{"artistName":"Strawberry Guy","trackName":"Mrs Magic","collectionName":"Ten Hour Loop",
              |"artworkUrl100":"https://example.com/x.jpg","trackTimeMillis":36000000}]}""".trimMargin()
        })
        assertNull(long.suggest("Strawberry Guy", "Mrs Magic", 209_000L))
        val s = YtSuggestionBuilder.build(YtExtract("Strawberry Guy - Mrs Magic", "chan", 209_000L), long)
        assertNotNull(s)
        assertTrue(s!!.options.isEmpty())
        assertNull(s.correction)
    }

    @Test fun sameIsrcMergesSources() {
        val dz = DeezerCorrection(CorrectionFetcher { _ ->
            """{"data":[
              |{"title":"Mrs Magic","artist":{"name":"Strawberry Guy"},"duration":209,"isrc":"USABC1234567",
              |"album":{"title":"Sun Outside My Window","cover_big":"https://example.com/dz.jpg"}}]}""".trimMargin()
        })
        val scored = dz.candidates("Strawberry Guy", "Mrs Magic").map { it to 100 } +
            listOf(CorrectionCandidate("Strawberry Guy", "Mrs Magic", "Sun Outside My Window", "https://example.com/it.jpg", "itunes", 209_000L, "usabc1234567") to 100)
        val grouped = groupReleases(scored)
        assertEquals(1, grouped.size)
        assertEquals(listOf("deezer", "itunes"), grouped[0].sources)
        assertEquals("USABC1234567", grouped[0].isrc)
    }

    @Test fun crossPollinatesTagsBetweenSourcesEvenIfWinnerLacksThem() {
        // Deezer wins on score (100 vs 90), but iTunes has year, genre, trackNumber
        val deezerWin = listOf(
            CorrectionCandidate(
                artist = "M83", trackTitle = "Midnight City", album = "Hurry Up, We're Dreaming",
                artworkUrl = "https://example.com/1000x1000.jpg", source = "deezer",
                durationMs = 243_000L, isrc = "FR01T1100371"
            ) to 100,
            CorrectionCandidate(
                artist = "M83", trackTitle = "Midnight City", album = "Hurry Up, We're Dreaming",
                artworkUrl = "https://example.com/600x600.jpg", source = "itunes",
                durationMs = 243_000L, isrc = null, year = "2011", genre = "Electronic",
                trackNumber = 3, trackCount = 22, discNumber = 1, discCount = 2
            ) to 90
        )
        val grouped = groupReleases(deezerWin)
        assertEquals(1, grouped.size)
        val release = grouped[0]
        assertEquals("https://example.com/1000x1000.jpg", release.artworkUrl)
        assertEquals("FR01T1100371", release.isrc)
        assertEquals("2011", release.year)
        assertEquals("Electronic", release.genre)
        assertEquals(3, release.trackNumber)
        assertEquals(22, release.trackCount)
        assertEquals(1, release.discNumber)
        assertEquals(2, release.discCount)
    }

    @Test fun singleAndAlbumStaySeparateOptions() {
        val multi = ITunesCorrection(CorrectionFetcher { _ ->
            """{"resultCount":2,"results":[
              |{"artistName":"Strawberry Guy","trackName":"Mrs Magic","collectionName":"Mrs Magic - Single",
              |"artworkUrl100":"https://example.com/single.jpg","trackTimeMillis":209000},
              |{"artistName":"Strawberry Guy","trackName":"Mrs Magic","collectionName":"Sun Outside My Window",
              |"artworkUrl100":"https://example.com/album.jpg","trackTimeMillis":209000}]}""".trimMargin()
        })
        val s = YtSuggestionBuilder.build(YtExtract("Strawberry Guy - Mrs Magic", "chan", 209_000L), multi)!!
        assertEquals(2, s.options.size)
        assertEquals("Mrs Magic - Single", s.options[0].album)
        assertEquals("https://example.com/single.jpg", s.options[0].artworkUrl)
        assertEquals("Sun Outside My Window", s.options[1].album)
        assertEquals(5, groupReleases((1..7).map {
            CorrectionCandidate("A", "T$it", "Album$it", null, "itunes", null, null) to 100
        }).size.coerceAtMost(5))
    }

    @Test fun pickByIndexStampsChosenRelease() {
        val multi = ITunesCorrection(CorrectionFetcher { _ ->
            """{"resultCount":2,"results":[
              |{"artistName":"Strawberry Guy","trackName":"Mrs Magic","collectionName":"Mrs Magic - Single",
              |"artworkUrl100":"https://example.com/single.jpg","trackTimeMillis":209000},
              |{"artistName":"Strawberry Guy","trackName":"Mrs Magic","collectionName":"Sun Outside My Window",
              |"artworkUrl100":"https://example.com/album.jpg","trackTimeMillis":209000}]}""".trimMargin()
        })
        val s = YtSuggestionBuilder.build(YtExtract("Strawberry Guy - Mrs Magic", "chan", 209_000L), multi)!!
        val awaiting = QueueRow("1", "yt-url").afterYouTubeSuggest(s)
        val picked = awaiting.afterYouTubePick(s, 1)
        assertEquals(RowStatus.METADATA_READY, picked.status)
        assertEquals("Sun Outside My Window", picked.album)
        assertEquals("https://example.com/album.jpg", picked.coverUrl)
        val bad = awaiting.afterYouTubePick(s, 9)
        assertEquals(RowStatus.FAILED_METADATA, bad.status)
    }

    @Test fun pipeDelimitedTitleAndNoiseStripping() {
        val (a1, t1) = YtTitleSplitter.split("Stranger Things | Title Sequence [HD] | Netflix", "Stranger Things")
        assertEquals("", a1)
        assertEquals("Stranger Things", t1)

        val (a2, t2) = YtTitleSplitter.split("Stranger Things | Title Sequence [HD] | Netflix", "Netflix")
        assertEquals("", a2)
        assertEquals("Stranger Things", t2)

        val (a3, t3) = YtTitleSplitter.split("Stranger Things | Title Sequence [HD] | Netflix", "SomeFanChannel")
        assertEquals("SomeFanChannel", a3)
        assertEquals("Stranger Things", t3)
    }

    @Test fun soundtrackThemeMatchingAccommodatesTvCut() {
        val score = ITunesCorrection.comatchScore(
            query = "Stranger Things",
            artist = "Kyle Dixon & Michael Stein",
            track = "Stranger Things",
            candidateDurationMs = 67_500L,
            videoDurationMs = 53_000L,
            album = "Stranger Things, Vol. 1 (a Netflix Original Series Soundtrack)"
        )
        assertTrue("TV sequence cut (53s vs 67.5s master) with soundtrack album context must score >= 60", score >= 60)

        val unrelatedPopCoverScore = ITunesCorrection.comatchScore(
            query = "Stranger Things",
            artist = "Random Cover Band",
            track = "Stranger Things Cover",
            candidateDurationMs = 210_000L,
            videoDurationMs = 53_000L,
            album = "Covers Vol 2"
        )
        assertTrue("Unrelated cover with 3m+ duration must receive prohibitive duration penalty", unrelatedPopCoverScore < 0)
    }

    @Test fun threeProviderMergeKeepsLargestArtAndCombinesSources() {
        val scored = listOf(
            CorrectionCandidate(
                artist = "Kyle Dixon & Michael Stein",
                trackTitle = "Stranger Things",
                album = "Stranger Things, Vol. 1 (a Netflix Original Series Soundtrack)",
                artworkUrl = "https://i.scdn.co/image/ab67616d0000b273large",
                source = "spotify",
                durationMs = 67_500L,
                year = "2016"
            ) to 80,
            CorrectionCandidate(
                artist = "Kyle Dixon & Michael Stein",
                trackTitle = "Stranger Things",
                album = "Stranger Things, Vol. 1 (A Netflix Original Series Soundtrack)",
                artworkUrl = "https://example.com/600x600bb.jpg",
                source = "itunes",
                durationMs = 67_500L,
                genre = "Soundtrack"
            ) to 80,
            CorrectionCandidate(
                artist = "Kyle Dixon & Michael Stein",
                trackTitle = "Stranger Things",
                album = "Stranger Things, Vol. 1 (a Netflix Original Series Soundtrack)",
                artworkUrl = "https://example.com/cover_xl.jpg",
                source = "deezer",
                durationMs = 68_000L
            ) to 80
        )
        val grouped = groupReleases(scored)
        assertEquals(1, grouped.size)
        val release = grouped[0]
        assertEquals("https://example.com/cover_xl.jpg", release.artworkUrl)
        assertEquals(listOf("spotify", "itunes", "deezer"), release.sources)
        assertEquals("2016", release.year)
        assertEquals("Soundtrack", release.genre)
    }

    @Test fun builderFindsOfficialReleaseForStrangerThingsVideo() {
        val spotifyFake = object : CorrectionClient {
            override fun candidates(artistGuess: String, titleGuess: String): List<CorrectionCandidate> = listOf(
                CorrectionCandidate(
                    artist = "Kyle Dixon & Michael Stein",
                    trackTitle = "Stranger Things",
                    album = "Stranger Things, Vol. 1 (a Netflix Original Series Soundtrack)",
                    artworkUrl = "https://i.scdn.co/image/ab67616d0000b273large",
                    source = "spotify",
                    durationMs = 67_500L,
                    year = "2016"
                )
            )
        }
        val extract = YtExtract(
            videoTitle = "Stranger Things | Title Sequence [HD] | Netflix",
            channel = "Stranger Things",
            durationMs = 53_000L
        )
        val suggestion = YtSuggestionBuilder.build(extract, spotifyFake)
        assertNotNull(suggestion)
        assertEquals("Kyle Dixon & Michael Stein", suggestion!!.acceptedArtist())
        assertEquals("Stranger Things", suggestion.acceptedTitle())
        assertEquals("Stranger Things, Vol. 1 (a Netflix Original Series Soundtrack)", suggestion.acceptedAlbum())
        assertEquals("spotify", suggestion.correctedBy())
    }
}
