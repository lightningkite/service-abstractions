package com.lightningkite.serviceabstractions.http

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json


/**
 * This is an HttpClient. There are many times when the server will need to make an external call
 * and this is the provided default option to do so.
 */
val client = HttpClient(CIO) {
    install(ContentNegotiation) {
        json()
    }
    engine {
        this.requestTimeout = 60000
    }
}

/**
 * HttpResponseException is an exception that handles external request error messages.
 */
class HttpResponseException(val response: HttpResponse, val body: String) :
    Exception("Got response ${response.status}: ${body.take(300)}")

/**
 * Checks the HttpResponse code and if it is not a success code it will throw an HttpResponseException
 */
suspend fun HttpResponse.statusFailing(): HttpResponse {
    if (!this.status.isSuccess()) throw HttpResponseException(this, bodyAsText())
    return this
}

fun HttpMessageBuilder.json() {
    accept(ContentType.Application.Json)
    contentType(ContentType.Application.Json)
}