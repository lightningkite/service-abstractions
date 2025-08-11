package com.lightningkite.services

public abstract class UrlSettingParser<T> {
    private val handlers = HashMap<String, (name: String, url: String, SettingContext) -> T>()
    public val options: Set<String> get() = handlers.keys
    public fun register(key: String, handler: (name: String, url: String, SettingContext) -> T) {
        if(key in handlers) throw Error("Key $key already registered for ${this::class}.  This could be an attempt from a hostile library to control a particular implementation.")
        handlers[key] = handler
    }

    public fun parse(name: String, url: String, module: SettingContext): T {
        val key = url.substringBefore("://")
        val h = handlers[key]
            ?: throw IllegalArgumentException("No handler $key for ${this::class} - available handlers are ${options.joinToString()}")
        return h(name, url, module)
    }
}
