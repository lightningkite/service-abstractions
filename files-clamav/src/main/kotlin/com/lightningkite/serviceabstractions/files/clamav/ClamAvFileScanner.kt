package com.lightningkite.serviceabstractions.files.clamav

import com.lightningkite.MediaType
import com.lightningkite.services.HealthStatus
import com.lightningkite.services.SettingContext
import com.lightningkite.services.files.FileScanException
import com.lightningkite.services.files.FileScanner
import kotlinx.io.Source
import kotlinx.io.asInputStream
import xyz.capybara.clamav.ClamavClient
import xyz.capybara.clamav.Platform
import xyz.capybara.clamav.commands.scan.result.ScanResult
import java.io.InputStream

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
 * - **Lazy client**: ClamAV client created on first use via `get()` factory
 * - **Stream processing**: File contents streamed directly to clamd (no temp files)
 * - **Exception on detection**: Throws FileScanException if virus found
 *
 * ## Important Gotchas
 *
 * - **clamd must be running**: Scanner will fail if clamd is not accessible
 * - **Virus definitions**: Ensure clamd has up-to-date virus definitions (via freshclam)
 * - **Max file size**: clamd has default limits (StreamMaxLength, typically 25MB)
 * - **Network timeout**: Health check uses 5-second timeout
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
 * @property get Lazy factory for creating ClamAV client (enables reconnection)
 */
public class ClamAvFileScanner(
    override val name: String,
    override val context: SettingContext,
    private val get: () -> ClamavClient,
) : FileScanner {
    override fun requires(claimedType: MediaType): FileScanner.Requires = FileScanner.Requires.Whole

    public companion object {
        public fun FileScanner.Settings.Companion.clamav(
            host: String = "localhost",
            port: Int = 3310,
            platform: Platform = Platform.UNIX
        ): FileScanner.Settings = FileScanner.Settings("clamav://$host:$port/$platform")
        init {
            FileScanner.Settings.register("clamav") { name, url, context ->
                Regex("""clamav://(?<host>[^:/]+):?(?<port>[0-9]+)?/(?<platform>[^/]+)?(\?(?<params>.*))?""").matchEntire(url)
                    ?.let { match ->
                        val host = match.groups.get("host")!!.value
                        val port = match.groups.get("port")?.value?.toInt() ?: 3310
                        val platform = match.groups.get("platform")?.value?.let { Platform.valueOf(it) } ?: Platform.JVM_PLATFORM
                        ClamAvFileScanner(name, context) { ClamavClient(host, port, platform) }
                    }
                    ?: throw IllegalStateException("Invalid ClamAV. It must follow the pattern: clamav://host[:port]/[UNIX or WINDOWS]")
            }
        }
    }

    override suspend fun scan(claimedType: MediaType, data: Source) {
        when(val r = data.use{ it -> get().scan(it.asInputStream()) }) {
            ScanResult.OK -> {}
            is ScanResult.VirusFound -> throw FileScanException("File seems to contain malicious content; ${r.foundViruses.keys.joinToString()}")
        }
    }

    override suspend fun healthCheck(): HealthStatus {
        return if(get().isReachable(5_000)) HealthStatus(HealthStatus.Level.OK)
        else HealthStatus(HealthStatus.Level.ERROR, additionalMessage = "Service not reachable")
    }
}