package com.lightningkite.services.database

import com.lightningkite.services.data.TextIndex
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.min

/**
 * The text a full-text search looks at on [model]: its [TextIndex] fields joined by spaces,
 * or its `toString()` if the type has no [TextIndex].
 */
public fun <T> textIndexContent(serializer: KSerializer<T>, model: T): String {
    val fieldPaths = serializer.descriptor.annotations.filterIsInstance<TextIndex>().firstOrNull()?.fields
        ?: return model.toString()
    val element = Json.encodeToJsonElement(serializer, model) as? JsonObject ?: return model.toString()
    return fieldPaths.joinToString(" ") { fieldPath ->
        var current: JsonElement? = element
        for (part in fieldPath.split(".")) {
            current = (current as? JsonObject)?.get(part)
        }
        when (val p = current) {
            is JsonPrimitive -> p.content
            null -> ""
            else -> p.toString()
        }
    }
}


public data class TextQuery(
    val exact: Set<String> = setOf(),
    val loose: Set<String> = setOf(),
    val reject: Set<String> = setOf(),
) {
    public companion object {
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
                loose = loose,
                reject = reject,
            )
        }
    }

    public fun fuzzyPresent(input: String, off: Int = 2): Boolean {
        val words = input.split(' ', '\n', '\t')
        return exact.all {
            input.contains(it, true)
        } && loose.all { l ->
            words.any { w ->
                if (l.termShouldUseFuzzySearch())
                    levenshtein(l.lowercase(), w.lowercase()) <= off
                else
                    input.contains(l, true)
            }
        } && reject.none {
            input.contains(it, true)
        }
    }

    /**
     * How relevant [input] is to this query, or null if it doesn't match at all.
     * Counts the words of [input] that match a loose term, plus each exact phrase present.
     */
    public fun relevance(input: String, off: Int = 2): Float? {
        if (!fuzzyPresent(input, off)) return null
        val words = input.split(' ', '\n', '\t')
        val looseHits = words.count { w ->
            loose.any { l ->
                if (l.termShouldUseFuzzySearch())
                    levenshtein(l.lowercase(), w.lowercase()) <= off
                else
                    w.contains(l, true)
            }
        }
        return (looseHits + exact.size).toFloat()
    }

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