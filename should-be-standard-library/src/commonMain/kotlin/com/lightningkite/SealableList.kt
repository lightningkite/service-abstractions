package com.lightningkite

/**
 * A [MutableList] that can be sealed to prevent any further modifications.
 *
 * Once [seal] is called, all mutation operations throw [IllegalStateException].
 * This is useful for creating immutable lists after an initial setup phase,
 * or for debugging to catch unintended modifications.
 *
 * ## Usage
 *
 * ```kotlin
 * val list = SealableList<String>()
 * list.add("item1")
 * list.add("item2")
 * list.seal()
 *
 * list.add("item3")  // Throws IllegalStateException
 * ```
 *
 * Use [buildSealedList] for a more convenient builder pattern:
 * ```kotlin
 * val list = buildSealedList {
 *     add("item1")
 *     add("item2")
 * }  // Automatically sealed
 * ```
 *
 * @param E Element type
 * @param wraps Underlying mutable list implementation (default: ArrayList)
 */
public class SealableList<E>(private val wraps: MutableList<E> = ArrayList()): MutableList<E> by wraps {
    private var sealed: Throwable? = null

    private fun assertNotSealed() {
        sealed?.let {
            throw IllegalStateException("Data has been sealed and cannot be modified. Sealed here: ${it.stackTraceToString()}")
        }
    }

    private inline fun <T> checkSealed(crossinline action: MutableList<E>.() -> T): T {
        assertNotSealed()
        return wraps.action()
    }

    override fun add(element: E): Boolean = checkSealed { add(element) }
    override fun remove(element: E): Boolean = checkSealed { remove(element) }
    override fun addAll(elements: Collection<E>): Boolean = checkSealed { addAll(elements) }
    override fun addAll(index: Int, elements: Collection<E>): Boolean = checkSealed { addAll(index, elements) }
    override fun removeAll(elements: Collection<E>): Boolean = checkSealed { removeAll(elements) }
    override fun retainAll(elements: Collection<E>): Boolean = checkSealed { retainAll(elements) }
    override fun clear() = checkSealed { clear() }
    override fun set(index: Int, element: E): E = checkSealed { set(index, element) }
    override fun add(index: Int, element: E) = checkSealed { add(index, element) }
    override fun removeAt(index: Int): E = checkSealed { removeAt(index) }

    override fun iterator(): MutableIterator<E> = Iterator(wraps.listIterator())
    override fun listIterator(): MutableListIterator<E> = Iterator(wraps.listIterator())
    override fun listIterator(index: Int): MutableListIterator<E> = Iterator(wraps.listIterator(index))

    override fun subList(fromIndex: Int, toIndex: Int): MutableList<E> = SealableList(wraps.subList(fromIndex, toIndex)).also { it.sealed = sealed }

    private inner class Iterator(private val wraps: MutableListIterator<E>): MutableListIterator<E> by wraps {
        override fun remove() {
            assertNotSealed()
            wraps.remove()
        }

        override fun set(element: E) {
            assertNotSealed()
            wraps.set(element)
        }

        override fun add(element: E) {
            assertNotSealed()
            wraps.add(element)
        }
    }

    /**
     * Seals this list, preventing any future modifications.
     *
     * After calling this method, all mutation operations will throw [IllegalStateException].
     * This method is idempotent - calling it multiple times has no additional effect.
     *
     * The current stack trace is captured for debugging purposes and will be included
     * in the exception message if modification is attempted.
     */
    public fun seal() {
        if (sealed == null) sealed = Exception()
    }

    override fun toString(): String = wraps.toString()
}

/**
 * Creates a sealed list using a builder pattern.
 *
 * The provided [setup] block can mutate the list, but once the block completes,
 * the list is automatically sealed and returned as a read-only [List].
 *
 * @param T Element type
 * @param setup Builder block for populating the list
 * @return Sealed list that cannot be modified
 */
public fun <T> buildSealedList(setup: MutableList<T>.() -> Unit): List<T> =
    SealableList<T>().apply { setup(); seal() }

/**
 * Converts this [Iterable] to a sealed list.
 *
 * Creates a [SealableList] from the elements of this iterable, then immediately seals it.
 * The returned list cannot be modified.
 *
 * @param T Element type
 * @return Sealed list containing all elements from this iterable
 */
public fun <T> Iterable<T>.toSealedList(): List<T> = SealableList(toMutableList()).apply { seal() }