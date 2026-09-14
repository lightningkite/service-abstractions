package com.lightningkite.services.email

import com.lightningkite.services.TestSettingContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Pins the contract of [EmailService.sendBulk] default implementations.
 *
 * The 1.0.0 perf pass changed the default from `forEach { send(it) }` (sequential, all-or-some
 * delivery) to `coroutineScope { async { send(it) }.awaitAll() }` (concurrent, structured-
 * concurrency: one failure cancels siblings). Servers relying on best-effort delivery must
 * override; these tests prevent a silent revert.
 */
class SendBulkSemanticsTest {

    private val context = TestSettingContext()
    private val from = EmailAddressWithName("from@example.com")
    private val to = listOf(EmailAddressWithName("to@example.com"))

    @Test
    fun sendBulkDispatchesConcurrently() = runTest {
        // A service whose send() suspends until externally completed proves whether sendBulk
        // dispatches sequentially or concurrently: if sequential, only the first send is
        // observed waiting; if concurrent, all three are observed simultaneously.
        val arrivals = mutableListOf<Int>()
        val release = CompletableDeferred<Unit>()
        val service = object : EmailService {
            override val name: String = "concurrent-test"
            override val context = this@SendBulkSemanticsTest.context
            override suspend fun send(email: Email) {
                arrivals += email.subject.toInt()
                release.await()
            }
        }

        val emails = listOf(
            Email(subject = "0", to = to, plainText = "a"),
            Email(subject = "1", to = to, plainText = "b"),
            Email(subject = "2", to = to, plainText = "c"),
        )

        coroutineScope {
            val job = launch { service.sendBulk(emails) }
            // All three should arrive before any of them finishes, because the default impl
            // dispatches concurrently.
            withTimeout(2.seconds) {
                while (arrivals.size < 3) {
                    kotlinx.coroutines.yield()
                }
            }
            assertEquals(setOf(0, 1, 2), arrivals.toSet())
            release.complete(Unit)
            job.join()
        }
    }

    @Test
    fun sendBulkSurfacesFailureFromOneSibling() = runTest {
        // Structured concurrency means one async throwing cancels the others, and
        // awaitAll() rethrows.
        val service = object : EmailService {
            override val name: String = "failing-test"
            override val context = this@SendBulkSemanticsTest.context
            override suspend fun send(email: Email) {
                if (email.subject == "boom") error("simulated provider failure")
            }
        }

        val emails = listOf(
            Email(subject = "ok-1", to = to, plainText = "a"),
            Email(subject = "boom", to = to, plainText = "b"),
            Email(subject = "ok-2", to = to, plainText = "c"),
        )

        val ex = assertFailsWith<IllegalStateException> { service.sendBulk(emails) }
        assertTrue(ex.message!!.contains("simulated provider failure"))
    }

    @Test
    fun personalizationBulkDispatchesConcurrently() = runTest {
        val arrivals = mutableListOf<String>()
        val release = CompletableDeferred<Unit>()
        val service = object : EmailService {
            override val name: String = "personalization-test"
            override val context = this@SendBulkSemanticsTest.context
            override suspend fun send(email: Email) {
                arrivals += email.subject
                release.await()
            }
        }

        val template = Email(subject = "base", to = listOf(EmailAddressWithName("x@example.com")), plainText = "")
        val personalizations = listOf(
            EmailPersonalization(subject = "p-0"),
            EmailPersonalization(subject = "p-1"),
            EmailPersonalization(subject = "p-2"),
        )

        coroutineScope {
            val job = launch { service.sendBulk(template, personalizations) }
            withTimeout(2.seconds) {
                while (arrivals.size < 3) {
                    kotlinx.coroutines.yield()
                }
            }
            assertEquals(setOf("p-0", "p-1", "p-2"), arrivals.toSet())
            release.complete(Unit)
            job.join()
        }
    }
}
