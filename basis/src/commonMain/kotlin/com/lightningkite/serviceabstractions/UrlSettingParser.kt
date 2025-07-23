package com.lightningkite.serviceabstractions

public abstract class UrlSettingParser<T> {
    private val handlers = HashMap<String, (String, SettingContext) -> T>()
    public val options: Set<String> get() = handlers.keys
    public fun register(key: String, handler: (String, SettingContext) -> T) {
        if(key in handlers) throw Error("Key $key already registered for ${this::class.qualifiedName}.  This could be an attempt from a hostile library to control a particular implementation.")
        handlers[key] = handler
    }

    public fun parse(url: String, module: SettingContext): T {
        val key = url.substringBefore("://")
        val h = handlers[key]
            ?: throw IllegalArgumentException("No handler $key for ${this::class.qualifiedName} - available handlers are ${options.joinToString()}")
        return h(url, module)
    }
}
