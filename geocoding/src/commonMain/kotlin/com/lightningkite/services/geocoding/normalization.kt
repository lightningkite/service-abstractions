package com.lightningkite.services.geocoding

// Generated from Unicode NFD decompositions of U+00C0..U+017F; the two strings are
// index-aligned. Folding is done with an explicit table rather than the platform's
// Unicode support because `java.text.Normalizer` has no multiplatform equivalent, and
// because a fixed table gives every target byte-identical results.
private const val FOLD_FROM =
    "àáâãäåçèéêëìíîïñòóôõöùúûüýÿāăąćĉċčďēĕėęěĝğġģĥĩīĭįĵķĺļľńņňōŏőŕŗřśŝşšţťũūŭůűųŵŷźżž"
private const val FOLD_TO =
    "aaaaaaceeeeiiiinooooouuuuyyaaaccccdeeeeegggghiiiijklllnnnooorrrssssttuuuuuuwyzzz"

/** Letters that expand to more than one ASCII character, or that NFD does not decompose. */
private val FOLD_MULTI: Map<Char, String> = mapOf(
    'æ' to "ae", 'œ' to "oe", 'ß' to "ss", 'þ' to "th", 'ĳ' to "ij",
    'ø' to "o", 'ð' to "d", 'đ' to "d", 'ħ' to "h", 'ł' to "l",
    'ŧ' to "t", 'ŋ' to "n", 'ı' to "i", 'ſ' to "s", 'ŀ' to "l", 'ĸ' to "k", 'ŉ' to "n",
)

/**
 * Reduces a place name to a form suitable for comparing user input against reference
 * data: lowercased, accents folded to ASCII, and every run of punctuation or whitespace
 * collapsed to a single space.
 *
 * This is what lets `"Espanola nm"`, `"Española, NM"` and `"ESPAÑOLA  NM"` all match the
 * same record. Letters outside the Latin folding table are lowercased but otherwise left
 * alone, so non-Latin scripts survive intact rather than being stripped.
 *
 * ```kotlin
 * "St. Mary's, ND".normalizedForGeocoding()  // "st mary s nd"
 * ```
 */
public fun String.normalizedForGeocoding(): String {
    val out = StringBuilder(length)
    var pendingSeparator = false
    for (raw in this.lowercase()) {
        // Combining diacritical marks (U+0300..U+036F) are dropped so decomposed input
        // folds the same as precomposed input.
        if (raw.code in 0x300..0x36F) continue

        val folded: String? = when {
            raw in 'a'..'z' || raw in '0'..'9' -> raw.toString()
            else -> {
                val i = FOLD_FROM.indexOf(raw)
                if (i >= 0) FOLD_TO[i].toString()
                else FOLD_MULTI[raw] ?: if (raw.isLetterOrDigit()) raw.toString() else null
            }
        }

        if (folded == null) {
            pendingSeparator = out.isNotEmpty()
        } else {
            if (pendingSeparator) out.append(' ')
            pendingSeparator = false
            out.append(folded)
        }
    }
    return out.toString()
}
