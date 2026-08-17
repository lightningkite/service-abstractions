package com.lightningkite.services.geocoding

import kotlinx.serialization.Serializable

/**
 * A postal address broken into components, used both to ask for a location and to
 * describe one that came back.
 *
 * Every component is optional because geocoding is inherently partial work: a query
 * may only know `"Reno, NV"`, and a result for a rural coordinate may have no
 * [street]. Providers fill in what they can and leave the rest null.
 *
 * Component names follow the Pelias/OpenStreetMap vocabulary rather than US-specific
 * terms, since "state" and "ZIP code" do not generalize:
 *
 * | This library  | US        | UK             | France             |
 * |---------------|-----------|----------------|--------------------|
 * | [locality]    | city      | post town      | commune            |
 * | [region]      | state     | county         | région             |
 * | [postalCode]  | ZIP code  | postcode       | code postal        |
 *
 * @property street Street line including any house number, e.g. `"1600 Pennsylvania Ave NW"`
 * @property locality City, town, or village
 * @property region State, province, or equivalent top-level subdivision
 * @property postalCode Postal or ZIP code
 * @property country ISO 3166-1 alpha-2 code where known, e.g. `"US"`. Providers accept
 * full country names too, but the two-letter code is unambiguous and is what results use.
 */
@Serializable
public data class Address(
    public val street: String? = null,
    public val locality: String? = null,
    public val region: String? = null,
    public val postalCode: String? = null,
    public val country: String? = null,
) {
    /** True when no component is set, meaning there is nothing to search for. */
    public val isEmpty: Boolean
        get() = street == null && locality == null && region == null && postalCode == null && country == null

    /**
     * Renders the address as a single line, skipping absent components.
     *
     * Used by providers that only accept freeform text, and as a reasonable display
     * label when the provider did not supply one.
     */
    public fun toSingleLine(): String = listOfNotNull(
        street,
        locality,
        listOfNotNull(region, postalCode).joinToString(" ").takeIf { it.isNotEmpty() },
        country,
    ).joinToString(", ")

    override fun toString(): String = toSingleLine()
}
