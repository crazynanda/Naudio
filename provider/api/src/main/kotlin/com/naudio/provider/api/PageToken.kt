package com.naudio.provider.api

/**
 * Cursor for paged provider operations. Two kinds exist on purpose:
 *
 * - [Offset] — a native numeric offset, for providers whose backends page by
 *   index (iTunes, MediaStore). The value is the offset of the first item of
 *   the NEXT page, not a count.
 * - [Opaque] — an opaque continuation token, for providers that hand out
 *   server-side cursors (YouTube Music). The value is passed back to the
 *   provider verbatim; its contents are meaningless to callers.
 *
 * Tokens are provider-scoped by type: a provider must consume only the token
 * kind its backend produces. Receiving the other kind means the caller mixed
 * results from different providers (e.g. a stale UI token after switching
 * providers) and is not a valid cursor — providers must fail deterministically
 * rather than reinterpret it (see [MetadataProvider.searchTracks]).
 */
sealed interface PageToken {
    /** Native numeric offset cursor. */
    data class Offset(val value: Int) : PageToken

    /** Opaque continuation token, opaque to everyone except its provider. */
    data class Opaque(val value: String) : PageToken
}
