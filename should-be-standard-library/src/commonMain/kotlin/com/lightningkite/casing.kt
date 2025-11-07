package com.lightningkite

/**
 * String case conversion utilities.
 *
 * Provides functions to convert between different naming conventions:
 * - camelCase (myVariableName)
 * - PascalCase (MyClassName)
 * - snake_case (my_variable_name)
 * - SCREAMING_SNAKE_CASE (MY_CONSTANT_NAME)
 * - kabob-case (my-css-class)
 * - Title Case (My Document Title)
 * - space case (my sentence here)
 *
 * These functions intelligently detect word boundaries from various formats
 * and convert between them.
 */

private val casingSeparatorRegex: Regex = Regex("([-_\\s]+([A-Z]*[a-z0-9]+))|([-_\\s]*[A-Z]+)")

private inline fun String.caseAlter(crossinline update: (after: String) -> String): String =
    casingSeparatorRegex.replace(this) {
        if (it.range.first == 0) it.value
        else update(it.value.filter { !(it == '-' || it == '_' || it.isWhitespace()) })
    }

private fun String.capitalize(): String = replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
private fun String.decapitalize(): String = replaceFirstChar { if (it.isUpperCase()) it.lowercase() else it.toString() }

/**
 * Converts to Title Case (first letter of each word capitalized, separated by spaces).
 *
 * Example: "myVariableName" → "My Variable Name"
 */
public fun String.titleCase(): String = caseAlter { " " + it.capitalize() }.capitalize()

/**
 * Converts to space case (lowercase words separated by spaces).
 *
 * Example: "MyClassName" → "my class name"
 */
public fun String.spaceCase(): String = caseAlter { " $it" }.decapitalize()

/**
 * Converts to kabob-case (lowercase words separated by hyphens).
 *
 * Also known as kebab-case or dash-case. Commonly used in URLs and CSS class names.
 *
 * Example: "myVariableName" → "my-variable-name"
 */
public fun String.kabobCase(): String = caseAlter { "-$it" }.lowercase()

/**
 * Converts to snake_case (lowercase words separated by underscores).
 *
 * Commonly used in Python, Ruby, and database column names.
 *
 * Example: "MyClassName" → "my_class_name"
 */
public fun String.snakeCase(): String = caseAlter { "_$it" }.lowercase()

/**
 * Converts to SCREAMING_SNAKE_CASE (uppercase words separated by underscores).
 *
 * Commonly used for constants and environment variables.
 *
 * Example: "myConstantName" → "MY_CONSTANT_NAME"
 */
public fun String.screamingSnakeCase(): String = caseAlter { "_$it" }.uppercase()

/**
 * Converts to camelCase (first word lowercase, subsequent words capitalized, no separators).
 *
 * Commonly used for variable and function names in Java, JavaScript, and Kotlin.
 *
 * Example: "MyClassName" → "myClassName"
 */
public fun String.camelCase(): String = caseAlter { it.capitalize() }.decapitalize()

/**
 * Converts to PascalCase (all words capitalized, no separators).
 *
 * Also known as UpperCamelCase. Commonly used for class and type names.
 *
 * Example: "my_class_name" → "MyClassName"
 */
public fun String.pascalCase(): String = caseAlter { it.capitalize() }.capitalize()
