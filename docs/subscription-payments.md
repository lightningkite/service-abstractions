# Subscription payments

`SubscriptionService` sells recurring subscriptions through Stripe's hosted pages: create a customer, send them to checkout, let them manage billing, and react to webhook events.  Your app never handles card details.

## Modules

| Module | Add it for |
|---|---|
| `subscription-payments` | `SubscriptionService`, `SubscriptionEvent`, `console` and `test`.  Multiplatform. |
| `subscription-payments-stripe` | `stripe://`, and the `stripeWebhook` Terraform helper.  JVM. |
| `subscription-payments-test` | `SubscriptionServiceTests`, the shared contract suite. |

## Quick start

```kotlin
StripeSubscriptionService  // registers stripe://
val payments = SubscriptionService.Settings("stripe://sk_test_xxx@whsec_xxx")("payments", context)

val customer: SubscriptionCustomerId = payments.createCustomer(email = "ada@example.com", name = "Ada")
val checkout: String = payments.checkoutUrl(
    CheckoutSessionRequest(
        customerId = customer,
        priceId = SubscriptionPriceId("price_123"),
        successUrl = "https://app.example.com/billing/success",
        cancelUrl = "https://app.example.com/billing",
    )
)
// Redirect the user to `checkout`.
```

Store the `SubscriptionCustomerId` on your user.  `CheckoutSessionRequest` also takes `quantity`, `trialPeriodDays`, `allowPromotionCodes` and `metadata`.

## Common tasks

### React to events

Point a Stripe webhook at your endpoint and parse each request with `onEvent`:

```kotlin
when (val event = payments.onEvent.parse(queryParameters, headers, body)) {
    is SubscriptionEvent.CheckoutCompleted -> grantAccess(event)
    is SubscriptionEvent.SubscriptionDeleted -> revokeAccess(event)
    is SubscriptionEvent.PaymentFailed -> warnUser(event)
    else -> {}          // null for event types it doesn't model
}
```

`parse` verifies the `Stripe-Signature` header with the webhook secret.  Events include `SubscriptionCreated`/`Updated`/`Deleted`/`Paused`/`Resumed`/`Expired` and `PaymentSucceeded`/`Failed`/`Recovered`.  Stripe retries and may deliver out of order: make handlers idempotent, and re-read with `getSubscription` when order matters.

### Manage subscriptions

```kotlin
val subs: List<Subscription> = payments.getSubscriptions(customer)
val portal: String = payments.manageSubscriptionUrl(subs.first().id, returnUrl = "https://app.example.com/billing")
payments.cancelSubscription(subs.first().id)
payments.pauseSubscription(id); payments.resumeSubscription(id)
```

## Settings URLs

| Scheme | Module | Example |
|---|---|---|
| `console` | `subscription-payments` | `console` (the default; prints each call) |
| `test` | `subscription-payments` | `test` |
| `stripe` | `subscription-payments-stripe` | `stripe://sk_live_xxx@whsec_xxx` |

Without `@<webhookSecret>`, the secret comes from `STRIPE_WEBHOOK_SECRET`.  Details: [Settings URL reference: Subscription payments](settings-url-reference.md#subscription-payments).

`TerraformNeed<SubscriptionService.Settings>.stripeWebhook(webhookUrl, apiKey)` registers the webhook endpoint with Stripe and fills in the setting with its signing secret.

## Testing

`test` keeps customers, checkout sessions and subscriptions in memory (see `TestSubscriptionService`).  Provider authors: subclass `SubscriptionServiceTests`.

## Gotchas

- **`console` is the default**, so a missing setting silently "succeeds".
- **Webhook secrets are per endpoint**: each environment's webhook has its own `whsec_`.
- **Test and live keys** (`sk_test_` / `sk_live_`) see different customers and prices.
