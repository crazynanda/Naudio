package com.naudio.data.repository

import com.naudio.core.model.Lyrics
import com.naudio.core.model.Track
import com.naudio.provider.api.LyricsProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.Collections

/**
 * Manages fetching and caching of lyrics.
 */
class LyricsRepository(
    private val lyricsProvider: LyricsProvider,
) {
    // 100 tracks of lyrics should be plenty. Using LinkedHashMap for LRU behavior.
    private val cache = Collections.synchronizedMap(object : LinkedHashMap<TrackKey, Lyrics>(100, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TrackKey, Lyrics>?): Boolean {
            return size > 100
        }
    })
    
    // Per-track mutexes to prevent duplicate concurrent network requests for the same track
    private val loadingLocks = ConcurrentHashMap<TrackKey, Mutex>()

    /**
     * Retrieves lyrics for the given track. Uses memory cache if available.
     * Caches [Lyrics.NotFound], [Lyrics.Synced], [Lyrics.Plain], and [Lyrics.Instrumental].
     * Does not cache [Lyrics.Unavailable].
     */
    suspend fun getLyrics(track: Track): Lyrics {
        val key = TrackKey(track)
        
        // Fast path cache hit
        cache.get(key)?.let { return it }

        // Slow path: fetch from provider, avoiding concurrent duplicate requests
        val mutex = loadingLocks.getOrPut(key) { Mutex() }
        
        return mutex.withLock {
            // Check cache again after acquiring lock
            cache.get(key)?.let { return it }

            try {
                val result = lyricsProvider.getLyrics(track)
                // Cache all results except transient failures
                if (result !is Lyrics.Unavailable) {
                    cache.put(key, result)
                }
                result
            } catch (e: CancellationException) {
                throw e
            } finally {
                // Clean up the lock if nobody else is waiting (simplification, 
                // but removing it after lock release is usually okay or we can just leave it since the map is small)
                loadingLocks.remove(key)
            }
        }
    }
}
