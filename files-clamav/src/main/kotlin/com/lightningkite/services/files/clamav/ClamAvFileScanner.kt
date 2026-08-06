package com.lightningkite.services.files.clamav

import com.lightningkite.services.SettingContext
import com.lightningkite.services.data.HealthStatus
import com.lightningkite.services.data.MediaType
import com.lightningkite.services.telemetry.TelemetryAttributes
import com.lightningkite.services.telemetry.TelemetryKey
import com.lightningkite.services.files.FileScanException
import com.lightningkite.services.files.FileScanner
import com.lightningkite.services.telemetry.telemetryTrace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.Source
import kotlinx.io.asInputStream
import xyz.capybara.clamav.ClamavClient
import xyz.capybara.clamav.Platform
import xyz.capybara.clamav.commands.scan.result.ScanResult
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * ClamAV antivirus implementation of the FileScanner abstraction.
 *
 * Provides malware scanning capabilities using ClamAV daemon (clamd) via TCP connection.
 * This scanner requires access to a running ClamAV daemon instance.
 *
 * ## Features
 *
 * - **Virus detection**: Scans files for malware using ClamAV virus definitions
 * - **Stream scanning**: Sends file contents to clamd via network stream (no disk writes)
 * - **Health monitoring**: Validates clamd connectivity via ping
 * - **Platform support**: Works with Unix socket or TCP connections (JVM/UNIX/WINDOWS)
 *
 * ## Supported URL Schemes
 *
 * - `clamav://host:port/platform` - Connect to ClamAV daemon
 *
 * Format: `clamav://[host]:[port]/[UNIX|JVM_PLATFORM|WINDOWS]`
 *
 * ## Configuration Examples
 *
 * ```kotlin
 * // Local ClamAV daemon on default port
 * FileScanner.Settings("clamav://localhost:3310/JVM_PLATFORM")
 *
 * // Production ClamAV on remote host
 * FileScanner.Settings("clamav://clamav.internal:3310/UNIX")
 *
 * // Using helper function
 * FileScanner.Settings.Companion.clamav(host = "localhost", port = 3310, platform = Platform.UNIX)
 * ```
 *
 * ## Implementation Notes
 *
 * - **Requires whole file**: This scanner needs the complete file contents (FileScanner.Requires.Whole)
 * - **Network dependency**: Requires network access to clamd daemon
 * - **Cached client**: ClamAV client is reused across calls; recreated on scan/connection errors.
 * - **Stream processing**: File contents streamed directly to clamd (no temp files)
 * - **Exception on detection**: Throws FileScanException if virus found
 *
 * ## Important Gotchas
 *
 * - **clamd must be running**: Scanner will fail if clamd is not accessible
 * - **Virus definitions**: Ensure clamd has up-to-date virus definitions (via freshclam)
 * - **Max file size**: clamd has default limits (StreamMaxLength, typically 25MB)
 * - **Network timeout**: Health check uses a 5-second timeout; [scan] uses [scanTimeout] (default 30s)
 * - **Platform parameter**: Must match clamd socket configuration (UNIX socket vs TCP)
 * - **No streaming resume**: If scan fails mid-stream, entire file must be rescanned
 *
 * ## ClamAV Daemon Setup
 *
 * You need a running ClamAV daemon. Typical setup:
 * ```bash
 * # Install ClamAV
 * apt-get install clamav-daemon
 *
 * # Update virus definitions
 * freshclam
 *
 * # Start daemon
 * systemctl start clamav-daemon
 *
 * # Configure TCP access in /etc/clamav/clamd.conf
 * TCPSocket 3310
 * TCPAddr 0.0.0.0
 * ```
 *
 * @property name Service name for logging/metrics
 * @property context Service context
 * @property get Factory for creating a fresh ClamAV client (used on first use and after errors)
 * @property scanTimeout Maximum time to wait for a single [scan] call. The underlying clamd client
 * opens a plain blocking socket with no read timeout of its own, so an unresponsive daemon would
 * otherwise wedge the calling coroutine forever; a timeout is treated as a scan failure (fail-closed).
 */
