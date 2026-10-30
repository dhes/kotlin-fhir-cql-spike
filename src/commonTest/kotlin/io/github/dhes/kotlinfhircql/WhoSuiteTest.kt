package io.github.dhes.kotlinfhircql

import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * Runs on every target: WHO's immunization test suite and the Condition twins, each scenario's outcome compared
 * with the HAPI outcome recorded on the JVM by [Step4Test]: the digest of every definition in the closure, the
 * guidance, and the resource count `$apply` would return.
 */
class WhoSuiteTest {
    @Test
    fun matchesRecordedHapiOutcomes() = runTest(timeout = 120.minutes) {
        preloadFixtures()
        val expected: Map<String, WhoOutcome> = MeaslesFixtures.json.decodeFromString(MeaslesFixtures.readText(WhoSuite.EXPECTED))
        val start = TimeSource.Monotonic.markNow()
        val evaluator =
            FhirCqlEvaluator(
                WhoSuite.librarySources,
                MeaslesFixtures.modelInfoXml,
                InMemoryTerminologyProvider(WhoSuite.valueSets),
                ucumService = platformUcumService(),
            )
        val outcomes = mutableMapOf<String, WhoOutcome>()
        val differences = mutableListOf<String>()
        val rejected = mutableListOf<String>()
        // Scenarios that reach an engine operation not implemented on this target (Split on JS and Wasm in 5.4.0).
        val notImplemented = mutableListOf<String>()
        for (scenario in WhoSuite.scenarios) {
            val resources =
                try {
                    WhoSuite.kotlinFhirResources(scenario)
                } catch (e: Exception) {
                    rejected += "${scenario.id}: ${e.message}"
                    continue
                }
            val outcome = WhoSuite.outcome(scenario, WhoSuite.evaluateClosure(evaluator, scenario, resources))
            outcomes[scenario.id] = outcome
            val reference = expected.getValue(scenario.id)
            if (outcome != reference) {
                if (outcome.errors.any { "NotImplementedError" in it }) {
                    notImplemented += scenario.id
                } else {
                    differences += "${scenario.id}\n    this target: $outcome\n    HAPI (JVM):  $reference"
                }
            }
        }
        println("${outcomes.size} scenarios in ${start.elapsedNow()}; ${differences.size} differ from the recorded HAPI outcomes")
        differences.take(20).forEach(::println)
        println("Scenarios that reach an engine operation not implemented on this target: ${notImplemented.size}")
        notImplemented.groupBy { it.substringBefore("/") }.forEach { (plan, ids) -> println("  $plan: ${ids.size}") }
        outcomes.values.flatMap { it.errors }.filter { "NotImplementedError" in it }.map { it.substringBefore(" (error)") }
            .groupingBy { it }.eachCount().forEach { (library, n) -> println("  failing library $library: $n") }
        println("Bundles kotlin-fhir rejects (scenario skipped): ${rejected.size}")
        rejected.forEach { println("  $it") }
        val guidance = outcomes.values.mapNotNull { it.guidanceMatchesWho }
        val contained = outcomes.values.mapNotNull { it.containedMatchesWho }
        println("guidance matches WHO ${guidance.count { it }}/${guidance.size}; resource count matches WHO ${contained.count { it }}/${contained.size}")
        val unaffected = outcomes.filterKeys { it !in notImplemented }.values
        val g = unaffected.mapNotNull { it.guidanceMatchesWho }
        val c = unaffected.mapNotNull { it.containedMatchesWho }
        println("Scenarios not reaching an unimplemented operation: ${unaffected.size}; guidance matches WHO ${g.count { it }}/${g.size}; resource count matches WHO ${c.count { it }}/${c.size}")
        assertTrue(differences.isEmpty(), "${differences.size} scenarios differ from the recorded HAPI outcomes")
    }
}
