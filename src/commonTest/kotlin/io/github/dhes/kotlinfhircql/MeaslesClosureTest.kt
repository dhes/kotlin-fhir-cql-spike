package io.github.dhes.kotlinfhircql

import io.github.dhes.kotlinfhircql.MeaslesFixtures.LOGIC_LIBRARY
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource
import org.opencds.cqf.cql.engine.runtime.String as CqlString

/**
 * Runs on every target: every definition in the measles closure, for WHO's 7 example patients,
 * compared with the HAPI results recorded on the JVM by [Step2Test], and the logic library's
 * guidance compared with what WHO's test suite expects.
 */
class MeaslesClosureTest {
    @Test
    fun matchesRecordedHapiResultsAndWhoExpectations() {
        val expected =
            MeaslesFixtures.json.decodeFromString<Map<String, Map<String, String?>>>(
                MeaslesFixtures.readText(MeaslesFixtures.EXPECTED_RESULTS)
            )
        val clock = TimeSource.Monotonic
        val setUp = clock.markNow()
        val evaluator =
            FhirCqlEvaluator(
                MeaslesFixtures.librarySources,
                MeaslesFixtures.modelInfoXml,
                InMemoryTerminologyProvider(MeaslesFixtures.valueSets),
                ucumService = platformUcumService(),
            )
        println("Set-up: ${setUp.elapsedNow()}")

        val differences = mutableListOf<String>()
        var compared = 0
        for (patient in MeaslesFixtures.patients) {
            val mark = clock.markNow()
            val results = MeaslesFixtures.evaluateAll(evaluator, patient, MeaslesFixtures.kotlinFhirResources(patient))
            val reference = expected.getValue(patient)
            println("$patient: ${results.size} results in ${mark.elapsedNow()}")
            results.filterKeys { it.endsWith("(error)") }.forEach { (k, v) -> println("    $k $v") }
            for (key in (results.keys + reference.keys).sorted()) {
                compared++
                if (results[key] != reference[key]) {
                    differences += "$patient $key\n    this target: ${results[key]}\n    HAPI (JVM):  ${reference[key]}"
                }
            }
            assertEquals(CqlString(MeaslesFixtures.expectedGuidance.getValue(patient)).toString(), results["$LOGIC_LIBRARY.Guidance"], "Guidance for $patient")
            assertEquals("true", results["$LOGIC_LIBRARY.Test Validation"], "Test Validation for $patient")
        }
        println("Compared $compared results with the recorded HAPI results; ${differences.size} differ")
        differences.forEach(::println)
        assertTrue(differences.isEmpty(), "${differences.size} results differ from the recorded HAPI results")
    }
}
