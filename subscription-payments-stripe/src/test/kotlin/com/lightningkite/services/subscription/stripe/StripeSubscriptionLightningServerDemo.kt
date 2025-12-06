package com.lightningkite.services.subscription.stripe

import com.lightningkite.MediaType
import com.lightningkite.lightningserver.BadRequestException
import com.lightningkite.lightningserver.definition.StartupTask
import com.lightningkite.lightningserver.definition.builder.ServerBuilder
import com.lightningkite.lightningserver.engine.netty.NettyEngine
import com.lightningkite.lightningserver.http.*
import com.lightningkite.lightningserver.pathing.fullUrl
import com.lightningkite.lightningserver.runtime.ServerRuntime
import com.lightningkite.lightningserver.runtime.location
import com.lightningkite.lightningserver.settings.loadFromFile
import com.lightningkite.services.data.TypedData
import com.lightningkite.services.data.workingDirectory
import com.lightningkite.services.database.Database
import com.lightningkite.services.database.jsonfile.JsonFileDatabase
import com.lightningkite.services.subscription.*
import kotlinx.coroutines.*
import kotlin.time.Clock

/**
 * Lightning Server demo for Stripe Subscription Payments.
 *
 * This demonstrates:
 * 1. Setting up a Lightning Server to handle Stripe webhooks
 * 2. Creating customers and checkout sessions
 * 3. Processing subscription events (checkout completed, payment failed, etc.)
 * 4. Managing subscriptions (cancel, pause, resume)
 *
 * ## Prerequisites
 *
 * 1. Create `local/settings.json` with your Stripe credentials:
 *    ```json
 *    {
 *      "general": {
 *        "projectName": "subscription-demo",
 *        "publicUrl": "https://YOUR-NGROK-URL.ngrok.io",
 *        "debug": true
 *      },
 *      "stripe": "stripe://sk_test_xxx@whsec_xxx"
 *    }
 *    ```
 *
 * 2. Expose your local server using ngrok:
 *    ```bash
 *    ngrok http 8080
 *    ```
 *
 * 3. Configure your Stripe webhook endpoint at https://dashboard.stripe.com/webhooks:
 *    - URL: `https://YOUR-NGROK-URL.ngrok.io/webhooks/subscription`
 *    - Events:
 *      - checkout.session.completed
 *      - customer.subscription.created
 *      - customer.subscription.updated
 *      - customer.subscription.deleted
 *      - invoice.payment_succeeded
 *      - invoice.payment_failed
 *
 * 4. Create a price in Stripe Dashboard and note the price ID (price_xxx)
 *
 * ## Running
 *
 * ```bash
 * ./gradlew :subscription-payments-stripe:test --tests "StripeSubscriptionLightningServerDemo"
 * ```
 *
 * Or run directly from IDE.
 */
object StripeSubscriptionLightningServerDemo {
    init {
        StripeSubscriptionService
    }

