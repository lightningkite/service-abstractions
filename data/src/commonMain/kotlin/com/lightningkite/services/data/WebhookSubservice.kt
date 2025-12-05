package com.lightningkite.services.data

interface WebhookSubserviceWithResponse<Input, Output>: HttpAdapter<Input, Output> {
    suspend fun configureWebhook(httpUrl: String)
    suspend fun onSchedule()

}

interface WebhookSubservice<Input>: WebhookSubserviceWithResponse<Input, Unit> {
    override suspend fun render(output: Unit): HttpAdapter.HttpResponseLike = HttpAdapter.HttpResponseLike(204, mapOf(), null)
}

interface HttpAdapter<Input, Output> {
    suspend fun parse(queryParameters: List<Pair<String, String>>, headers: Map<String, List<String>>, body: TypedData): Input
    suspend fun render(output: Output): HttpResponseLike

    data class HttpResponseLike(
        val status: Int,
        val headers: Map<String, List<String>>,
        val body: TypedData?
    )
}
interface WebsocketAdapter<Startup, Inbound, Outbound> {
    suspend fun parseStart(queryParameters: List<Pair<String, String>>, headers: Map<String, List<String>>, body: TypedData): Startup
    suspend fun parse(frame: Frame): Inbound
    suspend fun render(output: Outbound): Frame

    sealed class Frame {
        data class Text(val text: String) : Frame()
        data class Binary(val bytes: ByteArray) : Frame() {
            override fun hashCode(): Int = bytes.contentHashCode()
            override fun equals(other: Any?): Boolean = other is Binary && bytes.contentEquals(other.bytes)
        }
    }
}