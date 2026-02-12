package com.lightningkite.services.database

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.time.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.internal.GeneratedSerializer
import kotlin.uuid.Uuid

public interface SerializableProperty<A, B> {
    public val name: String
    public fun get(receiver: A): B
    public fun setCopy(receiver: A, value: B): A
    public val serializer: KSerializer<B>
    public val annotations: List<Annotation> get() = listOf()
    public val default: B? get() = null
    public val defaultCode: String? get() = null
    public val serializableAnnotations: List<SerializableAnnotation>
        get() = annotations.mapNotNull {
            SerializableAnnotation.Companion.parseOrNull(
                it
            )
        }

    @Suppress("UNCHECKED_CAST")
    @OptIn(InternalSerializationApi::class)
    public class Generated<A, B>(
        public val parent: GeneratedSerializer<A>,
        public val index: Int,
    ) : SerializableProperty<A, B> {
        override val name: String by lazy { parent.descriptor.getElementName(index) }
        override val serializer: KSerializer<B> by lazy { parent.childSerializers()[index] as KSerializer<B> }
        override fun setCopy(receiver: A, value: B): A = parent.set(receiver, index, serializer, value)
        override fun get(receiver: A): B = (parent as KSerializer<A>).get(receiver, index, serializer)
        override val annotations: List<Annotation> by lazy {
            parent.descriptor.getElementAnnotations(index) + serializer.descriptor.annotations
        }
        @OptIn(ExperimentalSerializationApi::class)
        override val serializableAnnotations: List<SerializableAnnotation> by lazy {
            annotations
                .mapNotNull { SerializableAnnotation.parseOrNull(it) }
        }

        // by Claude - Detect dynamic defaults (Uuid.random(), Clock.System.now(), etc.) by deserializing twice
        @OptIn(ExperimentalSerializationApi::class)
        private val defaultInfo: Pair<B?, String?> by lazy {
            // Skip for non-optional fields (no defaults)
            if (!parent.descriptor.isElementOptional(index)) {
                null to null
            } else {
                try {
                    // Deserialize twice to detect dynamic defaults
                    val instance1 = (parent as KSerializer<A>).default()
                    val instance2 = (parent as KSerializer<A>).default()

                    val value1 = get(instance1)
                    val value2 = get(instance2)

                    if (value1 != value2) {
                        // Dynamic default detected - determine type
                        when {
                            value1 is Uuid && value2 is Uuid -> null to "Uuid.random()"
                            value1 is Instant && value2 is Instant -> null to "Clock.System.now()"
                            value1 is LocalDate && value2 is LocalDate -> null to "Clock.System.nowLocal().date"
                            value1 is LocalTime && value2 is LocalTime -> null to "Clock.System.nowLocal().time"
                            else -> null to null // Unknown dynamic default
                        }
                    } else {
                        // Static default - return the value
                        value1 to null
                    }
                } catch (e: GenericPlaceholderException) {
                    // Field involves a generic type parameter that can't be deserialized
                    null to null
                }
            }
        }

        override val default: B? get() = defaultInfo.first
        override val defaultCode: String? get() = defaultInfo.second

        override fun toString(): String = parent.descriptor.serialName + "." + name
        override fun hashCode(): Int = parent.descriptor.serialName.hashCode() + index

        private fun GeneratedSerializer<*>.isEqual(other: GeneratedSerializer<*>): Boolean {
            val myParams = this.typeParametersSerializers()
            val otherParams = other.typeParametersSerializers()

            return this.descriptor.serialName == other.descriptor.serialName &&
                    myParams.size == otherParams.size &&
                    (myParams.isEmpty() ||
                            myParams.withIndex().all { (index, p1) ->
                                val p2 = otherParams[index]
                                if (p1 is GeneratedSerializer<*>) {
                                    if (p2 is GeneratedSerializer<*>) p1.isEqual(p2)
                                    else false
                                } else (p1.descriptor.serialName == p2.descriptor.serialName)
                            })
        }

        override fun equals(other: Any?): Boolean =
            other is Generated<*, *> && other.parent.isEqual(this.parent) && other.index == this.index
    }

    public class FromVirtualField(
        public val source: VirtualField,
        public val registry: SerializationRegistry,
        public val context: Map<String, KSerializer<*>>,
    ) : SerializableProperty<VirtualInstance, Any?> {
        override val name: String get() = source.name
        override val serializer: KSerializer<Any?> by lazy { source.type.serializer(registry, context) }
        override val annotations: List<Annotation> get() = listOf()
        override val serializableAnnotations: List<SerializableAnnotation> get() = source.annotations
        override fun get(receiver: VirtualInstance): Any? = receiver.values[source.index]
        override fun setCopy(receiver: VirtualInstance, value: Any?): VirtualInstance =
            VirtualInstance(receiver.type, receiver.values.toMutableList().also {
                it[source.index] = value
            })

        override fun toString(): String = source.name
        override fun hashCode(): Int = source.hashCode()
        override fun equals(other: Any?): Boolean = other is FromVirtualField && other.source == source
    }
}

@OptIn(ExperimentalSerializationApi::class)
public fun <T> KSerializer<T>.tryFindAnnotations(propertyName: String): List<Annotation> {
    val i = descriptor.getElementIndex(propertyName)
    if (i < 0) return listOf()
    else return descriptor.getElementAnnotations(i)
}

private val serNameToProperties = HashMap<String, Array<SerializableProperty<*, *>>>()

@OptIn(ExperimentalSerializationApi::class, InternalSerializationApi::class)
@Suppress("UNCHECKED_CAST")
public val <T> KSerializer<T>.serializableProperties: Array<SerializableProperty<T, *>>?
    get() {
        if (this is VirtualStruct.Concrete) return this.serializableProperties as Array<SerializableProperty<T, *>>
        return if (this !is GeneratedSerializer<*>) null
        else if (this.typeParametersSerializers().isEmpty()) serNameToProperties.getOrPut(this.descriptor.serialName) {
            (0..<descriptor.elementsCount).map<Int, SerializableProperty<T, *>> { index ->
                SerializableProperty.Generated<T, Any?>(
                    this as GeneratedSerializer<T>,
                    index
                )
            }.toTypedArray()
        } as Array<SerializableProperty<T, *>> else (0..<descriptor.elementsCount).map<Int, SerializableProperty<T, *>> { index ->
            SerializableProperty.Generated<T, Any?>(
                this as GeneratedSerializer<T>,
                index
            )
        }.toTypedArray()
    }
