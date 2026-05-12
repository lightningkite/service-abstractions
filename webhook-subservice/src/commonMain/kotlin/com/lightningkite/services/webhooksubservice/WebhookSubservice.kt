package com.lightningkite.services.webhooksubservice

import com.lightningkite.services.data.TypedData

public interface WebhookSubserviceWithResponse<Input, Output> : HttpAdapter<Input, Output> {
    public suspend fun configureWebhook(httpUrl: String)
    public suspend fun onSchedule()
}

public interface WebhookSubservice<Input> : WebhookSubserviceWithResponse<Input, Unit> {
    override suspend fun render(output: Unit): HttpAdapter.HttpResponseLike =
        HttpAdapter.HttpResponseLike(204, mapOf(), null)
}

public interface HttpAdapter<Input, Output> {
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

public interface WebsocketAdapter<Startup, Inbound, Outbound> {
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