package com.lightningkite.services.geocoding

import com.lightningkite.services.data.GeoCoordinate
import kotlinx.serialization.Serializable

/**
 * How precisely a [GeocodeResult] pins down its coordinate.
 *
 * Declared coarsest to finest, so ordinal comparison is meaningful and reads naturally:
 *
 * ```kotlin
 * val usable = results.filter { it.precision >= GeocodePrecision.STREET }
 * ```
 *
 * [UNKNOWN] is the exception — it sorts lowest because a provider that will not say
 * how precise a match is should never win a comparison against one that will.
 *
 * Checking this matters more than it looks. A geocoder asked for a bad street address
 * will happily return the centroid of the city or postal code instead of failing, and
 * that result is fine for drawing a map pin but badly wrong for dispatching a driver.
 */
public enum class GeocodePrecision {
    /** The provider did not report a precision. */
    UNKNOWN,

    /** Resolved only to a country. */
    COUNTRY,

    /** Resolved to a state, province, or equivalent. */
    REGION,

    /** Resolved to a city, town, or village — the coordinate is an area centroid. */
    LOCALITY,

    /** Resolved to a postal code — the coordinate is an area centroid. */
    POSTAL_CODE,

    /** Resolved to a street, but not to a specific building on it. */
    STREET,

    /** Estimated along a street by interpolating between known house numbers. */
    INTERPOLATED,

    /** An exact known position for the building itself. */
    ROOFTOP,
}

/**
 * A rectangular geographic area, as reported by providers alongside a match.
 *
 * Mostly useful for fitting a map viewport: a result for a whole city should zoom out
 * further than one for a single building.
 *
 * @property southwest The minimum-latitude, minimum-longitude corner
 * @property northeast The maximum-latitude, maximum-longitude corner
 */
@Serializable
public data class GeoBoundingBox(
    public val southwest: GeoCoordinate,
    public val northeast: GeoCoordinate,
) {
    /** The center of the box. */
    public val center: GeoCoordinate
        get() = GeoCoordinate(
            latitude = (southwest.latitude + northeast.latitude) / 2,
            longitude = (southwest.longitude + northeast.longitude) / 2,
        )
}

/**
 * One candidate location for a geocoding query.
 *
 * Results are ordered best-match first. Note that a non-empty result list is *not* the
 * same as a good match — always check [precision] before treating a coordinate as
 * exact. See [GeocodePrecision] for why.
 *
 * @property coordinate Where the match is
 * @property label The provider's one-line rendering of the match, suitable for display
 * @property address The match broken into components, as far as the provider resolved them
 * @property precision How precisely [coordinate] is pinned down
 * @property confidence The provider's own 0.0–1.0 score where it supplies one. Not
 * comparable across providers, and null for those that report nothing.
 * @property boundingBox The area the match covers, where the provider supplies one
 */
@Serializable
public data class GeocodeResult(
    public val coordinate: GeoCoordinate,
    public val label: String,
    public val address: Address = Address(),
    public val precision: GeocodePrecision = GeocodePrecision.UNKNOWN,
    public val confidence: Double? = null,
    public val boundingBox: GeoBoundingBox? = null,
)

/**
 * One type-ahead suggestion from [Geocoding.autocomplete].
 *
 * @property label The text to show the user, e.g. `"350 5th Ave, New York, NY, USA"`
 * @property address Components, as far as the provider resolved them
 * @property coordinate Where the suggestion is, **when the provider supplies it**.
 *
 * This is null for providers whose autocomplete returns references rather than
 * positions — notably Google, whose Places Autocomplete returns a place ID and
 * requires a second billed call to resolve coordinates. Callers that need a position
 * should geocode [label] once the user picks a suggestion, which works against every
 * provider and only costs a request for the one suggestion actually chosen.
 */
@Serializable
public data class AddressSuggestion(
    public val label: String,
    public val address: Address = Address(),
    public val coordinate: GeoCoordinate? = null,
)
