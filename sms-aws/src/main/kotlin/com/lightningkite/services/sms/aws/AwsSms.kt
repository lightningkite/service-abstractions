package com.lightningkite.services.sms.aws

import aws.sdk.kotlin.runtime.auth.credentials.StaticCredentialsProvider
import aws.sdk.kotlin.services.pinpointsmsvoicev2.PinpointSmsVoiceV2Client
import aws.sdk.kotlin.services.pinpointsmsvoicev2.model.AccountAttributeName
import aws.sdk.kotlin.services.pinpointsmsvoicev2.model.MessageType
import aws.sdk.kotlin.services.pinpointsmsvoicev2.model.SendTextMessageRequest
import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import kotlinx.coroutines.CancellationException
import com.lightningkite.services.SettingContext
import com.lightningkite.services.data.HealthStatus
import com.lightningkite.services.data.PhoneNumber
import com.lightningkite.services.sms.SMS
import com.lightningkite.services.sms.SMSException
import com.lightningkite.services.telemetry.TelemetryAttributes
import com.lightningkite.services.telemetry.TelemetryKeys
import com.lightningkite.services.telemetry.telemetryTrace

/**
 * AWS End User Messaging SMS implementation.
 *
 * Provides direct, dedicated SMS delivery using the AWS End User Messaging API
 * (internally known in the SDK as PinpointSmsVoiceV2).
 *
 * ## Supported URL Schemes
 *
 * Format: `awssms://[accessKey]:[secretKey]@[region]?originationIdentity=[id]`
 *
 * The `originationIdentity` is REQUIRED and can be:
 * - A 10DLC number, Toll-Free number, or Short Code (e.g., +15551234567)
 * - An alphanumeric Sender ID (e.g., MyBrand)
 * - A Pool ID
 *
 * ## Configuration Examples
 *
 * ```kotlin
 * // Standard AWS Default Credentials (uses IAM roles, Env Vars, ~/.aws/credentials)
 * SMS.Settings("awssms://us-east-1?originationIdentity=+15551234567")
 *
 * // Explicit Credentials
 * SMS.Settings("awssms://AKIAXXXXXXXXXXXXXXXX:SecretKeyHere@us-east-1?originationIdentity=+15551234567")
 * ```
 *
 *
 * ## AWS Setup
 *  ### 1. AWS Console Configuration
 *   Before using this service, you must set up AWS End User Messaging SMS in the AWS Console:
 *   1. Navigate to **AWS End User Messaging SMS** (or Amazon Pinpoint).
 *   2. Request or register an **Origination Identity** (e.g., Toll-Free Number, 10DLC,
 *      Short Code, or Sender ID).
 *   3. Copy the phone number or Pool ID to use as your `originationIdentity`.
 *
 * * ### 2. Required IAM Permissions
 *   The IAM user or role executing this service must have the following IAM policy attached:
 *
 *   ```json
 *   {
 *     "Version": "2012-10-17",
 *     "Statement": [
 *       {
 *         "Effect": "Allow",
 *         "Action": [
 *           "sms-voice:SendTextMessage",
 *           "sms-voice:DescribeAccountAttributes"
 *         ],
 *         "Resource": "*"
 *       }
 *     ]
 *   }
 * ```
 *
 *
 *
 * @property name Service name for logging/metrics
 * @property context Service context
 * @property region The AWS region (e.g. us-west-2) to make requests to. See about AWS global infrastructure   for more information. When specified, this static region configuration takes precedence over other region resolution methods.
 * The region resolution order is:
 *  1. Static region (if specified)
 *  2. Custom region provider (if configured)
 *  3. Default region provider chain
 * @property accessKeyId aws Identifies the user interacting with services
 * @property secretAccessKey aws secret key used to authenticate the user and sign requests.
 * @property originationIdentity aws sender phone number.  The origination identity of the message. This can be either the PhoneNumber, PhoneNumberId, PhoneNumberArn, RcsAgentId, RcsAgentArn, SenderId, SenderIdArn, PoolId, or PoolArn.
 * If you are using a shared End User Messaging SMS resource then you must use the full Amazon Resource Name(ARN).
 */
