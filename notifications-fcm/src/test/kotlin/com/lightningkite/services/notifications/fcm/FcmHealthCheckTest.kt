package com.lightningkite.services.notifications.fcm

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.data.HealthStatus
import com.lightningkite.services.notifications.fcm.FcmTestSupport.mockClient
import com.lightningkite.services.notifications.fcm.FcmTestSupport.serviceAccount
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies that [FcmNotificationClient.healthCheck] maps `validate_only` dry-run outcomes to the
 * correct [HealthStatus]. The point of the check is distinguishing "credentials work" (OK) from
 * "credentials broken" (ERROR), with transient/transport problems reported as WARNING.
 */
class FcmHealthCheckTest {

    private fun client(
        tokenStatus: HttpStatusCode = HttpStatusCode.OK,
        onSend: (FcmSendRequest) -> Pair<HttpStatusCode, String>,
    ) = FcmNotificationClient(
        name = "fcm-health-test",
        context = TestSettingContext(),
        credentials = serviceAccount(),
        baseClient = mockClient(tokenStatus, onSend),
    )

    @Test
    fun success_isOk() = runBlocking {
        val c = client { HttpStatusCode.OK to """{"name":"projects/fake-project/messages/1"}""" }
        assertEquals(HealthStatus.Level.OK, c.healthCheck().level)
    }

    @Test
    fun probeTokenRejected_isOk_becauseCredentialsAuthenticated() = runBlocking {
        val c = client { HttpStatusCode.BadRequest to """{"error":{"status":"INVALID_ARGUMENT"}}""" }
        assertEquals(HealthStatus.Level.OK, c.healthCheck().level)
    }

    @Test
    fun sendForbidden_isError_becauseCredentialsBroken() = runBlocking {
        val c = client { HttpStatusCode.Forbidden to """{"error":{"status":"PERMISSION_DENIED"}}""" }
        assertEquals(HealthStatus.Level.ERROR, c.healthCheck().level)
    }

    @Test
    fun tokenEndpointUnauthorized_isError_becauseCredentialsBroken() = runBlocking {
        val c = client(tokenStatus = HttpStatusCode.Unauthorized) { HttpStatusCode.OK to "{}" }
        assertEquals(HealthStatus.Level.ERROR, c.healthCheck().level)
    }

    @Test
    fun serverError_isWarning() = runBlocking {
        val c = client { HttpStatusCode.ServiceUnavailable to """{"error":{"status":"UNAVAILABLE"}}""" }
        assertEquals(HealthStatus.Level.WARNING, c.healthCheck().level)
    }

    @Test
    fun transportFailure_isWarning_doesNotThrow() = runBlocking {
        val c = client { throw IOException("simulated network failure") }
        assertEquals(HealthStatus.Level.WARNING, c.healthCheck().level)
    }
}
