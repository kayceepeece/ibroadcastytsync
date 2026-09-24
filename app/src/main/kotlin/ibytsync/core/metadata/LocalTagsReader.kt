package ibytsync.core.metadata

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset

/**
 * Local audio file tag read with filename fallback.
 *
 * Reads title/artist/album + duration when available, with filename stem
 * fallback. Never throws.
 *
 * The built-in [Id3TagBackend] is a pure-JVM ID3v2.3/v2.4 + ID3v1 reader, so
 * this module has no new dependencies. To swap backends later, add a
 * `JAudioTaggerBackend : TagBackend` and inject it — [read]
 * already takes any [TagBackend], so pipeline code will not change.
 * Likewise [DurationProbe] is the seam for real duration probing (no
 * on-device duration probing in this slice,
 * so duration is null unless a probe is injected; matching treats null as
 * "widen search", never as failure).
 */
data class RawTags(
    val title: String?,
    val artist: String?,
    val album: String?
) {
    fun hasAny(): Boolean =
        !title.isNullOrBlank() || !artist.isNullOrBlank() || !album.isNullOrBlank()
}

enum class LocalSource { TAGS, FILENAME }

data class LocalMetadata(
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long?,
    val source: LocalSource
)

fun interface TagBackend {
    /** Returns parsed tags, or null when the file has none / is unreadable. Must not throw. */
    fun read(file: File): RawTags?
}

fun interface DurationProbe {
    /** Returns duration in ms, or null when unknown. Must not throw. */
    fun probe(file: File): Long?
}

object LocalTagsReader {

    /**
     * Read metadata for [file]. Never throws: on any failure the filename
     * stem is used as the title with empty artist/album.
     */
    fun read(
        file: File,
        backend: TagBackend = Id3TagBackend,
        durationProbe: DurationProbe? = null
    ): LocalMetadata {
        val stem = filenameStem(file)
        return try {
            val raw = try {
                backend.read(file)
            } catch (_: Exception) {
                null
            }
            val durationMs = try {
                durationProbe?.probe(file)
            } catch (_: Exception) {
                null
            }
            if (raw == null || !raw.hasAny()) {
                LocalMetadata(
                    title = stem,
                    artist = "",
                    album = "",
                    durationMs = durationMs,
                    source = LocalSource.FILENAME
                )
            } else {
                LocalMetadata(
                    // Title must never be blank downstream; stem is the last resort.
                    title = raw.title?.trim()?.takeIf { it.isNotEmpty() } ?: stem,
                    artist = raw.artist?.trim().orEmpty(),
                    // Blank albums are fixed at tag-write time (fallback to title).
                    album = raw.album?.trim().orEmpty(),
                    durationMs = durationMs,
                    source = LocalSource.TAGS
                )
            }
        } catch (_: Exception) {
            LocalMetadata(
                title = stem,
                artist = "",
                album = "",
                durationMs = null,
                source = LocalSource.FILENAME
            )
        }
    }

    /** Filename stem used as the title fallback. Never blank, never throws. */
    fun filenameStem(file: File): String {
        return try {
            val stem = file.nameWithoutExtension.trim()
            if (stem.isNotEmpty()) return stem
            val bare = file.name.substringBeforeLast('.').trim()
            if (bare.isNotEmpty()) bare else "Unknown Title"
        } catch (_: Exception) {
            "Unknown Title"
        }
    }

    /**
     * Default pure-JVM backend: ID3v2 (TIT2/TPE1/TALB, plus v2.2 TT2/TP1/TAL)
     * with ID3v1 fallback for the last 128 bytes. Null when no tags found.
     */
    object Id3TagBackend : TagBackend {
        override fun read(file: File): RawTags? {
            try {
                if (!file.isFile || !file.canRead()) return null
                parseId3v2(file)?.takeIf { it.hasAny() }?.let { return it }
                return parseId3v1(file)?.takeIf { it.hasAny() }
            } catch (_: Exception) {
                return null
            }
        }
    }

    // ---- ID3v2 ----