public class AwsSms(
    override val name: String,
    override val context: SettingContext,
    private val region: String,
    private val accessKeyId: String? = null,
    private val secretAccessKey: String? = null,
    private val originationIdentity: String
) : SMS {
    private val client = PinpointSmsVoiceV2Client {
        this.region = this@AwsSms.region
        if (accessKeyId != null && secretAccessKey != null) {
            credentialsProvider = StaticCredentialsProvider(
                Credentials(accessKeyId = accessKeyId, secretAccessKey = secretAccessKey)
            )
        }
    }

    /**
     * Sends SMS message using the AWS PinpointSmsVoiceV2Client
     *
     *
     */

    override suspend fun send(to: PhoneNumber, message: String): Unit = telemetryTrace(
        "send",
        attributes = TelemetryAttributes {
            put(TelemetryKeys.Sms.operation, "send")
            put(TelemetryKeys.Messaging.system, "aws_end_user_messaging")
            put(TelemetryKeys.Sms.to, context.telemetrySanitization.redactPhoneNumber(to.raw))
            put(TelemetryKeys.Sms.from, context.telemetrySanitization.redactPhoneNumber(originationIdentity))
            put(TelemetryKeys.Sms.bodyLength, message.length.toLong())
        }
    ) { span ->
        try {
            val request = SendTextMessageRequest {
                this.destinationPhoneNumber = to.raw
                this.originationIdentity = this@AwsSms.originationIdentity
                this.messageBody = message

                // Set as Transactional to guarantee highest delivery priority
                // (Optimized for OTPs, alerts, and time-critical messages over promotional traffic)
                this.messageType = MessageType.Transactional
            }

            val response = client.sendTextMessage(request)

            span.enrich(TelemetryAttributes {
                put(TelemetryKeys.Sms.awsMessageId, response.messageId ?: "unknown")
            })

        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw SMSException("Failed to send AWS SMS via End User Messaging: ${e.message}")
        }
    }

    override suspend fun disconnect() {
        client.close()
    }

    override suspend fun healthCheck(): HealthStatus {
        return try {
            val response = client.describeAccountAttributes(aws.sdk.kotlin.services.pinpointsmsvoicev2.model.DescribeAccountAttributesRequest {})

            // Find if the account is in Sandbox or Production
            println("DEBUG response.accountAttributes ${response.accountAttributes}")
            val tier = response.accountAttributes?.firstOrNull { it.name == AccountAttributeName.AccountTier }?.value
            val isSandbox = tier?.equals("SANDBOX", ignoreCase = true) == true
            val safeIdentity = context.telemetrySanitization.redactPhoneNumber(originationIdentity)
            if (isSandbox) {
                HealthStatus(
                    HealthStatus.Level.WARNING,
                    additionalMessage = "AWS SMS is in SANDBOX mode. Messages can only be sent to verified phone numbers."
                )
            } else {
                HealthStatus(
                    HealthStatus.Level.OK,
                    additionalMessage = "AWS End User Messaging SMS - Region: $region, Identity: $safeIdentity, Tier: ${tier ?: "UNKNOWN"}"
                )
            }
        } catch (e: Exception) {
            HealthStatus(
                HealthStatus.Level.ERROR,
                additionalMessage = "AWS configuration error: ${e.message ?: e.toString()}"
            )
        }

    }

    public companion object {

        public fun SMS.Settings.Companion.awsSms(
            region: String,
            originationIdentity: String,
            accessKey: String? = null,
            secretKey: String? = null
        ): SMS.Settings {
            val authPart = if (accessKey != null && secretKey != null) "$accessKey:$secretKey@" else ""
            return SMS.Settings("awssms://$authPart$region?originationIdentity=$originationIdentity")
        }

        init {
            SMS.Settings.register("awssms") { name, url, context ->
                // Matches: awssms://[accessKey]:[secretKey]@[region]?originationIdentity=[identity]
                // OR simpler fallback: awssms://[region]?originationIdentity=[identity]
                val regex = Regex("""awssms://(?:(?<accessKey>[^:]+):(?<secretKey>[^@]+)@)?(?<region>[a-zA-Z0-9-]+)\?originationIdentity=(?<originationIdentity>.+)""")
                val match = regex.matchEntire(url)
                    ?: throw IllegalArgumentException(
                        "Invalid AWS SMS URL. Should match: awssms://[accessKey]:[secretKey]@[region]?originationIdentity=[identity]"
                    )

                val accessKey = match.groups["accessKey"]?.value
                val secretKey = match.groups["secretKey"]?.value
                val region = match.groups["region"]?.value
                    ?: throw IllegalArgumentException("AWS region not provided in URL")

                val originationIdentity = match.groups["originationIdentity"]?.value
                    ?: throw IllegalArgumentException("originationIdentity is required for AWS End User Messaging")

                AwsSms(name, context, region, accessKey, secretKey, originationIdentity)
            }
        }
    }
}