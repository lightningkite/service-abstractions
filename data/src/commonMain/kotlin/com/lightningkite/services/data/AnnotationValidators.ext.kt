package com.lightningkite.services.data

import kotlin.reflect.KClass

context(builder: AnnotationValidators.Builder)
fun <A : Annotation> KClass<A>.validatesCollections(condition: A.(Collection<*>) -> String?) {
    with(builder) {
        validates(List::class, condition)
        validates(LinkedHashSet::class, condition)
        validates(HashSet::class, condition)
    }
}

context(builder: AnnotationValidators.Builder)
fun <A : Annotation> KClass<A>.validatesMaps(condition: A.(Map<*, *>) -> String?) {
    with(builder) {
        validates(LinkedHashMap::class, condition)    // also works for regular Map<*, *>
        validates(HashMap::class, condition)
    }
}