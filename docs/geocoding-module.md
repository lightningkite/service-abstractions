# Geocoding Module - User Guide

**Module:** `geocoding`
**Package:** `com.lightningkite.services.geocoding`
**Purpose:** Turn addresses into coordinates and coordinates back into addresses

---

## Overview

The geocoding module provides a unified interface over hosted geocoders (Stadia Maps, Google, Nominatim) plus a
bundled offline provider that answers real US queries with no API key and no network.

### Key Features

- **Forward, reverse, and autocomplete** in one interface
- **Honest precision reporting** - every result says how precisely it was resolved
- **Structured or freeform queries** - use whichever your data model already has
- **Offline provider** - real US coordinates from bundled public-domain Census data
- **Caching and rate limiting** built in - geocoding is billed per request, and some providers ban you for
  exceeding published limits

---

## Quick Start

### 1. Configure the service

```kotlin
@Serializable
data class ServerSettings(
    val geocoding: Geocoding.Settings = Geocoding.Settings("local")
)

val context = SettingContext(...)
val geocoding: Geocoding = settings.geocoding("geocoding", context)
```

**Supported URL schemes:**

| Scheme                                  | Module                 | Notes                                    |
|-----------------------------------------|------------------------|------------------------------------------|
| `test`                                  | `geocoding`            | Empty programmable table for unit tests  |
| `local`                                 | `geocoding-local`      | Offline US data, no key, no network      |
| `stadia://apiKey`                       | `geocoding-stadia`     | Hosted Pelias; relaxed storage terms     |
| `stadia://apiKey@api-eu.stadiamaps.com` | `geocoding-stadia`     | EU endpoint for data residency           |
| `google://apiKey`                       | `geocoding-google`     | Most accurate, most restrictive terms    |
| `nominatim://you@example.com`           | `geocoding-nominatim`  | Free OpenStreetMap; 1 req/sec            |
| `nominatim://you@example.com/your-host` | `geocoding-nominatim`  | Self-hosted instance                     |

Provider modules register their scheme in a companion `init` block, so the class must be referenced at least once
before its URL parses. Importing the matching helper (`Geocoding.Settings.stadia(...)`) is the usual way.

### 2. Look something up

```kotlin
// Forward: address to coordinate
val match = geocoding.geocodeOne("350 5th Ave, New York NY 10118")

// Reverse: coordinate to address
val here = geocoding.reverseGeocodeOne(GeoCoordinate(40.7484, -73.9857))

// Type-ahead
val suggestions = geocoding.autocomplete(AutocompleteQuery("350 5th a"))
```

---

## Always Check the Precision

This is the single most important thing to get right.

A geocoder handed an address it cannot resolve exactly does **not** fail. It returns the centroid of the street, the
postal code, or the whole city — whichever it could match. That result is fine for a map pin and badly wrong for
dispatching a driver.

```kotlin
val match = geocoding.geocodeOne(address) ?: return AddressUnresolved

when {
    match.precision >= GeocodePrecision.STREET -> dispatchDriver(match.coordinate)
    match.precision >= GeocodePrecision.LOCALITY -> showOnMapButAskForConfirmation(match)
    else -> AddressTooVague
}
```

`GeocodePrecision` is declared coarsest to finest, so `>=` reads naturally:

`UNKNOWN` < `COUNTRY` < `REGION` < `LOCALITY` < `POSTAL_CODE` < `STREET` < `INTERPOLATED` < `ROOFTOP`

`UNKNOWN` sorts lowest deliberately: a provider that will not say how precise a match is should never win a
comparison against one that will.

---

## Queries

Two shapes, because providers genuinely treat them differently.

```kotlin
// Freeform - a search box
geocoding.geocode(GeocodeQuery.Text("350 5th Ave, New York NY"))

// Structured - your data model already has the parts
geocoding.geocode(GeocodeQuery.Structured(Address(
    street = "350 5th Ave",
    locality = "New York",
    region = "NY",
    postalCode = "10118",
    country = "US",
)))
```

