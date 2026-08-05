package com.lightningkite.services.database.test

/**
 * The shared conformance suites every database driver is expected to subclass.
 *
 * This list exists because the failure mode it guards against is invisible: a driver that simply
 * never subclasses a suite reports a green build, and the gap only surfaces when something breaks in
 * production. An audit found four suites the Postgres driver had never run, one that only MongoDB
 * ran, and one that no driver ran at all.
 *
 * Drivers assert against this list with [assertConformanceSuitesCovered]. A driver that genuinely
 * cannot support a suite should still subclass it and override the individual tests it cannot
 * satisfy — or expose a capability flag, as [VectorSearchTests] does — so the reason is recorded in
 * code rather than in an omission.
 *
 * [ConcurrencyTests] is deliberately on this list with no way to opt out: row-level atomicity is a
 * contract every implementation must meet, not an optional feature.
 */
public val conformanceSuites: List<String> = listOf(
    "AggregationsTest",
    "ConcurrencyTests",
    "ConditionTests",
    "IndexTests",
    "InlinePropertiesTests",
    "MetaTest",
    "ModificationTests",
    "OperationsTests",
    "PaginationTests",
    "ReturnContractTests",
    "ScaleAndBoundaryTests",
    "SingleRowOperationTests",
    "SortTest",
)

/**
 * Fails with a readable message listing any suite in [conformanceSuites] that [covered] omits.
 *
 * Each driver's test module passes the suites it actually subclasses. Pass the *shared* suite names
 * (e.g. `"SortTest"`), not the driver's subclass names.
 */
public fun assertConformanceSuitesCovered(driver: String, covered: Collection<String>) {
    val unknown = covered - conformanceSuites.toSet()
    check(unknown.isEmpty()) {
        "$driver claims coverage of unrecognized suites: ${unknown.sorted()}. " +
            "Add them to conformanceSuites, or correct the name."
    }
    val missing = conformanceSuites - covered.toSet()
    check(missing.isEmpty()) {
        "$driver does not subclass these shared conformance suites: ${missing.sorted()}. " +
            "Subclass them, overriding any individual test the driver cannot satisfy, so the gap is " +
            "recorded in code rather than silently untested."
    }
}
