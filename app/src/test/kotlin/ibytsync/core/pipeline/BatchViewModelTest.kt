package ibytsync.core.pipeline

import ibytsync.core.download.DownloadRequest
import ibytsync.core.download.DownloadResult
import ibytsync.core.download.YtDlpEngine
import ibytsync.core.matching.YtCandidate
import ibytsync.core.metadata.CorrectionFetcher
import ibytsync.core.metadata.ITunesCorrection
import ibytsync.core.metadata.ReleaseOption
import ibytsync.core.metadata.SpotifyPageFetcher
import ibytsync.core.metadata.SpotifyScraper
import ibytsync.core.metadata.YtExtract
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.*
import org.junit.Test

class BatchViewModelTest {

    private class FakeEngine(
        var extract: YtExtract? = null
    ) : YtDlpEngine {
        override fun download(request: DownloadRequest): DownloadResult? = null
        override fun searchCandidates(query: String, count: Int): List<YtCandidate> = emptyList()
        override fun describeVideo(url: String): YtExtract? = extract
        override fun activeVariantLog(): List<String> = emptyList()
    }

    private fun trackHtml(): String = """
        <html><head>
        <meta property="og:title" content="Mrs Magic">
        <meta property="og:description" content="Strawberry Guy · Sun Outside My Window · Song · 2019">
        <meta property="og:image" content="https://i.scdn.co/image/xyz">
        <meta name="music:musician_description" content="Strawberry Guy">
        <meta name="music:duration" content="209">
        </head><body></body></html>
    """.trimIndent()

    @Test
    fun testAddInputInvalidUrlImmediatelySkipped() {
        val vm = BatchViewModel()
        vm.addInput("invalid url text")
        val rows = vm.rows.value
        assertEquals(1, rows.size)
        assertEquals(RowStatus.SKIPPED_BAD_URL, rows[0].status)
        assertFalse(vm.canStart())
    }

    @Test
    fun testAddLocalFileReadyAndStarts() {
        val vm = BatchViewModel()
        vm.addLocalFile(
            sourcePathOrUri = "file:///sdcard/Music/test.mp3",
            title = "Test Song",
            artist = "Test Artist",
            album = "Test Album"
        )
        val rows = vm.rows.value
        assertEquals(1, rows.size)
        assertEquals(RowStatus.METADATA_READY, rows[0].status)
        assertEquals("Test Song", rows[0].title)
        assertTrue(vm.canStart())
    }

    @Test
    fun testSpotifyTrackResolutionAsync() = runBlocking {
        val scraper = SpotifyScraper(SpotifyPageFetcher { trackHtml() })
        val resolver = MetadataResolver(scraper, FakeEngine())
        val vm = BatchViewModel(resolver)

        vm.addInput("https://open.spotify.com/track/4cOdK2wGLETKBW3PvgPWqT")

        val ready = withTimeoutOrNull(5000) {
            while (true) {
                val row = vm.rows.value.firstOrNull()
                if (row != null && row.status == RowStatus.METADATA_READY) return@withTimeoutOrNull row
                delay(50)
            }
            null
        }

        assertNotNull("Row did not resolve in time", ready)
        assertEquals(RowStatus.METADATA_READY, ready!!.status)
        assertEquals("Mrs Magic", ready.title)
        assertEquals("Strawberry Guy", ready.artist)
        assertEquals("Sun Outside My Window", ready.album)
        assertTrue(vm.canStart())
    }

