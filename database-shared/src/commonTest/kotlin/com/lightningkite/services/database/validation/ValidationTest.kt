@file:OptIn(ExperimentalSerializationApi::class)

package com.lightningkite.services.database.validation

import com.lightningkite.services.data.*
import com.lightningkite.services.database.Modification
import com.lightningkite.services.database.modification
import com.lightningkite.services.database.notNull
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlin.reflect.KClass
import kotlin.reflect.typeOf
import kotlin.test.*

@Serializable
@GenerateDataClassPaths
data class Sample(
    @MaxLength(5) val x: String = "asdf",
    @IntegerRange(0, 100) val y: Int = 4,
    @IntegerRange(0, 100) val yNullable: Int? = 5,
    @MaxLength(5) @MaxSize(5) val z: List<String> = listOf(),
)

@Serializable
enum class TestEnum {
    First, Second, Third
}

@Serializable
@GenerateDataClassPaths
data class BadSample(
    @MaxSize(1) val a: String = "fdsa",
    @MaxLength(5) val x: String = "asdf",
)

@Serializable
@GenerateDataClassPaths
data class ArgSample<T>(
    val value: T,
    @MaxLength(5) val str: String = "asdf",
)

@Serializable
@GenerateDataClassPaths
data class Box<T>(val value: T)

@Serializable
@GenerateDataClassPaths
data class CustomSample(
    @StringListContainsAll("hello", "world") val list: List<String> = listOf("hello", "world"),
    @StringListContainsAll("unapplied") val intList: List<Int> = listOf(1, 2, 3),
    @EnumNotEqualTo(TestEnum.First) val enum: TestEnum = TestEnum.Second,
    @EnumNotEqualTo(TestEnum.First) val enumList: List<TestEnum> = emptyList(),
    @IntegerRange(0, 100) val nullableInt: Int? = 3,
    @SampleXNotContains("xyz") val sample: Sample? = null,
)

@Serializable
@GenerateDataClassPaths
data class TestWarnings(
    @AlwaysPrintsMismatchedTypesWarning val warn: Int = 5,
    @IntegerRange(0, 100) val shouldNotWarn: Int? = null,
    @IntegerRange(0, 100) val shouldNotWarnCascading: Box<Box<Box<Box<Int>>>> = Box(Box(Box(Box(50)))),
    @IntegerRange(0, 100) val shouldWarnCascading: Box<Box<Box<Box<String>>>> = Box(Box(Box(Box("50")))),
)

@SerialInfo
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class StringListContainsAll(vararg val values: String)

@SerialInfo
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class EnumNotEqualTo(val value: TestEnum)

@SerialInfo
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class SampleXNotContains(val value: String)

@SerialInfo
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class AlwaysPrintsMismatchedTypesWarning

@Serializable
class NeverUsed

@SerialInfo
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class MimeTypeCheck(val type: String)

@Serializable
data class File(val type: String)

@Serializable
@GenerateDataClassPaths
data class FileWithMetadata(val file: File, val metadata: List<Int> = emptyList())

@Serializable
@GenerateDataClassPaths
data class SampleWithFile(
    @MimeTypeCheck("text") val file: File = File("text"),
    @MimeTypeCheck("text") val fileWithMetadata: FileWithMetadata? = FileWithMetadata(File("text")),
)

class ValidationTest {
    var validators = AnnotationValidators(Json.serializersModule) + AnnotationValidators {
        validate<AlwaysPrintsMismatchedTypesWarning, NeverUsed> {
            null
        }
    }

    inline fun <reified T> assertPasses(item: T) {
        val issues = validators.validateSkipSuspending(validators.serializersModule.serializer<T>(), item)
        if (issues.isNotEmpty()) fail("Validation did not pass: $issues")
    }

    inline fun <reified T> assertFails(item: T, failures: Int = 1) {
        val issues = validators.validateSkipSuspending(validators.serializersModule.serializer<T>(), item)
        if (issues.size != failures) fail("Validation did not fail as expected. Expected $failures, got ${issues.size}. Found issues: $issues")
        else println("Found issues: $issues")
    }

