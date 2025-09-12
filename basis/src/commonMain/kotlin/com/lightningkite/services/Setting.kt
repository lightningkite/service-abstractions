package com.lightningkite.services

public interface Setting<T> {
    public operator fun invoke(name: String, context: SettingContext): T
}