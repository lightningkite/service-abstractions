package com.lightningkite

public class SealableList<E>(private val wraps: MutableList<E> = ArrayList()): MutableList<E> by wraps {
    private var sealed: Throwable? = null

    private fun assertNotSealed() {
        sealed?.let {
            throw IllegalStateException("Data has been sealed and cannot be modified. Sealed here: ${it.printStackTrace()}")
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

    public fun seal() {
        if (sealed == null) sealed = Exception()
    }

    override fun toString(): String = wraps.toString()
}

public fun <T> buildSealedList(setup: MutableList<T>.() -> Unit): List<T> =
    SealableList<T>().apply { setup(); seal() }

public fun <T> Iterable<T>.toSealedList(): List<T> = SealableList(toMutableList()).apply { seal() }