    @Test
    fun testCascadingAnnotationsThatApplyToMultipleTypes() {
        validators += AnnotationValidators {
            validate<MimeTypeCheck, File> {
                if (it.type != type) "File MimeType does not match $type"
                else null
            }
            validate<MimeTypeCheck, FileWithMetadata> {
                if (it.file.type != type) "FileWithMetadata MimeType does not match $type"
                else null
            }
        }

        assertPasses(SampleWithFile())
        assertFails(SampleWithFile(file = File("nottext")))
        assertFails(SampleWithFile(fileWithMetadata = FileWithMetadata(File("nottext"))), failures = 2)

        assertPasses(modification<SampleWithFile> { it.file assign File("text") })
        assertFails(modification<SampleWithFile> { it.file assign File("nottext") })

        assertPasses(modification<SampleWithFile> { it.fileWithMetadata.notNull.file assign File("text") })
        assertFails(modification<SampleWithFile> { it.fileWithMetadata.notNull.file assign File("nottext") })
        assertFails(
            Modification.Chain<SampleWithFile>(
                listOf(
                    Modification.OnField(
                        SampleWithFile_fileWithMetadata,
                        Modification.IfNotNull(
                            Modification.Chain(
                                listOf(
                                    Modification.OnField(
                                        FileWithMetadata_file,
                                        Modification.Assign(File("nottext"))
                                    )
                                )
                            )
                        )
                    )
                )
            )
        )
    }

    @Test
    fun testTypeNameNormalization() {
        fun test(k1: KClass<*>, k2: KClass<*>) {
            println("$k1 -> ${k1.normalizedTypeName()}")
            println("$k2 -> ${k2.normalizedTypeName()}")
            assertEquals(k1.normalizedTypeName(), k2.normalizedTypeName())
        }
        test(MaxLength::class, MaxLength(1)::class)
        test(MaxLength::class, MaxLength(1)::class)
        test(FloatRange::class, FloatRange(0.0, 1.0)::class)
        test(FloatRange::class, FloatRange(0.0, 1.0)::class)
    }

    @Test
    fun test() {
        assertPasses(Sample("ASDFA"))
        assertFails(Sample("ASDFAB"))
        assertPasses(Sample(y = 0))
        assertFails(Sample(y = -1))
        assertFails(Sample(y = 101))

        // annotations cascade through list elements
        assertFails(Sample(z = listOf("123456")))

        assertFails(Sample(x = "123456", y = 101, z = List(10) { "a" }), failures = 3)

        // annotations cascade through nullability
        assertPasses(Sample(yNullable = null))
        assertFails(Sample(yNullable = 101))
    }

    @Test
    fun testWarnings() {
        assertPasses(TestWarnings())
    }

    @Test
    fun testStrings() {
        println(AnnotationValidators.Standard)
        println(AnnotationValidators())
        println(AnnotationValidators(SerializersModule { }))
        println(AnnotationValidators(SerializersModule { }).prettyPrint(qualified = true))
        println(AnnotationValidators(SerializersModule { }).prettyPrint(qualified = false))
    }

    @Test
    fun testModificationValidation() {
        assertPasses(Modification.Assign(Sample("ASDFA")))
        assertFails(Modification.Assign(Sample("ASDFAB")))
        assertPasses(modification<Sample> { it.x assign "ASDFA" })
        assertFails(modification<Sample> { it.x assign "ASDFAB" })
    }

    @Test
    fun testNullableValidation() {
        validators += AnnotationValidators {
            validate<SampleXNotContains, Sample> {
                if (it.x.contains(value, ignoreCase = true)) "x Cannot contain '$value'"
                else null
            }
        }

        assertPasses<Sample?>(Sample("ASDFA"))
        assertFails<Sample?>(Sample("ASDFAB"))

        assertPasses(CustomSample(sample = Sample()))
        assertFails(CustomSample(sample = Sample("xyz")))

        assertPasses(modification<CustomSample> { it.sample assign Sample() })
        assertFails(modification<CustomSample> { it.sample assign Sample("xyz") })
    }

    @Test
    fun testCustomValidators() {
        validators += AnnotationValidators {
            validate<StringListContainsAll, List<String>> {
                if (!it.containsAll(values.toList())) "Does not contain all values: $it !in ${values.contentToString()}"
                else null
            }
            validate<EnumNotEqualTo, TestEnum> {
                if (it == value) "Cannot be $value"
                else null
            }
            validate<IntegerRange, Int?> {  // specific null-override
                when (it) {
                    null -> "Cannot be null"        // this is incredibly stupid, never do this in reality
                    !in min..max -> "Out of range"
                    else -> null
                }
            }
        }

        println(validators)

        assertPasses(CustomSample())
        assertFails(CustomSample(list = listOf("hello")))

        assertFails(CustomSample(enum = TestEnum.First))
        assertPasses(CustomSample(enumList = listOf(TestEnum.Second)))
        assertFails(CustomSample(enumList = listOf(TestEnum.First)))

        assertPasses(CustomSample(nullableInt = 50))
        assertFails(CustomSample(nullableInt = null))
        assertFails(CustomSample(nullableInt = 101))
    }

