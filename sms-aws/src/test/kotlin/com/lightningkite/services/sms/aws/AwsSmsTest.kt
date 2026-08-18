package com.lightningkite.services.sms.aws

import aws.sdk.kotlin.services.pinpointsmsvoicev2.model.DescribeAccountAttributesRequest
import aws.sdk.kotlin.services.pinpointsmsvoicev2.model.DescribeAccountAttributesResponse
import aws.sdk.kotlin.services.pinpointsmsvoicev2.model.SendTextMessageRequest
import aws.sdk.kotlin.services.pinpointsmsvoicev2.model.SendTextMessageResponse
import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.data.toPhoneNumber
import com.lightningkite.services.sms.SMSException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Exercises [AwsSms]'s real request-building using internal hooks,
 * so the exact SDK parameters sent to AWS can be asserted without live calls or mock libraries.
 */

class MockAwsSmsEngine(
    private val onSend: suspend (SendTextMessageRequest) -> SendTextMessageResponse = { SendTextMessageResponse {} },
    private val onDescribe: suspend (DescribeAccountAttributesRequest) -> DescribeAccountAttributesResponse = { DescribeAccountAttributesResponse {} }
) : AwsSmsEngine {
    override suspend fun sendTextMessage(request: SendTextMessageRequest): SendTextMessageResponse = onSend(request)
    override suspend fun describeAccountAttributes(request: DescribeAccountAttributesRequest): DescribeAccountAttributesResponse = onDescribe(request)
    override suspend fun close() {}
}

class AwsSmsTest {

    /** Builds the service and captures the outgoing request for testing. */
    private fun service(
        onSend: suspend (SendTextMessageRequest) -> SendTextMessageResponse
    ): AwsSms {
        return AwsSms(
            name = "aws-test",
            context = TestSettingContext(),
            region = "us-east-1",
            originationIdentity = "+15551234567", // Set to match test requirements
            engine = MockAwsSmsEngine(onSend = onSend),
        )
    }

    @Test
    fun send_toUsNumber_sendsRawE164FormatNotDisplayFormat() = runTest {
        var capturedRequest: SendTextMessageRequest? = null
        val svc = service { request ->
            capturedRequest = request
            SendTextMessageResponse { messageId = "test-message-id-123" }
        }

        svc.send("+15559876543".toPhoneNumber(), "Your code is 123456")

        // AWS API requires strict E.164; PhoneNumber.toString() would have sent
        // "+1 (555) 987-6543" (display formatting) instead of this raw wire format.
        assertEquals("+15559876543", capturedRequest?.destinationPhoneNumber)
    }

    @Test
    fun send_toInternationalNumber_sendsRawFormat() = runTest {
        var capturedRequest: SendTextMessageRequest? = null
        val svc = service { request ->
            capturedRequest = request
            SendTextMessageResponse { messageId = "test-message-id-123" }
        }

        svc.send("+442071838750".toPhoneNumber(), "Hello")

        assertEquals("+442071838750", capturedRequest?.destinationPhoneNumber)
    }

    @Test
    fun send_originationIdentityAndBody_areSentUnmodified() = runTest {
        var capturedRequest: SendTextMessageRequest? = null
        val svc = service { request ->
            capturedRequest = request
            SendTextMessageResponse { messageId = "test-message-id-123" }
        }

        svc.send("+15559876543".toPhoneNumber(), "Your code is 123456")

        assertEquals("+15551234567", capturedRequest?.originationIdentity)
        assertEquals("Your code is 123456", capturedRequest?.messageBody)
        assertEquals("TRANSACTIONAL", capturedRequest?.messageType?.value)
    }

    @Test
    fun send_sdkException_throwsSmsException() = runTest {
        var attempts = 0
        val svc = service { request ->
            attempts++
            // Simulate an AWS exception thrown by the SDK when a number is invalid
            throw Exception("Invalid parameter: PhoneNumber")
        }

        assertFailsWith<SMSException> {
            svc.send("+15559876543".toPhoneNumber(), "Hello")
        }

        assertEquals(1, attempts, "An SDK exception should not be retried infinitely")
    }
}