Prefer structured when you have the components. Stadia sends it to a dedicated structured endpoint, and Google turns
it into a components filter, both of which match better than re-parsing a string you already had in pieces.

`Address` uses Pelias/OSM component names rather than US-specific ones, since "state" and "ZIP code" do not
generalize:

| This library | US       | UK        | France      |
|--------------|----------|-----------|-------------|
| `locality`   | city     | post town | commune     |
| `region`     | state    | county    | région      |
| `postalCode` | ZIP code | postcode  | code postal |

### Focus points

`focus` biases results toward a point without excluding distant ones. It is the cheapest way to disambiguate — there
are more than thirty Springfields.

```kotlin
geocoding.geocodeOne("Springfield", focus = userLocation)
```

---

## Empty Results vs Errors

- **An address the provider does not recognize returns an empty list.** This is normal and not an error.
- **`GeocodingException` means the provider failed** - network down, bad credentials, quota exhausted, unparseable
  response.

Google is a notable trap here: it reports quota and key problems with HTTP 200 and a `status` field in the body.
`GoogleGeocoding` checks that field, so `OVER_QUERY_LIMIT` raises rather than silently looking like "no results".

---

## Caching

Wrap any hosted provider. Buildings do not move, so a long TTL is safe, and applications geocode the same handful of
addresses over and over.

```kotlin
val geocoding = Geocoding.Settings("stadia://$apiKey")("geocoding", context)
    .cached(cache)                       // 30 day default
    .cached(cache, timeToLive = 7.days)  // or shorter, if your provider's terms require it
```

- Misses are cached too — bad input arrives repeatedly, and re-billing for it is the bug this avoids.
- Autocomplete passes straight through; every keystroke is a different query, so the hit rate is near zero.
- **Check your provider's terms.** Google generally prohibits caching coordinates beyond 30 days. Stadia and
  Nominatim are far more permissive.

---

## Rate Limiting

```kotlin
val geocoding = someProvider.rateLimited(1.seconds)
```

Callers queue rather than fail, so this bounds throughput, not latency. `NominatimGeocoding` applies this to itself
by default — its public endpoint allows one request per second and enforces that by banning your IP, which is not a
retryable error.

---

## The Offline Provider

`geocoding-local` answers real US queries from a ~400 KB public-domain US Census Gazetteer extract bundled in the
jar: the centroid of every ZIP Code Tabulation Area (~33,800) and every incorporated place and CDP (~32,400).

```kotlin
val geocoding = Geocoding.Settings("local")("geocoding", context)
geocoding.geocodeOne("Sacramento, CA")   // 38.568, -121.468
geocoding.geocodeOne("89501")            // 39.526, -119.813
```

It handles spelled-out state names, folds accents (`Espanola` matches `Española`), finds the city inside a full
street address, and knows consolidated city-counties by their city name (`Augusta, GA` resolves even though the
Census calls it "Augusta-Richmond County consolidated government").

**Design around these limits:**

- **No street-level data.** House-number databases are gigabytes. A street address resolves to its ZIP or city
  centroid and reports `POSTAL_CODE` or `LOCALITY`. It never claims `ROOFTOP`. Code that checks precision — which it
  should anyway — works correctly against this provider unchanged.
- **United States only**, including DC and Puerto Rico. Other countries return no results.
- **Same-named cities rank crudely.** The public domain source carries no population data, so `Springfield` with no
  state returns incorporated places first, then alphabetically by state. Pass a region or a focus point.
- **Costs about 1 MB of heap** and ~100 ms to decode, lazily on first use. Call `connect()` at startup to pay that
  before the first request.

### Refreshing the data

The packed resource is checked in. To move to a newer Census vintage:

```bash
python3 scripts/pack-gazetteer.py --year 2026
```

---

## Testing

`TestGeocoding` starts empty, so nothing matches by accident:

```kotlin
val geocoding = TestGeocoding("test", context).apply {
    add(GeoCoordinate(39.5296, -119.8138), Address(locality = "Reno", region = "NV"))
}
```

For realistic data with no setup, use `local` instead — it is a real geocoder over real data and needs no fixtures.