    inline fun <reified T> match(descriptions: List<SerialKType>) {
        val type = typeOf<T>()
        val description = SerialKType(type, validators.serializersModule)
        val serializer = validators.serializersModule.serializer(type)

        val typeMatches = descriptions.filter { it.matches(description) }
        val serMatches = descriptions.filter { it.matches(serializer) }

        assertEquals(
            typeMatches, serMatches,
            """
                Matches are not equal for $description
                Type Matches:       ${typeMatches.joinToString()}
                Serializer Matches: ${serMatches.joinToString()}
            """.trimIndent()
        )

        println("\nMatches for $description")
        println(
            serMatches
                .map { it to it.generality() }
                .sortedBy { it.second }
                .joinToString("\n") { "- ${it.first} (${it.second})" }
        )
    }

    @Test
    fun testGeneralityAlgorithm() {
        val descriptions = listOf(
            serialKTypeOf<Int>(),
            serialKTypeOf<List<Map<*, *>>>(),
            serialKTypeOf<List<Map<List<Int>, *>>>(),
            serialKTypeOf<List<Map<*, List<Int>>>>(),
            serialKTypeOf<List<*>>(),
            serialKTypeOf<Map<*, *>>(),
            serialKTypeOf<Map<*, Map<*, *>>>(),
            serialKTypeOf<Map<*, Map<*, List<*>>>>(),
            serialKTypeOf<Map<*, Map<Int, List<*>>>>(),
            serialKTypeOf<Map<*, Map<Pair<Int, *>, List<*>>>>(),
            serialKTypeOf<Map<*, Map<Pair<*, *>, List<*>>>>(),
            serialKTypeOf<Map<*, Map<Pair<*, ArgSample<*>>, List<*>>>>(),
            serialKTypeOf<Map<*, Map<Pair<*, ArgSample<Int>>, *>>>(),
            serialKTypeOf<List<Triple<*, Pair<Int, Int>, *>>>(),
            serialKTypeOf<List<ArgSample<*>?>>(),
            serialKTypeOf<List<ArgSample<*>?>>(),
        )

        println("\nSorted:")
        println(
            descriptions
                .map { it to it.generality() }
                .sortedBy { it.second }
                .joinToString("\n") { "- ${it.first} (${it.second})" }
        )

        match<Map<Int, Map<Int, List<String>>>>(descriptions)
        match<Map<String, Map<Pair<Int, Int>, String>>>(descriptions)
        match<Map<Int, Map<Pair<ArgSample<Int>, String>, List<String>>>>(descriptions)
        match<Map<Int, Map<Pair<ArgSample<ArgSample<Int>>, String>, List<String>>>>(descriptions)
        match<Map<Int, Map<Pair<String, ArgSample<Int>>, Map<Int, Int>>>>(descriptions)
        match<List<Triple<Int, Pair<Int, Int>, Int>>>(descriptions)
        match<List<ArgSample<Int>?>>(descriptions)
    }

    @Test
    fun testMatching() {
        assertTrue { serialKTypeOf<String>().matches(serialKTypeOf<String>()) }
    }


    // =============================================================================================
    // AI-Generated Tests
    // =============================================================================================

    /** The paths that [item] produces invalid-annotation-type warnings for. */
    internal inline fun <reified T> warningPaths(item: T): Set<String> =
        validators.validateSkipSuspendingCollectingWarnings(validators.serializersModule.serializer<T>(), item)
            .warnings
            .filterIsInstance<EncodingWarning.InvalidAnnotationType>()
            .mapTo(mutableSetOf()) { it.path }

    /** The type each warning was reported against, keyed by path. */
    internal inline fun <reified T> warningTypes(item: T): Map<String, String> =
        validators.validateSkipSuspendingCollectingWarnings(validators.serializersModule.serializer<T>(), item)
            .warnings
            .filterIsInstance<EncodingWarning.InvalidAnnotationType>()
            .associate { it.path to it.appliedTo.toString() }

    // --- Which annotations are reported as misapplied ---

