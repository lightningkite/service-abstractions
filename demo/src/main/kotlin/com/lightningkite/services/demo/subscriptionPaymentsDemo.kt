package com.lightningkite.services.demo

import com.lightningkite.services.TestSettingContext
import com.lightningkite.services.subscription.*
import kotlinx.coroutines.runBlocking

/**
 * Demonstrates the subscription payments subsystem via `test://`: create a customer, then
 * create a checkout session for them, reading back what the fake provider recorded.
 */
fun main() = runBlocking {
    val context = TestSettingContext()
    val payments = SubscriptionService.Settings("test://")("payments", context) as TestSubscriptionService

    val customerId = payments.createCustomer(email = "user@example.com", name = "Jane Doe")
    println("Created customer: ${payments.getCustomer(customerId)}")

    val checkoutUrl = payments.checkoutUrl(
        CheckoutSessionRequest(
            customerId = customerId,
            priceId = SubscriptionPriceId("price_demo_monthly"),
            successUrl = "https://example.com/success",
            cancelUrl = "https://example.com/cancel",
        )
    )
    println("Checkout URL: $checkoutUrl")
    println("Session recorded: ${payments.checkoutSessions[payments.lastCheckoutSessionId]}")
}