Implementations share a contract suite in `geocoding-test`:

```kotlin
class MyGeocodingTest : GeocodingTests() {
    override val geocoding = MyGeocoding("test", TestSettingContext())
    override val knownQuery = GeocodeQuery.Text("Reno, NV")
    override val knownCoordinate = GeoCoordinate(39.5296, -119.8138)
}
```

### Running the live provider tests

Every provider has a mocked suite that always runs, plus a live suite that talks to the real
API. The live suites skip themselves — printing a reason, not failing — until you configure
credentials, so a fresh checkout stays green.

Create `secrets/geocoding.properties` at the repo root. **The `secrets/` directory is
git-ignored; never move these values into `local.properties` or a build file.**

```properties
# Stadia Maps -> https://client.stadiamaps.com/dashboard/  (free tier available)
STADIA_API_KEY=...

# Google Cloud -> enable BOTH "Geocoding API" and "Places API" on the key.
# Autocomplete is billed through Places and returns REQUEST_DENIED if it is not enabled.
GOOGLE_MAPS_API_KEY=...

# Nominatim needs no key, only a contact address, which its usage policy requires on
# every request. Setting this opts you in to using the shared public instance.
NOMINATIM_CONTACT_EMAIL=you@example.com
```

Then:

```bash
./gradlew :geocoding-stadia:test :geocoding-google:test :geocoding-nominatim:test
```

Each value is read from an environment variable of the same name first, falling back to that
file, so CI can inject them as secrets without a file existing. The lookup walks up from the
working directory, which means the file is found whichever module Gradle runs from.

A few things worth knowing before you enable these:

- **They cost money.** Stadia and Google bill per request, and `./gradlew test` will run them
  once configured. Comment a key out to switch its provider back off.
- **Nominatim is a shared free service.** Its suite runs on a real clock rather than
  `runTest`'s virtual one, because the provider paces itself with `delay` and a virtual clock
  would fire every request at once — which is what gets an IP banned. Expect it to take a few
  seconds per test.
- **Keep the live suites small.** They exist to catch a provider changing its response shape,
  not to re-test logic the mocked suites already cover against recorded payloads.

---

## Provider Comparison

|                          | Stadia            | Google              | Nominatim            | Local            |
|--------------------------|-------------------|---------------------|----------------------|------------------|
| API key                  | Yes               | Yes                 | Contact email        | None             |
| Coverage                 | Global            | Global, best        | Global, uneven       | US only          |
| Rooftop precision        | Yes               | Yes, best           | Yes, where mapped    | Never            |
| Autocomplete coordinates | Yes               | No, needs 2nd call  | Yes                  | Yes              |
| Storage terms            | Relaxed           | Restrictive, 30 day | ODbL, attribution    | Unrestricted     |
| Rate limit               | Per plan          | Per plan            | 1/sec, self-hosted   | None             |
| Self-hostable            | No                | No                  | Yes                  | N/A              |

---

## Gotchas

- **Confidence is not comparable across providers.** Each vendor scores on its own scale, and Nominatim's
  `importance` measures prominence rather than match quality. Use it to rank within one result list, nothing more.
- **Google's autocomplete returns no coordinates.** `AddressSuggestion.coordinate` is null; resolving a place ID is a
  second billed call. Geocode the label once the user picks a suggestion — this works on every provider and only
  costs a request for the one suggestion actually chosen.
- **Google bills autocomplete per keystroke** unless you send a session token, which this implementation does not.
  Debounce your input.
- **Nominatim autocomplete is discouraged by its operators.** It works, but against the public endpoint it is both
  slow and rude. Self-host or use another provider for interactive input.
- **API keys travel in query parameters** for Stadia and Google. Do not log raw request URLs. This module's
  exceptions deliberately report status and body but never the URL.
- **Health checks cost a request.** `healthCheckFrequency` defaults to one hour rather than the usual minute for
  exactly this reason.

---

## See Also

- [Basis Module](basis-module.md) - `Setting`, `Service`, `SettingContext`
- [Cache Module](cache-module.md) - backs `CachedGeocoding`
