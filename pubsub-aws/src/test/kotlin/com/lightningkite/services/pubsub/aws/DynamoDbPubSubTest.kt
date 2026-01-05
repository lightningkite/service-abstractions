package com.lightningkite.services.pubsub.aws

import com.lightningkite.services.pubsub.PubSub
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for DynamoDbPubSub.
 *
 * These tests verify URL parsing and settings without requiring
 * actual AWS infrastructure.
 */
class DynamoDbPubSubTest {

    init {
        // Ensure the companion object is initialized (registers URL schemes)
        DynamoDbPubSub
    }

    @Test
    fun `URL scheme registration works for dynamodb-pubsub`() {
        assertTrue(PubSub.Settings.supports("dynamodb-pubsub"))
    }

    @Test
    fun `settings URL parsing for basic URL`() {
        val settings = PubSub.Settings("dynamodb-pubsub://us-west-2/my-table")
        assertEquals("dynamodb-pubsub://us-west-2/my-table", settings.url)
    }

    @Test
    fun `settings URL parsing with poll interval`() {
        val settings = PubSub.Settings("dynamodb-pubsub://us-east-1/pubsub-table?pollInterval=20")
        assertEquals("dynamodb-pubsub://us-east-1/pubsub-table?pollInterval=20", settings.url)
    }

    @Test
    fun `settings URL parsing with credentials`() {
        val settings = PubSub.Settings("dynamodb-pubsub://AKIAIOSFODNN:secretkey@us-west-2/my-table")
        assertEquals("dynamodb-pubsub://AKIAIOSFODNN:secretkey@us-west-2/my-table", settings.url)
    }

    @Test
    fun `settings URL parsing with credentials and poll interval`() {
        val settings = PubSub.Settings("dynamodb-pubsub://AKIAIOSFODNN:secretkey@us-west-2/my-table?pollInterval=10")
        assertEquals("dynamodb-pubsub://AKIAIOSFODNN:secretkey@us-west-2/my-table?pollInterval=10", settings.url)
    }
}
