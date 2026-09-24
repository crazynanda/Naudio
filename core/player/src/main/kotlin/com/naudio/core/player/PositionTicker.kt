package com.naudio.core.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

/**
 * Cold flow emitting one unit per [intervalMs] while [isPlaying] stays true,
 * and completing (no emissions) when paused. flatMapLatest cancels the
 * in-flight delay on pause immediately. The consumer polls the real position
 * per tick — this keeps PositionTicker pure and unit-testable with virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PositionTicker(
    private val intervalMs: Long = 500L,
) {

    fun ticks(isPlaying: Flow<Boolean>): Flow<Unit> =
        isPlaying
            .distinctUntilChanged()
            .flatMapLatest { playing ->
                if (playing) tickFlow(intervalMs) else emptyFlow()
            }

    private fun tickFlow(intervalMs: Long): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(intervalMs)
        }
    }
}
