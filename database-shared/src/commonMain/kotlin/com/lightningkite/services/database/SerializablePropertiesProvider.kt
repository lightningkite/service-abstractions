package com.lightningkite.services.database

/**
 * Interface implemented by serializers that provide SerializableProperty instances with default values.
 *
 * This interface is automatically implemented by the compiler plugin for classes annotated with
 * @GenerateDataClassPaths or @DatabaseModel. It allows the serializer to provide pre-generated
 * SerializableProperty instances (with default values embedded) instead of creating fresh instances
 * without defaults.
 *
 * The compiler plugin injects an implementation that returns the KSP-generated
 * {ClassName}__serializableProperties array.
 */
public interface SerializablePropertiesProvider<T> {
    /**
     * Returns an array of SerializableProperty instances with default values embedded.
     * This is called by the serializableProperties extension when available.
     */
    public fun getSerializablePropertiesWithDefaults(): Array<SerializableProperty<T, *>>
}
