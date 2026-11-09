package com.lightningkite.services.http

import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import kotlinx.browser.window
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class HttpClientJsTest {
    @Test
    fun requestsWorkInTheBrowser() = runTest {
        // The test page itself is served by Karma, so this needs no outside network.
        val response = client.get(window.location.href)
        assertEquals(HttpStatusCode.OK, response.status)
    }
}
