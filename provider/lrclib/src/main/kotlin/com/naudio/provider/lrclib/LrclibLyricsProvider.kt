package com.naudio.provider.lrclib

import com.naudio.core.model.LyricLine
import com.naudio.core.model.Lyrics
import com.naudio.core.model.Track
import com.naudio.core.network.NaudioHttpClient
import com.naudio.core.network.NetworkException
import com.naudio.provider.api.LyricsProvider
import com.naudio.provider.api.ProviderId
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlin.math.abs
import kotlin.math.max

class LrclibLyricsProvider(
    private val client: HttpClient
) : LyricsProvider {

    override val id: ProviderId = ProviderId("lrclib")
    override val displayName: String = "LRCLIB"

    private val apiUrl = "https://lrclib.net/api"

    override suspend fun getLyrics(track: Track): Lyrics {
        return try {
            NaudioHttpClient.requestWithRetry {
                fetchLyrics(track)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: NetworkException.HttpError) {
            if (e.code == HttpStatusCode.NotFound.value) {
                Lyrics.NotFound
            } else {
                Lyrics.Unavailable
            }
        } catch (e: Exception) {
            Lyrics.Unavailable
        }
    }

    private suspend fun fetchLyrics(track: Track): Lyrics {
        // 1. Try exact match
        try {
            val response = client.get("$apiUrl/get") {
                parameter("track_name", track.title)
                parameter("artist_name", track.artist)
                parameter("album_name", track.album)
                parameter("duration", track.durationMs / 1000)
            }
            if (response.status == HttpStatusCode.NotFound) {
                // Not found via exact match, will fallback to search
            } else {
                val record = response.body<LrclibRecordDto>()
                return mapToLyrics(record)
            }
        } catch (e: NetworkException.HttpError) {
            if (e.code != HttpStatusCode.NotFound.value) {
                throw e
            }
        } catch (e: Exception) {
            if (e !is CancellationException) {
                // Wrap in NetworkException or let retry handle it if appropriate
                // Actually NaudioHttpClient wraps in NetworkException, so this catch might get the raw Ktor exceptions or NetworkExceptions depending on interceptors.
                // Ktor's ClientRequestException is wrapped by NaudioHttpClient.
                throw e
            } else throw e
        }

        // 2. Fallback to search
        val searchQuery = "${track.title} ${track.artist}".trim()
        val searchResponse = client.get("$apiUrl/search") {
            parameter("q", searchQuery)
        }
        val results = searchResponse.body<List<LrclibRecordDto>>()

        val bestMatch = findBestMatch(track, results)
            ?: return Lyrics.NotFound

        return mapToLyrics(bestMatch)
    }

    private fun mapToLyrics(record: LrclibRecordDto): Lyrics {
        if (record.instrumental) return Lyrics.Instrumental

        val synced = record.syncedLyrics
        if (!synced.isNullOrBlank()) {
            val parsedLines = LrcParser.parse(synced)
            if (parsedLines != null) {
                return Lyrics.Synced(parsedLines, displayName)
            }
        }

        val plain = record.plainLyrics
        if (!plain.isNullOrBlank()) {
            return Lyrics.Plain(plain, displayName)
        }

        return Lyrics.NotFound
    }

    private fun findBestMatch(track: Track, results: List<LrclibRecordDto>): LrclibRecordDto? {
        val targetTitle = normalize(track.title)
        val targetArtist = normalize(track.artist)
        val targetDurationSec = (track.durationMs / 1000).toInt()

        var bestMatch: LrclibRecordDto? = null
        var bestScore = -1.0

        for (result in results) {
            val resTitle = normalize(result.trackName ?: "")
            val resArtist = normalize(result.artistName ?: "")
            val resDuration = result.duration ?: continue

            val titleSim = similarity(targetTitle, resTitle)
            val artistSim = similarity(targetArtist, resArtist)
            val durationDiff = abs(targetDurationSec - resDuration)

            if (titleSim >= 0.75 && artistSim >= 0.50 && durationDiff <= 5) {
                // Score based on similarity and having synced lyrics
                var score = titleSim * 0.6 + artistSim * 0.4
                if (!result.syncedLyrics.isNullOrBlank()) {
                    score += 0.5 // Boost synced lyrics
                }
                if (score > bestScore) {
                    bestScore = score
                    bestMatch = result
                }
            }
        }

        return bestMatch
    }

    private fun normalize(text: String): String {
        return text.lowercase()
            .replace(Regex("""\([^)]*\)"""), "") // Remove (Live), (feat. ...), etc.
            .replace(Regex("""\[[^]]*]"""), "")  // Remove [Remastered], etc.
            .replace(Regex("""[-_]"""), " ")     // Replace hyphens and underscores
            .replace(Regex("""\s+"""), " ")      // Normalize spaces
            .trim()
    }

    private fun similarity(s1: String, s2: String): Double {
        if (s1.isEmpty() && s2.isEmpty()) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0

        // Simple Levenshtein distance based similarity
        val distance = levenshtein(s1, s2)
        val maxLength = max(s1.length, s2.length)
        return 1.0 - (distance.toDouble() / maxLength.toDouble())
    }

    private fun levenshtein(lhs: CharSequence, rhs: CharSequence): Int {
        var cost = IntArray(rhs.length + 1) { it }
        var newCost = IntArray(rhs.length + 1)

        for (i in 1..lhs.length) {
            newCost[0] = i
            for (j in 1..rhs.length) {
                val match = if (lhs[i - 1] == rhs[j - 1]) 0 else 1
                val costReplace = cost[j - 1] + match
                val costInsert = cost[j] + 1
                val costDelete = newCost[j - 1] + 1
                newCost[j] = minOf(costInsert, costDelete, costReplace)
            }
            val swap = cost
            cost = newCost
            newCost = swap
        }
        return cost[rhs.length]
    }
}
