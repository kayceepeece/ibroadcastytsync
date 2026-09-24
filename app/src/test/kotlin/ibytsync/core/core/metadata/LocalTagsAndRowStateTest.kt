package ibytsync.core.metadata

import ibytsync.core.pipeline.QueueRow
import ibytsync.core.pipeline.RowStates
import ibytsync.core.pipeline.RowStates.afterLocalTags
import ibytsync.core.pipeline.RowStates.afterSpotifyTrackFetch
import ibytsync.core.pipeline.RowStatus
import java.io.File
import java.nio.file.Files
import org.junit.Test

/**
 * LocalTagsReader + row-state tests.
 *
 * Covers filename-fallback tag reads and the
 * metadata-phase row rules (Start gate, retry/remove, failure isolation).
 */
class LocalTagsAndRowStateTest {

    private val tmpRoot: File = Files.createTempDirectory("ibytsync-meta-test").toFile()

    private fun eq(expected: Any?, actual: Any?, name: String) {
        check(expected == actual) { "$name: expected <$expected> got <$actual>" }
    }

    private fun newFile(name: String): File = File(tmpRoot, name).also {
        it.parentFile?.mkdirs()
        it.createNewFile()
    }

    // ---- filename fallback ----

    @Test
    fun `missing file falls back to filename stem without throwing`() {
        val meta = LocalTagsReader.read(File(tmpRoot, "my song.mp3"))
        eq("my song", meta.title, "stem-title")
        eq("", meta.artist, "stem-artist")
        eq(LocalSource.FILENAME, meta.source, "stem-source")
    }

    @Test
    fun `untagged file falls back to stem`() {
        val f = newFile("plain.wav")
        f.writeBytes(ByteArray(256) { 0 })
        val meta = LocalTagsReader.read(f)
        eq("plain", meta.title, "untagged-title")
        eq(LocalSource.FILENAME, meta.source, "untagged-source")
    }

    @Test
    fun `throwing backend still falls back without throwing`() {
        val f = newFile("safe.mp3")
        val meta = LocalTagsReader.read(f, backend = TagBackend { throw RuntimeException("boom") })
        eq("safe", meta.title, "throw-title")
        eq(LocalSource.FILENAME, meta.source, "throw-source")
    }

    @Test
    fun `blank title from tags falls back to stem`() {
        val f = newFile("fallback.mp3")
        val meta = LocalTagsReader.read(
            f,
            backend = TagBackend { RawTags(title = "  ", artist = "Some Artist", album = "") }
        )
        eq("fallback", meta.title, "blank-title-fallback")
        eq("Some Artist", meta.artist, "kept-artist")
        eq(LocalSource.TAGS, meta.source, "tags-source")
    }

    @Test
    fun `duration probe failure is swallowed`() {
        val f = newFile("dur.mp3")
        val meta = LocalTagsReader.read(
            f,
            backend = TagBackend { RawTags("T", "A", "B") },
            durationProbe = DurationProbe { throw RuntimeException("nope") }
        )
        eq(null, meta.durationMs, "probe-null")
        eq("T", meta.title, "probe-title")
    }

    // ---- real ID3 bytes through the built-in backend ----

    @Test
    fun `id3v1 tail is parsed`() {
        val f = newFile("v1.mp3")
        val audio = ByteArray(1024) { 0x11 }
        val tag = ByteArray(128)
        tag[0] = 'T'.code.toByte()
        tag[1] = 'A'.code.toByte()
        tag[2] = 'G'.code.toByte()
        "V1 Title".toByteArray(Charsets.ISO_8859_1).copyInto(tag, 3)
        "V1 Artist".toByteArray(Charsets.ISO_8859_1).copyInto(tag, 33)
        "V1 Album".toByteArray(Charsets.ISO_8859_1).copyInto(tag, 63)
        f.writeBytes(audio + tag)
        val meta = LocalTagsReader.read(f)
        eq("V1 Title", meta.title, "v1-title")
        eq("V1 Artist", meta.artist, "v1-artist")
        eq("V1 Album", meta.album, "v1-album")
        eq(LocalSource.TAGS, meta.source, "v1-source")
    }

    @Test
    fun `id3v23 frames are parsed`() {
        val f = newFile("v23.mp3")
        f.writeBytes(buildId3v23(title = "Frame Title", artist = "Frame Artist", album = "Frame Album"))
        val meta = LocalTagsReader.read(f)
        eq("Frame Title", meta.title, "v23-title")
        eq("Frame Artist", meta.artist, "v23-artist")
        eq("Frame Album", meta.album, "v23-album")
        eq(LocalSource.TAGS, meta.source, "v23-source")
    }

    private fun textFrame(id: String, value: String): ByteArray {
        val body = byteArrayOf(3) + value.toByteArray(Charsets.UTF_8)
        val header = id.toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(
                (body.size shr 24).toByte(),
                (body.size shr 16).toByte(),
                (body.size shr 8).toByte(),
                body.size.toByte(),
                0, 0
            )
        return header + body
    }

