package com.naudio.provider.api

/**
 * One page of results from a paged provider operation. [nextToken] is the
 * cursor to pass for the following page, or null when no further page exists
 * (empty page or short final page). Tokens are provider-specific — see
 * [PageToken] for the two supported kinds.
 */
data class Page<T>(
    val items: List<T>,
    val nextToken: PageToken?,
)
