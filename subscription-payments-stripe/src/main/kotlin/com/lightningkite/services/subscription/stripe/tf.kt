package com.lightningkite.services.subscription.stripe

import com.lightningkite.services.subscription.*
import com.lightningkite.services.terraform.TerraformEmitter
import com.lightningkite.services.terraform.TerraformJsonObject.Companion.expression
import com.lightningkite.services.terraform.TerraformNeed
import com.lightningkite.services.terraform.TerraformProviderImport
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

/**
 * Configuration for a Stripe product with recurring pricing.
 *
 * @property id Your internal product identifier (used in resource names)
 * @property name Customer-facing product name (appears on invoices)
 * @property description Optional product description
 * @property monthlyPriceCents Price in cents for monthly billing (e.g., 999 = $9.99)
 * @property yearlyPriceCents Price in cents for yearly billing (e.g., 9999 = $99.99)
 * @property metadata Optional key-value pairs stored with the product
 */
@Serializable
public data class StripeProductConfig(
    val id: String,
    val name: String,
    val description: String? = null,
    val monthlyPriceCents: Int,
    val yearlyPriceCents: Int,
    val metadata: Map<String, String> = emptyMap(),
)

/**
 * Creates Stripe products and prices via Terraform.
 *
 * This function generates Terraform resources for subscription products with
 * monthly and yearly pricing options using the [lukasaron/stripe](https://github.com/lukasaron/terraform-provider-stripe) provider.
 *
 * ## Provider Configuration
 *
 * The Stripe provider requires an API key. Configure it via:
 * - Environment variable: `STRIPE_API_KEY`
 * - Or in Terraform: `provider "stripe" { api_key = var.stripe_api_key }`
 *
 * ## Usage
 *
 * ```kotlin
 * context(emitter: TerraformEmitter) {
 *     TerraformNeed<Map<String, SubscriptionProductConfiguration>>("products").stripeProducts(
 *         products = listOf(
 *             StripeProductConfig(
 *                 id = "basic",
 *                 name = "Basic Plan",
 *                 description = "Access to basic features",
 *                 monthlyPriceCents = 999,   // $9.99/month
 *                 yearlyPriceCents = 9999,   // $99.99/year
 *             ),
 *             StripeProductConfig(
 *                 id = "pro",
 *                 name = "Pro Plan",
 *                 description = "Access to all features",
 *                 monthlyPriceCents = 2999,  // $29.99/month
 *                 yearlyPriceCents = 29999,  // $299.99/year
 *             )
 *         ),
 *         currency = "usd"
 *     )
 * }
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
 * Stripe product ID and price IDs. The map uses Terraform interpolation, so the actual
 * IDs are resolved at apply time.
 *
 * @param products List of product configurations to create
 * @param currency Three-letter ISO currency code (lowercase), default "usd"
 */
context(emitter: TerraformEmitter)
public fun TerraformNeed<Map<String, SubscriptionProductConfiguration>>.stripeProducts(
    products: List<StripeProductConfig>,
    currency: String = "usd",
): Unit {
    emitter.require(TerraformProviderImport.stripe)

    // Build the fulfillment expression - a Terraform expression that constructs the map
    // This uses Terraform's object syntax with interpolation
    val configExpression = buildString {
        append("{")
        products.forEachIndexed { index, product ->
            if (index > 0) append(", ")
            append("\"${product.id}\" = {")
            append("\"product\" = stripe_product.${name}_${product.id}.id, ")
            append("\"prices\" = {")
            append("\"Monthly\" = stripe_price.${name}_${product.id}_monthly.id, ")
            append("\"Yearly\" = stripe_price.${name}_${product.id}_yearly.id")
            append("}")
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
                    "metadata" {
                        product.metadata.forEach { (k, v) ->
                            k - v
                        }
                    }
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

/**
 * Creates a Stripe webhook endpoint via Terraform.
 *
 * This function creates a webhook endpoint in Stripe that will receive
 * subscription-related events. The webhook secret is automatically extracted
 * from the created resource and embedded in the settings URL.
 *
 * ## Provider Configuration
 *
 * The Stripe provider requires an API key. Configure it via:
 * - Environment variable: `STRIPE_API_KEY`
 * - Or in Terraform: `provider "stripe" { api_key = var.stripe_api_key }`
 *
 * ## Usage
 *
 * ```kotlin
 * context(emitter: TerraformEmitter) {
 *     TerraformNeed<SubscriptionService.Settings>("subscriptions").stripeWebhook(
 *         webhookUrl = "https://api.example.com/webhooks/stripe",
 *         apiKey = "\${var.stripe_api_key}",  // Terraform variable reference
 *         description = "Subscription webhook for MyApp"
 *     )
 * }
 * ```
 *
 * ## Created Resources
 *
 * - `stripe_webhook_endpoint.{name}` - Webhook endpoint configured for subscription events
 *
 * ## Enabled Events
 *
 * The following events are automatically enabled:
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
 * Returns `SubscriptionService.Settings` with format: `stripe://{apiKey}@{webhookSecret}`
 *
 * The webhook secret is interpolated from the Terraform resource, so it's resolved
 * at apply time and stored securely.
 *
 * @param webhookUrl The URL where Stripe will send webhook events (must be HTTPS in production)
 * @param apiKey Stripe API key - can be a literal or Terraform expression like `${var.stripe_api_key}`
 * @param description Optional description for the webhook endpoint
 *
 * @throws IllegalArgumentException if StripeSubscriptionService is not registered
 */
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
    // The $$ syntax is Kotlin's raw string interpolation to avoid escaping
    emitter.fulfillSetting(
        name, JsonPrimitive(
            value = $$"""stripe://$${apiKey}@${stripe_webhook_endpoint.$${name}.secret}"""
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