    private fun buildId3v23(title: String, artist: String, album: String): ByteArray {
        val frames = textFrame("TIT2", title) + textFrame("TPE1", artist) + textFrame("TALB", album)
        val n = frames.size
        val header = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0) +
            byteArrayOf(
                ((n shr 21) and 0x7F).toByte(),
                ((n shr 14) and 0x7F).toByte(),
                ((n shr 7) and 0x7F).toByte(),
                (n and 0x7F).toByte()
            )
        return header + frames + ByteArray(64)
    }

    // ---- row-state hooks ----

    @Test
    fun `start enabled only when every row is metadata-ready`() {
        check(RowStates.canStart(listOf(RowStatus.METADATA_READY))) { "single ready" }
        check(
            RowStates.canStart(
                listOf(RowStatus.METADATA_READY, RowStatus.METADATA_READY, RowStatus.METADATA_READY)
            )
        ) { "all ready" }
        check(!RowStates.canStart(emptyList())) { "empty blocks" }
        check(!RowStates.canStart(listOf(RowStatus.METADATA_READY, RowStatus.FETCHING_METADATA))) {
            "fetching blocks"
        }
        check(!RowStates.canStart(listOf(RowStatus.METADATA_READY, RowStatus.FAILED_METADATA))) {
            "failed blocks"
        }
        check(!RowStates.canStart(listOf(RowStatus.METADATA_READY, RowStatus.SKIPPED_BAD_URL))) {
            "skipped blocks until removed"
        }
        check(!RowStates.canStart(listOf(RowStatus.QUEUED))) { "queued blocks" }
    }

    @Test
    fun `only metadata failure invalidates rows`() {
        eq(setOf(RowStatus.FAILED_METADATA), RowStates.FAILURE_STATUSES, "failure-set")
    }

    @Test
    fun `invalid url skips immediately to skipped-bad-url`() {
        eq(
            RowStatus.SKIPPED_BAD_URL,
            RowStates.statusForClassifiedInput(UrlKind.Invalid("bad URL")),
            "invalid-skips"
        )
        eq(
            RowStatus.FETCHING_METADATA,
            RowStates.statusForClassifiedInput(UrlKind.SpotifyTrack("x")),
            "valid-fetches"
        )
    }

    @Test
    fun `null spotify metadata marks failed-metadata without touching siblings`() {
        val failed = QueueRow("1", "url1", RowStatus.FETCHING_METADATA).afterSpotifyTrackFetch(null)
        eq(RowStatus.FAILED_METADATA, failed.status, "failed-status")
        val sibling = QueueRow("2", "url2", RowStatus.METADATA_READY)
        eq(RowStatus.METADATA_READY, sibling.status, "sibling-untouched")
        check(RowStates.canRetry(failed.status)) { "retry offered" }
        eq(RowStatus.FETCHING_METADATA, RowStates.statusAfterRetry(failed.status), "retry-state")
    }

    @Test
    fun `spotify track success fills row and blank album falls back to title`() {
        val row = QueueRow("1", "url", RowStatus.FETCHING_METADATA).afterSpotifyTrackFetch(
            SpotifyTrackMetadata("Artist", "Title", "", 200_000L, null)
        )
        eq(RowStatus.METADATA_READY, row.status, "ready")
        eq("Title", row.album, "album-fallback")
        eq(200_000L, row.durationMs, "duration")
    }

    @Test
    fun `local tags always land metadata-ready via fallback`() {
        val row = QueueRow("9", "file", RowStatus.FETCHING_METADATA).afterLocalTags(
            LocalMetadata("stem", "", "", null, LocalSource.FILENAME)
        )
        eq(RowStatus.METADATA_READY, row.status, "local-ready")
        eq("stem", row.title, "local-title")
    }
}

/** JVM entry point so the suite runs without a JUnit runner (see header note). */
object LocalTagsAndRowStateMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val t = LocalTagsAndRowStateTest()
        val cases = listOf(
            "stem-missing" to { t.`missing file falls back to filename stem without throwing`() },
            "stem-untagged" to { t.`untagged file falls back to stem`() },
            "stem-throw" to { t.`throwing backend still falls back without throwing`() },
            "stem-blank-title" to { t.`blank title from tags falls back to stem`() },
            "probe-fail" to { t.`duration probe failure is swallowed`() },
            "id3v1" to { t.`id3v1 tail is parsed`() },
            "id3v23" to { t.`id3v23 frames are parsed`() },
            "start-gate" to { t.`start enabled only when every row is metadata-ready`() },
            "failure-set" to { t.`only metadata failure invalidates rows`() },
            "skip-immediate" to { t.`invalid url skips immediately to skipped-bad-url`() },
            "failed-isolation" to { t.`null spotify metadata marks failed-metadata without touching siblings`() },
            "track-row" to { t.`spotify track success fills row and blank album falls back to title`() },
            "local-row" to { t.`local tags always land metadata-ready via fallback`() }
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
        println("LOCAL+ROWSTATE JVM CHECKS PASSED ($passed/${cases.size})")
    }
}
