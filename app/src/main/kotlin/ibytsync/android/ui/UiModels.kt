package ibytsync.android.ui

import androidx.compose.ui.graphics.Color
import ibytsync.core.pipeline.QueueRow

enum class NavTab(val title: String) {
    QUEUE("Queue"),
    SYNCED("Synced"),
    SETTINGS("Settings")
}

enum class SyncedSortOrder {
    NEWEST_FIRST,
    OLDEST_FIRST;

    companion object {
        fun fromCode(code: String): SyncedSortOrder =
            if (code.equals("oldest", ignoreCase = true)) OLDEST_FIRST else NEWEST_FIRST
    }
}

enum class SourceType(val label: String, val color: Color) {
    SPOTIFY("SPOTIFY", Color(0xFF1DB954)),
    YOUTUBE("YOUTUBE", ibytsync.android.ui.theme.InfoBlue),
    LOCAL("LOCAL FILE", Color(0xFFE0E0E0))
}

fun QueueRow.sourceType(): SourceType {
    val input = sourceInput.lowercase()
    return when {
        input.contains("spotify.com") -> SourceType.SPOTIFY
        input.contains("youtube.com") || input.contains("youtu.be") -> SourceType.YOUTUBE
        else -> SourceType.LOCAL
    }
}

fun formatDurationMs(ms: Long?): String {
    if (ms == null || ms <= 0) return "--:--"
    val sec = (ms / 1000) % 60
    val min = ms / 60000
    return "$min:${"%02d".format(sec)}"
}
