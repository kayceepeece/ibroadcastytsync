package ibytsync.core.tagging

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.Charset

object Id3Writer {
    private val LATIN1: Charset = Charset.forName("ISO-8859-1")

    data class Tags(
        val title: String,
        val artist: String,
        val album: String,
        val coverBytes: ByteArray? = null,
        val coverMime: String? = null,
        val year: String? = null,
        val genre: String? = null,
        val trackNumber: Int? = null,
        val trackCount: Int? = null,
        val discNumber: Int? = null,
        val discCount: Int? = null
    )

    fun resolve(title: String, artist: String, album: String): Tags =
        Tags(title, artist, if (album.isBlank()) title else album)

    fun resolveRich(
        title: String,
        artist: String,
        album: String,
        year: String? = null,
        genre: String? = null,
        trackNumber: Int? = null,
        trackCount: Int? = null,
        discNumber: Int? = null,
        discCount: Int? = null
    ): Tags = Tags(title, artist, if (album.isBlank()) title else album,
        year = year?.takeIf { it.length == 4 && it.all { c -> c.isDigit() } },
        genre = genre?.trim()?.takeIf { it.isNotEmpty() },
        trackNumber = trackNumber?.takeIf { it > 0 },
        trackCount = trackCount?.takeIf { it > 0 },
        discNumber = discNumber?.takeIf { it > 0 },
        discCount = discCount?.takeIf { it > 0 })

    fun hasApic(bytes: ByteArray): Boolean {
        if (bytes.size < 10 || !(bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte())) return false
        val tagSize = synchsafeToInt(bytes, 6)
        var pos = 10
        val end = (10 + tagSize).coerceAtMost(bytes.size)
        while (pos + 10 <= end) {
            val id = String(bytes, pos, 4, LATIN1)
            val size = ((bytes[pos + 4].toInt() and 0xFF) shl 24) or
                ((bytes[pos + 5].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 6].toInt() and 0xFF) shl 8) or
                (bytes[pos + 7].toInt() and 0xFF)
            if (id == "APIC") return true
            if (id[0] == '\u0000' || size <= 0) break
            pos += 10 + size
        }
        return false
    }

    fun extractApicFrame(bytes: ByteArray): ByteArray? {
        if (bytes.size < 10 || !(bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte())) return null
        val tagSize = synchsafeToInt(bytes, 6)
        var pos = 10
        val end = (10 + tagSize).coerceAtMost(bytes.size)
        while (pos + 10 <= end) {
            val id = String(bytes, pos, 4, LATIN1)
            val size = ((bytes[pos + 4].toInt() and 0xFF) shl 24) or
                ((bytes[pos + 5].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 6].toInt() and 0xFF) shl 8) or
                (bytes[pos + 7].toInt() and 0xFF)
            if (id == "APIC") return bytes.copyOfRange(pos, (pos + 10 + size).coerceAtMost(bytes.size))
            if (id[0] == '\u0000' || size <= 0) break
            pos += 10 + size
        }
        return null
    }
    fun audioStart(bytes: ByteArray): Int {
        if (bytes.size < 10 || !(bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte())) return 0
        return 10 + synchsafeToInt(bytes, 6)
    }

    fun write(file: File, tags: Tags, embedCoverOnlyIfAbsent: Boolean = true) {
        val bytes = file.readBytes()
        val existingApic = hasApic(bytes)
        val keepExisting = embedCoverOnlyIfAbsent && existingApic
        val cover = if (tags.coverBytes != null && tags.coverMime != null && !keepExisting
        ) tags.coverBytes to tags.coverMime else null
        val frames = mutableListOf<ByteArray>()
        if (tags.title.isNotEmpty()) frames.add(textFrame("TIT2", tags.title))
        if (tags.artist.isNotEmpty()) frames.add(textFrame("TPE1", tags.artist))
        val album = tags.album.ifBlank { tags.title }
        if (album.isNotEmpty()) frames.add(textFrame("TALB", album))
        if (!tags.year.isNullOrEmpty()) frames.add(textFrame("TYER", tags.year))
        if (!tags.genre.isNullOrEmpty()) frames.add(textFrame("TCON", tags.genre))
        numberedFrame("TRCK", tags.trackNumber, tags.trackCount)?.let { frames.add(it) }
        numberedFrame("TPOS", tags.discNumber, tags.discCount)?.let { frames.add(it) }
        if (keepExisting) {
            // keep existing art: re-emit the original APIC frame untouched
            extractApicFrame(bytes)?.let { frames.add(it) }
        } else if (cover != null) {
            frames.add(apicFrame(cover.second!!, cover.first!!))
        }
        val body = frames.fold(ByteArray(0)) { acc, f -> acc + f }
        val header = ByteBuffer.allocate(10)
        header.put("ID3".toByteArray(LATIN1))
        header.put(3); header.put(0); header.put(0)
        header.put(intToSynchsafe(body.size))
        val audio = bytes.copyOfRange(audioStart(bytes).coerceAtMost(bytes.size), bytes.size)
        file.writeBytes(header.array() + body + audio)
    }

    private fun textFrame(id: String, text: String): ByteArray {
        val data = byteArrayOf(3) + text.toByteArray(Charsets.UTF_8)
        return frame(id, data)
    }

    private fun numberedFrame(id: String, number: Int?, count: Int?): ByteArray? {
        if (number == null || number <= 0) return null
        val text = if (count != null && count > 0) "$number/$count" else "$number"
        return textFrame(id, text)
    }

    private fun apicFrame(mime: String, data: ByteArray): ByteArray {
        val mimeBytes = mime.toByteArray(LATIN1)
        val payload = byteArrayOf(3) + mimeBytes + byteArrayOf(0) + byteArrayOf(3) +
            byteArrayOf(0) + data
        return frame("APIC", payload)
    }

    private fun frame(id: String, payload: ByteArray): ByteArray {
        val h = ByteBuffer.allocate(10)
        h.put(id.toByteArray(LATIN1))
        h.putInt(payload.size)
        h.putShort(0)
        return h.array() + payload
    }

    private fun synchsafeToInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0x7F) shl 21) or
            ((b[off + 1].toInt() and 0x7F) shl 14) or
            ((b[off + 2].toInt() and 0x7F) shl 7) or
            (b[off + 3].toInt() and 0x7F)

    private fun intToSynchsafe(v: Int): ByteArray = byteArrayOf(
        ((v shr 21) and 0x7F).toByte(),
        ((v shr 14) and 0x7F).toByte(),
        ((v shr 7) and 0x7F).toByte(),
        (v and 0x7F).toByte()
    )
}
