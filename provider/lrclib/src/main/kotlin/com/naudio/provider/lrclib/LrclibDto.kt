package com.naudio.provider.lrclib

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * LRCLIB record DTO. Serves both response shapes:
 *  - `GET /api/get` → a single JSON object (or 404 when no exact record).
 *  - `GET /api/search` → a JSON array of these objects.
 *
 * Field notes (LRCLIB contract): `duration` is whole seconds;
 * `plainLyrics` is newline-separated text; `syncedLyrics` is an LRC-format
 * string (`[mm:ss.xx] text`, multiple timestamps per line allowed); either
 * lyric field may be absent/null; `instrumental` is the server's explicit
 * instrumental flag. Unknown fields are tolerated by the shared lenient
 * [com.naudio.core.network.NaudioHttpClient.json] decoder.
 */
@Serializable
internal data class LrclibRecordDto(
    val id: Long = 0L,
    @SerialName("trackName") val trackName: String? = null,
    @SerialName("artistName") val artistName: String? = null,
    @SerialName("albumName") val albumName: String? = null,
    val duration: Int? = null,
    val instrumental: Boolean = false,
    @SerialName("plainLyrics") val plainLyrics: String? = null,
    @SerialName("syncedLyrics") val syncedLyrics: String? = null,
)
