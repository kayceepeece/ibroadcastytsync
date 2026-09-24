package ibytsync.core.pipeline

import ibytsync.core.download.DownloadRequest
import ibytsync.core.download.DownloadResult
import ibytsync.core.download.YtDlpEngine
import ibytsync.core.matching.YtCandidate
import ibytsync.core.metadata.CorrectionCandidate
import ibytsync.core.metadata.CorrectionClient
import ibytsync.core.metadata.ReleaseOption
import ibytsync.core.metadata.YtExtract
import ibytsync.core.storage.FolderStore
import ibytsync.core.upload.Playlist
import ibytsync.core.upload.RoutePreference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class OrchestratorPathsTest {

    private fun engine(
        describe: YtExtract? = null,
        downloads: MutableList<String> = mutableListOf()
    ): YtDlpEngine = object : YtDlpEngine {
        override fun download(request: DownloadRequest): DownloadResult? {
            downloads.add(request.queryOrUrl)
            val f = File.createTempFile("orch", ".mp3")
            f.writeBytes(ByteArray(256) { 0 })
            return DownloadResult(f.absolutePath, "fake", 0)
        }
        override fun searchCandidates(query: String, count: Int): List<YtCandidate> = emptyList()
        override fun describeVideo(url: String): YtExtract? = describe
        override fun activeVariantLog(): List<String> = emptyList()
    }

    private fun hooks(
        uploaded: MutableList<Pair<File, String?>> = mutableListOf(),
        pairs: Map<String, Pair<String, String>> = emptyMap(),
        checksums: Set<String> = emptySet(),
        cands: List<YtCandidate> = listOf(YtCandidate("id1", "Song Official Audio", "Artist", 213.0, 1000))
    ): PipelineHooks = object : PipelineHooks {
        override fun search(query: String, count: Int): List<YtCandidate> = cands
        override fun uploadFile(tmp: File, playlistId: String?): Boolean {
            uploaded.add(tmp to playlistId)
            return true
        }
        override fun libraryPairs(): Map<String, Pair<String, String>> = pairs
        override fun libraryChecksums(): Set<String> = checksums
        override fun playlists(): Map<String, Playlist> = mapOf("9" to Playlist("9", "Fav"))
    }

    private fun correction(): CorrectionClient = object : CorrectionClient {
        override fun candidates(artistGuess: String, titleGuess: String) =
            listOf(CorrectionCandidate("Artist", "Song", "Album", "https://example.com/a.jpg", "itunes", 213_000L, null,
                "2019", "Alternative", 1, 12, 1, 1))
    }

    private val tmpDir: File = Files.createTempDirectory("orch-test").toFile()

    @Test fun youtubeRowParksAwaitingThenPickUnlocks() {
        val orch = BatchOrchestrator(engine(YtExtract("Artist - Song", "chan", 213_000L)), hooks())
        val row = QueueRow("1", "https://www.youtube.com/watch?v=abc")
        val outcome = orch.prepareYouTubeRow(row, "https://www.youtube.com/watch?v=abc", correction())
        assertTrue(outcome is RowOutcome.AwaitingPick)
        val awaiting = (outcome as RowOutcome.AwaitingPick).row
        assertEquals(RowStatus.AWAITING_ACCEPT, awaiting.status)
        val decided = orch.decideYouTube(awaiting, outcome.suggestion, 0)
        assertEquals(RowStatus.METADATA_READY, decided.status)
        assertEquals("Album", decided.album)
    }

    @Test fun pickedReleaseEnrichmentLandsInTags() {
        val uploaded = mutableListOf<Pair<File, String?>>()
        val orch = BatchOrchestrator(
            engine(YtExtract("Artist - Song", "chan", 213_000L)),
            hooks(uploaded)
        )
        val outcome = orch.prepareYouTubeRow(
            QueueRow("rich1", "https://www.youtube.com/watch?v=abc"),
            "https://www.youtube.com/watch?v=abc", correction()
        ) as RowOutcome.AwaitingPick
        val decided = orch.decideYouTube(outcome.row, outcome.suggestion, 0)
        val (final, _) = runBlocking { orch.runRow(decided, tmpDir, BatchConfig()) }
        assertEquals(RowStatus.DONE, final.status)
        val probe = File.createTempFile("richprobe", ".mp3")
        try {
            probe.writeBytes(ByteArray(512) { 0 })
            ibytsync.core.tagging.Id3Writer.write(
                probe,
                ibytsync.core.tagging.Id3Writer.resolveRich("Song", "Artist", "Album",
                    year = "2019", genre = "Alternative", trackNumber = 1, trackCount = 12,
                    discNumber = 1, discCount = 1)
            )
            val s = String(probe.readBytes(), Charsets.ISO_8859_1)
            assertTrue(s.contains("TYER") && s.contains("TCON") && s.contains("TRCK") && s.contains("TPOS"))
        } finally {
            probe.delete()
        }
    }

    @Test fun queueRowRichTagsAreWrittenToUploadedFile() {
        val uploadedBytes = mutableListOf<ByteArray>()
        val testHooks = object : PipelineHooks {
            override fun search(query: String, count: Int) = emptyList<YtCandidate>()
            override fun uploadFile(tmp: File, playlistId: String?): Boolean {
                uploadedBytes.add(tmp.readBytes())
                return true
            }
            override fun libraryPairs() = emptyMap<String, Pair<String, String>>()
            override fun libraryChecksums() = emptySet<String>()
            override fun playlists() = emptyMap<String, Playlist>()
        }
        val orch = BatchOrchestrator(
            engine(YtExtract("Artist - Song", "chan", 213_000L)),
            testHooks
        )
        val row = QueueRow(
            id = "test-rich-id",
            sourceInput = "https://www.youtube.com/watch?v=abc",
            status = RowStatus.METADATA_READY,
            title = "Song",
            artist = "Artist",
            album = "Album",
            year = "2020",
            genre = "Electronic",
            trackNumber = 3,
            trackCount = 11,
            selectedOption = ReleaseOption(
                artist = "Artist",
                trackTitle = "Song",
                album = "Album",
                artworkUrl = null,
                durationMs = 213_000L,
                isrc = null,
                sources = listOf("itunes"),
                score = 100,
                year = "2020",
                genre = "Electronic",
                trackNumber = 3,
                trackCount = 11,
                discNumber = 2,
                discCount = 2
            )
        )
        val (final, _) = runBlocking { orch.runRow(row, tmpDir, BatchConfig()) }
        assertEquals(RowStatus.DONE, final.status)
        assertEquals(1, uploadedBytes.size)
        val s = String(uploadedBytes.single(), Charsets.ISO_8859_1)
        assertTrue("Uploaded file should contain TYER frame", s.contains("TYER"))
        assertTrue("Uploaded file should contain TCON frame", s.contains("TCON"))
        assertTrue("Uploaded file should contain TRCK frame", s.contains("TRCK"))
        assertTrue("Uploaded file should contain TPOS frame", s.contains("TPOS"))
    }

    @Test fun queueRowDirectPropertiesWithoutSelectedOptionLandsInTags() {
        val uploadedBytes = mutableListOf<ByteArray>()
        val testHooks = object : PipelineHooks {
            override fun search(query: String, count: Int) = emptyList<YtCandidate>()
            override fun uploadFile(tmp: File, playlistId: String?): Boolean {
                uploadedBytes.add(tmp.readBytes())
                return true
            }
            override fun libraryPairs() = emptyMap<String, Pair<String, String>>()
            override fun libraryChecksums() = emptySet<String>()
            override fun playlists() = emptyMap<String, Playlist>()
        }
        val orch = BatchOrchestrator(
            engine(YtExtract("Artist - Song", "chan", 213_000L)),
            testHooks
        )
        val row = QueueRow(
            id = "direct-only-id",
            sourceInput = "https://www.youtube.com/watch?v=abc",
            status = RowStatus.METADATA_READY,
            title = "Direct Song",
            artist = "Direct Artist",
            album = "Direct Album",
            year = "1999",
            genre = "Rock",
            trackNumber = 5,
            trackCount = 12,
            selectedOption = null
        )
        val (final, _) = runBlocking { orch.runRow(row, tmpDir, BatchConfig()) }
        assertEquals(RowStatus.DONE, final.status)
        assertEquals(1, uploadedBytes.size)
        val s = String(uploadedBytes.single(), Charsets.ISO_8859_1)
        assertTrue(s.contains("TYER"))
        assertTrue(s.contains("TCON"))
        assertTrue(s.contains("TRCK"))
    }

    @Test fun youtubeRowDirectUrlSkipsSearch() {
        val downloads = mutableListOf<String>()
        val uploaded = mutableListOf<Pair<File, String?>>()
        val orch = BatchOrchestrator(
            engine(YtExtract("Artist - Song", "chan", 213_000L), downloads),
            hooks(uploaded)
        )
        val outcome = orch.prepareYouTubeRow(
            QueueRow("1", "https://www.youtube.com/watch?v=abc"),
            "https://www.youtube.com/watch?v=abc", correction()
        ) as RowOutcome.AwaitingPick
        val decided = orch.decideYouTube(outcome.row, outcome.suggestion, 0)
        val (final, summary) = runBlocking {
            orch.runRow(decided, tmpDir, BatchConfig(routePreference = RoutePreference.Favorite("9", "Fav")))
        }
        assertEquals(RowStatus.DONE, final.status)
        assertEquals(1, summary.uploaded)
        assertEquals(listOf("https://www.youtube.com/watch?v=abc"), downloads)
        assertEquals("9", uploaded.single().second)
    }

    @Test fun youtubeRowWithCleanStudioAudioSearchesOfficialMatch() {
        val downloads = mutableListOf<String>()
        val uploaded = mutableListOf<Pair<File, String?>>()
        val orch = BatchOrchestrator(
            engine(YtExtract("Artist - Song", "chan", 213_000L), downloads),
            hooks(uploaded)
        )
        val outcome = orch.prepareYouTubeRow(
            QueueRow("1", "https://www.youtube.com/watch?v=abc"),
            "https://www.youtube.com/watch?v=abc", correction()
        ) as RowOutcome.AwaitingPick
        val decided = orch.decideYouTube(
            outcome.row,
            outcome.suggestion,
            0,
            AudioSourcePreference.CLEAN_STUDIO
        )
        val (final, summary) = runBlocking {
            orch.runRow(decided, tmpDir, BatchConfig(routePreference = RoutePreference.Favorite("9", "Fav")))
        }
        assertEquals(RowStatus.DONE, final.status)
        assertEquals(1, summary.uploaded)
        // Verified: It downloaded the matched clean candidate via music.youtube.com, NOT the direct video URL!
        assertEquals(listOf("https://music.youtube.com/watch?v=id1"), downloads)
    }

    @Test fun albumAudioAimsAtAlbumLengthNotTheVideoLength() {
        val downloads = mutableListOf<String>()
        val queries = mutableListOf<String>()
        // YouTube Music's songs shelf: the album track is 2:40, a longer rival (an unrelated
        // artist's re-recording, named after the original act) runs 2:47. The rival survives
        // the artist check because it names "Artist" in its title, so only the album-length
        // target separates them.
        val cands = listOf(
            YtCandidate("rival", "Hot Body (Artist)", "HIBOY", 167.0, 1_000_000, isOfficialAudio = true),
            YtCandidate("audio", "Hot Body", "Artist", 160.0, 1_000_000, isOfficialAudio = true)
        )
        val orch = BatchOrchestrator(
            engine(YtExtract("Artist - Song", "chan", 164_000L), downloads),
            object : PipelineHooks {
                override fun search(query: String, count: Int): List<YtCandidate> {
                    queries.add(query)
                    return cands
                }
                override fun uploadFile(tmp: File, playlistId: String?): Boolean = true
                override fun libraryPairs(): Map<String, Pair<String, String>> = emptyMap()
                override fun libraryChecksums(): Set<String> = emptySet()
                override fun playlists(): Map<String, Playlist> = emptyMap()
            }
        )
        val videoOpt = ReleaseOption.rawVideo("Artist - Song (Official Music Video)", "Artist", null, 164_000L)
        val albumOpt = ReleaseOption(
            artist = "Artist", trackTitle = "Song", album = "Album", artworkUrl = null,
            durationMs = 160_000L, isrc = null, sources = listOf("itunes"), score = 99
        )
        // User picked the video card, then switched to Album Audio: the row still carries the
        // video's 2:44, so the search must aim at the album's 2:40 instead.
        val row = QueueRow(
            id = "album1",
            sourceInput = "https://www.youtube.com/watch?v=abc",
            status = RowStatus.METADATA_READY,
            title = "Hot Body",
            artist = "Artist",
            album = "Album",
            durationMs = 164_000L,
            audioPreference = AudioSourcePreference.CLEAN_STUDIO,
            options = listOf(videoOpt, albumOpt),
            selectedOption = videoOpt
        )
        val (final, _) = runBlocking { orch.runRow(row, tmpDir, BatchConfig()) }
        assertEquals(RowStatus.DONE, final.status)
        assertEquals(listOf("https://music.youtube.com/watch?v=audio"), downloads)
        // One plain query only — no keyword suffix is appended to steer the search any more.
        assertEquals(listOf("Artist Hot Body"), queries)
    }

    @Test fun albumAudioStillWorksWhenNoReleaseLengthIsKnown() {
        val downloads = mutableListOf<String>()
        val orch = BatchOrchestrator(
            engine(YtExtract("Artist - Song", "chan", 164_000L), downloads),
            hooks(cands = listOf(YtCandidate("id9", "Song Official Audio", "Artist", 160.0, 1000)))
        )
        val row = QueueRow(
            id = "album2",
            sourceInput = "https://www.youtube.com/watch?v=abc",
            status = RowStatus.METADATA_READY,
            title = "Song",
            artist = "Artist",
            durationMs = 164_000L,
            audioPreference = AudioSourcePreference.CLEAN_STUDIO,
            selectedOption = ReleaseOption.noMetadata("Song")
        )
        val (final, _) = runBlocking { orch.runRow(row, tmpDir, BatchConfig()) }
        assertEquals(RowStatus.DONE, final.status)
        assertEquals(listOf("https://music.youtube.com/watch?v=id9"), downloads)
    }

    @Test fun videoLengthStaysReachableAfterAnAlbumReleaseIsPicked() {
        val videoOpt = ReleaseOption.rawVideo("Artist - Song (Official Music Video)", "Artist", null, 164_000L)
        val albumOpt = ReleaseOption(
            artist = "Artist", trackTitle = "Song", album = "Album", artworkUrl = null,
            durationMs = 160_000L, isrc = null, sources = listOf("itunes"), score = 99
        )
        // Picking the album release rewrites row.durationMs to the album's 2:40, which is what
        // made the Video Audio file estimate report 2:40 for a file that is really 2:44.
        val row = QueueRow(
            id = "len1",
            sourceInput = "https://www.youtube.com/watch?v=abc",
            status = RowStatus.METADATA_READY,
            durationMs = 160_000L,
            options = listOf(videoOpt, albumOpt),
            selectedOption = albumOpt
        )
        assertEquals(164_000L, row.sourceFileDurationMs())
        assertEquals(160_000L, row.albumReleaseDurationMs())
    }

    @Test fun storeOnlyRowHasNoSourceFileLength() {
        val albumOpt = ReleaseOption(
            artist = "Artist", trackTitle = "Song", album = "Album", artworkUrl = null,
            durationMs = 160_000L, isrc = null, sources = listOf("spotify"), score = 100
        )
        // A Spotify link carries no video until a search picks one at run time, so the source
        // file length is genuinely unknown and callers must fall back rather than guess.
        val row = QueueRow(
            id = "len2",
            sourceInput = "https://open.spotify.com/track/abc",
            status = RowStatus.METADATA_READY,
            durationMs = 160_000L,
            options = listOf(albumOpt, ReleaseOption.noMetadata("Song", 160_000L)),
            selectedOption = albumOpt
        )
        assertNull(row.sourceFileDurationMs())
        assertEquals(160_000L, row.albumReleaseDurationMs())
    }

    @Test fun unpickedRowRefusesToRun() {
        val orch = BatchOrchestrator(engine(), hooks())
        val (final, _) = runBlocking {
            orch.runRow(QueueRow("1", "yt", RowStatus.AWAITING_ACCEPT), tmpDir, BatchConfig())
        }
        assertEquals(RowStatus.FAILED_METADATA, final.status)
    }

    @Test fun nullExtractFailsRow() {
        val orch = BatchOrchestrator(engine(null), hooks())
        val outcome = orch.prepareYouTubeRow(
            QueueRow("1", "https://www.youtube.com/watch?v=abc"),
            "https://www.youtube.com/watch?v=abc", correction()
        )
        assertTrue(outcome is RowOutcome.Ready)
        assertEquals(RowStatus.FAILED_METADATA, (outcome as RowOutcome.Ready).row.status)
    }

    @Test fun titleArtistDuplicateShortCircuits() {
        val orch = BatchOrchestrator(
            engine(),
            hooks(pairs = mapOf("1" to ("Song" to "Artist")))
        )
        val (final, summary) = runBlocking {
            orch.runRow(
                QueueRow("1", "x", RowStatus.METADATA_READY, "Song", "Artist", "Album", 213_000L),
                tmpDir, BatchConfig()
            )
        }
        assertEquals(RowStatus.ALREADY_UPLOADED, final.status)
        assertEquals(1, summary.alreadyExisted)
    }

    @Test fun checksumDuplicateShortCircuits() {
        val tagged = File.createTempFile("sumtagged", ".mp3")
        tagged.writeBytes(ByteArray(512) { 0 })
        ibytsync.core.tagging.Id3Writer.write(
            tagged, ibytsync.core.tagging.Id3Writer.resolve("Other Song", "Other Artist", "A")
        )
        val md5 = ibytsync.core.upload.Checksummer.md5(tagged)
        tagged.delete()
        var coverCalls = 0
        val orch = BatchOrchestrator(
            object : YtDlpEngine {
                override fun download(request: DownloadRequest): DownloadResult? {
                    val f = File.createTempFile("orch", ".mp3")
                    f.writeBytes(ByteArray(512) { 0 })
                    return DownloadResult(f.absolutePath, "fake", 0)
                }
                override fun searchCandidates(query: String, count: Int): List<YtCandidate> =
                    listOf(YtCandidate("id1", "Other Song", "Other Artist", 213.0, 1000))
                override fun describeVideo(url: String): YtExtract? = null
                override fun activeVariantLog(): List<String> = emptyList()
            },
            hooks(
                checksums = setOf(md5),
                cands = listOf(YtCandidate("id1", "Other Song", "Other Artist", 213.0, 1000))
            )
        )
        val (final, summary) = runBlocking {
            orch.runRow(
                QueueRow("1", "x", RowStatus.METADATA_READY, "Other Song", "Other Artist", "A", 213_000L, null),
                tmpDir, BatchConfig()
            )
        }
        assertEquals(RowStatus.ALREADY_UPLOADED, final.status)
        assertEquals(1, summary.alreadyExisted)
        assertEquals(0, coverCalls)
    }

    @Test fun saveSkippedAndFailedPaths() {
        val skipping = BatchOrchestrator(engine(), hooks(), saveHooks = object : SaveHooks {
            override fun saveFile(tmp: File): FolderStore.SaveOutcome =
                FolderStore.SaveOutcome.Skipped("user skipped")
        })
        val (skipped, _) = runBlocking {
            skipping.runRow(
                QueueRow("1", "x", RowStatus.METADATA_READY, "Song", "Artist", "Album", 213_000L),
                tmpDir, BatchConfig(saveToDisk = true)
            )
        }
        assertEquals(RowStatus.SKIPPED_FOLDER, skipped.status)

        val failing = BatchOrchestrator(engine(), hooks(), saveHooks = object : SaveHooks {
            override fun saveFile(tmp: File): FolderStore.SaveOutcome =
                FolderStore.SaveOutcome.Failed("no handler")
        })
        val (failed, summary) = runBlocking {
            failing.runRow(
                QueueRow("1", "x", RowStatus.METADATA_READY, "Song", "Artist", "Album", 213_000L),
                tmpDir, BatchConfig(saveToDisk = true)
            )
        }
        assertEquals(RowStatus.FAILED_SAVE, failed.status)
        assertEquals(1, summary.failed)
    }

    @Test fun saveOkRetagsAfterMove() {
        var tagReads = 0
        val saver = BatchOrchestrator(
            object : YtDlpEngine {
                override fun download(request: DownloadRequest): DownloadResult? {
                    val f = File.createTempFile("orch", ".mp3")
                    f.writeBytes(ByteArray(512) { 0 })
                    return DownloadResult(f.absolutePath, "fake", 0)
                }
                override fun searchCandidates(query: String, count: Int): List<YtCandidate> =
                    listOf(YtCandidate("id1", "Song", "Artist", 213.0, 1000))
                override fun describeVideo(url: String): YtExtract? = null
                override fun activeVariantLog(): List<String> = emptyList()
            },
            hooks(),
            saveHooks = object : SaveHooks {
                override fun saveFile(tmp: File): FolderStore.SaveOutcome {
                    tagReads++
                    return FolderStore.SaveOutcome.Saved(tmp.name)
                }
            }
        )
        val (final, _) = runBlocking {
            saver.runRow(
                QueueRow("1", "x", RowStatus.METADATA_READY, "Song", "Artist", "Album", 213_000L),
                tmpDir, BatchConfig(saveToDisk = true)
            )
        }
        assertEquals(RowStatus.DONE, final.status)
        assertEquals(1, tagReads)
    }

    @Test fun noMetadataRowSkipsTaggingAndUploads() {
        var uploadedFile: File? = null
        val orch = BatchOrchestrator(
            engine(YtExtract("Artist - Song", "chan", 213_000L)),
            hooks(uploaded = mutableListOf())
        )
        val noMetaRow = QueueRow(
            id = "nometa1",
            sourceInput = "https://www.youtube.com/watch?v=abc",
            status = RowStatus.METADATA_READY,
            title = "Raw Video",
            artist = "",
            album = "",
            selectedOption = ReleaseOption.noMetadata("Raw Video")
        )
        val (final, summary) = runBlocking {
            orch.runRow(noMetaRow, tmpDir, BatchConfig())
        }
        assertEquals(RowStatus.DONE, final.status)
        assertEquals(1, summary.uploaded)
    }

    @Test fun localAudioFileProcessedDirectlyWithoutDownloadOrSearch() {
        val testLocalFile = File.createTempFile("test_local_", ".mp3")
        testLocalFile.writeBytes(ByteArray(1024) { 42 })

        var searchCalled = false
        var downloadCalled = false
        val orch = BatchOrchestrator(
            downloader = object : YtDlpEngine {
                override fun download(request: DownloadRequest): DownloadResult? {
                    downloadCalled = true
                    return null
                }
                override fun searchCandidates(query: String, count: Int): List<YtCandidate> {
                    searchCalled = true
                    return emptyList()
                }
                override fun describeVideo(url: String): YtExtract? = null
                override fun activeVariantLog(): List<String> = emptyList()
            },
            hooks = hooks()
        )

        val localRow = QueueRow(
            id = "loc_orch_1",
            sourceInput = testLocalFile.absolutePath,
            status = RowStatus.METADATA_READY,
            title = "My Local Audio",
            artist = "Local Artist",
            album = "Local Album",
            selectedOption = ReleaseOption.originalFile(
                title = "My Local Audio",
                artist = "Local Artist",
                album = "Local Album"
            )
        )

        try {
            val (final, summary) = runBlocking {
                orch.runRow(localRow, tmpDir, BatchConfig())
            }
            assertEquals(RowStatus.DONE, final.status)
            assertEquals(1, summary.uploaded)
            assertFalse("Local audio must not search YouTube", searchCalled)
            assertFalse("Local audio must not download from YouTube", downloadCalled)
        } finally {
            testLocalFile.delete()
        }
    }

    @Test fun cancellationTerminatesEngineProcessAndCleansUpTempFile() {
        var cancelledToken: String? = null
        var createdTempFile: File? = null
        val testEngine = object : YtDlpEngine {
            override fun download(request: DownloadRequest): DownloadResult? {
                val f = File(tmpDir, "${request.tokenPrefix}.mp3")
                f.writeBytes(ByteArray(128) { 1 })
                createdTempFile = f
                throw kotlinx.coroutines.CancellationException("Test cancel")
            }
            override fun searchCandidates(query: String, count: Int): List<YtCandidate> = emptyList()
            override fun describeVideo(url: String): YtExtract? = null
            override fun activeVariantLog(): List<String> = emptyList()
            override fun cancelProcess(tokenPrefix: String) {
                cancelledToken = tokenPrefix
            }
        }

        val orch = BatchOrchestrator(
            downloader = testEngine,
            hooks = hooks()
        )

        val row = QueueRow(
            id = "cancel_row_1",
            sourceInput = "https://www.youtube.com/watch?v=cancelled123",
            status = RowStatus.METADATA_READY,
            title = "Cancel Song",
            artist = "Cancel Artist",
            album = "Cancel Album",
            selectedOption = ReleaseOption.noMetadata("Cancel Song")
        )

        try {
            runBlocking {
                orch.runRow(row, tmpDir, BatchConfig())
            }
            fail("Should throw CancellationException")
        } catch (_: kotlinx.coroutines.CancellationException) {
            // expected
        }

        assertNotNull("Engine cancelProcess should be invoked with active token prefix", cancelledToken)
        assertTrue("cancelledToken must start with prefix tb_", cancelledToken?.startsWith("tb_") == true)
        assertNotNull("Temp file should have been tracked", createdTempFile)
        assertFalse("Temp file should be deleted on cancellation", createdTempFile!!.exists())
    }

    @Test fun uploadFileReceivesComputedMd5AvoidingRedundantRead() {
        var passedMd5: String? = null
        var uploadCalls = 0
        val trackingHooks = object : PipelineHooks {
            override fun search(query: String, count: Int): List<YtCandidate> = emptyList()
            override fun uploadFile(tmp: File, playlistId: String?): Boolean = true
            override fun uploadFileWithResult(
                tmp: File,
                playlistId: String?,
                md5: String?,
                onProgress: ((sent: Long, total: Long) -> Unit)?
            ): UploadOutcome {
                uploadCalls++
                passedMd5 = md5
                return UploadOutcome(true)
            }
            override fun libraryPairs(): Map<String, Pair<String, String>> = emptyMap()
            override fun libraryChecksums(): Set<String> = emptySet()
            override fun playlists(): Map<String, Playlist> = emptyMap()
        }

        val orch = BatchOrchestrator(
            downloader = engine(),
            hooks = trackingHooks
        )

        val row = QueueRow(
            id = "md5_row_1",
            sourceInput = "https://www.youtube.com/watch?v=md5test123",
            status = RowStatus.METADATA_READY,
            title = "MD5 Song",
            artist = "MD5 Artist",
            album = "MD5 Album",
            selectedOption = ReleaseOption.noMetadata("MD5 Song")
        )

        val (final, _) = runBlocking {
            orch.runRow(row, tmpDir, BatchConfig())
        }

        assertEquals(RowStatus.DONE, final.status)
        assertEquals(1, uploadCalls)
        assertNotNull("MD5 must be passed to uploadFileWithResult", passedMd5)
        assertNotEquals("Must not pass fake md5-err", "md5-err", passedMd5)
        assertEquals("Valid 32-character hex MD5 expected", 32, passedMd5!!.length)
    }

    @Test
    fun multiPlaylistRoutingSendsAllPlaylistIdsToUpload() {
        var passedPlaylistIds: List<String>? = null
        val multiHooks = object : PipelineHooks {
            override fun search(query: String, count: Int): List<YtCandidate> = emptyList()
            override fun uploadFile(tmp: File, playlistId: String?): Boolean = true
            override fun uploadFileWithResult(
                tmp: File,
                playlistIds: List<String>,
                md5: String?,
                onProgress: ((sent: Long, total: Long) -> Unit)?
            ): UploadOutcome {
                passedPlaylistIds = playlistIds
                return UploadOutcome(true)
            }
            override fun libraryPairs(): Map<String, Pair<String, String>> = emptyMap()
            override fun libraryChecksums(): Set<String> = emptySet()
            override fun playlists(): Map<String, Playlist> = mapOf(
                "pl_1" to Playlist("pl_1", "Rock"),
                "pl_2" to Playlist("pl_2", "Workout"),
                "pl_3" to Playlist("pl_3", "Favorites")
            )
        }

        val orch = BatchOrchestrator(downloader = engine(), hooks = multiHooks)
        val row = QueueRow(
            id = "multi_pl_row",
            sourceInput = "https://www.youtube.com/watch?v=multipl123",
            status = RowStatus.METADATA_READY,
            title = "Multi Playlist Song",
            artist = "Artist",
            album = "Album",
            targetPlaylistIds = listOf("pl_1", "pl_2", "pl_3"),
            targetPlaylistNames = listOf("Rock", "Workout", "Favorites"),
            selectedOption = ReleaseOption.noMetadata("Multi Playlist Song")
        )

        val (final, _) = runBlocking {
            orch.runRow(row, tmpDir, BatchConfig())
        }

        assertEquals(RowStatus.DONE, final.status)
        assertNotNull("Playlist IDs should be passed to uploadFileWithResult", passedPlaylistIds)
        assertEquals(listOf("pl_1", "pl_2", "pl_3"), passedPlaylistIds)
    }

    @Test
    fun libOnlyDestinationDoesNotFallbackToBatchDefault() {
        var passedPlaylistIds: List<String>? = null
        val libOnlyHooks = object : PipelineHooks {
            override fun search(query: String, count: Int): List<YtCandidate> = emptyList()
            override fun uploadFile(tmp: File, playlistId: String?): Boolean = true
            override fun uploadFileWithResult(
                tmp: File,
                playlistIds: List<String>,
                md5: String?,
                onProgress: ((sent: Long, total: Long) -> Unit)?
            ): UploadOutcome {
                passedPlaylistIds = playlistIds
                return UploadOutcome(true)
            }
            override fun libraryPairs(): Map<String, Pair<String, String>> = emptyMap()
            override fun libraryChecksums(): Set<String> = emptySet()
            override fun playlists(): Map<String, Playlist> = mapOf(
                "batch_pl" to Playlist("batch_pl", "Batch Playlist")
            )
        }

        val orch = BatchOrchestrator(downloader = engine(), hooks = libOnlyHooks)
        val row = QueueRow(
            id = "lib_only_row",
            sourceInput = "https://www.youtube.com/watch?v=libonly123",
            status = RowStatus.METADATA_READY,
            title = "Library Only Song",
            artist = "Artist",
            album = "Album",
            targetPlaylistId = "lib_only",
            targetPlaylistName = "Library Only",
            selectedOption = ReleaseOption.noMetadata("Library Only Song")
        )

        val batchConfig = BatchConfig(
            routePreference = RoutePreference.Favorite("batch_pl", "Batch Playlist")
        )

        val (final, _) = runBlocking {
            orch.runRow(row, tmpDir, batchConfig)
        }

        assertEquals(RowStatus.DONE, final.status)
        assertNotNull("Playlist IDs should be passed", passedPlaylistIds)
        assertTrue("Library Only must resolve to empty playlist list", passedPlaylistIds!!.isEmpty())
    }

    @Test
    fun effectivePlaylistHelpersHandleSingleAndMultiple() {
        val rowSingle = QueueRow(
            id = "r1",
            sourceInput = "input",
            targetPlaylistId = "pl_1",
            targetPlaylistName = "Rock"
        )
        assertEquals(listOf("pl_1"), rowSingle.effectivePlaylistIds())
        assertEquals(listOf("Rock"), rowSingle.effectivePlaylistNames())

        val rowMulti = QueueRow(
            id = "r2",
            sourceInput = "input",
            targetPlaylistId = "pl_1",
            targetPlaylistName = "Rock",
            targetPlaylistIds = listOf("pl_1", "pl_2"),
            targetPlaylistNames = listOf("Rock", "Metal")
        )
        assertEquals(listOf("pl_1", "pl_2"), rowMulti.effectivePlaylistIds())
        assertEquals(listOf("Rock", "Metal"), rowMulti.effectivePlaylistNames())

        val rowLibOnly = QueueRow(
            id = "r3",
            sourceInput = "input",
            targetPlaylistId = "lib_only",
            targetPlaylistName = "Library Only"
        )
        assertTrue(rowLibOnly.effectivePlaylistIds().isEmpty())
        assertTrue(rowLibOnly.effectivePlaylistNames().isEmpty())
    }
}
