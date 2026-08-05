package com.lightningkite.services.database.postgres

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.v1.jdbc.Database

/**
 * A live Exposed [Database] together with the [HikariDataSource] backing it.
 *
 * Held by [PostgresDatabase] so [PostgresDatabase.disconnect] can close the pool and drop its
 * connections — required for the serverless reconnect contract.
 */
public class PooledDatabase(
    public val database: Database,
    public val dataSource: HikariDataSource?,
)

/**
 * Connection-pool parameters parsed from a settings URL's query string. Every field mirrors a
 * HikariCP setting of the same name; `null` means "leave Hikari's default". Times are milliseconds.
 */
internal data class PoolParams(
    val maxPoolSize: Int? = null,
    val minIdle: Int? = null,
    val connectionTimeout: Long? = null,
    val idleTimeout: Long? = null,
    val maxLifetime: Long? = null,
    val validationTimeout: Long? = null,
    val poolName: String? = null,
)

/** Query-param keys that configure the pool itself rather than the JDBC driver. */
private val POOL_PARAM_KEYS = setOf(
    "maxPoolSize", "minIdle", "connectionTimeout", "idleTimeout",
    "maxLifetime", "validationTimeout", "poolName",
)

/**
 * The result of splitting a settings URL remainder into the JDBC-bound portion and the parsed pool
 * params. JDBC-specific query params (anything not in [POOL_PARAM_KEYS]) are preserved on
 * [jdbcQuery] so they keep flowing to the driver.
 */
internal data class SplitUrl(
    val base: String,
    val jdbcQuery: String?,
    val pool: PoolParams,
)

/** The `[user[:password]]@destination` portion of a `postgresql://` settings URL, as parsed by [parsePostgresAuthUrl]. */
internal data class ParsedAuthUrl(
    val user: String?,
    val password: String?,
    val destination: String,
)

/**
 * Parses a `postgresql://[user[:password]]@destination` settings URL using [java.net.URI] rather
 * than a hand-rolled regex. A regex without a real grammar can't tell where the user-info ends and
 * the host begins when the password itself contains `:` or `@` — [java.net.URI] does, and it
 * percent-decodes the credentials for us. Returns null if the URL has no host (i.e. isn't a valid
 * `postgresql://` URL at all).
 */
internal fun parsePostgresAuthUrl(url: String): ParsedAuthUrl? {
    val uri = try {
        java.net.URI(url)
    } catch (e: java.net.URISyntaxException) {
        return null
    }
    val host = uri.host ?: return null
    val userInfo = uri.userInfo
    val user = userInfo?.substringBefore(':')
    val password = userInfo?.let { if (':' in it) it.substringAfter(':') else "" }
    val port = if (uri.port != -1) ":${uri.port}" else ""
    val destination = "$host$port${uri.rawPath}${uri.rawQuery?.let { "?$it" } ?: ""}"
    return ParsedAuthUrl(user = user, password = password, destination = destination)
}

/**
 * Splits a URL remainder (everything after the scheme prefix) on a trailing `?query`, routing
 * pool-only params to [PoolParams] and forwarding any remaining JDBC params on [SplitUrl.jdbcQuery].
 */
internal fun splitPoolQuery(remainder: String): SplitUrl {
    val base = remainder.substringBefore('?')
    val query = remainder.substringAfter('?', "").takeIf { it.isNotBlank() }
        ?: return SplitUrl(base, null, PoolParams())

    val pairs = query.split('&').filter { it.isNotBlank() }.map {
        it.substringBefore('=') to it.substringAfter('=', "")
    }
    val poolMap = pairs.filter { it.first in POOL_PARAM_KEYS }.toMap()
    val jdbcPairs = pairs.filter { it.first !in POOL_PARAM_KEYS }

    val pool = PoolParams(
        maxPoolSize = poolMap["maxPoolSize"]?.toInt(),
        minIdle = poolMap["minIdle"]?.toInt(),
        connectionTimeout = poolMap["connectionTimeout"]?.toLong(),
        idleTimeout = poolMap["idleTimeout"]?.toLong(),
        maxLifetime = poolMap["maxLifetime"]?.toLong(),
        validationTimeout = poolMap["validationTimeout"]?.toLong(),
        poolName = poolMap["poolName"],
    )
    val jdbcQuery = jdbcPairs.takeIf { it.isNotEmpty() }
        ?.joinToString("&") { (k, v) -> if (v.isEmpty()) k else "$k=$v" }
    return SplitUrl(base, jdbcQuery, pool)
}

/** Builds a HikariCP-pooled Exposed [Database] for PostgreSQL. */
internal fun makePooledDatabase(
    jdbcUrl: String,
    driver: String,
    user: String? = null,
    password: String? = null,
    pool: PoolParams = PoolParams(),
): PooledDatabase {
    val config = HikariConfig()
    config.jdbcUrl = jdbcUrl
    config.driverClassName = driver
    user?.let { config.username = it }
    password?.let { config.password = it }

    pool.maxPoolSize?.let { config.maximumPoolSize = it }
    pool.minIdle?.let { config.minimumIdle = it }
    pool.connectionTimeout?.let { config.connectionTimeout = it }
    pool.idleTimeout?.let { config.idleTimeout = it }
    pool.maxLifetime?.let { config.maxLifetime = it }
    pool.validationTimeout?.let { config.validationTimeout = it }
    pool.poolName?.let { config.poolName = it }

    val dataSource = HikariDataSource(config)
    return PooledDatabase(Database.connect(dataSource), dataSource)
}
