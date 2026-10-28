package com.lightningkite.services.ai.bedrock.awssdk

import kotlinx.coroutines.test.runTest
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Resolves `profile=` the way a `bedrock-sdk://...&profile=lk` URL does, against fake profile files, so the SDK's own
 * profile machinery (not a stand-in provider) is what's under test.
 */
class AwsSdkProfileTest {
    private val dir = createTempDirectory("aws-profile-test").toFile()
    private val config = File(dir, "config")

    @BeforeTest
    fun pointSdkAtFakeFiles() {
        System.setProperty("aws.configFile", config.path)
        System.setProperty("aws.sharedCredentialsFile", File(dir, "credentials").path)
        System.setProperty("aws.loginCacheDirectory", File(dir, "login-cache").path)
    }

    @AfterTest
    fun restore() {
        System.clearProperty("aws.configFile")
        System.clearProperty("aws.sharedCredentialsFile")
        System.clearProperty("aws.loginCacheDirectory")
        dir.deleteRecursively()
    }

    @Test
    fun loginProfileUsesCachedSessionCredentials() = runTest {
        // `aws login` writes a profile with login_session and caches the session under sha256(login_session).json.
        val session = "arn:aws:iam::123456789012:user/tester"
        config.writeText(
            """
            [profile lk]
            login_session = $session
            region = us-west-2
            """.trimIndent()
        )
        val cacheKey = MessageDigest.getInstance("SHA-256").digest(session.toByteArray())
            .joinToString("") { "%02x".format(it) }
        File(dir, "login-cache").apply { mkdirs() }.resolve("$cacheKey.json").writeText(
            """
            {
              "accessToken": {
                "accessKeyId": "ASIALOGIN", "secretAccessKey": "SECRET", "sessionToken": "TOKEN",
                "accountId": "123456789012", "expiresAt": "${Instant.now().plusSeconds(3600)}"
              },
              "clientId": "client", "dpopKey": "unused-until-refresh", "refreshToken": "refresh"
            }
            """.trimIndent()
        )

        val creds = awsSdkProfileCredentials("lk").resolve()

        assertEquals("ASIALOGIN", creds.accessKeyId)
        assertEquals("TOKEN", creds.sessionToken)
    }

    @Test
    fun expiringProfileCredentialsAreFetchedAgain() = runTest {
        // credential_process stands in for any short-lived source: it hands out a new key each run, valid for 5 seconds,
        // which is inside the SDK's refresh window, so every request must go back to the process.
        val counter = File(dir, "count")
        val script = File(dir, "creds.sh").apply {
            writeText(
                """
                #!/bin/sh
                n=$(( $(cat ${counter.path} 2>/dev/null || echo 0) + 1 ))
                echo ${'$'}n > ${counter.path}
                echo '{"Version":1,"AccessKeyId":"ASIA'${'$'}n'","SecretAccessKey":"SECRET","SessionToken":"TOKEN","Expiration":"'$(date -u -v+5S +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -d '+5 seconds' +%Y-%m-%dT%H:%M:%SZ)'"}'
                """.trimIndent()
            )
            setExecutable(true)
        }
        config.writeText(
            """
            [profile lk]
            credential_process = ${script.path}
            """.trimIndent()
        )

        val provider = awsSdkProfileCredentials("lk")
        val first = provider.resolve()
        val second = provider.resolve()

        assertEquals("TOKEN", first.sessionToken)
        assertNotEquals(first.accessKeyId, second.accessKeyId)
    }
}
