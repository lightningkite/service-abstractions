package com.lightningkite.services

public abstract class UrlSettingParser<T> {
    private val handlers = HashMap<String, (name: String, url: String, SettingContext) -> T>()
    public val options: Set<String> get() = handlers.keys
    public fun register(key: String, handler: (name: String, url: String, SettingContext) -> T) {
        if(key in handlers) throw Error("Key $key already registered for ${this::class}.  This could be an attempt from a hostile library to control a particular implementation.")
        handlers[key] = handler
    }
    public fun supports(schema: String): Boolean {
        return schema in options
    }

    public fun parse(name: String, url: String, module: SettingContext): T {
        val key = url.substringBefore("://")
        val h = handlers[key]
            ?: throw IllegalArgumentException("No handler $key for ${this::class} - available handlers are ${options.joinToString()}")
        return h(name, url, module)
    }
}

public interface HasUrl { public val url: String }
public abstract class HasUrlSettingParser<SETTING: HasUrl, T> {
    private val handlers = HashMap<String, (name: String, setting: SETTING, SettingContext) -> T>()
    public val options: Set<String> get() = handlers.keys
    public fun register(key: String, handler: (name: String, setting: SETTING, SettingContext) -> T) {
        if(key in handlers) throw Error("Key $key already registered for ${this::class}.  This could be an attempt from a hostile library to control a particular implementation.")
        handlers[key] = handler
    }

    public fun parse(name: String, setting: SETTING, module: SettingContext): T {
        val key = setting.url.substringBefore("://")
        val h = handlers[key]
            ?: throw IllegalArgumentException("No handler $key for ${this::class} - available handlers are ${options.joinToString()}")
        return h(name, setting, module)
    }
}