    @Test
    fun testOnlyGenuinelyMisappliedAnnotationsWarn() {
        assertEquals(
            setOf("warn", "shouldWarnCascading"),
            warningPaths(TestWarnings()),
            "Expected warnings only for the annotation with no matching validator and the cascade that " +
                    "bottoms out in the wrong type."
        )
    }

    @Test
    fun testNullValueDoesNotWarn() {
        // A validator registered for `Int` legitimately applies to an `Int?` field - it simply has
        // nothing to check when the value is null, which is not a mistaken application.
        assertFalse("shouldNotWarn" in warningPaths(TestWarnings(shouldNotWarn = null)))
        assertFalse("shouldNotWarn" in warningPaths(TestWarnings(shouldNotWarn = 50)))
    }

    @Test
    fun testEmptyCollectionDoesNotWarn() {
        // A cascaded annotation with no elements to cascade to had nothing to check - exactly like a
        // null value. That isn't a mistaken application and shouldn't be reported as one.
        assertEquals(
            emptySet(),
            warningPaths(EmptyCollectionSample()),
            "No field here misapplies @MaxLength - each one either cascades to a String element or " +
                    "has no elements to cascade to at all."
        )
    }

    @Test
    fun testNestedCollectionsDoNotWarn() {
        // The type-argument scan has to recurse: the String @MaxLength applies to is two levels deep
        // in List<List<String>>, and one level deep as a Map key.
        assertEquals(emptySet(), warningPaths(NestedCollectionSample()))

        // Populating them must not change the verdict either.
        assertEquals(
            emptySet(),
            warningPaths(
                NestedCollectionSample(
                    nested = listOf(listOf("ok")),
                    nestedEmptyInner = listOf(emptyList()),
                    map = mapOf("ok" to 1)
                )
            )
        )
    }

    @Test
    fun testNestedCollectionsStillValidate() {
        // Suppressing the warning must not suppress the validation itself.
        assertFails(NestedCollectionSample(nested = listOf(listOf("toolong"))))
        assertFails(NestedCollectionSample(map = mapOf("toolong" to 1)))
    }

    @Test
    fun testOneBadAnnotationAmongGoodOnesWarnsAlone() {
        // @MaxSize applies to the list itself, @AlwaysPrints... applies to nothing here.
        val warnings = warningPaths(MixedAnnotationSample())
        assertEquals(setOf("items"), warnings)
        assertEquals(1, validators.validateSkipSuspendingCollectingWarnings(
            validators.serializersModule.serializer<MixedAnnotationSample>(), MixedAnnotationSample()
        ).warnings.size, "The valid @MaxSize must not also warn")
    }

    @Test
    fun testWarningPathIncludesEnclosingFields() {
        assertEquals(setOf("inner.bad"), warningPaths(NestedPathSample()))
    }

    @Test
    fun testModificationsDoNotWarnSpuriously() {
        // Modification serializers defer validation to the original field's annotations via
        // ShouldValidateSub, which historically produced bogus "invalid type" warnings.
        assertEquals(emptySet(), warningPaths(modification<Sample> { it.x assign "ASDFA" }))
        assertEquals(emptySet(), warningPaths(Modification.Assign(Sample("ASDFA"))))
    }

    @Test
    fun testModificationOfEmptyCollectionDoesNotWarn() {
        // The wrapper here is Modification<List<String>> - a sealed class, not a collection - so the
        // empty-collection suppression has to look at type arguments rather than the descriptor kind.
        assertEquals(emptySet(), warningPaths(modification<Sample> { it.z assign listOf() }))
        assertEquals(emptySet(), warningPaths(modification<Sample> { it.z assign listOf("ok") }))
        assertEquals(emptySet(), warningPaths(modification<Sample> { it.yNullable assign null }))
    }

    @Test
    fun testModificationsStillWarnAndValidate() {
        // The counterpart to the tests above: genuinely misapplied annotations must survive the
        // trip through a modification serializer, and validation must still reach the elements.
        assertEquals(
            setOf("a"),
            warningPaths(modification<BadSample> { it.a assign "fdsa" }),
            "@MaxSize on a String is misapplied whether or not it is wrapped in a Modification"
        )
        assertEquals(
            mapOf("z.Assign.0" to "Too long; maximum 5 characters allowed"),
            validators.validateSkipSuspending(
                validators.serializersModule.serializer<Modification<Sample>>(),
                modification<Sample> { it.z assign listOf("toolong") }
            )
        )
    }

    // --- What the warning reports ---

