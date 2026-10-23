package io.github.dhes.kotlinfhircql

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Runs on every target: each definition of [RetrieveFixtures.LIBRARY] compiled without and with the compiler's date
 * range optimization, which moves the date test from the where clause into the retrieve, and the results compared.
 */
class RetrieveDateRangeTest {
    @Test
    fun optimizedRetrievesAgreeWithWhereClauses() {
        val resources = RetrieveFixtures.kotlinFhirResources()
        val plain = RetrieveFixtures.evaluator(optimized = false)
        val optimized = RetrieveFixtures.evaluator(optimized = true)
        assertEquals(emptyMap(), RetrieveFixtures.datePaths(plain), "Date ranges in retrieves without the optimization")
        assertEquals(RetrieveFixtures.EXPECTED_DATE_PATHS, RetrieveFixtures.datePaths(optimized), "Date ranges in retrieves")

        val expected = RetrieveFixtures.evaluate(plain, resources)
        val actual = RetrieveFixtures.evaluate(optimized, resources)
        val differing = expected.keys.filter { expected[it] != actual[it] }.map { it.substringAfter('.') }.toSet()
        for (key in expected.keys.sorted()) {
            println("${if (expected[key] == actual[key]) "same  " else "DIFFER"} $key\n    where clause: ${expected[key]}\n    retrieve:     ${actual[key]}")
        }
        val ranged = RetrieveFixtures.EXPECTED_DATE_PATHS.size
        println("Of the $ranged definitions given a date range, ${ranged - differing.size} agree with the where clause")
        assertEquals(RetrieveFixtures.LOST_IN_COMPILATION, differing, "Definitions whose results differ")
    }
}
