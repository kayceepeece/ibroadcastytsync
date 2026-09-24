package ibytsync.core.pipeline

import ibytsync.core.tagging.CoverFetcher
import ibytsync.core.tagging.Id3Writer
import ibytsync.core.upload.BatchSummary
import ibytsync.core.upload.DuplicateDetector
import ibytsync.core.upload.Playlist
import ibytsync.core.upload.RoutePreference
import ibytsync.core.upload.UploadRouter
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class Slice0711Test {

    // 08 — ID3 writer semantics
    @Test fun albumFallsBackToTitle() {
        val tags = Id3Writer.resolve("Track", "Artist", "")
        assertEquals("Track", tags.album)
    }

    @Test fun tagRoundtripKeepsFrames() {
        val f = File.createTempFile("t0711", ".mp3")
        try {
            f.writeBytes(ByteArray(1024) { 0 })
            Id3Writer.write(f, Id3Writer.resolve("T", "A", ""))
            val bytes = f.readBytes()
            val s = String(bytes, Charsets.ISO_8859_1)
            assertTrue(s.contains("TIT2"))
            assertTrue(s.contains("TPE1"))
            assertTrue(s.contains("TALB"))
            assertTrue(Id3Writer.audioStart(bytes) > 0)
        } finally {
            f.delete()
        }
    }

    @Test fun richTagsWriteYearGenreTrackDisc() {
        val f = File.createTempFile("t0711r", ".mp3")
        try {
            f.writeBytes(ByteArray(1024) { 0 })
            Id3Writer.write(
                f,
                Id3Writer.resolveRich("T", "A", "Al",
                    year = "2019", genre = "Alternative",
                    trackNumber = 1, trackCount = 12, discNumber = 1, discCount = 2)
            )
            val s = String(f.readBytes(), Charsets.ISO_8859_1)
            assertTrue(s.contains("TYER"))
            assertTrue(s.contains("2019"))
            assertTrue(s.contains("TCON"))
            assertTrue(s.contains("Alternative"))
            assertTrue(s.contains("TRCK"))
            assertTrue(s.contains("1/12"))
            assertTrue(s.contains("TPOS"))
            assertTrue(s.contains("1/2"))
        } finally {
            f.delete()
        }
    }

    @Test fun richTagsRejectGarbage() {
        val tags = Id3Writer.resolveRich("T", "A", "Al",
            year = "20xx", genre = "  ", trackNumber = 0, discNumber = -1)
        assertNull(tags.year)
        assertNull(tags.genre)
        assertNull(tags.trackNumber)
        assertNull(tags.discNumber)
    }

    @Test fun apicEmbeddedOnlyWhenAbsent() {
        val cover = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x00)
        val f = File.createTempFile("t0711c", ".mp3")
        try {
            f.writeBytes(ByteArray(512) { 0 })
            Id3Writer.write(
                f,
                Id3Writer.resolve("T", "A", "Al").copy(coverBytes = cover, coverMime = "image/jpeg")
            )
            assertTrue(Id3Writer.hasApic(f.readBytes()))
            // second write with a different cover must keep the first (no clobber)
            val other = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01)
            Id3Writer.write(
                f,
                Id3Writer.resolve("T", "A", "Al").copy(coverBytes = other, coverMime = "image/jpeg")
            )
            val bytes = f.readBytes()
            assertTrue(Id3Writer.hasApic(bytes))
            assertFalse(bytes.toList().windowed(4).any { it == listOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01) })
        } finally {
            f.delete()
        }
    }

    @Test fun coverSniffAndCap() {
        assertEquals("image/jpeg", CoverFetcher.sniffMime(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x11)))
        assertEquals("image/png", CoverFetcher.sniffMime(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
        assertNull(CoverFetcher.sniffMime("GIF8".toByteArray()))
        assertNull(CoverFetcher.fetch("file:///etc/passwd"))

        val oversize = ByteArray(CoverFetcher.MAX_BYTES + 1024)
        oversize[0] = 0xFF.toByte()
        oversize[1] = 0xD8.toByte()
        oversize[2] = 0xFF.toByte()
        assertNull(CoverFetcher.fetch("content://oversize", streamOpener = { oversize.inputStream() }))
    }

    // 11 — duplicates, routing, summary
    @Test fun duplicateIsCasefoldExact() {
        val lib = mapOf("1" to ("Song" to "Artist"))
        assertTrue(DuplicateDetector.isDuplicate("song", "artist", lib))
        assertTrue(DuplicateDetector.isDuplicate("  Song ", " ARTIST ", lib))
        assertFalse(DuplicateDetector.isDuplicate("Song", "Other", lib))
        assertFalse(DuplicateDetector.isDuplicate("Song2", "Artist", lib))
    }

    @Test fun routingFallback() {
        val pls = mapOf("9" to Playlist("9", "Fav"))
        assertNull(UploadRouter.resolve(RoutePreference.LibraryOnly, pls, "9"))
        assertEquals("9", UploadRouter.resolve(RoutePreference.Favorite("9", "Fav"), pls))
        assertNull(UploadRouter.resolve(RoutePreference.Favorite("gone", "G"), pls))
        assertEquals("9", UploadRouter.resolve(RoutePreference.AskEachTime, pls, "9"))
        assertNull(UploadRouter.resolve(RoutePreference.AskEachTime, pls, null))
    }

    // 07 — row-state gate rules the orchestrator obeys
    @Test fun startGateAndFailureSet() {
        assertTrue(RowStates.canStart(listOf(RowStatus.METADATA_READY, RowStatus.METADATA_READY)))
        assertFalse(RowStates.canStart(emptyList()))
        assertFalse(RowStates.canStart(listOf(RowStatus.METADATA_READY, RowStatus.QUEUED)))
        assertEquals(setOf(RowStatus.FAILED_METADATA), RowStates.FAILURE_STATUSES)
        assertTrue(RowStates.isTerminal(RowStatus.DONE))
        assertTrue(RowStates.isTerminal(RowStatus.ALREADY_UPLOADED))
        assertFalse(RowStates.isTerminal(RowStatus.DOWNLOADING))
    }
}
