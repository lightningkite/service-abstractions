package com.lightningkite.services.database

import kotlin.math.min

/**
 * A parsed search-box query: plain words, `"quoted phrases"`, and `-excluded` words or phrases.
 *
 * Words tolerate typos in proportion to their length; phrases and exclusions must match exactly.
 * Matching is case-insensitive and compares whole words, so `cat` does not match `concatenate`.
 */
public data class TextQuery(
    val exact: Set<String> = setOf(),
    val fuzzy: Set<String> = setOf(),
    val reject: Set<String> = setOf(),
) {
    public companion object {
        /** Parses [string] using the syntax most search boxes accept, e.g. `kotlin "type safe" -java`. */
        public fun fromString(string: String): TextQuery {
            val exact: HashSet<String> = HashSet()
            val loose: HashSet<String> = HashSet()
            val reject: HashSet<String> = HashSet()
            var inQuotes = false
            var isReject = false
            val building = StringBuilder()
            fun register(exactMode: Boolean) {
                if (building.length == 0) return
                val str = building.toString()
                building.clear()
                if (isReject) reject += str
                else if (exactMode) exact += str
                else loose += str
                isReject = false
            }
            for (char in string) {
                if (inQuotes) {
                    if (char == '\"') {
                        inQuotes = false
                        register(true)
                    } else {
                        building.append(char.lowercaseChar())
                    }
                } else {
                    when (char) {
                        ' ' -> register(false)
                        '"' -> inQuotes = true
                        '-' -> if (building.length > 0) building.append(char) else isReject = true
                        else -> building.append(char.lowercaseChar())
                    }
                }
            }
            register(false)

            return TextQuery(
                exact = exact,
                fuzzy = loose,
                reject = reject,
            )
        }
    }

    /**
     * Returns how well [text] matches this query, from just above `0` to `1`, or `null` if it doesn't match.
     *
     * A match needs every word and phrase present and no exclusion present. An exact word scores `1`, and a word
     * matched with typos scores less the more edits it took. A query with no words or phrases matches everything
     * not excluded with a score of `1`.
     */
    public fun score(text: String): Float? {
        val textWords = words(text)
        if (reject.any { textWords.containsPhrase(words(it)) }) return null
        if (!exact.all { textWords.containsPhrase(words(it)) }) return null
        val terms = fuzzy.flatMap { words(it) }
        if (terms.isEmpty()) return 1f
        var total = 0f
        for (term in terms) {
            val allowed = allowedEdits(term)
            val edits = textWords.minOfOrNull { levenshtein(term, it) } ?: return null
            if (edits > allowed) return null
            total += 1f - edits.toFloat() / term.length
        }
        return (total + exact.size) / (terms.size + exact.size)
    }

    // Claude: same scale as Elasticsearch's "AUTO" fuzziness. Numbers never fuzz: 2024 is not 2025.
    private fun allowedEdits(term: String): Int = when {
        !term.all { it.isLetter() } -> 0
        term.length <= 3 -> 0
        term.length <= 7 -> 1
        else -> 2
    }

    private fun words(text: String): List<String> =
        text.lowercase().split(nonWordCharacters).filter { it.isNotEmpty() }

    private fun List<String>.containsPhrase(phrase: List<String>): Boolean =
        phrase.isEmpty() || windowed(phrase.size).any { it == phrase }

    private fun levenshtein(lhs: CharSequence, rhs: CharSequence): Int {
        if (lhs == rhs) {
            return 0
        }
        if (lhs.isEmpty()) {
            return rhs.length
        }
        if (rhs.isEmpty()) {
            return lhs.length
        }

        val lhsLength = lhs.length + 1
        val rhsLength = rhs.length + 1

        var cost = Array(lhsLength) { it }
        var newCost = Array(lhsLength) { 0 }

        for (i in 1..<rhsLength) {
            newCost[0] = i

            for (j in 1..<lhsLength) {
                val match = if (lhs[j - 1] == rhs[i - 1]) 0 else 1

                val costReplace = cost[j - 1] + match
                val costInsert = cost[j] + 1
                val costDelete = newCost[j - 1] + 1

                newCost[j] = min(min(costInsert, costDelete), costReplace)
            }

            val swap = cost
            cost = newCost
            newCost = swap
        }

        return cost[lhsLength - 1]
    }
}

private val nonWordCharacters = Regex("[^\\p{L}\\p{N}]+")
