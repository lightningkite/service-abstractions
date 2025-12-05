package com.lightningkite.services.email.sendgrid

import com.lightningkite.services.Untested
import com.lightningkite.services.email.EmailInboundService
import com.lightningkite.services.terraform.TerraformEmitter
import com.lightningkite.services.terraform.TerraformNeed
import kotlinx.serialization.json.JsonPrimitive

/**
 * Configures SendGrid Inbound Parse for receiving emails.
 *
 * SendGrid Inbound Parse is a hosted service that requires manual configuration
 * in the SendGrid dashboard. This terraform helper just registers the setting
 * and provides documentation for the required setup.
 *
 * ## SendGrid Dashboard Setup
 *
 * 1. Go to Settings > Inbound Parse in SendGrid dashboard
 * 2. Click "Add Host & URL"
 * 3. Configure:
 *    - **Receiving Domain**: Your domain (e.g., `mail.example.com`)
 *    - **Destination URL**: Your webhook endpoint (e.g., `https://api.example.com/webhooks/email`)
 *    - **Spam Check**: Enable if you want spam scoring
 *    - **Send Raw**: Disable (we parse the structured format)
 *    - **POST the raw, full MIME message**: Disable
 *
 * ## DNS Setup
 *
 * Add an MX record for your receiving domain:
 * ```
 * mail.example.com  MX  10  mx.sendgrid.net
 * ```
 *
 * ## Webhook Format
 *
 * SendGrid posts emails as `multipart/form-data` with fields:
 * - `from`, `to`, `cc`, `subject`, `text`, `html`
 * - `envelope` (JSON with SMTP envelope)
 * - `headers` (raw email headers)
 * - `spam_score`, `spam_report` (if spam check enabled)
 * - Attachments as file parts
 *
 * ## Security Considerations
 *
 * SendGrid does not sign webhook requests. Consider:
 * - IP allowlisting (SendGrid publishes their IP ranges)
 * - Using a secret path in your webhook URL
 * - Rate limiting on your webhook endpoint
 *
 * @param webhookUrl The URL where SendGrid will POST incoming emails.
 *   Used for documentation/output purposes only - must be configured manually in SendGrid.
 */
@Untested
context(emitter: TerraformEmitter)
public fun TerraformNeed<EmailInboundService.Settings>.sendgridInboundParse(
    webhookUrl: String? = null,
): Unit {
    if (!EmailInboundService.Settings.supports("sendgrid")) {
        throw IllegalArgumentException("You need to reference SendGridEmailInboundService in your server definition to use this.")
    }

    emitter.fulfillSetting(name, JsonPrimitive("sendgrid://"))

    emitter.emit(name) {
        // Output for documentation
        "output.${name}_setup_instructions" {
            "value" - """
                |SendGrid Inbound Parse requires manual configuration:
                |1. Go to SendGrid Dashboard > Settings > Inbound Parse
                |2. Add Host & URL with your domain and webhook: ${webhookUrl ?: "<your-webhook-url>"}
                |3. Add MX record: <subdomain> MX 10 mx.sendgrid.net
            """.trimMargin()
            "description" - "Setup instructions for SendGrid Inbound Parse"
        }

        if (webhookUrl != null) {
            "output.${name}_webhook_url" {
                "value" - webhookUrl
                "description" - "Webhook URL to configure in SendGrid dashboard"
            }
        }
    }
}