    internal fun parseId3v2(file: File): RawTags? {
        RandomAccessFile(file, "r").use { raf ->
            val header = ByteArray(10)
            if (raf.length() < 10) return null
            raf.readFully(header)
            if (header[0] != 'I'.code.toByte() ||
                header[1] != 'D'.code.toByte() ||
                header[2] != '3'.code.toByte()
            ) {
                return null
            }
            val major = header[3].toInt() and 0xFF
            if (major != 2 && major != 3 && major != 4) return null
            if (header[6] < 0 || header[7] < 0 || header[8] < 0 || header[9] < 0) return null
            val tagSize = syncSafe(header, 6)
            if (tagSize <= 0 || tagSize > 32 * 1024 * 1024) return null

            var pos = 10L
            // Skip extended header when present (flag 0x40).
            if (header[5].toInt() and 0x40 != 0) {
                val extSize = if (major == 4) {
                    val b = ByteArray(4)
                    raf.readFully(b)
                    pos += 4
                    syncSafe(b, 0)
                } else {
                    val b = ByteArray(4)
                    raf.readFully(b)
                    pos += 4
                    be32(b, 0)
                }
                if (extSize > 0) {
                    raf.seek(pos + extSize)
                    pos += extSize
                }
            }

            val end = 10L + tagSize
            var title: String? = null
            var artist: String? = null
            var album: String? = null
            val frameHeader = ByteArray(10)
            while (pos + 10 <= end && pos + 10 <= raf.length()) {
                raf.seek(pos)
                raf.readFully(frameHeader)
                if (major == 2) {
                    val id = String(frameHeader, 0, 3, Charsets.ISO_8859_1)
                    if (id[0] == '\u0000') break
                    val size = ((frameHeader[3].toInt() and 0xFF) shl 16) or
                        ((frameHeader[4].toInt() and 0xFF) shl 8) or
                        (frameHeader[5].toInt() and 0xFF)
                    pos += 6
                    if (size <= 0 || pos + size > end) break
                    val body = ByteArray(size)
                    raf.seek(pos)
                    raf.readFully(body)
                    pos += size
                    when (id) {
                        "TT2" -> if (title == null) title = decodeTextFrame(body)
                        "TP1" -> if (artist == null) artist = decodeTextFrame(body)
                        "TAL" -> if (album == null) album = decodeTextFrame(body)
                    }
                } else {
                    val id = String(frameHeader, 0, 4, Charsets.ISO_8859_1)
                    if (id[0] == '\u0000' || !id.all { it in 'A'..'Z' || it in '0'..'9' }) break
                    val size = if (major == 4) syncSafe(frameHeader, 4) else be32(frameHeader, 4)
                    pos += 10
                    if (size <= 0 || pos + size > end) {
                        if (size == 0) continue else break
                    }
                    val body = ByteArray(size)
                    raf.seek(pos)
                    raf.readFully(body)
                    pos += size
                    when (id) {
                        "TIT2" -> if (title == null) title = decodeTextFrame(body)
                        "TPE1" -> if (artist == null) artist = decodeTextFrame(body)
                        "TALB" -> if (album == null) album = decodeTextFrame(body)
                    }
                }
                if (!title.isNullOrBlank() && !artist.isNullOrBlank() && !album.isNullOrBlank()) break
            }
            val result = RawTags(title, artist, album)
            return if (result.hasAny()) result else null
        }
    }

    internal fun decodeTextFrame(body: ByteArray): String? {
        if (body.isEmpty()) return null
        val enc = body[0].toInt() and 0xFF
        val payload = body.copyOfRange(1, body.size)
        val charset: Charset = when (enc) {
            0 -> Charsets.ISO_8859_1
            1 -> Charsets.UTF_16 // BOM-sniffed below
            2 -> Charsets.UTF_16BE
            3 -> Charsets.UTF_8
            else -> Charsets.ISO_8859_1
        }
        val text = if (enc == 1) {
            decodeUtf16WithBom(payload) ?: String(payload, Charsets.UTF_16LE)
        } else {
            String(payload, charset)
        }
        // Multi-value frames are NUL-separated; join with ", " like standard tag text lists.
        val joined = text.split('\u0000').map { it.trim('\u0000', ' ', '\t') }
            .filter { it.isNotEmpty() }.joinToString(", ")
        return joined.takeIf { it.isNotEmpty() }
    }

    private fun decodeUtf16WithBom(payload: ByteArray): String? {
        return try {
            when {
                payload.size >= 2 && payload[0] == 0xFF.toByte() && payload[1] == 0xFE.toByte() ->
                    String(payload, 2, payload.size - 2, Charsets.UTF_16LE)
                payload.size >= 2 && payload[0] == 0xFE.toByte() && payload[1] == 0xFF.toByte() ->
                    String(payload, 2, payload.size - 2, Charsets.UTF_16BE)
                else -> String(payload, Charsets.UTF_16LE)
            }
        } catch (_: Exception) {
            null
        }
    }

    // ---- ID3v1 ----

    internal fun parseId3v1(file: File): RawTags? {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 128) return null
            raf.seek(raf.length() - 128)
            val tail = ByteArray(128)
            raf.readFully(tail)
            if (tail[0] != 'T'.code.toByte() ||
                tail[1] != 'A'.code.toByte() ||
                tail[2] != 'G'.code.toByte()
            ) {
                return null
            }
            fun field(off: Int, len: Int): String? {
                val s = String(tail, off, len, Charsets.ISO_8859_1)
                    .trim('\u0000', ' ', '\t', '\n', '\r')
                return s.takeIf { it.isNotEmpty() }
            }
            val result = RawTags(
                title = field(3, 30),
                artist = field(33, 30),
                album = field(63, 30)
            )
            return if (result.hasAny()) result else null
        }
    }

    private fun syncSafe(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0x7F) shl 21) or
            ((b[off + 1].toInt() and 0x7F) shl 14) or
            ((b[off + 2].toInt() and 0x7F) shl 7) or
            (b[off + 3].toInt() and 0x7F)

    private fun be32(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)
}