    @JvmStatic
    fun main(args: Array<String>) = runBlocking {
        println("=".repeat(60))
        println("Lightning Server - Stripe Subscription Payments Demo")
        println("=".repeat(60))

        val built = SubscriptionServer.build()

        val engine = NettyEngine(built, Clock.System)

        // Start server in background
        val serverJob = launch(Dispatchers.Default) {
            try {
                engine.settings.loadFromFile(workingDirectory.then("settings.json"), engine.internalSerializersModule)
                println("Engine starting...")
                engine.start()
                println("Engine started!")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        with(engine) {
            SubscriptionServer.cliInteractive()
        }
        serverJob.cancelAndJoin()
    }
}


object SubscriptionServer : ServerBuilder() {
    init {
        JsonFileDatabase
    }
    val stripe = setting("stripe", SubscriptionService.Settings())
    val database = setting("database", Database.Settings())

    // Store received events for inspection
    private val receivedEvents = mutableListOf<SubscriptionEvent>()

    context(runtime: ServerRuntime)
    suspend fun cliInteractive() {
        println("\n" + "=".repeat(60))
        println("INTERACTIVE OPTIONS")
        println("=".repeat(60))
        println(
            """
            |
            |Enter a command:
            |  create-customer <email>     - Create a new customer
            |  checkout <email> <priceId>  - Create checkout session for customer
            |  portal <email>              - Create portal session for customer
            |  subscriptions <email>       - List customer's subscriptions
            |  cancel <subscriptionId>     - Cancel subscription immediately
            |  events                      - Show received webhook events
            |  clear                       - Clear received events
            |  quit                        - Stop server and exit
            |
            |Example:
            |  create-customer test@example.com
            |  checkout test@example.com price_1ABC123
            |  portal test@example.com
            |
        """.trimMargin()
        )

        // Interactive command loop
        while (currentCoroutineContext().isActive) {
            print("\n> ")
            val input = readlnOrNull()?.trim() ?: continue

            val parts = input.split(" ")
            val command = parts.firstOrNull()?.lowercase() ?: continue

            when (command) {
                "create-customer" -> {
                    val email = parts.getOrNull(1)
                    if (email == null) {
                        println("Usage: create-customer <email>")
                        continue
                    }
                    try {
                        val customerId = stripe().createCustomer(email, name = email.substringBefore("@"))
                        println("Created customer: $customerId")
                    } catch (e: Exception) {
                        println("Error creating customer: ${e.message}")
                    }
                }

                "checkout" -> {
                    val customerIdOrEmail = parts.getOrNull(1)
                    val priceId = parts.getOrNull(2)
                    if (customerIdOrEmail == null || priceId == null) {
                        println("Usage: checkout <customer email or ID> <priceId>")
                        continue
                    }
                    val customerId = if(customerIdOrEmail.contains("@")) stripe().createCustomer(customerIdOrEmail) else SubscriptionCustomerId(customerIdOrEmail)
                    try {
                        val session = stripe().checkoutUrl(
                            CheckoutSessionRequest(
                                customerId = customerId,
                                priceId = SubscriptionPriceId(priceId),
                                successUrl = success.location.path.resolved().fullUrl() + "?session_id={CHECKOUT_SESSION_ID}",
                                cancelUrl = cancel.location.path.resolved().fullUrl(),
                                metadata = mapOf("source" to "demo")
                            )
                        )
                        println("Checkout session created!")
                        println("  URL: ${session}")
                        println("\nOpen this URL in your browser to complete checkout.")
                    } catch (e: Exception) {
                        println("Error creating checkout session: ${e.message}")
                        e.printStackTrace()
                    }
                }

                "portal" -> {
                    val subscription = parts.getOrNull(1)
                    if (subscription == null) {
                        println("Usage: portal <subscription>")
                        continue
                    }
                    try {
                        val portal = stripe().manageSubscriptionUrl(
                            subscriptionId = SubscriptionId(subscription),
                            returnUrl = account.location.path.resolved().fullUrl()
                        )
                        println("Portal session created!")
                        println("  URL: ${portal}")
                        println("\nOpen this URL in your browser to manage subscriptions.")
                    } catch (e: Exception) {
                        println("Error creating portal session: ${e.message}")
                    }
                }

                "subscriptions" -> {
                    val customerIdOrEmail = parts.getOrNull(1)
                    if (customerIdOrEmail == null) {
                        println("Usage: subscriptions <customerIdOrEmail>")
                        continue
                    }
                    val customerId = if(customerIdOrEmail.contains("@")) stripe().createCustomer(customerIdOrEmail) else SubscriptionCustomerId(customerIdOrEmail)
                    try {
                        val subs = stripe().getSubscriptions(customerId)
                        if (subs.isEmpty()) {
                            println("No subscriptions found for $customerIdOrEmail")
                        } else {
                            println("Subscriptions for $customerIdOrEmail:")
                            subs.forEachIndexed { index, sub ->
                                println("  ${index + 1}. ${sub.id.value}")
                                println("     Status: ${sub.status}")
                                println("     Price: ${sub.priceId.value}")
                                println("     Period: ${sub.currentPeriodStart} - ${sub.currentPeriodEnd}")
                                println("     Cancel at period end: ${sub.cancelAtPeriodEnd}")
                                println()
                            }
                        }
                    } catch (e: Exception) {
                        println("Error fetching subscriptions: ${e.message}")
                    }
                }

                "cancel" -> {
                    val subscriptionId = parts.getOrNull(1)
                    if (subscriptionId == null) {
                        println("Usage: cancel <subscriptionId>")
                        continue
                    }
                    try {
                        val sub = stripe().cancelSubscription(SubscriptionId(subscriptionId), immediately = true)
                        println("Subscription canceled!")
                        println("  Status: ${sub.status}")
                        println("  Canceled at: ${sub.canceledAt}")
                    } catch (e: Exception) {
                        println("Error canceling subscription: ${e.message}")
                    }
                }

                "events" -> {
                    if (receivedEvents.isEmpty()) {
                        println("No events received yet.")
                    } else {
                        println("Received events (${receivedEvents.size}):")
                        receivedEvents.forEachIndexed { index, event ->
                            println("  ${index + 1}. ${event::class.simpleName}")
                            println("     Provider Event ID: ${event.providerEventId}")
                            when (event) {
                                is SubscriptionEvent.CheckoutCompleted -> {
                                    println("     Session ID: ${event.sessionId.value}")
                                    println("     Customer ID: ${event.customerId.value}")
                                    println("     Subscription ID: ${event.subscriptionId?.value}")
                                }
                                is SubscriptionEvent.SubscriptionCreated -> {
                                    println("     Subscription: ${event.subscription.id.value}")
                                    println("     Status: ${event.subscription.status}")
                                }
                                is SubscriptionEvent.SubscriptionUpdated -> {
                                    println("     Subscription: ${event.subscription.id.value}")
                                    println("     Status: ${event.subscription.status}")
                                    println("     Previous attributes: ${event.previousAttributes}")
                                }
                                is SubscriptionEvent.SubscriptionDeleted -> {
                                    println("     Subscription: ${event.subscription.id.value}")
                                }
                                is SubscriptionEvent.PaymentSucceeded -> {
                                    println("     Subscription ID: ${event.subscriptionId.value}")
                                    println("     Amount: ${event.amountCents} ${event.currency}")
                                }
                                is SubscriptionEvent.PaymentFailed -> {
                                    println("     Subscription ID: ${event.subscriptionId.value}")
                                    println("     Amount: ${event.amountCents} ${event.currency}")
                                    println("     Failure: ${event.failureMessage}")
                                }
                                else -> {}
                            }
                            println()
                        }
                    }
                }

                "clear" -> {
                    receivedEvents.clear()
                    println("Events cleared.")
                }

                "quit", "exit" -> {
                    println("Shutting down...")
                    break
                }

                else -> {
                    if (input.isNotEmpty()) {
                        println("Unknown command: $command")
                    }
                }
            }
        }
    }

    // ==================== Webhook Endpoints ====================

    /**
     * Main subscription webhook - handles Stripe events.
     * Stripe POSTs JSON payloads here when subscription events occur.
     */
    val subscriptionWebhook = path.path("webhooks").path("subscription").post bind HttpHandler { request ->
        println("\n" + "=".repeat(40))
        println("Incoming Stripe Webhook!")
        println("=".repeat(40))

        try {
            // Debug: print raw headers
            println("Raw headers:")
            request.headers.normalizedEntries.forEach { (key, values) ->
                println("  $key: ${values.map { it.toHttpString() }}")
            }

            // Dynamic mode - use database to look up the webhook secret
            val handler = stripe().onEvent
            val event = handler.parse(
                queryParameters = request.queryParameters.entries,
                headers = request.headers.normalizedEntries.mapValues { it.value.map { v -> v.toHttpString() } },
                body = request.body ?: throw BadRequestException("Missing request body")
            ) ?: return@HttpHandler HttpResponse(
                status = HttpStatus.OK,
                body = TypedData.text("{\"received\": true}", MediaType.Application.Json)
            )

            // Store the event
            receivedEvents.add(event)

            println("Event Type: ${event::class.simpleName}")
            println("Provider Event ID: ${event.providerEventId}")

            when (event) {
                is SubscriptionEvent.CheckoutCompleted -> {
                    println("Checkout completed!")
                    println("  Customer: ${event.customerId.value}")
                    println("  Subscription: ${event.subscriptionId?.value}")
                    println("  Metadata: ${event.metadata}")
                }
                is SubscriptionEvent.SubscriptionCreated -> {
                    println("Subscription created!")
                    println("  ID: ${event.subscription.id.value}")
                    println("  Status: ${event.subscription.status}")
                }
                is SubscriptionEvent.SubscriptionUpdated -> {
                    println("Subscription updated!")
                    println("  ID: ${event.subscription.id.value}")
                    println("  Status: ${event.subscription.status}")
                    println("  Changes: ${event.previousAttributes}")
                }
                is SubscriptionEvent.SubscriptionDeleted -> {
                    println("Subscription deleted!")
                    println("  ID: ${event.subscription.id.value}")
                }
                is SubscriptionEvent.PaymentSucceeded -> {
                    println("Payment succeeded!")
                    println("  Subscription: ${event.subscriptionId.value}")
                    println("  Amount: ${event.amountCents / 100.0} ${event.currency.uppercase()}")
                }
                is SubscriptionEvent.PaymentFailed -> {
                    println("Payment failed!")
                    println("  Subscription: ${event.subscriptionId.value}")
                    println("  Amount: ${event.amountCents / 100.0} ${event.currency.uppercase()}")
                    println("  Reason: ${event.failureMessage}")
                }
                else -> {
                    println("Other event received")
                }
            }

            // Return success response
            HttpResponse(
                status = HttpStatus.OK,
                body = TypedData.text("{\"received\": true}", MediaType.Application.Json)
            )
        } catch (e: SecurityException) {
            println("Security error: ${e.message}")
            HttpResponse(
                status = HttpStatus.Forbidden,
                body = TypedData.text("Invalid signature", MediaType.Text.Plain)
            )
        } catch (e: Exception) {
            println("Error processing webhook: ${e.message}")
            e.printStackTrace()
            HttpResponse(
                status = HttpStatus.BadRequest,
                body = TypedData.text("Error: ${e.message}", MediaType.Text.Plain)
            )
        }
    }

    // ==================== Helper Endpoints ====================

    /**
     * Health check endpoint.
     */
    val health = path.path("health").get bind HttpHandler {
        HttpResponse(body = TypedData.text("OK - Subscription Payments Demo", MediaType.Text.Plain))
    }

    /**
     * Success page after checkout.
     */
    val success = path.path("success").get bind HttpHandler { request ->
        val sessionId = request.queryParameters["session_id"]
        HttpResponse(
            body = TypedData.text(
                """
                    <!DOCTYPE html>
                    <html>
                    <head><title>Checkout Success</title></head>
                    <body>
                        <h1>Checkout Successful!</h1>
                        <p>Session ID: $sessionId</p>
                        <p>Your subscription has been created.</p>
                    </body>
                    </html>
                """.trimIndent(),
                MediaType.Text.Html
            )
        )
    }

    /**
     * Cancel page for checkout.
     */
    val cancel = path.path("cancel").get bind HttpHandler {
        HttpResponse(
            body = TypedData.text(
                """
                    <!DOCTYPE html>
                    <html>
                    <head><title>Checkout Canceled</title></head>
                    <body>
                        <h1>Checkout Canceled</h1>
                        <p>You have canceled the checkout process.</p>
                    </body>
                    </html>
                """.trimIndent(),
                MediaType.Text.Html
            )
        )
    }

    /**
     * Account return page after portal.
     */
    val account = path.path("account").get bind HttpHandler {
        HttpResponse(
            body = TypedData.text(
                """
                    <!DOCTYPE html>
                    <html>
                    <head><title>Account</title></head>
                    <body>
                        <h1>Account Page</h1>
                        <p>You have returned from the customer portal.</p>
                    </body>
                    </html>
                """.trimIndent(),
                MediaType.Text.Html
            )
        )
    }
}
