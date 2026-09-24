package ibytsync.core.pipeline

import ibytsync.core.metadata.ReleaseOption
import ibytsync.core.storage.DiskStore
import ibytsync.core.upload.IBroadcastOAuth
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class Phase6SyncedTest {

    @Test
    fun testStartReSyncCreatesStagedRowWithReplacementId() {
        val vm = BatchViewModel()
        val syncedTrack = QueueRow(
            id = "synced_123",
            sourceInput = "https://www.youtube.com/watch?v=abc",
            status = RowStatus.DONE,
            title = "Test Track",
            artist = "Test Artist",
            album = "Test Album",
            audioFormat = AudioFormatChoice.MP3_320K,
            ibroadcastTrackId = "ib_987654",
            targetPlaylistName = "Favorites"
        )

        val staged = vm.startReSync(syncedTrack)
        val activeRows = vm.rows.value

        assertEquals(1, activeRows.size)
        assertEquals(staged.id, activeRows[0].id)
        assertNotEquals("synced_123", staged.id)
        assertEquals("ib_987654", staged.replacesUploadedTrackId)
        assertTrue("re-sync must bypass dup gate", staged.forceUpload)
        assertEquals(RowStatus.METADATA_READY, staged.status)
        assertEquals(AudioFormatChoice.MP3_320K, staged.audioFormat)
        assertEquals("Test Track", staged.title)
        assertEquals("Test Artist", staged.artist)
        assertEquals("Favorites", staged.targetPlaylistName)
        assertTrue(staged.detail.contains("Staged for Re-Sync"))
    }

    @Test
    fun testRemoveRowReturnsReplacedIdForUnGrey() {
        val vm = BatchViewModel()
        val syncedTrack = QueueRow(
            id = "synced_456",
            sourceInput = "https://www.youtube.com/watch?v=def",
            status = RowStatus.DONE,
            ibroadcastTrackId = "ib_112233"
        )

        val staged = vm.startReSync(syncedTrack)
        assertEquals(1, vm.rows.value.size)

        val removedReplacedId = vm.removeRow(staged.id)
        assertEquals("ib_112233", removedReplacedId)
        assertTrue(vm.rows.value.isEmpty())
    }

    @Test
    fun testResyncChangeKindMatrix() {
        val base = QueueRow("s", "https://www.youtube.com/watch?v=abc", RowStatus.DONE, "T", "A", "Al", 200_000L, "cover1")
        assertEquals(ResyncChange.IDENTICAL, ResyncDiff.changeKind(base, base))
        assertEquals(ResyncChange.TAGS_CHANGED, ResyncDiff.changeKind(base.copy(coverUrl = "cover2"), base))
        assertEquals(ResyncChange.TAGS_CHANGED, ResyncDiff.changeKind(base.copy(title = "T2"), base))
        assertEquals(ResyncChange.SOURCE_CHANGED, ResyncDiff.changeKind(base.copy(sourceInput = "https://www.youtube.com/watch?v=xyz"), base))
        assertEquals(ResyncChange.SOURCE_CHANGED, ResyncDiff.changeKind(base.copy(durationMs = 300_000L), base))
    }

    @Test
    fun testExtractTrackIdFromUploadResponse() {
        val sampleResponse = """{"result": true, "message": "song.mp3 (87654321) uploaded successfully"}"""
        val extractedId = IBroadcastOAuth.extractTrackId(sampleResponse)
        assertEquals("87654321", extractedId)

        val noIdResponse = """{"result": false, "message": "Failed upload"}"""
        assertNull(IBroadcastOAuth.extractTrackId(noIdResponse))
    }

    @Test
    fun testDiskStorePhase6FieldsSerialization() {
        val row = QueueRow(
            id = "row_phase6",
            sourceInput = "https://www.youtube.com/watch?v=ghi",
            status = RowStatus.DONE,
            title = "Phase 6 Song",
            artist = "Phase 6 Artist",
            batchId = "batch_1700000000",
            ibroadcastTrackId = "ib_555",
            replacesUploadedTrackId = "ib_444",
            isEditingInStudio = true,
            isUpdated = true,
            savedLocalUri = "/storage/emulated/0/Music/Phase 6 Song.mp3"
        )

        val json = DiskStore.rowToJson(row)
        val deserialized = DiskStore.jsonToRow(json)

        assertEquals("row_phase6", deserialized.id)
        assertEquals("batch_1700000000", deserialized.batchId)
        assertEquals("ib_555", deserialized.ibroadcastTrackId)
        assertEquals("ib_444", deserialized.replacesUploadedTrackId)
        assertTrue(deserialized.isEditingInStudio)
        assertTrue(deserialized.isUpdated)
        assertEquals("/storage/emulated/0/Music/Phase 6 Song.mp3", deserialized.savedLocalUri)
    }

    @Test
    fun testLegacyUntaggedRowRestoresStructuralFlag() {
        val legacy = """
            {"id":"old_1","sourceInput":"https://www.youtube.com/watch?v=abc","status":"METADATA_READY",
             "title":"Some Song","detail":"Raw audio • Untagged"}
        """.trimIndent()

        val restored = DiskStore.jsonToRow(Json.parseToJsonElement(legacy).jsonObject)

        assertNull(restored.options.firstOrNull())
        assertTrue(
            "legacy untagged detail must restore isNoMetadata so tagFile still skips tagging",
            restored.selectedOption?.isNoMetadata == true
        )
        assertEquals("Some Song", restored.selectedOption?.trackTitle)
    }

    @Test
    fun testLegacyUntaggedRowWithRealMatchIsNotClobbered() {
        val matched = QueueRow(
            id = "matched_1",
            sourceInput = "https://www.youtube.com/watch?v=abc",
            status = RowStatus.METADATA_READY,
            title = "Some Song",
            detail = "Raw audio • Untagged",
            selectedOption = ReleaseOption.originalFile(title = "Some Song", artist = "A", album = "Al")
        )

        val restored = DiskStore.jsonToRow(DiskStore.rowToJson(matched))

        assertEquals(false, restored.selectedOption?.isNoMetadata)
        assertEquals("Some Song", restored.selectedOption?.trackTitle)
    }
}
