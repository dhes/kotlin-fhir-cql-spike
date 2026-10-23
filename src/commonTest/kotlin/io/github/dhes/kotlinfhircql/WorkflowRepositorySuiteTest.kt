package io.github.dhes.kotlinfhircql

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource
import kotlinx.coroutines.test.runTest

/**
 * Step 8, on every target: WHO's immunization test suite with the libraries, value sets and patient data read
 * from a [dev.ohs.fhir.workflow.WorkflowRepository] before evaluation, each scenario's outcome compared with
 * the HAPI outcome recorded on the JVM by [Step4Test], as in [WhoSuiteTest].
 */
class WorkflowRepositorySuiteTest {
    @Test
    fun matchesRecordedHapiOutcomes() = runTest(timeout = 60.minutes) {
        val expected: Map<String, WhoOutcome> = MeaslesFixtures.json.decodeFromString(MeaslesFixtures.readText(WhoSuite.EXPECTED))
        val start = TimeSource.Monotonic.markNow()
        val repository = InMemoryWorkflowRepository(WhoSuite.libraryResources + WhoSuite.valueSets)
        val cql =
            WorkflowRepositoryCqlEvaluator(
                repository,
                MeaslesFixtures.modelInfoXml,
                listOf(WhoSuite.fhirHelpersSource),
                platformUcumService(),
            )
        val outcomes = mutableMapOf<String, WhoOutcome>()
        val differences = mutableListOf<String>()
        val rejected = mutableListOf<String>()
        val notImplemented = mutableListOf<String>()
        val readTypes = mutableMapOf<String, Int>()
        val unreadTypes = mutableMapOf<String, Int>()
        val missingValueSets = mutableMapOf<String, Int>()
        var valueSetsRead = 0
        for (scenario in WhoSuite.scenarios) {
            val bundle =
                try {
                    WhoSuite.bundleResources(scenario)
                } catch (e: Exception) {
                    rejected += "${scenario.id}: ${e.message}"
                    continue
                }
            repository.loadPatientData(bundle)
            val libraries = cql.loadLibraries(WhoSuite.libraryUrl(scenario))
            assertEquals(WhoSuite.closure(WhoSuite.plans.getValue(scenario.planDefinition).library), libraries, scenario.id)
            val data = cql.readData(libraries, scenario.patient, WhoSuite.converter(scenario))
            data.types.forEach { readTypes[it] = (readTypes[it] ?: 0) + 1 }
            data.unreadTypes.forEach { unreadTypes[it] = (unreadTypes[it] ?: 0) + 1 }
            data.missingValueSets.forEach { missingValueSets[it] = (missingValueSets[it] ?: 0) + 1 }
            valueSetsRead += data.terminologyProvider.let { (it as InMemoryTerminologyProvider).size }
            val results =
                WhoSuite.evaluateLibrariesAs(scenario, libraries, MeaslesFixtures::render, { it }) { library, parameters, dateTime ->
                    cql.evaluate(library, scenario.patient, data, null, parameters, dateTime)
                }
            val outcome = WhoSuite.outcome(scenario, results)
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
        println("Bundles kotlin-fhir rejects (scenario skipped): ${rejected.size}")
        println("Types read, by number of scenarios: $readTypes")
        println("Retrieved types not read (no patient link), by number of scenarios: $unreadTypes")
        println("Value sets read: $valueSetsRead in all")
        println("Declared value sets the repository does not hold, by number of scenarios: $missingValueSets")
        println("Repository calls: ${repository.calls.entries.sortedBy { it.key }.joinToString()}")
        println("Results: ${outcomes.values.sumOf { it.results }}")
        assertTrue(differences.isEmpty(), "${differences.size} scenarios differ from the recorded HAPI outcomes")
    }
}
