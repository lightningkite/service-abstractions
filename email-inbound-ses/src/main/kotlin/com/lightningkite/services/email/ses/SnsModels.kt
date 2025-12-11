package com.lightningkite.services.email.ses

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * SNS notification wrapper that contains the SES notification in the Message field.
 *
 * AWS SNS wraps all notifications in this envelope format. The actual SES notification
 * is JSON-encoded in the [Message] field and must be parsed separately.
 *
 * @property Type The type of SNS notification (e.g., "Notification", "SubscriptionConfirmation")
 * @property MessageId Unique identifier for this SNS message
 * @property TopicArn ARN of the SNS topic
 * @property Subject Optional subject line
 * @property Message The actual notification content (SES notification JSON for email notifications)
 * @property Timestamp ISO 8601 timestamp of when the notification was sent
 * @property SignatureVersion Version of the SNS signature
 * @property Signature Digital signature for verifying the message authenticity
 * @property SigningCertURL URL to the certificate used for signing
 * @property UnsubscribeURL URL to unsubscribe from the SNS topic
 * @property SubscribeURL URL to confirm subscription (only present for SubscriptionConfirmation)
 * @property Token Subscription confirmation token (only present for SubscriptionConfirmation)
 */
@Serializable
public data class SnsNotification(
    @SerialName("Type") val Type: String,
    @SerialName("MessageId") val MessageId: String,
    @SerialName("TopicArn") val TopicArn: String? = null,
    @SerialName("Subject") val Subject: String? = null,
    @SerialName("Message") val Message: String,
    @SerialName("Timestamp") val Timestamp: String,
    @SerialName("SignatureVersion") val SignatureVersion: String,
    @SerialName("Signature") val Signature: String,
    @SerialName("SigningCertURL") val SigningCertURL: String,
    @SerialName("UnsubscribeURL") val UnsubscribeURL: String? = null,
    @SerialName("SubscribeURL") val SubscribeURL: String? = null,
    @SerialName("Token") val Token: String? = null
)

/**
 * AWS SES inbound email notification.
 *
 * This is the structure of the notification sent by SES when an email is received.
 * It's JSON-encoded in the SNS Message field.
 *
 * @property notificationType Type of notification (should be "Received" for inbound emails)
 * @property mail Metadata about the received email
 * @property receipt Receipt information including spam/virus verdicts and actions
 * @property content Raw MIME content of the email (may be null if stored in S3)
 */
@Serializable
public data class SesNotification(
    val notificationType: String,
    val mail: SesMailObject,
    val receipt: SesReceiptObject,
    val content: String? = null
)

/**
 * Mail metadata from SES notification.
 *
 * Contains information about the email extracted from SMTP and MIME headers.
 *
 * @property timestamp ISO 8601 timestamp when SES received the email
 * @property messageId SES-assigned message ID
 * @property source SMTP envelope sender (MAIL FROM)
 * @property destination SMTP envelope recipients (RCPT TO)
 * @property headersTruncated Whether headers were truncated
 * @property headers All email headers (may be truncated)
 * @property commonHeaders Commonly used headers extracted for convenience
 */
@Serializable
public data class SesMailObject(
    val timestamp: String,
    val messageId: String,
    val source: String,
    val destination: List<String>,
    val headersTruncated: Boolean = false,
    val headers: List<SesHeader> = emptyList(),
    val commonHeaders: SesCommonHeaders
)

/**
 * Email header as provided by SES.
 *
 * @property name Header name (e.g., "From", "Subject")
 * @property value Header value
 */
@Serializable
public data class SesHeader(
    val name: String,
    val value: String
)

/**
 * Commonly used email headers extracted by SES for convenience.
 *
 * @property from List of From addresses
 * @property to List of To addresses (may be empty if not present in headers)
 * @property cc List of CC addresses (may be empty)
 * @property bcc List of BCC addresses (may be empty)
 * @property subject Email subject
 * @property messageId Message-ID from headers
 * @property date Date header
 * @property returnPath Return-Path header
 */
@Serializable
public data class SesCommonHeaders(
    val from: List<String>? = null,
    val to: List<String>? = null,
    val cc: List<String>? = null,
    val bcc: List<String>? = null,
    val subject: String? = null,
    val messageId: String? = null,
    val date: String? = null,
    val returnPath: String? = null
)

/**
 * Receipt information from SES including verdicts and actions.
 *
 * @property timestamp ISO 8601 timestamp when the email was processed
 * @property recipients List of recipients that triggered this receipt rule
 * @property spamVerdict Spam detection verdict
 * @property virusVerdict Virus scan verdict
 * @property spfVerdict SPF check verdict
 * @property dkimVerdict DKIM check verdict
 * @property dmarcVerdict DMARC check verdict
 * @property action Action that was triggered by the receipt rule
 * @property processingTimeMillis Time taken to process the email
 */
@Serializable
public data class SesReceiptObject(
    val timestamp: String,
    val recipients: List<String>,
    val spamVerdict: SesVerdict,
    val virusVerdict: SesVerdict,
    val spfVerdict: SesVerdict? = null,
    val dkimVerdict: SesVerdict? = null,
    val dmarcVerdict: SesVerdict? = null,
    val action: SesAction? = null,
    val processingTimeMillis: Long? = null
)

/**
 * Verdict from SES email scanning.
 *
 * @property status Verdict status ("PASS", "FAIL", "GRAY", "PROCESSING_FAILED")
 */
@Serializable
public data class SesVerdict(
    val status: String
)

/**
 * Action taken by SES receipt rule.
 *
 * @property type Type of action (e.g., "SNS", "S3", "Lambda")
 * @property topicArn ARN of SNS topic (for SNS action)
 * @property bucketName S3 bucket name (for S3 action)
 * @property objectKey S3 object key (for S3 action)
 */
@Serializable
public data class SesAction(
    val type: String,
    val topicArn: String? = null,
    val bucketName: String? = null,
    val objectKey: String? = null
)
