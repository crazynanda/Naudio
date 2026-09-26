package com.naudio.provider.api

/**
 * One page of results from a paged provider operation. [nextOffset] is the
 * offset to pass for the following page, or null when no further page exists
 * (empty page or short final page).
 */
data class Page<T>(
    val items: List<T>,
    val nextOffset: Int?,
)
