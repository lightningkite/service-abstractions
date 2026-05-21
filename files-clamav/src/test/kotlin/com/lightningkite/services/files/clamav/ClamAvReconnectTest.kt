package com.lightningkite.services.files.clamav

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.files.scan
import kotlinx.coroutines.runBlocking
import xyz.capybara.clamav.ClamavClient
import xyz.capybara.clamav.Platform
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails

/**
 * Verifies that [ClamAvFileScanner] invalidates its cached [ClamavClient] when a scan throws,
 * and creates a fresh client via the factory on the next call.
 *
 * No real ClamAV daemon is required: each [ClamavClient] is pointed at 127.0.0.1:1
 * (closed port), causing the underlying socket connect to fail and the call to throw.
 * What we assert is the *factory call count* — which proves the cached client was
 * invalidated rather than reused.
 */
class ClamAvReconnectTest {
    @Test
    fun cachedClientInvalidatedOnError(): Unit = runBlocking {
        // Pointing at a port nothing is listening on guarantees scan() will throw.
        // ClamavClient is `open` but its scan(InputStream) is `final`, so we can't override it.
        // Instead we let the real method run and fail at socket connect.
        val unreachable = InetSocketAddress("127.0.0.1", 1)
        val factoryCalls = AtomicInteger(0)

        val scanner = ClamAvFileScanner(
            name = "test",
            context = TestSettingContext(),
            get = {
                factoryCalls.incrementAndGet()
                ClamavClient(unreachable, Platform.JVM_PLATFORM)
            },
        )

        // First scan: factory invoked once, scan fails, cached client is invalidated.
        assertFails {
            scanner.scan(TypedData.text("hello", MediaType.Text.Plain))
        }
        assertEquals(1, factoryCalls.get(), "Factory should be called on first scan")

        // Second scan: if the broken client were reused the factory count would stay at 1.
        // Because invalidateClient() was called after the first failure, the factory must run again.
        assertFails {
            scanner.scan(TypedData.text("hello again", MediaType.Text.Plain))
        }
        assertEquals(2, factoryCalls.get(), "Factory must be called again after error invalidates cache")
    }
}
