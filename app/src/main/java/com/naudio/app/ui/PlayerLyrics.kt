package com.naudio.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.naudio.core.model.Lyrics
import kotlinx.coroutines.delay

/**
 * Lyrics panel for the full-screen player.
 *
 * Rendering is driven by the authoritative `PlayerState.positionMs` passed in
 * from [PlayerScreen]. Because that position only refreshes about every 500 ms,
 * a purely local frame clock interpolates a smooth *visual* position between
 * samples (see [LyricPositionInterpolator]). That interpolation is scoped to
 * this composable: it is not published through any ViewModel, StateFlow,
 * repository, or playback component, and it never affects playback.
 */
@Composable
fun PlayerLyrics(
    lyricsState: LyricsState,
    positionMs: Long,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (lyricsState) {
            is LyricsState.Idle -> {
                // Empty state
            }
            is LyricsState.Loading -> {
                CircularProgressIndicator()
            }
            is LyricsState.Success -> {
                when (val lyrics = lyricsState.lyrics) {
                    is Lyrics.Synced -> {
                        SyncedLyricsList(
                            lyrics = lyrics,
                            positionMs = positionMs,
                            isPlaying = isPlaying,
                            onSeek = onSeek,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    is Lyrics.Plain -> {
                        val scrollState = rememberScrollState()
                        Text(
                            text = lyrics.text,
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(scrollState)
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    is Lyrics.Instrumental -> {
                        Text(
                            text = "This track is instrumental.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    is Lyrics.NotFound -> {
                        Text(
                            text = "No lyrics found for this track.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    is Lyrics.Unavailable -> {
                        Text(
                            text = "Lyrics are currently unavailable.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }
}

/** Offset kept above the active line so the current line sits below centre. */
private const val ACTIVE_LINE_LEAD_ITEMS = 3

/** Idle time after a manual scroll before auto-follow resumes. */
private const val RESUME_FOLLOW_DELAY_MS = 1_500L

@Composable
private fun SyncedLyricsList(
    lyrics: Lyrics.Synced,
    positionMs: Long,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val interpolator = remember { LyricPositionInterpolator() }

    // Visual position. Written once per frame while playing, and once per
    // authoritative sample otherwise. Local to this composable, and unboxed
    // (mutableLongStateOf) so a per-frame write allocates nothing.
    var interpolatedPositionMs by remember { mutableLongStateOf(positionMs) }

    // New lyrics (new track, retry, or re-fetch) must never inherit the
    // previous track's interpolation: drop all anchor state and jump to the
    // top of the new list.
    LaunchedEffect(lyrics) {
        interpolator.reset()
        interpolatedPositionMs = positionMs
        listState.scrollToItem(0)
    }

    // Re-anchor on EVERY authoritative positionMs arrival, plus on pause and
    // resume. This is what bounds drift: the clock is rebuilt from player
    // truth at least twice a second, so the visual position can never wander
    // more than one poll interval away from `PlayerState.positionMs`. It also
    // corrects a forward or backward seek on the very next authoritative
    // sample instead of continuing from the old anchor.
    LaunchedEffect(positionMs, isPlaying) {
        withFrameMillis { frameTimeMs ->
            interpolator.reanchor(positionMs, isPlaying, frameTimeMs)
            interpolatedPositionMs = positionMs
        }
    }

    // Frame clock: runs only while playing. Pausing cancels it, so the visual
    // position freezes; resuming re-anchors above and restarts it.
    LaunchedEffect(isPlaying) {
        if (!isPlaying) return@LaunchedEffect
        while (true) {
            withFrameMillis { frameTimeMs ->
                interpolatedPositionMs = interpolator.positionAt(frameTimeMs)
            }
        }
    }

    // Active line from the INTERPOLATED position. Keyed only on the lyric list
    // (NOT on the position), so the derived value is computed once and reused;
    // `derivedStateOf` then only notifies readers when the resulting INDEX
    // actually changes — i.e. once per line change instead of once per frame.
    // `findActiveLineIndex` is a binary search, so this is O(log n) per frame.
    val activeLineIndex by remember(lyrics.lines) {
        derivedStateOf { findActiveLineIndex(lyrics.lines, interpolatedPositionMs) }
    }

    // Manual scrolling suppresses auto-follow so the list never fights the
    // user; following resumes after a short idle period.
    var userScrolling by remember { mutableStateOf(false) }

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            userScrolling = true
        } else {
            delay(RESUME_FOLLOW_DELAY_MS)
            userScrolling = false
        }
    }

    // Auto-scroll fires ONLY when the active line index changes — never on
    // every interpolated position update.
    LaunchedEffect(activeLineIndex) {
        if (activeLineIndex >= 0 && !userScrolling) {
            listState.animateScrollToItem(
                index = maxOf(0, activeLineIndex - ACTIVE_LINE_LEAD_ITEMS)
            )
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(vertical = 32.dp, horizontal = 16.dp)
    ) {
        itemsIndexed(lyrics.lines) { index, line ->
            val isActive = index == activeLineIndex
            Text(
                text = line.text,
                style = if (isActive) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyLarge,
                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSeek(line.startTimeMs) }
                    .padding(vertical = 12.dp)
                    .alpha(if (isActive) 1f else 0.6f),
                textAlign = TextAlign.Center
            )
        }
    }
}
