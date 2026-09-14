package com.lightningkite.services.webhooksubservice

import com.lightningkite.services.data.TypedData

public interface WebhookAdapterWithResponse<Input, Output> : HttpAdapter<Input, Output> {
    public suspend fun configureWebhook(httpUrl: String)
}

public interface WebhookAdapter<Input> : WebhookAdapterWithResponse<Input, Unit> {
    override suspend fun render(output: Unit): HttpAdapter.HttpResponseLike =
        HttpAdapter.HttpResponseLike(204, mapOf(), null)

    /**
     * Fetches and returns a batch of pending [Input]s for pull-based (as opposed to
     * webhook-pushed) implementations.
     *
     * ## No acknowledgment mechanism (known limitation)
     *
     * This method has no way for the caller to signal "I successfully processed this batch" —
     * consumption is implicitly acknowledged by the act of returning the items, not by the
     * caller finishing whatever it does with them. That forces every pull-based implementation
     * to choose between two bad options:
     * - Mark items consumed *before* returning them (at-most-once): if the caller crashes or
     *   throws partway through processing the batch, the unprocessed remainder is lost forever.
     * - Mark items consumed *after* returning them, on the next call (at-least-once): items can
     *   be redelivered indefinitely if the caller never calls [pull] again.
     *
     * `ImapEmailInboundService` (in `email-inbound-imap`), the one pull-based implementation
     * today, takes the first option (marks messages SEEN inside `pull()`, before the caller
     * processes them) and therefore risks message loss on downstream failure.
     *
     * Fixing this requires an API change here, not in the implementations — the candidate is a
     * scoped form, `suspend fun <R> pull(process: suspend (Set<Input>) -> R): R`, which would
     * mark items consumed only after `process` returns normally.
     */
    public suspend fun pull(): Set<Input>
}

public interface HttpAdapter<Input, Output> {
    /**
     * Parses an incoming HTTP request into the adapter's input type.
     *
     * ## Span kind
     * Implementations that create a telemetry span should mark it as a SERVER span, because this is
     * the entry point of an inbound request (not an outbound client call).
     *
     * ## Single-use body
     * [body] is a single-use [TypedData]: calling [TypedData.text], [TypedData.write], or any
     * other read method more than once will throw [IllegalStateException] for [Source]- and
     * [Sink]-backed payloads. Read the body exactly once and store the result if you need it
     * more than once.
     */
    public suspend fun parse(
        queryParameters: List<Pair<String, String>>,
        headers: Map<String, List<String>>,
        body: TypedData,
    ): Input

    public suspend fun render(output: Output): HttpResponseLike

    public data class HttpResponseLike(
        public val status: Int,
        public val headers: Map<String, List<String>>,
        public val body: TypedData?,
    )

    public class SpecialCaseException(
        public val intendedResponse: HttpResponseLike,
    ) : Exception("Special case response: ${intendedResponse.status}")
}

public interface WebSocketAdapter<Startup, Inbound, Outbound> {
    public suspend fun parseStart(
        queryParameters: List<Pair<String, String>>,
        headers: Map<String, List<String>>,
        body: TypedData,
    ): Startup

    public suspend fun parse(frame: Frame): Inbound
    public suspend fun render(output: Outbound): Frame

    public sealed class Frame {
        public data class Text(public val text: String) : Frame()
        public data class Binary(public val bytes: ByteArray) : Frame() {
            override fun hashCode(): Int = bytes.contentHashCode()
            override fun equals(other: Any?): Boolean = other is Binary && bytes.contentEquals(other.bytes)
        }
    }
}