    @Test
    fun testSelectReleaseAndAudioPreference() {
        val vm = BatchViewModel()
        vm.addInput("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        val rowId = vm.rows.value[0].id

        val option = ReleaseOption(
            artist = "Rick Astley",
            trackTitle = "Never Gonna Give You Up",
            album = "Whenever You Need Somebody",
            artworkUrl = "https://example.com/cover.jpg",
            durationMs = 213000,
            isrc = "GBARL8700014",
            sources = listOf("itunes"),
            score = 99,
            year = "1987",
            genre = "Dance-pop",
            trackNumber = 1,
            trackCount = 10
        )

        vm.selectRelease(rowId, option, AudioSourcePreference.CLEAN_STUDIO)
        val updated = vm.rows.value.first { it.id == rowId }
        assertEquals(RowStatus.METADATA_READY, updated.status)
        assertEquals("Never Gonna Give You Up", updated.title)
        assertEquals("Rick Astley", updated.artist)
        assertEquals("Whenever You Need Somebody", updated.album)
        assertEquals(AudioSourcePreference.CLEAN_STUDIO, updated.audioPreference)
        assertEquals("https://example.com/cover.jpg", updated.coverUrl)
        assertTrue(vm.canStart())

        vm.updateAudioPreference(rowId, AudioSourcePreference.ORIGINAL_VIDEO)
        val afterAudioSwitch = vm.rows.value.first { it.id == rowId }
        assertEquals(AudioSourcePreference.ORIGINAL_VIDEO, afterAudioSwitch.audioPreference)
        assertTrue(afterAudioSwitch.detail.contains("Video Audio"))
    }

    @Test
    fun testManualEditArtworkAndMetadata() {
        val vm = BatchViewModel()
        vm.addInput("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        val rowId = vm.rows.value[0].id

        val option = ReleaseOption(
            artist = "Custom Artist",
            trackTitle = "Custom Title",
            album = "Custom Album",
            artworkUrl = "https://example.com/initial.jpg",
            durationMs = 180000,
            isrc = null,
            sources = listOf("custom"),
            score = 100,
            year = "2024",
            genre = "Indie"
        )
        vm.manualEditMetadata(rowId, option)
        val metaUpdated = vm.rows.value.first { it.id == rowId }
        assertEquals("Custom Title", metaUpdated.title)
        assertEquals("Custom Artist", metaUpdated.artist)
        assertEquals("Custom Album", metaUpdated.album)

        vm.manualEditArtwork(rowId, "content://media/picker/12345")
        val artUpdated = vm.rows.value.first { it.id == rowId }
        assertEquals("content://media/picker/12345", artUpdated.coverUrl)
        assertTrue(artUpdated.detail.contains("Custom Edited") || artUpdated.detail.contains("Custom Artwork"))
    }

    @Test
    fun testLocalFileResolutionAsyncWithStoreMatch() = runBlocking {
        val itunes = ITunesCorrection(CorrectionFetcher { _ ->
            """{"resultCount":1,"results":[
              |{"artistName":"Strawberry Guy","trackName":"Mrs Magic","collectionName":"Sun Outside My Window",
              |"artworkUrl100":"https://example.com/100x100bb.jpg","trackTimeMillis":209000}]}""".trimMargin()
        })
        val resolver = MetadataResolver(
            scraper = SpotifyScraper(SpotifyPageFetcher { null }),
            downloader = FakeEngine(),
            correctionClients = listOf(itunes)
        )
        val vm = BatchViewModel(resolver)

        vm.addLocalFile(
            sourcePathOrUri = "file:///sdcard/Music/mrs_magic.mp3",
            title = "Mrs Magic",
            artist = "Strawberry Guy",
            album = "",
            durationMs = 209000L
        )

        val awaiting = withTimeoutOrNull(5000) {
            while (true) {
                val row = vm.rows.value.firstOrNull()
                if (row != null && row.status == RowStatus.AWAITING_ACCEPT) return@withTimeoutOrNull row
                delay(50)
            }
            null
        }

        assertNotNull("Local file should resolve to AWAITING_ACCEPT when store match is found", awaiting)
        assertEquals(RowStatus.AWAITING_ACCEPT, awaiting!!.status)
        assertEquals("Mrs Magic", awaiting.title)
        assertEquals("Strawberry Guy", awaiting.artist)
        assertEquals("Sun Outside My Window", awaiting.album)
        assertTrue("Should contain store options + original tags + no metadata", awaiting.options.size >= 3)
        assertTrue(awaiting.options.any { it.isNoMetadata })
        assertTrue(awaiting.options.any { it.isOriginalSource })

        // User accepts the top suggested official release
        vm.acceptCurrentOption(awaiting.id)
        val accepted = vm.rows.value.first { it.id == awaiting.id }
        assertEquals(RowStatus.METADATA_READY, accepted.status)
        assertTrue(vm.canStart())
    }

    @Test
    fun testSelectNoMetadataAcrossSurfaces() {
        val vm = BatchViewModel()
        vm.addInput("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        val rowId = vm.rows.value[0].id

        vm.selectNoMetadata(rowId)
        val untagged = vm.rows.value.first { it.id == rowId }
        assertEquals(RowStatus.METADATA_READY, untagged.status)
        assertEquals("", untagged.artist)
        assertEquals("", untagged.album)
        assertNull(untagged.coverUrl)
        assertTrue(untagged.selectedOption?.isNoMetadata == true)
        // The no-tags state must be carried structurally, not by matching display text.
        assertEquals("No tags", untagged.detail)
        assertTrue("No metadata row must unblock the batch start gate", vm.canStart())
    }

    @Test
    fun testPreferencesPersistWithoutDecidingTheMatch() {
        val vm = BatchViewModel()
        vm.addInput("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        val rowId = vm.rows.value[0].id

        // A preference must survive being set on an undecided row...
        vm.stageDestination(rowId, "pl_42", "Late Night")
        vm.stageAudioChoice(rowId, AudioSourcePreference.CLEAN_STUDIO, AudioFormatChoice.MP3_320K, true)
        val staged = vm.rows.value.first { it.id == rowId }
        assertEquals("pl_42", staged.targetPlaylistId)
        assertEquals("Late Night", staged.targetPlaylistName)
        assertEquals(AudioSourcePreference.CLEAN_STUDIO, staged.audioPreference)
        assertEquals(AudioFormatChoice.MP3_320K, staged.audioFormat)
        assertTrue(staged.forceUpload)

        // ...without deciding the match, which is what the NEEDS REVIEW guard depends on. The
        // exact status is incidental (the row is still resolving); not being decided is not.
        assertNotEquals(RowStatus.METADATA_READY, staged.status)
        assertFalse("Staging a preference must not unblock the batch", vm.canStart())
    }

    @Test
    fun testStagingBatchDefaultClearsAnEarlierPlaylist() {
        val vm = BatchViewModel()
        vm.addInput("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        val rowId = vm.rows.value[0].id

        vm.stageDestination(rowId, "pl_42", "Late Night")
        assertEquals("pl_42", vm.rows.value.first { it.id == rowId }.targetPlaylistId)

        // "Use batch default" is expressed as a null id with a descriptive name, so a
        // leave-alone-on-null implementation would silently keep the old playlist.
        vm.stageDestination(rowId, null, "Batch Default (Liked Songs)")
        val cleared = vm.rows.value.first { it.id == rowId }
        assertNull(cleared.targetPlaylistId)
        assertEquals("Batch Default (Liked Songs)", cleared.targetPlaylistName)
    }

    @Test
    fun testCommitMatchDecidesTheRowAndLeavesPreferencesAlone() {
        val vm = BatchViewModel()
        vm.addInput("https://www.youtube.com/watch?v=dQw4w9WgXcQ")
        val rowId = vm.rows.value[0].id
        vm.stageDestination(rowId, "pl_7", "Focus")
        vm.stageAudioChoice(rowId, AudioSourcePreference.CLEAN_STUDIO)

        val option = ReleaseOption(
            artist = "Rick Astley",
            trackTitle = "Never Gonna Give You Up",
            album = "Whenever You Need Somebody",
            artworkUrl = null,
            durationMs = 213_000L,
            isrc = null,
            sources = listOf("itunes"),
            score = 99
        )
        vm.commitMatch(rowId, option)
        val committed = vm.rows.value.first { it.id == rowId }

        assertEquals(RowStatus.METADATA_READY, committed.status)
        assertEquals(option, committed.selectedOption)
        assertTrue("Committing the match must unblock the batch", vm.canStart())
        // Preferences survive the commit rather than being rewritten from stale sheet state.
        assertEquals("pl_7", committed.targetPlaylistId)
        assertEquals(AudioSourcePreference.CLEAN_STUDIO, committed.audioPreference)
    }

    @Test
    fun testDownloaderReadyStateBlocksAndUnblocksBatch() {
        val vm = BatchViewModel()
        vm.addLocalFile(
            sourcePathOrUri = "file:///sdcard/Music/test.mp3",
            title = "Test Song",
            artist = "Test Artist",
            album = "Test Album"
        )
        assertTrue(vm.isDownloaderReady.value)
        assertTrue(vm.canStart())

        vm.setDownloaderReady(false)
        assertFalse(vm.isDownloaderReady.value)
        assertFalse("Batch should not start when downloader is not ready", vm.canStart())

        vm.setDownloaderReady(true)
        assertTrue(vm.isDownloaderReady.value)
        assertTrue(vm.canStart())
    }
}
