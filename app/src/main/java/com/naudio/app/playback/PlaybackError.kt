package com.naudio.app.playback

/**
 * User-visible reason the last playback request could not be served.
 *
 * These describe what happened to a *playback attempt*, never why a specific
 * provider declined. In particular no value asserts that a particular service
 * blocked the user: the app only knows that its own playback providers did not
 * hand back a playable source, which is a provider-capability fact, not a claim
 * about anyone else's policy.
 */
enum class PlaybackError {
    /**
     * A particular queued track has no playable source — typically the queue
     * SKIPPED it and moved on, or it is the item the user just tapped. This is
     * the "one item" case and is expected to be transient.
     */
    UNAVAILABLE,

    /**
     * A playback provider exists but could not resolve a playable source — e.g.
     * the lookup failed because of the network. Distinct from [UNAVAILABLE]
     * because retrying may succeed.
     */
    UNRESOLVABLE,

    /**
     * M20: a resolution run walked the whole remaining queue and found nothing
     * playable, so playback stopped. This is a TERMINAL state for that run,
     * distinct from [UNAVAILABLE] (which merely means "this one was skipped and
     * something else is playing"), so the UI can say so explicitly instead of
     * going quiet after a single transient message.
     *
     * A track can reach this state for any provider, not only YouTube Music —
     * it is the shared, provider-agnostic outcome of "no queued item could be
     * played". YouTube Music currently lands here often, because its backend
     * only returns an [com.naudio.core.model.AudioSource] when the service
     * hands over a directly playable URL, and treats protected/ciphered stream
     * forms as unavailable by design. That is a deliberate capability boundary,
     * not a fault to be worked around.
     */
    QUEUE_UNPLAYABLE,
}