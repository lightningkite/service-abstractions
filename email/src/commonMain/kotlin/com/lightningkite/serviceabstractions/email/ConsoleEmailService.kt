package com.lightningkite.serviceabstractions.email

import com.lightningkite.serviceabstractions.SettingContext

/**
 * An email service implementation that outputs emails to the console.
 * Useful for development and debugging.
 */
public class ConsoleEmailService(override val context: SettingContext) : MetricTrackingEmailService() {
    /**
     * Sends an email by outputting it to the console.
     */
    override suspend fun sendInternal(email: Email) {
        println("--------- EMAIL ---------")
        println("From: ${email.from ?: ""} <${email.from ?: "no-reply@example.com"}>")
        println("To: ${email.to.joinToString(", ") { "${it.label ?: ""} <${it.value}>" }}")
        if (email.cc.isNotEmpty()) {
            println("CC: ${email.cc.joinToString(", ") { "${it.label ?: ""} <${it.value}>" }}")
        }
        if (email.bcc.isNotEmpty()) {
            println("BCC: ${email.bcc.joinToString(", ") { "${it.label ?: ""} <${it.value}>" }}")
        }
        println("Subject: ${email.subject}")
        if (email.customHeaders.isNotEmpty()) {
            println("Custom Headers:")
            email.customHeaders.forEach { (key, values) ->
                values.forEach { value ->
                    println("  $key: $value")
                }
            }
        }
        println("------- CONTENT --------")
        println(email.plainText)
        if (email.attachments.isNotEmpty()) {
            println("------ ATTACHMENTS ------")
            email.attachments.forEach {
                println("${if (it.inline) "Inline" else "Attachment"}: ${it.filename} (${it.typedData.mediaType})")
            }
        }
        println("------------------------")
    }
}