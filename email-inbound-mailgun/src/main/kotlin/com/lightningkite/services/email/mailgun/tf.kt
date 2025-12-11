package com.lightningkite.services.email.mailgun

import com.lightningkite.services.Untested
import com.lightningkite.services.email.EmailInboundService
import com.lightningkite.services.terraform.TerraformEmitter
import com.lightningkite.services.terraform.TerraformNeed
import kotlinx.serialization.json.JsonPrimitive

/**
 * Configures Mailgun Routes for receiving emails.
 *
 * Mailgun Routes is a hosted service that requires manual configuration
 * in the Mailgun dashboard or via their API. This terraform helper registers
 * the setting and provides documentation for the required setup.
 *
 * ## Mailgun Dashboard Setup
 *
 * 1. Go to Sending > Routes in Mailgun dashboard
 * 2. Click "Create Route"
 * 3. Configure:
 *    - **Expression Type**: Match Recipient
 *    - **Recipient**: `.*@yourdomain.com` (or specific address)
 *    - **Actions**: Forward to your webhook URL
 *    - **Priority**: 0 (or as needed)
 *
 * ## DNS Setup
 *
 * Add MX records for your domain pointing to Mailgun:
 * ```
 * example.com  MX  10  mxa.mailgun.org
 * example.com  MX  10  mxb.mailgun.org
 * ```
 *
 * ## Webhook Format
 *
 * Mailgun posts emails as `multipart/form-data` with fields:
 * - `sender`, `from`, `recipient`, `subject`
 * - `body-plain`, `body-html`, `stripped-text`, `stripped-html`
 * - `Message-Id`, `In-Reply-To`, `References`
 * - `timestamp`, `token`, `signature` (for verification)
 * - Attachments as file parts with `attachment-count` field
 *
 * ## Security
 *
 * Mailgun signs webhook requests. Verify using:
 * - `timestamp` + `token` + your API key → HMAC-SHA256 → compare with `signature`
 *
 * The [MailgunEmailInboundService] can optionally verify signatures if you provide
 * the signing key in the URL: `mailgun://signing-key@`
 *
 * @param webhookUrl The URL where Mailgun will POST incoming emails.
 *   Used for documentation/output purposes only - must be configured manually in Mailgun.
 * @param signingKey Optional Mailgun webhook signing key for signature verification.
 */
@Untested
context(emitter: TerraformEmitter)
public fun TerraformNeed<EmailInboundService.Settings>.mailgunRoutes(
    webhookUrl: String? = null,
    signingKey: String? = null,
): Unit {
    if (!EmailInboundService.Settings.supports("mailgun")) {
        throw IllegalArgumentException("You need to reference MailgunEmailInboundService in your server definition to use this.")
    }

    val settingUrl = if (signingKey != null) {
        "mailgun://$signingKey@"
    } else {
        "mailgun://"
    }
    emitter.fulfillSetting(name, JsonPrimitive(settingUrl))

    emitter.emit(name) {
        // Output for documentation
        "output.${name}_setup_instructions" {
            "value" - """
                |Mailgun Routes requires manual configuration:
                |1. Go to Mailgun Dashboard > Sending > Routes
                |2. Create a route matching your recipient pattern
                |3. Set action to forward to: ${webhookUrl ?: "<your-webhook-url>"}
                |4. Add MX records: <domain> MX 10 mxa.mailgun.org, mxb.mailgun.org
            """.trimMargin()
            "description" - "Setup instructions for Mailgun Routes"
        }

        if (webhookUrl != null) {
            "output.${name}_webhook_url" {
                "value" - webhookUrl
                "description" - "Webhook URL to configure in Mailgun routes"
            }
        }
    }
}
