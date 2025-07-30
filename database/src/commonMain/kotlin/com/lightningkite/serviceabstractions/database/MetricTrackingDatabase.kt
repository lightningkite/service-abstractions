package com.lightningkite.serviceabstractions.database

import kotlinx.serialization.KSerializer

class MetricTrackingDatabase(val wraps: Database, val metricsKeyName: String): Database by wraps {
    override fun <T : Any> collection(serializer: KSerializer<T>, name: String): FieldCollection<T> = wraps.collection<T>(serializer, name).metrics(metricsKeyName)
}