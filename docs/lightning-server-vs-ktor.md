# Why Lightning Server Instead of Ktor

This document explains the architectural reasoning behind Lightning Server's design and why it provides value beyond what could be achieved as a set of Ktor plugins.

## Executive Summary

Lightning Server's core value comes from treating **endpoints as values** rather than imperative route registrations. This architectural choice enables automatic SDK generation, self-documentation, Terraform infrastructure generation, and reflective server analysis—features that would require substantial reimplementation to achieve with Ktor.

## The Fundamental Architectural Difference

### Lightning Server: Endpoints as Values

```kotlin
object Server : ServerBuilder() {
    val getUser = path.path("users").arg<String>("id").get bind ApiHttpHandler(
        summary = "Get user by ID",
        description = "Retrieves a user's profile information",
        errorCases = listOf(LSError(404, "not-found", "User not found")),
        implementation = { id: String -> userTable.get(id) }
    )

    val createUser = path.path("users").post bind ApiHttpHandler(
        summary = "Create user",
        implementation = { input: CreateUserRequest -> userTable.insert(input.toUser()) }
    )
}
```

Endpoints are **properties on an object**. The framework can reflect over `Server::class` to discover all endpoints as inspectable data structures.

### Ktor: Routes as Statements

```kotlin
fun Application.module() {
    routing {
        get("/users/{id}") {
            val id = call.parameters["id"]!!
            call.respond(userTable.get(id))
        }
        post("/users") {
            val input = call.receive<CreateUserRequest>()
            call.respond(userTable.insert(input.toUser()))
        }
    }
}
```

Routes are **imperative statements** that execute once during startup. After execution, routes exist only in Ktor's internal routing tree—they're not accessible as data you can introspect.

## Features Enabled by "Endpoints as Values"

### 1. Automatic SDK Generation

Lightning Server can generate type-safe client SDKs for multiple platforms:

```kotlin
val meta = path.path("meta") module MetaEndpoints("v1", database, cache)

// Single command generates:
// - TypeScript SDK with full type definitions
// - Kotlin Multiplatform SDK
// - OpenAPI specification
```

**Why Ktor can't do this easily:**
- Route handlers are lambdas with erased type information at runtime
- No standard way to attach metadata (summary, error cases) to routes
- Would require mandatory annotations on every route plus a KSP processor

### 2. Self-Documentation (OpenAPI)

Because endpoints carry their own metadata:

```kotlin
val endpoint = path.path("orders").post bind ApiHttpHandler(
    summary = "Create order",
    description = "Creates a new order and begins processing",
    errorCases = listOf(
        LSError(400, "invalid-items", "Order contains invalid items"),
        LSError(402, "payment-required", "Payment method declined")
    ),
    implementation = { ... }
)
```

The framework generates accurate OpenAPI documentation by walking the endpoint definitions. Documentation is **always in sync** with code because it's derived from the same source.

**Ktor alternative:** Use `ktor-swagger` or similar, but this requires:
- Separate annotation layer (`@Operation`, `@ApiResponse`, etc.)
- Manual synchronization between annotations and implementation
- Risk of documentation drift

### 3. Terraform Infrastructure Generation

Lightning Server can generate complete AWS infrastructure from a server definition:

```kotlin
object Server : ServerBuilder() {
    val database = setting("database", Database.Settings())
    val cache = setting("cache", Cache.Settings())
    val files = setting("files", PublicFileSystem.Settings())

    val listUsers = path.path("users").get bind ApiHttpHandler { ... }
    val createUser = path.path("users").post bind ApiHttpHandler { ... }
    val cleanupTask = path.path("cleanup") bind ScheduledTask(frequency = 1.hours) { ... }
}

// Generates:
// - API Gateway routes for each endpoint
// - Lambda functions with correct handlers
// - CloudWatch Events for scheduled tasks
// - RDS/ElastiCache/S3 from settings
// - IAM policies with least-privilege permissions
```

**Why Ktor can't do this:**
- No way to introspect registered routes programmatically
- No standard metadata about what infrastructure each route needs
- Scheduled tasks aren't part of HTTP routing

### 4. Reflective Server Analysis

The `MetaEndpoints` module exposes the full server structure at runtime:

```kotlin
GET /meta → {
    "endpoints": [
        {
            "path": "/users/{id}",
            "method": "GET",
            "summary": "Get user by ID",
            "inputType": "kotlin.String",
            "outputType": "com.example.User",
            "errorCases": [...]
        },
        ...
    ],
    "models": { ... },
    "version": "v1"
}
```

This enables:
- Runtime API discovery
- Client SDK bootstrapping
- API versioning and compatibility checking
- Admin tooling and debugging

### 5. Type-Safe Testing

