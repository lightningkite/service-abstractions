# Plan: Terraform Generator for subscription-payments-stripe

## Summary

Create `tf.kt` in `subscription-payments-stripe` module that provides two Terraform generator functions:

1. **`stripeProducts()`** - Creates Stripe products and prices via Terraform
2. **`stripeWebhook()`** - Creates a Stripe webhook endpoint and passes the secret to the settings

## Provider Details

Using the [lukasaron/stripe](https://github.com/lukasaron/terraform-provider-stripe) Terraform provider (v3.4.1):
- Source: `lukasaron/stripe`
- Version: `~> 3.4.0`

Resources:
- `stripe_product` - Creates products
- `stripe_price` - Creates prices (supports recurring for subscriptions)
- `stripe_webhook_endpoint` - Creates webhooks, exposes `secret` attribute

## Implementation Details

### 1. Add Stripe Provider Import

In `TerraformProviderImport.kt` in the `basis` module, add:

```kotlin
public val stripe: TerraformProviderImport = TerraformProviderImport(
    name = "stripe",
    source = "lukasaron/stripe",
    version = "~> 3.4.0",
)
```

### 2. Create tf.kt in subscription-payments-stripe

**File:** `subscription-payments-stripe/src/main/kotlin/com/lightningkite/services/subscription/stripe/tf.kt`

#### Function 1: `stripeProducts()`

```kotlin
/**
 * Creates Stripe products and prices via Terraform.
 *
 * This function generates Terraform resources for subscription products with
 * monthly and yearly pricing options.
 *
 * ## Usage
 *
 * ```kotlin
 * products.stripeProducts(
 *     products = listOf(
 *         StripeProductConfig(
 *             id = "basic",
 *             name = "Basic Plan",
 *             description = "Access to basic features",
 *             monthlyPriceCents = 999,   // $9.99/month
 *             yearlyPriceCents = 9999,   // $99.99/year
 *         ),
 *         StripeProductConfig(
 *             id = "pro",
 *             name = "Pro Plan",
 *             description = "Access to all features",
 *             monthlyPriceCents = 2999,  // $29.99/month
 *             yearlyPriceCents = 29999,  // $299.99/year
 *         )
 *     ),
 *     currency = "usd"
 * )
 * ```
 *
 * ## Created Resources
 *
 * For each product:
 * - `stripe_product.{name}_{productId}` - The product
 * - `stripe_price.{name}_{productId}_monthly` - Monthly recurring price
 * - `stripe_price.{name}_{productId}_yearly` - Yearly recurring price
 *
 * ## Generated Setting
 *
 * Returns `Map<String, SubscriptionProductConfiguration>` mapping product IDs to their
 * Stripe product ID and price IDs.
 */
context(emitter: TerraformEmitter)
public fun TerraformNeed<Map<String, SubscriptionProductConfiguration>>.stripeProducts(
    products: List<StripeProductConfig>,
    currency: String = "usd",
): Unit
```

**Data class for configuration:**

```kotlin
@Serializable
public data class StripeProductConfig(
    val id: String,                  // Your internal product identifier
    val name: String,                // Customer-facing name
    val description: String? = null, // Optional description
    val monthlyPriceCents: Int,      // Price in cents for monthly billing
    val yearlyPriceCents: Int,       // Price in cents for yearly billing
    val metadata: Map<String, String> = emptyMap(),
)
```

#### Function 2: `stripeWebhook()`

```kotlin
/**
 * Creates a Stripe webhook endpoint via Terraform.
 *
 * This function creates a webhook endpoint in Stripe that will receive
 * subscription-related events. The webhook secret is passed to the settings
 * for signature verification.
 *
 * ## Usage
 *
 * ```kotlin
 * subscriptionService.stripeWebhook(
 *     webhookUrl = "https://api.example.com/stripe/webhook",
 *     apiKey = "\${var.stripe_api_key}",  // Reference to Terraform variable
 * )
 * ```
 *
 * ## Created Resources
 *
 * - `stripe_webhook_endpoint.{name}` - Webhook endpoint configured for subscription events
 *
 * ## Enabled Events
 *
 * - `checkout.session.completed`
 * - `customer.subscription.created`
 * - `customer.subscription.updated`
 * - `customer.subscription.deleted`
 * - `customer.subscription.paused`
 * - `customer.subscription.resumed`
 * - `invoice.payment_succeeded`
 * - `invoice.payment_failed`
 *
 * ## Generated Setting
 *
 * Returns `SubscriptionService.Settings` with format:
 * `stripe://{apiKey}@{webhookSecret}`
 *
 * The webhook secret is interpolated from the Terraform resource.
 */
context(emitter: TerraformEmitter)
public fun TerraformNeed<SubscriptionService.Settings>.stripeWebhook(
    webhookUrl: String,
    apiKey: String,  // Can be a Terraform expression like "${var.stripe_api_key}"
    description: String? = null,
): Unit
```

### 3. Implementation Details

#### stripeProducts Implementation

```kotlin
context(emitter: TerraformEmitter)
public fun TerraformNeed<Map<String, SubscriptionProductConfiguration>>.stripeProducts(
    products: List<StripeProductConfig>,
    currency: String = "usd",
): Unit {
    emitter.require(TerraformProviderImport.stripe)

    // Build the fulfillment JSON using Terraform interpolation
    // This creates a map of product configs with Terraform expressions for IDs
    val configExpression = buildString {
        append("{")
        products.forEachIndexed { index, product ->
            if (index > 0) append(", ")
            append(""""${product.id}" = {""")
            append(""""product" = stripe_product.${name}_${product.id}.id, """)
            append(""""prices" = {"Monthly" = stripe_price.${name}_${product.id}_monthly.id, """)
            append(""""Yearly" = stripe_price.${name}_${product.id}_yearly.id}""")
            append("}")
        }
        append("}")
    }
    emitter.fulfillSetting(name, JsonPrimitive(expression(configExpression)))

    emitter.emit(name) {
        products.forEach { product ->
            // Product resource
            "resource.stripe_product.${name}_${product.id}" {
                "name" - product.name
                "product_id" - "${emitter.projectPrefix}-${product.id}"
                product.description?.let { "description" - it }
                if (product.metadata.isNotEmpty()) {
                    "metadata" - product.metadata
                }
            }

            // Monthly price
            "resource.stripe_price.${name}_${product.id}_monthly" {
                "product" - expression("stripe_product.${name}_${product.id}.id")
                "currency" - currency
                "unit_amount" - product.monthlyPriceCents
                "recurring" {
                    "interval" - "month"
                }
            }

            // Yearly price
            "resource.stripe_price.${name}_${product.id}_yearly" {
                "product" - expression("stripe_product.${name}_${product.id}.id")
                "currency" - currency
                "unit_amount" - product.yearlyPriceCents
                "recurring" {
                    "interval" - "year"
                }
            }
        }
    }
}
```

#### stripeWebhook Implementation

```kotlin
context(emitter: TerraformEmitter)
public fun TerraformNeed<SubscriptionService.Settings>.stripeWebhook(
    webhookUrl: String,
    apiKey: String,
    description: String? = null,
): Unit {
    if (!SubscriptionService.Settings.supports("stripe")) {
        throw IllegalArgumentException("You need to reference StripeSubscriptionService in your server definition to use this.")
    }

    emitter.require(TerraformProviderImport.stripe)

    // Fulfill with the settings URL including webhook secret from Terraform
    // Using raw string interpolation to embed Terraform expression
    emitter.fulfillSetting(
        name, JsonPrimitive(
            value = $$"""
                stripe://$${apiKey}@${stripe_webhook_endpoint.$${name}.secret}
            """.trimIndent()
        )
    )

    emitter.emit(name) {
        "resource.stripe_webhook_endpoint.$name" {
            "url" - webhookUrl
            description?.let { "description" - it }
            "enabled_events" - listOf(
                "checkout.session.completed",
                "customer.subscription.created",
                "customer.subscription.updated",
                "customer.subscription.deleted",
                "customer.subscription.paused",
                "customer.subscription.resumed",
                "invoice.payment_succeeded",
                "invoice.payment_failed"
            )
        }
    }
}
```

### 4. Provider Configuration Note

The Stripe provider requires an API key. Users have two options:

1. **Environment variable**: Set `STRIPE_API_KEY` environment variable when running Terraform
2. **Provider configuration**: Configure the provider explicitly in their Terraform:

```hcl
provider "stripe" {
  api_key = var.stripe_api_key
}
```

The generator functions should document this requirement.

### 5. Testing

Create a test file that verifies the Terraform JSON generation is valid:

**File:** `subscription-payments-stripe/src/test/kotlin/com/lightningkite/services/subscription/stripe/TerraformTest.kt`

Test that:
- Products are generated with correct structure
- Prices reference their parent products
- Webhook has correct enabled_events
- Setting fulfillment uses correct interpolation

## Files to Create/Modify

1. **Modify:** `basis/src/commonMain/kotlin/com/lightningkite/services/terraform/TerraformProviderImport.kt`
   - Add `stripe` provider import

2. **Create:** `subscription-payments-stripe/src/main/kotlin/com/lightningkite/services/subscription/stripe/tf.kt`
   - `StripeProductConfig` data class
   - `stripeProducts()` function
   - `stripeWebhook()` function

3. **Create:** `subscription-payments-stripe/src/test/kotlin/com/lightningkite/services/subscription/stripe/TerraformTest.kt`
   - Basic tests for Terraform generation

## Usage Example

```kotlin
// In your infrastructure setup
context(emitter: TerraformEmitter) {
    // Set up products and prices
    TerraformNeed<Map<String, SubscriptionProductConfiguration>>("stripe_products").stripeProducts(
        products = listOf(
            StripeProductConfig(
                id = "basic",
                name = "Basic Plan",
                description = "Access to basic features",
                monthlyPriceCents = 999,
                yearlyPriceCents = 9999,
            ),
            StripeProductConfig(
                id = "pro",
                name = "Pro Plan",
                description = "Access to all features",
                monthlyPriceCents = 2999,
                yearlyPriceCents = 29999,
            )
        )
    )

    // Set up webhook
    TerraformNeed<SubscriptionService.Settings>("subscriptions").stripeWebhook(
        webhookUrl = "https://api.example.com/webhooks/stripe",
        apiKey = "\${var.stripe_api_key}",
        description = "Subscription webhook for MyApp"
    )
}
```

## Considerations

### Price Flexibility
The current design assumes monthly/yearly pricing. For more flexibility, we could support:
- Custom intervals (weekly, quarterly)
- Metered/usage-based pricing
- Tiered pricing

These can be added in future iterations if needed.

### API Key Handling
The `apiKey` parameter in `stripeWebhook` is passed as a string that can contain Terraform expressions. This allows users to:
- Use a Terraform variable: `"${var.stripe_api_key}"`
- Use a secret from a vault: `"${data.vault_generic_secret.stripe.data["api_key"]}"`
- Hardcode (not recommended): `"sk_live_xxx"`

### Webhook URL
The webhook URL must be known at Terraform plan time. For dynamic URLs (e.g., auto-scaling), users may need to:
- Use a stable domain with load balancer
- Create the webhook manually and use `stripeWebhookManual()` (potential future function)

## Sources

- [lukasaron/terraform-provider-stripe](https://github.com/lukasaron/terraform-provider-stripe) - Stripe Terraform provider v3.4.1
- [stripe_product resource](https://registry.terraform.io/providers/lukasaron/stripe/latest/docs/resources/stripe_product)
- [stripe_price resource](https://registry.terraform.io/providers/lukasaron/stripe/latest/docs/resources/stripe_price)
- [stripe_webhook_endpoint resource](https://registry.terraform.io/providers/lukasaron/stripe/latest/docs/resources/stripe_webhook_endpoint)
