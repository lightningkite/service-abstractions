package com.lightningkite.services.embedding

/**
 * Typed exception hierarchy for embedding calls. Provider implementations wrap their
 * underlying HTTP / transport / protocol exceptions into one of these subclasses so
 * callers can handle errors uniformly across providers.
 */
public sealed class EmbeddingException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    /** Authentication / authorization failure: missing, invalid, or expired credentials. */
    public class Auth(message: String, cause: Throwable? = null) : EmbeddingException(message, cause)

    /** Quota, rate limit, or concurrency limit hit. */
    public class RateLimit(
        message: String,
        public val retryAfter: kotlin.time.Duration? = null,
        cause: Throwable? = null,
    ) : EmbeddingException(message, cause)

    /** Model ID unknown, unavailable, or not enabled. */
    public class InvalidModel(
        public val modelId: EmbeddingModelId,
        message: String,
        cause: Throwable? = null,
    ) : EmbeddingException(message, cause)

    /** Request was malformed or violated provider-specific constraints. */
    public class InvalidRequest(message: String, cause: Throwable? = null) : EmbeddingException(message, cause)

    /** Provider-side failure: 5xx, internal error, overloaded. */
    public class ServerError(message: String, cause: Throwable? = null) : EmbeddingException(message, cause)

    /** Network / transport failure: DNS, TCP reset, TLS error, socket closed, read timeout. */
    public class Transport(message: String, cause: Throwable? = null) : EmbeddingException(message, cause)
}