public class ClamAvFileScanner(
    override val name: String,
    override val context: SettingContext,
    private val get: () -> ClamavClient,
    private val scanTimeout: Duration = 30.seconds,
) : FileScanner {
    override fun requires(claimedType: MediaType): FileScanner.Requires = FileScanner.Requires.Whole

    /** Cached client instance. Recreated on ScannerException or connection error. */
    @Volatile private var cachedClient: ClamavClient? = null

    private fun client(): ClamavClient = cachedClient ?: get().also { cachedClient = it }

    private fun invalidateClient() {
        cachedClient = null
    }

    public companion object {
        public fun FileScanner.Settings.Companion.clamav(
            host: String = "localhost",
            port: Int = 3310,
            platform: Platform = Platform.UNIX,
            scanTimeout: Duration = 30.seconds,
        ): FileScanner.Settings =
            FileScanner.Settings("clamav://$host:$port/$platform?timeoutSeconds=${scanTimeout.inWholeSeconds}")

        init {
            FileScanner.Settings.register("clamav") { name, url, context ->
                Regex("""clamav://(?<host>[^:/]+):?(?<port>[0-9]+)?/(?<platform>[^/?]+)?(\?(?<params>.*))?""").matchEntire(
                    url
                )
                    ?.let { match ->
                        val host = match.groups.get("host")!!.value
                        val port = match.groups.get("port")?.value?.toInt() ?: 3310
                        val platform =
                            match.groups.get("platform")?.value?.let { Platform.valueOf(it) } ?: Platform.JVM_PLATFORM
                        val timeoutSeconds = match.groups.get("params")?.value
                            ?.split("&")
                            ?.map { it.substringBefore('=') to it.substringAfter('=', "") }
                            ?.firstOrNull { it.first == "timeoutSeconds" }
                            ?.second?.toLongOrNull()
                        ClamAvFileScanner(
                            name,
                            context,
                            get = { ClamavClient(host, port, platform) },
                            scanTimeout = timeoutSeconds?.seconds ?: 30.seconds,
                        )
                    }
                    ?: throw IllegalStateException("Invalid ClamAV. It must follow the pattern: clamav://host[:port]/[UNIX or WINDOWS]")
            }
        }
    }

    override suspend fun scan(claimedType: MediaType, data: Source): Unit = telemetryTrace(
        "scan",
        attributes = TelemetryAttributes { put(TelemetryKey.OfString("content_type"), claimedType.toString()) },
    ) { span ->
        val startedAt = TimeSource.Monotonic.markNow()
        val result = try {
            withTimeout(scanTimeout) {
                // runInterruptible, not withContext: the clamav-client performs a *blocking*
                // SocketChannel read with no timeout of its own. withTimeout can only abandon a
                // coroutine at a cancellable suspension point, and a blocking read offers none — so
                // withContext(Dispatchers.IO) would leave scan() hanging forever despite the timeout
                // firing. runInterruptible interrupts the worker thread, and SocketChannel is an
                // InterruptibleChannel, so the read aborts with ClosedByInterruptException.
                runInterruptible(Dispatchers.IO) {
                    data.use { source -> client().scan(source.asInputStream()) }
                }
            }
        } catch (e: Exception) {
            // Invalidate the cached client - clamd may still be mid-response on that connection.
            invalidateClient()
            // A timeout does NOT reliably surface as TimeoutCancellationException here: our interrupt
            // aborts the blocking socket read with ClosedByInterruptException, which clamav-client
            // catches and re-wraps as its own CommunicationException before cancellation can
            // propagate. So the elapsed time, not the exception type, is what identifies a timeout.
            if (e is TimeoutCancellationException || startedAt.elapsedNow() >= scanTimeout) {
                // Fail-closed: a scanner that can't be reached within the timeout must never be
                // treated as "file is clean". FileScanException makes this an unambiguous scan
                // failure rather than something mistakable for routine coroutine cancellation.
                throw FileScanException("ClamAV scan timed out after $scanTimeout", e)
            }
            throw e
        }
        when (result) {
            ScanResult.OK -> {
                span.enrich(TelemetryAttributes { put(TelemetryKey.OfString("clamav.result"), "OK") })
            }
            is ScanResult.VirusFound -> {
                val viruses = result.foundViruses.keys.joinToString()
                span.enrich(TelemetryAttributes {
                    put(TelemetryKey.OfString("clamav.result"), "VirusFound")
                    put(TelemetryKey.OfString("clamav.viruses"), viruses)
                })
                // Throwing marks the trace's outcome as an error automatically.
                throw FileScanException("File seems to contain malicious content; $viruses")
            }
        }
    }

    override suspend fun healthCheck(): HealthStatus {
        return withContext(Dispatchers.IO) {
            if (get().isReachable(5_000)) HealthStatus(HealthStatus.Level.OK)
            else HealthStatus(HealthStatus.Level.ERROR, additionalMessage = "Service not reachable")
        }
    }
}
