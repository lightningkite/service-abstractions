package com.lightningkite.services.data

/**
 * Lifecycle state of a [SuspendingSource] or [SuspendingSink].
 *
 * - [Open]: bytes may still flow.
 * - [Complete]: the stream ended cleanly; no more bytes will flow.
 * - [ClosedAbnormally]: the stream ended because of [cause]; no more bytes will flow.
 */
public sealed interface StreamState {
    public data object Open : StreamState
    public data object Complete : StreamState
    public data class ClosedAbnormally(val cause: Throwable) : StreamState
}