    @Test
    fun testWarningReportsTheAnnotatedFieldsType() {
        // The warning must name the type the annotation was actually applied to. Reporting an inner
        // type means the annotation frame was popped before the field finished encoding.
        assertEquals(
            "Box<Box<Box<Box<String>>>>",
            warningTypes(TestWarnings())["shouldWarnCascading"]
        )
    }

    @Test
    fun testWarningReportsCollectionTypeNotElementType() {
        // Same frame-balance concern, but where the field is a collection of primitives - the
        // element pops used to consume the enclosing field's frame.
        validators += AnnotationValidators {
            validate<StringListContainsAll, List<String>> { null }
        }
        assertEquals("List<Int>", warningTypes(CustomSample())["intList"])
    }

    // --- collectWarnings ---

    @Test
    fun testCollectWarningsFlag() {
        assertTrue(validators.collectWarnings, "Warnings should be collected by default")

        val quiet = validators.withWarnings(false)
        assertFalse(quiet.collectWarnings)
        assertSame(quiet, quiet.withWarnings(false), "Setting the current value should not copy")

        // Turning warnings off must not change what validation itself reports.
        val serializer = validators.serializersModule.serializer<TestWarnings>()
        assertEquals(
            validators.validateSkipSuspending(serializer, TestWarnings()),
            quiet.validateSkipSuspending(serializer, TestWarnings())
        )
    }

    @Test
    fun testCollectWarningsFalseSkipsCollectionEntirely() {
        // The performance claim: with the flag off, no warning bookkeeping happens at all.
        val serializer = validators.serializersModule.serializer<TestWarnings>()

        val loud = validators.ValidationEncoder(doSuspendingChecks = false, collectWarnings = true)
        loud.encodeSerializableValue(serializer, TestWarnings())
        assertTrue(loud.encodingWarnings.isNotEmpty(), "This model is known to produce warnings")

        val quiet = validators.ValidationEncoder(doSuspendingChecks = false, collectWarnings = false)
        quiet.encodeSerializableValue(serializer, TestWarnings())
        assertEquals(emptyList(), quiet.encodingWarnings)

        // ...and the issues are identical either way.
        assertEquals(loud.issues, quiet.issues)
    }

    @Test
    fun testCollectWarningsCombination() {
        // Empty sets, so combining can't trip the duplicate-validator check and we observe the flag alone.
        val loud = EmptyAnnotationValidators()
        val quiet = loud.withWarnings(false)

        // `+` is conservative - warnings survive if either side wants them.
        assertTrue((quiet + loud).collectWarnings)
        assertTrue((loud + quiet).collectWarnings)
        assertTrue((loud + loud).collectWarnings)
        assertFalse((quiet + quiet).collectWarnings)

        // `overwriteWith` takes the right-hand setting, like every other part of the merge.
        assertFalse((loud overwriteWith quiet).collectWarnings)
        assertTrue((quiet overwriteWith loud).collectWarnings)
    }

    // --- Suspending validators ---

    @Test
    fun testSuspendingValidatorsRunAndRecordCorrectPaths() = runTest {
        validators += AnnotationValidators {
            validateSuspending<SuspendingMaxLength, String> {
                if (it.length > size) "Too long" else null
            }
        }

        // Paths for suspending checks are captured eagerly, before the encoder unwinds - a frame
        // imbalance would key these against the wrong path.
        assertEquals(
            mapOf("a" to "Too long", "inner.b" to "Too long"),
            validators.validate(
                validators.serializersModule.serializer<SuspendingSample>(),
                SuspendingSample(a = "toolong", inner = SuspendingInner(b = "toolong"))
            )
        )
    }

    @Test
    fun testSuspendingValidatorsSkippedByValidateSkipSuspending() = runTest {
        validators += AnnotationValidators {
            validateSuspending<SuspendingMaxLength, String> {
                if (it.length > size) "Too long" else null
            }
        }

        val serializer = validators.serializersModule.serializer<SuspendingSample>()
        val bad = SuspendingSample(a = "toolong", inner = SuspendingInner(b = "toolong"))

        assertEquals(2, validators.validate(serializer, bad).size)
        assertEquals(emptyMap(), validators.validateSkipSuspending(serializer, bad))
    }

    @Test
    fun testSuspendingOnlyAnnotationDoesNotWarnWhenSkipped() = runTest {
        validators += AnnotationValidators {
            validateSuspending<SuspendingMaxLength, String> { null }
        }

        // Skipping suspending checks means the annotation is never consulted - which must not be
        // mistaken for it having been applied to an invalid type.
        assertEquals(emptySet(), warningPaths(SuspendingSample()))
    }

