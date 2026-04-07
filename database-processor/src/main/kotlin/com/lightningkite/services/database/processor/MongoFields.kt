package com.lightningkite.services.database.processor

import com.google.devtools.ksp.symbol.*
import kotlin.reflect.KClass

private val KSType.useCustomType: Boolean
    get() {
        val actualDeclaration = declaration.findClassDeclaration()
        return when (actualDeclaration?.qualifiedName?.asString()) {
            "kotlin.Byte",
            "kotlin.Short",
            "kotlin.Int",
            "kotlin.Long",
            "kotlin.Float",
            "kotlin.Double",
            "kotlin.String",
            "kotlin.collections.List",
            "kotlin.collections.Map",
            "kotlin.Boolean",
            "kotlin.Pair",
            "com.lightningkite.UUID",
            "kotlin.Uuid",
            "kotlinx.datetime.Instant",
            "org.litote.kmongo.Id" -> false

            else -> true
        }
    }

private val KSType.conditionType: String
    get() {
        val actualDeclaration = declaration.findClassDeclaration()
        return when (val name = actualDeclaration?.qualifiedName?.asString()) {
            "kotlin.Int" -> "IntBitwiseComparableCondition"
            "kotlin.Long" -> "LongBitwiseComparableCondition"
            "kotlin.String" -> "TextCondition"
            "kotlin.Byte",
            "kotlin.Short",
            "kotlin.Float",
            "kotlin.Double",
            "kotlin.Uuid",
            "com.lightningkite.UUID",
            "kotlinx.datetime.Instant",
            "kotlin.Char" -> "ComparableCondition" + "<${this.makeNotNullable().toKotlin()}>"

            "kotlin.collections.List" -> "ArrayCondition" + "<${
                this.arguments[0].run {
                    "${
                        type!!.resolve().toKotlin(annotations)
                    }, ${type!!.resolve().conditionType}"
                }
            }>"

            "kotlin.collections.Map" -> "MapCondition" + "<${
                this.arguments[1].run {
                    "${
                        type!!.resolve().toKotlin(annotations)
                    }, ${type!!.resolve().conditionType}"
                }
            }>"

            "kotlin.Boolean", "org.litote.kmongo.Id", "kotlin.Pair" -> "EquatableCondition" + "<${
                this.makeNotNullable().toKotlin()
            }>"

            else -> {
                if ((declaration as? KSClassDeclaration)?.classKind == ClassKind.ENUM_CLASS) "EquatableCondition<$name>" else "${name}Condition"
            }
        }.let {
            if (isMarkedNullable) "NullableCondition<${this.toKotlin()}, ${
                this.makeNotNullable().toKotlin()
            }, $it>"
            else it
        }
    }

private val KSType.modificationType: String
    get() {
        val actualDeclaration = declaration.findClassDeclaration()
        return when (val name = actualDeclaration?.qualifiedName?.asString()) {
            "kotlin.Byte",
            "kotlin.Short",
            "kotlin.Int",
            "kotlin.Long",
            "kotlin.Float",
            "kotlin.Double" -> "NumberModification" + "<${this.makeNotNullable().toKotlin(annotations)}>"
            "java.util.UUID",
            "com.lightningkite.UUID",
            "kotlin.Uuid",
            "kotlinx.datetime.Instant",
            "kotlin.String", "kotlin.Char" -> "ComparableModification" + "<${
                this.makeNotNullable().toKotlin(annotations)
            }>"

            "kotlin.collections.List" -> "ArrayModification" + "<${
                this.arguments[0].run {
                    "${
                        type!!.resolve().toKotlin(annotations)
                    }, ${type!!.resolve().conditionType}, ${type!!.resolve().modificationType}"
                }
            }>"

            "kotlin.collections.Map" -> "MapModification" + "<${
                this.arguments[1].run {
                    "${
                        type!!.resolve().toKotlin(annotations)
                    }, ${type!!.resolve().modificationType}"
                }
            }>"

            "kotlin.Boolean", "org.litote.kmongo.Id", "kotlin.Pair" -> "EquatableModification" + "<${
                this.makeNotNullable().toKotlin()
            }>"

            else -> {
                if ((declaration as? KSClassDeclaration)?.classKind == ClassKind.ENUM_CLASS) "EquatableModification<$name>" else "${name}Modification"
            }
        }.let {
            if (isMarkedNullable) "NullableModification<${this.toKotlin()}, ${
                this.makeNotNullable().toKotlin()
            }, $it>"
            else it
        }
    }

private fun ResolvedAnnotation.writeSerialzable(): String {
    return "SerializableAnnotation(fqn = \"${this.type.qualifiedName?.asString()}\", values = mapOf(${this.arguments.entries.joinToString { "\"${it.key}\" to \"${it.value.jsonRender()}\"" }}))"
}

private fun Any?.jsonRender(): String {
    return when (this) {
        is KClass<*> -> "\"" + (this.qualifiedName) + "\""
        is KSType -> "\"" + (this.declaration?.qualifiedName?.asString() ?: "") + "\""
        is KSTypeReference -> "\"" + (this.tryResolve()?.declaration?.qualifiedName?.asString() ?: "") + "\""
        is KSClassDeclaration -> "\"" + (this.qualifiedName?.asString() ?: "") + "\""
        is Array<*> -> joinToString(", ", "[", "]") { it.jsonRender() }
        is String -> "\"$this\""
        null -> "null"
//        else -> "$this (${this::class})"
        else -> toString()
    }.replace("\"", "\\\"")
}