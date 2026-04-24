# Service Abstractions Suggestions

<!-- by Claude — Collected from patterns seen across etzgo, instaclub, open-ticketer, lightning-time -->

## Payment Service Abstraction

3 out of 4 projects built on Lightning Server integrate Stripe for payments. Each builds its own payment service layer
from scratch. A `PaymentService` abstraction (similar to `EmailService` or `NotificationService`) would save significant
effort.

Proposed interface:

```kotlin
interface PaymentService {
    suspend fun createCustomer(email: String, name: String?): String  // returns customer ID
    suspend fun createPaymentIntent(amount: Long, currency: String, customerId: String): PaymentIntent
    suspend fun createSubscription(customerId: String, priceId: String): Subscription
    suspend fun cancelSubscription(subscriptionId: String)
    suspend fun handleWebhook(payload: String, signature: String): WebhookEvent
}
```

With a `StripePaymentService` implementation in a `payments-stripe` module, similar to `email-javasmtp` or
`notifications-fcm`.
