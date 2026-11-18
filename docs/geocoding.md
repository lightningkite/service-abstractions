# Geocoding

`Geocoding` turns addresses into coordinates and back, and suggests addresses as the user types: Stadia Maps, Google, Amazon Location, Azure Maps, or an offline US dataset.

## Modules

| Module | Add it for |
|---|---|
| `geocoding` | `Geocoding`, `Address`, `GeocodeResult`, caching and rate-limiting wrappers, `test`.  Multiplatform. |
| `geocoding-local` | `local`: offline US places and ZIP codes, no key, no network.  JVM. |
| `geocoding-stadia` | `stadia://` (hosted Pelias; relaxed storage terms).  JVM. |
| `geocoding-google` | `google://` (Geocoding API and Places API (New)).  JVM. |
| `geocoding-aws` | `aws-location://` (Amazon Location Service), plus the `awsLocation` Terraform helper.  JVM. |
| `geocoding-azure` | `azure-maps://`.  JVM. |
| `geocoding-test` | `GeocodingTests`, the shared contract suite. |

## Quick start

```kotlin
StadiaGeocoding  // registers stadia://
val geocoding = Geocoding.Settings("stadia://sk_xxx")("geocoding", context).cached(cache)

val match: GeocodeResult? = geocoding.geocodeOne("350 5th Ave, New York NY 10118")
val here: GeocodeResult? = geocoding.reverseGeocodeOne(GeoCoordinate(40.7484, -73.9857))
```

## Common tasks

### Check the precision

A geocoder given an address it can't resolve exactly doesn't fail: it returns the centroid of the street, the postal code or the city.  That's fine for a map pin and badly wrong for dispatching a driver.

```kotlin
val match = geocoding.geocodeOne(address) ?: return AddressUnresolved
when {
    match.precision >= GeocodePrecision.STREET -> dispatchDriver(match.coordinate)
    match.precision >= GeocodePrecision.LOCALITY -> askUserToConfirm(match)
    else -> AddressTooVague
}
```

Precision runs `UNKNOWN` < `COUNTRY` < `REGION` < `LOCALITY` < `POSTAL_CODE` < `STREET` < `INTERPOLATED` < `ROOFTOP`.  `UNKNOWN` is lowest on purpose.

### Structured queries and focus

```kotlin
geocoding.geocodeOne(Address(street = "350 5th Ave", locality = "New York", region = "NY", postalCode = "10118", country = "US"))
geocoding.geocodeOne("Springfield", focus = userLocation)   // biases toward a point without excluding others
```

Prefer structured queries when you have the parts: providers match them better than a re-parsed string.  `Address` uses international names: `locality` (city, post town), `region` (state, county), `postalCode`.  The full query types are `GeocodeQuery.Text`, `GeocodeQuery.Structured`, `ReverseGeocodeQuery` (each with a `limit`).

### Autocomplete

```kotlin
val session = AutocompleteSession.random()          // one per address the user is typing
val suggestions: List<AddressSuggestion> = geocoding.autocomplete(AutocompleteQuery("350 5th a", session = session))
val chosen: GeocodeResult? = geocoding.resolve(suggestions.first().id, session)
```

Pass the session to both calls: Google bills a session ending in `resolve` as one unit, and every keystroke separately without one.  Debounce input either way.

### Cache and rate-limit

```kotlin
val geocoding = provider.cached(cache, timeToLive = 30.days).rateLimited(1.seconds)
```

`cached` stores misses too (bad input repeats) and passes autocomplete through.  Check your provider's terms before caching: Google generally forbids keeping coordinates beyond 30 days.  `rateLimited` queues callers rather than failing them.

### Errors

No match is an empty list (or `null` from `geocodeOne`).  `GeocodingException` means the provider failed: network, credentials, quota.  Google reports quota problems with HTTP 200, which `GoogleGeocoding` turns into an exception.

## Settings URLs

| Scheme | Module | Example |
|---|---|---|
| `test` | `geocoding` | `test` (the default; empty until you `add` results) |
| `local` | `geocoding-local` | `local` |
| `stadia` | `geocoding-stadia` | `stadia://sk_xxx` or `stadia://sk_xxx@api-eu.stadiamaps.com` |
| `google` | `geocoding-google` | `google://AIzaxxx` |
| `aws-location` | `geocoding-aws` | `aws-location://us-east-1?intendedUse=Storage` |
| `azure-maps` | `geocoding-azure` | `azure-maps://<subscription key>` or `azure-maps://<client id>?auth=entra` |

Details: [Settings URL reference: Geocoding](settings-url-reference.md#geocoding).  Reference the provider class (`StadiaGeocoding`, `GoogleGeocoding`, `AwsLocationGeocoding`, `AzureMapsGeocoding`, `LocalGeocoding`) before parsing settings.

- `aws-location://` defaults to `intendedUse=SingleUse`; storing the results requires `Storage`, which Amazon bills differently.
- Google keys need both the Geocoding API and Places API (New) enabled; autocomplete fails with `REQUEST_DENIED` otherwise.

### The offline provider

`local` answers US queries from a ~400 KB public-domain Census Gazetteer extract bundled in the jar: every ZIP Code Tabulation Area and every incorporated place.  It handles spelled-out state names and accents, and finds the city inside a full street address.  It has no street-level data, so a street address resolves to its ZIP or city centroid with `POSTAL_CODE` or `LOCALITY` precision.  US only (with DC and Puerto Rico).  It decodes ~1 MB on first use; `connect()` at startup pays that early.  Refresh the data with `python3 scripts/pack-gazetteer.py --year <year>`.

## Testing

`test` starts empty, so nothing matches by accident:

```kotlin
val geocoding = TestGeocoding("test", TestSettingContext()).apply {
    add(GeoCoordinate(39.5296, -119.8138), Address(locality = "Reno", region = "NV"))
}
```

For realistic data with no setup, use `local`.  Provider authors: subclass `GeocodingTests` (override `geocoding`, `knownQuery`, `knownCoordinate`, `unknownSuggestionId`).

## Gotchas

- **Confidence isn't comparable across providers**: each scores on its own scale.  Use it to rank within one result list.
- **Suggestions have no coordinates**: an `AddressSuggestion` is an id, a label and an address.  `resolve` the one the user picks (on Google, a second billed call).
- **API keys travel in query strings** for Stadia and Google; don't log request URLs.
- **Health checks cost a request**, so they run hourly.
