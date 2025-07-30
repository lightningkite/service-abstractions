package com.lightningkite.serviceabstractions.metrics.cloudwatch


import com.lightningkite.serviceabstractions.HealthStatus
import com.lightningkite.serviceabstractions.MetricEvent
import com.lightningkite.serviceabstractions.MetricSink
import com.lightningkite.serviceabstractions.MetricType
import com.lightningkite.serviceabstractions.MetricUnit
import com.lightningkite.serviceabstractions.ReportingContextElement
import com.lightningkite.serviceabstractions.SettingContext
import com.lightningkite.serviceabstractions.aws.AwsConnections
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import software.amazon.awssdk.auth.credentials.AwsCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient
import software.amazon.awssdk.services.cloudwatch.model.Dimension
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum
import software.amazon.awssdk.services.cloudwatch.model.StandardUnit
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.collections.chunked

public class CloudwatchMetricSink(
    override val context: SettingContext,
    public val namespace: String,
    public val region: Region,
    credentialProvider: AwsCredentialsProvider,
) : MetricSink {
    public companion object {
        private val logger = KotlinLogging.logger("com.lightningkite.serviceabstractions.metrics.cloudwatch")

        init {
            MetricSink.Settings.register("cloudwatch") { url, context ->
                Regex("""cloudwatch://((?:(?<user>[a-zA-Z0-9+/]+):(?<password>[a-zA-Z0-9+/]+)@)?(?<region>[a-zA-Z0-9-]+))/(?<namespace>[^?]+)""").matchEntire(
                    url
                )?.let { match ->
                    val user = match.groups["user"]?.value ?: ""
                    val password = match.groups["password"]?.value ?: ""
                    val namespace = match.groups["namespace"]?.value ?: "nonamespace"
                    val region = Region.of(match.groups["region"]!!.value.lowercase())
                    CloudwatchMetricSink(
                        context,
                        namespace,
                        region,
                        if (user.isNotBlank() && password.isNotBlank()) {
                            StaticCredentialsProvider.create(object : AwsCredentials {
                                override fun accessKeyId(): String = user
                                override fun secretAccessKey(): String = password
                            })
                        } else DefaultCredentialsProvider.create(),
                    )
                }
                    ?: throw IllegalStateException("Invalid CloudWatch metrics URL. The URL should match the pattern: cloudwatch://[user]:[password]@[region]/[namespace]")
            }
        }
    }

    override suspend fun healthCheck(): HealthStatus {
        return AwsConnections.health
    }

    private val cw = CloudWatchAsyncClient.builder()
        .region(region)
        .credentialsProvider(credentialProvider)
        .httpClient(AwsConnections.asyncClient)
        .overrideConfiguration(AwsConnections.clientOverrideConfiguration)
        .build()

    private val queue = ConcurrentLinkedQueue<MetricDatum>()
    override suspend fun report(reportingInfo: ReportingContextElement) {
        queue.addAll(reportingInfo.metricSums.map {
            MetricDatum.builder()
                .value(it.value)
                .metricName((it.type.service?.toString() ?: "Overall") + "/" + it.type.name)
                .dimensions(
                    Dimension.builder()
                        .name("context")
                        .value(reportingInfo.context)
                        .build()
                )
                .unit(
                    when (it.type.unit) {
                        MetricUnit.Seconds -> StandardUnit.SECONDS
                        MetricUnit.Bytes -> StandardUnit.BYTES
                        MetricUnit.Percent -> StandardUnit.PERCENT
                        MetricUnit.Count -> StandardUnit.COUNT
                        MetricUnit.BytesPerSecond -> StandardUnit.BYTES_SECOND
                        MetricUnit.CountPerSecond -> StandardUnit.COUNT_SECOND
                        MetricUnit.Microseconds -> StandardUnit.MICROSECONDS
                        MetricUnit.Milliseconds -> StandardUnit.MILLISECONDS
                        MetricUnit.Kilobytes -> StandardUnit.KILOBYTES
                        MetricUnit.Megabytes -> StandardUnit.MEGABYTES
                        MetricUnit.Gigabytes -> StandardUnit.GIGABYTES
                        MetricUnit.Terabytes -> StandardUnit.TERABYTES
                        MetricUnit.Bits -> StandardUnit.BITS
                        MetricUnit.Kilobits -> StandardUnit.KILOBITS
                        MetricUnit.Megabits -> StandardUnit.MEGABITS
                        MetricUnit.Gigabits -> StandardUnit.GIGABITS
                        MetricUnit.Terabits -> StandardUnit.TERABITS
                        MetricUnit.KilobytesPerSecond -> StandardUnit.KILOBYTES_SECOND
                        MetricUnit.MegabytesPerSecond -> StandardUnit.MEGABYTES_SECOND
                        MetricUnit.GigabytesPerSecond -> StandardUnit.GIGABYTES_SECOND
                        MetricUnit.TerabytesPerSecond -> StandardUnit.TERABYTES_SECOND
                        MetricUnit.BitsPerSecond -> StandardUnit.BITS_SECOND
                        MetricUnit.KilobitsPerSecond -> StandardUnit.KILOBITS_SECOND
                        MetricUnit.MegabitsPerSecond -> StandardUnit.MEGABITS_SECOND
                        MetricUnit.GigabitsPerSecond -> StandardUnit.GIGABITS_SECOND
                        MetricUnit.TerabitsPerSecond -> StandardUnit.TERABITS_SECOND
                        MetricUnit.Other -> StandardUnit.NONE
                    }
                )
                .build()
        })
    }

    override suspend fun flush() {
        val list = ArrayList<MetricDatum>()
        while (true) {
            queue.poll()?.let { list.add(it) } ?: break
        }
        cw.putMetricData {
            it.metricData(list)
            it.namespace(namespace)
        }.await()
    }
}