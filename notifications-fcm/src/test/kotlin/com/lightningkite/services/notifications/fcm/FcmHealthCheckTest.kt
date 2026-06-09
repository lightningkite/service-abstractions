package com.lightningkite.services.notifications.fcm

import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.ErrorCode
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.Message
import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.data.HealthStatus
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.Date
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies that [FcmNotificationClient.healthCheck] maps dry-run outcomes to the correct
 * [HealthStatus]. The whole point of the check is distinguishing "credentials work" (OK) from
 * "credentials broken" (ERROR), so each test drives the [FcmNotificationClient.sendDryRun] seam to
 * a specific [ErrorCode] and asserts the resulting level.
 *
 * The `(ErrorCode, String)` constructor of [FirebaseMessagingException] is package-private
 * (`@VisibleForTesting`), so we reach it via reflection.
 */
class FcmHealthCheckTest {

    private val appNames = mutableListOf<String>()

    @AfterTest
    fun cleanup() {
        for (name in appNames) {
            runCatching { FirebaseApp.getInstance(name).delete() }
        }
        appNames.clear()
    }

    private fun fakeFirebaseOptions(): FirebaseOptions {
        // No network is hit: the test subclass overrides sendDryRun. FirebaseOptions still requires
        // non-null credentials to build, so we supply a stub token.
        val token = AccessToken("fake-token", Date(System.currentTimeMillis() + 60_000))
        return FirebaseOptions.builder()
            .setCredentials(GoogleCredentials.create(token))
            .setProjectId("fake-project")
            .build()
    }

    private fun firebaseMessagingException(errorCode: ErrorCode): FirebaseMessagingException {
        val ctor = FirebaseMessagingException::class.java.getDeclaredConstructor(
            ErrorCode::class.java,
            String::class.java
        )
        ctor.isAccessible = true
        return ctor.newInstance(errorCode, "synthetic $errorCode")
    }

    /** Test subclass whose [sendDryRun] runs a scripted action instead of contacting FCM. */
    private class ScriptedHealthClient(
        name: String,
        ctx: TestSettingContext,
        opts: FirebaseOptions,
        private val script: () -> String,
    ) : FcmNotificationClient(name, ctx, opts) {
        public override fun sendDryRun(message: Message): String = script()
    }

    private fun client(name: String, script: () -> String): ScriptedHealthClient {
        appNames += name
        return ScriptedHealthClient(name, TestSettingContext(), fakeFirebaseOptions(), script)
    }

    @Test
    fun success_isOk() = runBlocking {
        val c = client("fcm-health-ok-${System.nanoTime()}") { "projects/fake/messages/1" }
        assertEquals(HealthStatus.Level.OK, c.healthCheck().level)
    }

    @Test
    fun invalidArgument_isOk_becauseCredentialsAuthenticated() = runBlocking {
        val c = client("fcm-health-invalid-${System.nanoTime()}") {
            throw firebaseMessagingException(ErrorCode.INVALID_ARGUMENT)
        }
        assertEquals(HealthStatus.Level.OK, c.healthCheck().level)
    }

    @Test
    fun unauthenticated_isError_becauseCredentialsBroken() = runBlocking {
        val c = client("fcm-health-unauth-${System.nanoTime()}") {
            throw firebaseMessagingException(ErrorCode.UNAUTHENTICATED)
        }
        assertEquals(HealthStatus.Level.ERROR, c.healthCheck().level)
    }

    @Test
    fun permissionDenied_isError_becauseCredentialsBroken() = runBlocking {
        val c = client("fcm-health-perm-${System.nanoTime()}") {
            throw firebaseMessagingException(ErrorCode.PERMISSION_DENIED)
        }
        assertEquals(HealthStatus.Level.ERROR, c.healthCheck().level)
    }

    @Test
    fun transportFailure_isWarning_doesNotThrow() = runBlocking {
        val c = client("fcm-health-transport-${System.nanoTime()}") {
            throw IOException("simulated network failure")
        }
        assertEquals(HealthStatus.Level.WARNING, c.healthCheck().level)
    }
}
