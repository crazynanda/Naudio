package com.naudio.provider.local

/** Stable identifiers for the on-device (MediaStore) provider. */
object LocalProviderIds {
    /** Provider id used by both the metadata and playback provider. */
    const val LOCAL = "local"

    /** Human-readable name shown in the UI. */
    const val DISPLAY_NAME = "Local Device"

    /** Error message for an incompatible (non-offset) page token. */
    const val TOKEN_TYPE_ERROR = "Local provider requires PageToken.Offset"
}
