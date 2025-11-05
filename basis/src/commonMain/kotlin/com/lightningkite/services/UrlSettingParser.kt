package com.lightningkite.services

/**
 * Registry pattern for URL-based service configuration.
 *
 * `UrlSettingParser` allows service implementations to register themselves under specific
 * URL schemes (e.g., "redis://", "mongodb://", "ram://"). When a URL string is parsed,
 * the appropriate handler is looked up by scheme and invoked to create the service.
 *
 * ## Usage Pattern
 *
 * Service libraries register their implementations in init blocks:
 * ```kotlin
 * object CacheUrlSettingParser : UrlSettingParser<Cache>()
 *
 * // In RedisCache implementation:
 * init {
 *     CacheUrlSettingParser.register("redis") { name, url, context ->
 *         RedisCache(name, url, context)
 *     }
 * }
 *
 * // In InMemoryCache implementation:
 * init {
 *     CacheUrlSettingParser.register("ram") { name, url, context ->
 *         InMemoryCache(name, context)
 *     }
 * }
 * ```
 *
 * ## URL Format
 *
 * URLs follow standard format: `scheme://host:port/path?query#fragment`
 *
 * The scheme (before "://") determines which handler is invoked.
 * Handler implementations parse the rest of the URL as needed.
 *
 * Common schemes:
 * - `ram://` or just `ram` - In-memory implementation
 * - `mongodb://host:port/database` - MongoDB connection
 * - `redis://host:port/db` - Redis connection
 * - `postgresql://user:pass@host/db` - PostgreSQL connection
 * - `s3://bucket/prefix` - AWS S3 storage
 *
 * @param T The type of service this parser creates
 */
public abstract class UrlSettingParser<T> {
    private val handlers = HashMap<String, (name: String, url: String, SettingContext) -> T>()

    /**
     * Set of registered URL schemes this parser can handle.
     *
     * Example: `["redis", "ram", "memcached"]` for a cache parser
     */
    public val options: Set<String> get() = handlers.keys

    /**
     * Registers a handler for a specific URL scheme.
     *
     * @param key URL scheme (without "://"), e.g., "redis", "mongodb", "ram"
     * @param handler Factory function that creates service instances from URLs
     * @throws Error if the key is already registered (security: prevents hostile takeover)
     */
    public fun register(key: String, handler: (name: String, url: String, SettingContext) -> T) {
        if(key in handlers) throw Error("Key $key already registered for ${this::class}.  This could be an attempt from a hostile library to control a particular implementation.")
        handlers[key] = handler
    }

    /**
     * Checks if this parser can handle a given URL scheme.
     *
     * @param schema URL scheme to check (with or without "://")
     * @return true if a handler is registered for this scheme
     */
    public fun supports(schema: String): Boolean {
        return schema in options
    }

    /**
     * Parses a URL string and creates the appropriate service instance.
     *
     * Extracts the scheme from the URL and delegates to the registered handler.
     *
     * @param name Unique identifier for the service instance
     * @param url Full URL string (e.g., "redis://localhost:6379/0")
     * @param module Runtime context for service instantiation
     * @return Configured service instance
     * @throws IllegalArgumentException if no handler is registered for the URL scheme
     */
    public fun parse(name: String, url: String, module: SettingContext): T {
        val key = url.substringBefore("://")
        val h = handlers[key]
            ?: throw IllegalArgumentException("No handler $key for ${this::class} - available handlers are ${options.joinToString()}")
        return h(name, url, module)
    }
}

/**
 * Marker interface for settings objects that contain a URL.
 *
 * Used with [HasUrlSettingParser] to enable richer configuration objects
 * that include a URL plus additional parameters.
 */
public interface HasUrl {
    /**
     * URL string that determines which service implementation to use.
     */
    public val url: String
}

/**
 * URL-based parser for settings objects (not just plain URL strings).
 *
 * Similar to [UrlSettingParser], but works with settings objects that contain
 * a URL plus additional configuration. This allows:
 * - Type-safe additional parameters beyond the URL
 * - Default values in the settings data class
 * - Serialization of the entire configuration
 *
 * ## Usage Pattern
 *
 * ```kotlin
 * @Serializable
 * data class DatabaseSettings(
 *     override val url: String,
 *     val poolSize: Int = 10,
 *     val timeout: Duration = 30.seconds
 * ) : HasUrl
 *
 * object DatabaseSettingParser : HasUrlSettingParser<DatabaseSettings, Database>()
 *
 * init {
 *     DatabaseSettingParser.register("mongodb") { name, settings, context ->
 *         MongoDatabase(name, settings.url, settings.poolSize, settings.timeout, context)
 *     }
 * }
 * ```
 *
 * @param SETTING Settings data class type that contains a URL
 * @param T The type of service this parser creates
 */
public abstract class HasUrlSettingParser<SETTING: HasUrl, T> {
    private val handlers = HashMap<String, (name: String, setting: SETTING, SettingContext) -> T>()

    /**
     * Set of registered URL schemes this parser can handle.
     */
    public val options: Set<String> get() = handlers.keys

    /**
     * Registers a handler for a specific URL scheme.
     *
     * @param key URL scheme (without "://"), e.g., "redis", "mongodb", "ram"
     * @param handler Factory function that creates service instances from settings
     * @throws Error if the key is already registered (security: prevents hostile takeover)
     */
    public fun register(key: String, handler: (name: String, setting: SETTING, SettingContext) -> T) {
        if(key in handlers) throw Error("Key $key already registered for ${this::class}.  This could be an attempt from a hostile library to control a particular implementation.")
        handlers[key] = handler
    }

    /**
     * Parses a settings object and creates the appropriate service instance.
     *
     * Extracts the scheme from the settings' URL and delegates to the registered handler.
     *
     * @param name Unique identifier for the service instance
     * @param setting Configuration object containing URL and additional parameters
     * @param module Runtime context for service instantiation
     * @return Configured service instance
     * @throws IllegalArgumentException if no handler is registered for the URL scheme
     */
    public fun parse(name: String, setting: SETTING, module: SettingContext): T {
        val key = setting.url.substringBefore("://")
        val h = handlers[key]
            ?: throw IllegalArgumentException("No handler $key for ${this::class} - available handlers are ${options.joinToString()}")
        return h(name, setting, module)
    }
}

/*
 * TODO: API Recommendations
 *
 * 1. Consider adding URL validation:
 *    - Method to validate URL format before attempting to parse
 *    - Would enable better error messages during configuration
 *    - Could check for common mistakes (missing "://", invalid characters, etc.)
 *
 * 4. Consider adding metadata to registrations:
 *    - Description, required URL format, example URLs
 *    - Would enable better error messages and documentation
 *    - Could power configuration UI/validators
 */
