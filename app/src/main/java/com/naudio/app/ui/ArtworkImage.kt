package com.naudio.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlin.math.absoluteValue

/**
 * Reusable artwork loader (M12). Loads network URLs and `content://` URIs via
 * Coil and always falls back to the deterministic [ArtworkPlaceholder] for
 * null, blank, loading, and failed states — no broken-image UI, no crashes.
 *
 * Provider-agnostic by design: the component receives an artwork URL/URI and
 * nothing else — no providerId, no provider-specific branching.
 */
@Composable
fun ArtworkImage(
    artworkUrl: String?,
    trackTitle: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    isBuffering: Boolean = false,
    cornerRadius: Dp = 24.dp,
    glyphSize: TextUnit = 64.sp,
) {
    if (artworkUrl.isNullOrBlank()) {
        ArtworkPlaceholder(
            trackTitle = trackTitle,
            isBuffering = isBuffering,
            modifier = modifier,
            cornerRadius = cornerRadius,
            glyphSize = glyphSize,
        )
        return
    }
    // The placeholder sits behind the image: while Coil loads it shows
    // through, and on any load failure the (transparent) image simply never
    // covers it — one graceful fallback path for every failure mode.
    Box(modifier = modifier) {
        ArtworkPlaceholder(
            trackTitle = trackTitle,
            isBuffering = isBuffering,
            modifier = Modifier.fillMaxSize(),
            cornerRadius = cornerRadius,
            glyphSize = glyphSize,
        )
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(artworkUrl)
                .crossfade(true)
                .build(),
            contentDescription = contentDescription,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(cornerRadius)),
        )
    }
}

/** Deterministic gradient pairs keyed by title hash — stable, no downloads. */
internal val ArtworkPalettes = listOf(
    Color(0xFF5E35B1) to Color(0xFF9575CD),
    Color(0xFF1E88E5) to Color(0xFF64B5F6),
    Color(0xFF00897B) to Color(0xFF4DB6AC),
    Color(0xFFF4511E) to Color(0xFFFF8A65),
    Color(0xFF6D4C41) to Color(0xFFA1887F),
    Color(0xFF3949AB) to Color(0xFF7986CB),
)

/**
 * Stable artwork placeholder: a deterministic gradient keyed on the title so
 * it does not flicker between recompositions. Graceful when no track is
 * loaded. No artwork downloading or caching architecture by design.
 */
@Composable
internal fun ArtworkPlaceholder(
    trackTitle: String?,
    isBuffering: Boolean,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    glyphSize: TextUnit = 64.sp,
) {
    val seed = trackTitle?.hashCode()?.absoluteValue ?: 0
    val (base, accent) = ArtworkPalettes[seed % ArtworkPalettes.size]
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(cornerRadius),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.linearGradient(listOf(base, accent)),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (isBuffering) {
                CircularProgressIndicator()
            } else {
                Text(
                    text = "♪",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = glyphSize,
                )
            }
        }
    }
}