    // --- Degenerate inputs ---

    @Test
    fun testRootLevelValuesDoNotUnbalanceTheEncoder() {
        // The encoder starts at depth 0, where a pop has nothing to pop. These must not throw.
        assertEquals(emptyMap(), validators.validateSkipSuspending(String.serializer(), "anything"))
        assertEquals(emptyMap(), validators.validateSkipSuspending(Int.serializer(), 7))
        assertEquals(emptyMap(), validators.validateSkipSuspending(TestEnum.serializer(), TestEnum.First))
        assertEquals(
            emptyMap(),
            validators.validateSkipSuspending(ListSerializer(String.serializer()), listOf("a", "b"))
        )
        assertEquals(emptyMap(), validators.validateSkipSuspending(NeverUsed.serializer(), NeverUsed()))
    }

    @Test
    fun testPhantomTypeParameterSuppressesWarning() {
        // KNOWN, ACCEPTED LIMITATION - not a bug to be fixed without a deliberate decision.
        //
        // The empty-collection suppression asks whether a validator matches any type argument, and
        // FakeBox<Int> carries an Int argument that never reaches the encoded content (its only
        // field is a String). So @IntegerRange is suppressed here even though it can never apply.
        //
        // The alternative - gating the scan on LIST/MAP descriptor kind - was tried and reintroduced
        // false positives for every collection wrapped in a Modification, which is a far more common
        // and more damaging failure. A phantom type parameter is rare; that trade was made knowingly.
        assertEquals(
            emptySet(),
            warningPaths(PhantomParameterSample()),
            "If this now warns, the suppression rule changed - confirm the Modification cases in " +
                    "testModificationOfEmptyCollectionDoesNotWarn still hold before accepting it."
        )
    }

    @Test
    fun testRepeatedValidationIsStable() {
        // Encoder state is per-call; validating the same value repeatedly must not accumulate or
        // drop warnings.
        val first = warningPaths(TestWarnings())
        repeat(3) { assertEquals(first, warningPaths(TestWarnings())) }
    }

    @Test
    fun testUnannotatedModelProducesNothing() {
        val result = validators.validateSkipSuspendingCollectingWarnings(
            validators.serializersModule.serializer<Box<Int>>(), Box(1)
        )
        assertEquals(emptyMap(), result.issues)
        assertEquals(emptyList(), result.warnings)
    }
}


// =================================================================================================
// AI-Generated Tests - models
// =================================================================================================

@Serializable
data class EmptyCollectionSample(
    // MaxLength only matches String, so it cascades to the elements rather than applying to the list.
    @MaxLength(5) val empty: List<String> = emptyList(),
    @MaxLength(5) val full: List<String> = listOf("ok"),
    @MaxLength(5) val emptyMap: Map<String, String> = emptyMap(),
    @MaxLength(5) val nullEmpty: List<String>? = null,
)

@Serializable
data class NestedCollectionSample(
    @MaxLength(5) val nested: List<List<String>> = emptyList(),
    @MaxLength(5) val nestedEmptyInner: List<List<String>> = listOf(emptyList()),
    // MaxLength cascades to the String key; the Int value simply never matches.
    @MaxLength(5) val map: Map<String, Int> = emptyMap(),
)

/** A type parameter that never appears in the encoded content. See [ValidationTest.testPhantomTypeParameterSuppressesWarning]. */
@Serializable
data class FakeBox<T>(val string: String)

@Serializable
data class PhantomParameterSample(
    @IntegerRange(0, 100) val field: FakeBox<Int> = FakeBox("not an int"),
)

@Serializable
data class MixedAnnotationSample(
    @MaxSize(2) @AlwaysPrintsMismatchedTypesWarning val items: List<String> = listOf("a"),
)

@Serializable
data class NestedPathSample(val inner: NestedPathInner = NestedPathInner())

@Serializable
data class NestedPathInner(@AlwaysPrintsMismatchedTypesWarning val bad: Int = 1)

@SerialInfo
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
annotation class SuspendingMaxLength(val size: Int)

@Serializable
data class SuspendingSample(
    @SuspendingMaxLength(5) val a: String = "ok",
    val inner: SuspendingInner = SuspendingInner(),
)

@Serializable
data class SuspendingInner(@SuspendingMaxLength(5) val b: String = "ok")