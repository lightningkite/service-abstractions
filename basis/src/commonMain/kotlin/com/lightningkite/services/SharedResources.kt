package com.lightningkite.services

public class SharedResources(private val map: MutableMap<Key<*>, Any?> = HashMap()) {
    public interface Key<T> {
        public fun setup(context: SettingContext): T
    }
    public fun <T> get(key: Key<T>, context: SettingContext): T = map.getOrPut(key) { key.setup(context) } as T
}