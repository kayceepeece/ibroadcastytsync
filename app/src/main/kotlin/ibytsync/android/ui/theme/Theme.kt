package ibytsync.android.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

val SpotifyGreen = Color(0xFF1DB954)
val DarkCharcoal = Color(0xFF121212)
val SurfaceCard = Color(0xFF181818)
val SurfaceElevated = Color(0xFF222222)
val TextPrimary = Color(0xFFEEEEEE)
val TextSecondary = Color(0xFFAAAAAA)
val ErrorRed = Color(0xFFE91429)

val AmberRetune = Color(0xFFFFB300)
val InfoBlue = Color(0xFF29B6F6)
val GreenComplete = Color(0xFF1DB954)

val IbytsyncColorScheme = darkColorScheme(
    primary = SpotifyGreen,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF0F5A28),
    onPrimaryContainer = Color(0xFF86E8A3),
    background = DarkCharcoal,
    onBackground = TextPrimary,
    surface = SurfaceCard,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = TextSecondary,
    error = ErrorRed,
    onError = Color.White
)

/** Frosted glass panel: solid backing + specular scrim + 1dp outline. No glow.
 * Pass [lightweight] for scrolling card rows — skips the scrim gradient. */
fun Modifier.liquidGlassPanel(
    cornerRadius: Dp = 12.dp,
    scrimAlpha: Float = 0.10f,
    borderAlpha: Float = 0.12f,
    lightweight: Boolean = false
): Modifier {
    val shape = RoundedCornerShape(cornerRadius)
    if (lightweight) {
        return this
            .clip(shape)
            .background(Color(0xFF181818))
            .border(1.dp, Color.White.copy(alpha = borderAlpha), shape)
    }
    val glassScrim = Brush.verticalGradient(
        listOf(
            Color.White.copy(alpha = scrimAlpha + 0.04f),
            Color.White.copy(alpha = scrimAlpha)
        )
    )
    val glassBorder = Brush.verticalGradient(
        listOf(
            Color.White.copy(alpha = borderAlpha),
            Color.White.copy(alpha = borderAlpha * 0.4f)
        )
    )
    return this
        .clip(shape)
        .background(Color(0xFF181818))
        .background(glassScrim)
        .border(1.dp, glassBorder, shape)
}

enum class PipelineStatus(val label: String, val color: Color) {
    QUEUED("QUEUED", Color(0xFF888888)),
    MATCHING("MATCHING", InfoBlue),
    DOWNLOADING("DOWNLOADING", InfoBlue),
    UPLOADING("UPLOADING", Color(0xFF1DB954)),
    DONE("SYNCED", Color(0xFF1DB954)),
    FAILED("FAILED", Color(0xFFE91429))
}
