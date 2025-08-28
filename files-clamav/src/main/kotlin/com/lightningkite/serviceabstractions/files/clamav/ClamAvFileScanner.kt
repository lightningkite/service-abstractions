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