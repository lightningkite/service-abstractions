package com.lightningkite.services.email

import com.lightningkite.services.SettingContext

/**
 * An email service implementation that stores emails for testing purposes.
 * Note: This implementation is not thread-safe and is intended for testing only.
 */
public class TestEmailService(override val name: String, override val context: SettingContext) : EmailService {
    /**
     * The list of emails that have been sent.
     */
    private val _sentEmails: MutableList<Email> = mutableListOf()
    
    /**
     * Access to sent emails.
     */
    public val sentEmails: List<Email>
        get() = _sentEmails.toList()

    /**
     * Clears the list of sent emails.
     */
    public fun clear() {
        _sentEmails.clear()
    }

    /**
     * Stores the email in the sentEmails list.
     */
    override suspend fun send(email: Email) {
        _sentEmails.add(email)
    }

    /**
     * Returns the last email sent to the specified address, or null if none was sent.
     */
    public fun lastEmailTo(email: String): Email? {
        return sentEmails.lastOrNull { emailObj -> 
            emailObj.to.any { it.value.toString() == email }
        }
    }

    /**
     * Returns all emails sent to the specified address.
     */
    public fun emailsTo(email: String): List<Email> {
        return sentEmails.filter { emailObj -> 
            emailObj.to.any { it.value.toString() == email }
        }
    }

    /**
     * Returns the last email with the specified subject, or null if none was sent.
     */
    public fun lastEmailWithSubject(subject: String): Email? {
        return sentEmails.lastOrNull { it.subject == subject }
    }

    /**
     * Returns all emails with the specified subject.
     */
    public fun emailsWithSubject(subject: String): List<Email> {
        return sentEmails.filter { it.subject == subject }
    }

    /**
     * Returns the last email sent, or null if none was sent.
     */
    public fun lastEmail(): Email? {
        return sentEmails.lastOrNull()
    }

    /**
     * Returns all emails sent.
     */
    public fun allEmails(): List<Email> {
        return sentEmails
    }
}