Endpoints are values you can reference directly in tests:

```kotlin
@Test
fun testGetUser() = runBlocking {
    val user = Server.users.info.table().insertOne(testUser)

    // Direct endpoint invocation with type safety
    val result = Server.getUser.test(
        auth = adminUser,
        input = user._id.toString()
    )

    assertEquals(testUser.email, result.email)
}
```

**Ktor alternative:** Use `testApplication` with HTTP client calls, losing type safety and requiring JSON serialization/deserialization in tests.

## What About Building This on Top of Ktor?

### Option A: Annotations + KSP

```kotlin
@TypedEndpoint(
    path = "/users/{id}",
    method = HttpMethod.Get,
    summary = "Get user"
)
suspend fun getUser(id: String): User { ... }
```

Then use KSP to generate Ktor routes, OpenAPI, and SDKs.

**Problems:**
- Duplicates what Lightning Server already does
- Annotations are less expressive than the DSL
- Still need to maintain the code generator
- Error cases, auth requirements don't map cleanly to annotations

### Option B: Runtime Registry

```kotlin
val endpoints = EndpointRegistry()
endpoints.register(TypedEndpoint("/users/{id}", ::getUser))

fun Application.module() {
    routing {
        endpoints.installAll(this)  // Register with Ktor
    }
}

// For generation
endpoints.generateOpenApi()
endpoints.generateSdk()
```

**Problems:**
- This is literally reimplementing Lightning Server's architecture
- You'd maintain both the registry abstraction AND Ktor integration
- No net reduction in maintenance burden

### Option C: Pure Ktor + External Tools

- Use Ktor normally for HTTP handling
- Write OpenAPI spec manually or use annotations
- Use openapi-generator for SDK generation
- Write Terraform manually

**Problems:**
- Loses "single source of truth" benefit
- Documentation drift becomes a real risk
- No infrastructure generation
- More manual work per endpoint

## When Ktor Add-ons Would Make Sense

The service abstractions in this repository (database, cache, files, email, SMS, etc.) are **already orthogonal to the HTTP layer**. They work equally well with Lightning Server, Ktor, or any other framework.

If the question is "should service-abstractions become Ktor plugins?", the answer is: **they already can be used with Ktor**. Just instantiate them in your Ktor application.

The question of Lightning Server specifically is about whether the **HTTP endpoint abstraction layer** provides enough value. Based on the features above, the answer is yes—for applications that need:

- Multi-platform SDK generation
- Infrastructure-as-code generation
- Self-documenting APIs
- Type-safe endpoint testing

## Historical Context

Lightning Server was originally designed to abstract server declaration from runner, enabling the same code to run on bare metal or AWS Lambda.

AWS Lambda/API Gateway specifically has become a pain point:
- Cold starts affecting latency
- Payload size limits
- WebSocket complexity through API Gateway
- API Gateway quirks and limitations

Container platforms (App Runner, Cloud Run, ECS) are simpler—they just run your server as-is with no special adaptation needed. The runner abstraction still provides value for these platforms (Ktor vs Netty vs JDK Server), but Lambda's unique execution model requires disproportionate maintenance effort.

**The endpoint introspection architecture remains valuable** independent of the Lambda question. The features it enables (SDK generation, documentation, Terraform) provide ongoing value regardless of deployment target.

## Recommendation

If Lambda/API Gateway is causing disproportionate maintenance burden:

1. **Deprecate the Lambda runner specifically** while keeping other engines (Ktor, Netty, JDK Server)
2. **Keep the ServerBuilder architecture** for its introspection benefits
3. **Keep service-abstractions separate** (they already are)

This removes the Lambda-specific pain while preserving:
- Runner flexibility for container platforms
- The unique value of typed endpoints, SDK generation, and Terraform generation

## Comparison Summary

| Feature | Lightning Server | Ktor + Plugins | Effort to Replicate |
|---------|-----------------|----------------|---------------------|
| Type-safe endpoints | Built-in | Annotations required | Medium |
| OpenAPI generation | Automatic from code | Requires annotations | Medium |
| SDK generation | Built-in (TS, Kotlin) | External tools | High |
| Terraform generation | Built-in | Manual or custom | Very High |
| Reflective analysis | Built-in (`MetaEndpoints`) | Not possible | Very High |
| Type-safe testing | `endpoint.test()` | HTTP client tests | Low |
| Settings/DI | `setting()` pattern | Bring your own DI | Low |
| WebSocket typed handlers | Built-in | Ktor WebSockets | Low |

## See Also

- [Basis Module](basis-module.md) - Core service abstractions
- [Database Module](database-module.md) - Database abstraction with type-safe queries
- [Architecture Overview](../CLAUDE.md) - High-level architecture
