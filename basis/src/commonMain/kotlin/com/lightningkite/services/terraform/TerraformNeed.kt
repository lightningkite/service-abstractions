package com.lightningkite.services.terraform

public interface TerraformNeed<T> {
    public val name: String

    public companion object {
        public operator fun <T> invoke(name: String): TerraformNeed<T> = object : TerraformNeed<T> {
            override val name: String = name
        }
    }
}