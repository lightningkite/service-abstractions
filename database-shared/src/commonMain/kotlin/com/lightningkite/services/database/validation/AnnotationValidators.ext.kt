package com.lightningkite.services.database.validation

import com.lightningkite.services.data.*
import com.lightningkite.services.database.validation.AnnotationValidators.Builder

public inline fun <reified A : Annotation> Builder.validateStrings(noinline condition: A.(String) -> String?) {
    validate<A, String>(condition)
    validate<A, TrimmedString> { condition(it.raw) }
    validate<A, CaselessString> { condition(it.raw) }
    validate<A, TrimmedCaselessString> { condition(it.raw) }
    validate<A, EmailAddress> { condition(it.raw) }
    validate<A, PhoneNumber> { condition(it.raw) }
}

public inline fun <reified A : Annotation> Builder.validateCollections(noinline condition: A.(Collection<*>) -> String?) {
    validate<A, List<*>>(condition)
    validate<A, Set<*>>(condition)  // LinkedHashSet serializer
    validate<A, HashSet<*>>(condition)  // HashSet serializer
}