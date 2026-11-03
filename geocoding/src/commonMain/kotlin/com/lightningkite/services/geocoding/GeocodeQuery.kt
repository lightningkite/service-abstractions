package com.lightningkite.services.geocoding

import com.lightningkite.services.data.GeoCoordinate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.uuid.Uuid

/**
 * What to look up when forward geocoding.
 *
 * Two shapes rather than one nullable-everything class, because providers genuinely
 * treat them differently: [Structured] maps onto dedicated structured endpoints that
 * return better matches when the caller already has separate city/state/postal fields,
 * while [Text] is the freeform search box case. Making them distinct types also means
 * "neither was supplied" cannot be represented.
 *
 * @see Geocoding.geocode
 */
@Serializable
public sealed interface GeocodeQuery {
    /**
     * Bias results toward this point. Not a filter — distant matches can still be
     * returned, they just rank lower. Useful when you know roughly where the user is.
     */
    public val focus: GeoCoordinate?

    /** Maximum number of results to return. Providers may return fewer. */
    public val limit: Int

    /**
     * A freeform query, as typed into a search box.
     *
     * ```kotlin
     * geocoding.geocode(GeocodeQuery.Text("350 5th Ave, New York NY"))
     * ```
     */
    @Serializable
    @SerialName("text")
    public data class Text(
        public val text: String,
        override val focus: GeoCoordinate? = null,
        override val limit: Int = 5,
    ) : GeocodeQuery {
        init {
            require(text.isNotBlank()) { "Geocode text must not be blank" }
        }
    }

    /**
     * A query whose components are already separated.
     *
     * Prefer this when your data model stores address parts individually — it avoids
     * making the provider re-parse a string you already have structured.
     *
     * ```kotlin
     * geocoding.geocode(GeocodeQuery.Structured(Address(
     *     street = "350 5th Ave", locality = "New York", region = "NY", country = "US",
     * )))
     * ```
     */
    @Serializable
    @SerialName("structured")
    public data class Structured(
        public val address: Address,
        override val focus: GeoCoordinate? = null,
        override val limit: Int = 5,
    ) : GeocodeQuery {
        init {
            require(!address.isEmpty) { "Structured geocode query must set at least one address component" }
        }
    }

    /**
     * This query rendered as a single line, for providers with no structured endpoint.
     */
    public fun toSingleLine(): String = when (this) {
        is Text -> text
        is Structured -> address.toSingleLine()
    }
}

/**
 * What to look up when reverse geocoding: which addresses are at or near a point.
 *
 * @property coordinate The point to describe
 * @property limit Maximum number of results, nearest first
 * @see Geocoding.reverseGeocode
 */
@Serializable
public data class ReverseGeocodeQuery(
    public val coordinate: GeoCoordinate,
    public val limit: Int = 1,
)

/**
 * A partial query for type-ahead address entry.
 *
 * @property text What the user has typed so far. May be a fragment of a word.
 * @property focus Bias suggestions toward this point — worth setting for autocomplete,
 * since "spring" means something very different in Texas than in Ohio.
 * @property country Restrict suggestions to this ISO 3166-1 alpha-2 country code.
 * @property limit Maximum number of suggestions.
 * @property session Groups this keystroke with the rest of one address entry. See [AutocompleteSession].
 * @see Geocoding.autocomplete
 */
@Serializable
public data class AutocompleteQuery(
    public val text: String,
    public val focus: GeoCoordinate? = null,
    public val country: String? = null,
    public val limit: Int = 5,
    public val session: AutocompleteSession? = null,
) {
    init {
        require(text.isNotBlank()) { "Autocomplete text must not be blank" }
    }
}

/**
 * One user's journey from typing an address to picking a suggestion.
 *
 * Create one when the user starts typing, send it with every [AutocompleteQuery], and
 * pass it to [Geocoding.resolve] for the suggestion they pick. Then discard it: the next
 * address entry needs a new one.
 *
 * Google bills a session as a unit, which is cheaper than billing each keystroke.
 * Reusing a token after [Geocoding.resolve] invalidates the session, and Google then bills
 * every call as if no token had been sent. Providers that do not bill by session ignore it.
 *
 * Usually created on the client, since one address entry spans many requests to your server.
 */
@Serializable
@JvmInline
public value class AutocompleteSession(public val token: Uuid) {
    public companion object {
        public fun random(): AutocompleteSession = AutocompleteSession(Uuid.random())
    }
}
