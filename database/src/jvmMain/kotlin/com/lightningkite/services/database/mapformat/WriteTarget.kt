// by Claude
package com.lightningkite.services.database.mapformat

/**
 * A row in a child structure (for future child table support).
 */
public class ChildRow(
    /** Index for ordered collections (lists), null for unordered (sets) */
    public val index: Int?,
    /** Key fields for maps */
    public val key: Map<String, Any?>?,
    /** The actual data fields */
    public val values: Map<String, Any?>,
)

/**
 * Result of serialization.
 */
public class WriteResult(
    /** The main record fields */
    public val mainRecord: Map<String, Any?>,
    /** Child rows keyed by child name (for future child table support) */
    public val children: Map<String, List<ChildRow>>,
)

/**
 * Target for writing serialized data.
 * Implementations handle the actual storage mechanism.
 */
public interface WriteTarget {
    /**
     * Write a scalar value to the main record.
     */
    public fun writeField(path: String, value: Any?)

    /**
     * Write to a child structure (for future child table support).
     */
    public fun writeChild(childName: String, row: ChildRow)

    /**
     * Get the result after serialization completes.
     */
    public fun result(): WriteResult
}

/**
 * Source for reading serialized data.
 */
public interface ReadSource {
    /**
     * Read a scalar value from the main record.
     */
    public fun readField(path: String): Any?

    /**
     * Check if a field path has data (exact match or nested fields).
     */
    public fun hasField(path: String): Boolean

    /**
     * Check if there are nested fields under this path (but not the path itself).
     * Used for detecting embedded classes.
     */
    public fun hasNestedFields(path: String): Boolean

    /**
     * Read child rows (for future child table support).
     */
    public fun readChildren(childName: String): List<ChildRow>
}

/**
 * Simple implementation of WriteTarget using mutable maps.
 */
public class SimpleWriteTarget : WriteTarget {
    private val main = mutableMapOf<String, Any?>()
    private val children = mutableMapOf<String, MutableList<ChildRow>>()

    override fun writeField(path: String, value: Any?) {
        main[path] = value
    }

    override fun writeChild(childName: String, row: ChildRow) {
        children.getOrPut(childName) { mutableListOf() }.add(row)
    }

    override fun result(): WriteResult = WriteResult(main.toMap(), children.mapValues { it.value.toList() })
}

/**
 * Simple implementation of ReadSource.
 */
public class SimpleReadSource(
    private val main: Map<String, Any?>,
    private val children: Map<String, List<ChildRow>> = emptyMap(),
    private val fieldSeparator: String = "__",
) : ReadSource {
    override fun readField(path: String): Any? = main[path]

    override fun hasField(path: String): Boolean =
        main.containsKey(path) || main.keys.any { it.startsWith("$path$fieldSeparator") }

    // Check for nested fields with non-null values - by Claude
    // This correctly handles nullable collections where the schema has columns
    // but all values are null when the collection itself is null
    override fun hasNestedFields(path: String): Boolean =
        main.entries.any { (k, v) -> k.startsWith("$path$fieldSeparator") && v != null }

    override fun readChildren(childName: String): List<ChildRow> = children[childName] ?: emptyList()
}
