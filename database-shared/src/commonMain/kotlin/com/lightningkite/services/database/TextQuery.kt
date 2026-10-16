package com.lightningkite.services.database

import kotlin.math.ln
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
     * Returns the relevance of each of [documents] to this query, or `null` for each one that doesn't match.
     *
     * Each document is the list of its field values, in the same order for every document. A match needs every word
     * and phrase present in some field and no exclusion present in any field. A query with no words or phrases
     * matches nothing.
     *
     * Scores follow Lucene's BM25, as MongoDB Atlas Search does: each field is scored separately and the scores are
     * added. Words rarer across [documents] count for more, repeats count for less each time, and a field shorter
     * than that field's average scores higher. A word matched with typos counts for less the more edits it took.
     */
    public fun scores(documents: List<List<String>>): List<Float?> {
        val terms = fuzzy.flatMap { words(it) }.distinct()
        val phrases = exact.map { words(it) }.filter { it.isNotEmpty() }
        val rejects = reject.map { words(it) }.filter { it.isNotEmpty() }
        if (terms.isEmpty() && phrases.isEmpty()) return documents.map { null }

        val tokens = documents.map { fields -> fields.map { words(it) } }
        val totals = DoubleArray(documents.size)
        val matched = Array(documents.size) { BooleanArray(terms.size + phrases.size) }
        for (field in 0 until (documents.firstOrNull()?.size ?: 0)) {
            val fieldTokens = tokens.map { it[field] }
            val docCount = fieldTokens.count { it.isNotEmpty() }
            if (docCount == 0) continue
            val averageLength = fieldTokens.sumOf { it.size }.toDouble() / docCount
            fun idf(docFreq: Int) = ln(1 + (docCount - docFreq + 0.5) / (docFreq + 0.5))

            // Claude: one (frequency in each document, idf) pair per clause; a phrase's idf is its words' summed, as in Lucene.
            val clauses = terms.map { term ->
                val frequencies = fieldTokens.map { words ->
                    words.sumOf { word ->
                        val edits = levenshtein(term, word)
                        if (edits <= allowedEdits(term)) 1.0 - edits.toDouble() / term.length else 0.0
                    }
                }
                frequencies to idf(frequencies.count { it > 0 })
            } + phrases.map { phrase ->
                val frequencies = fieldTokens.map { words -> words.windowed(phrase.size).count { it == phrase }.toDouble() }
                frequencies to phrase.sumOf { word -> idf(fieldTokens.count { word in it }) }
            }
            clauses.forEachIndexed { clause, (frequencies, idf) ->
                frequencies.forEachIndexed { doc, frequency ->
                    if (frequency > 0) {
                        matched[doc][clause] = true
                        val lengthNorm = 1 - BM25_B + BM25_B * fieldTokens[doc].size / averageLength
                        totals[doc] += idf * frequency * (BM25_K1 + 1) / (frequency + BM25_K1 * lengthNorm)
                    }
                }
            }
        }
        return documents.indices.map { doc ->
            val excluded = rejects.any { phrase -> tokens[doc].any { words -> words.windowed(phrase.size).any { it == phrase } } }
            if (excluded || !matched[doc].all { it }) null else totals[doc].toFloat()
        }
    }

    /**
     * Returns how many typos [term] may have and still match: none up to three letters, one up to seven, two beyond.
     *
     * Terms containing anything but letters must match exactly, so `2024` never matches `2025`.
     */
    public fun allowedEdits(term: String): Int = when {
        !term.all { it.isLetter() } -> 0
        term.length <= 3 -> 0
        term.length <= 7 -> 1
        else -> 2
    }

    private fun words(text: String): List<String> =
        text.lowercase().split(nonWordCharacters).filter { it.isNotEmpty() }

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

// Claude: Lucene's defaults, which Atlas Search uses.
private const val BM25_K1 = 1.2
private const val BM25_B = 0.75

private val nonWordCharacters = Regex("[^\\p{L}\\p{N}]+")
