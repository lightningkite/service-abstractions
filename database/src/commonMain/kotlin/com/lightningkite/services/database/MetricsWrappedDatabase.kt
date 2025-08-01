package com.lightningkite.services.database

import com.lightningkite.services.countMetric
import com.lightningkite.services.performanceMetric
import kotlinx.serialization.KSerializer

open class MetricsWrappedDatabase(val wraps: Database, val metricsKeyName: String) : Database by wraps {
    override fun <T : Any> collection(serializer: KSerializer<T>, name: String): FieldCollection<T> =
        MetricsFieldCollection(
            wraps.collection<T>(serializer, name),
            metricsKeyName,
            performanceMetric("$metricsKeyName Wait Time"),
            countMetric("$metricsKeyName Call Count"),
        )
}
