package io.github.dhes.kotlinfhircql

import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import dev.ohs.fhir.workflow.demo.data.EngineWorkflowRepository
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource
import kotlinx.coroutines.test.runTest
import org.opencds.cqf.cql.engine.runtime.Value
import org.opencds.cqf.cql.engine.runtime.List as CqlList

/**
 * Step 9, JVM: WHO's immunization test suite with kotlin-fhir-engine's in-memory database behind
 * kotlin-fhir-workflow's [EngineWorkflowRepository] (copied from its demo app), each definition's value compared,
 * element by element, with the value from an [InMemoryWorkflowRepository] holding the same resources, which
 * [WorkflowRepositorySuiteTest] compares with the recorded HAPI outcomes. A list may hold the same elements in a
 * different order: FHIR search defines no order, and a CQL query without a sort keeps its source's order.
 */
class EngineWorkflowRepositorySuiteTest {
    @Test
    fun matchesInMemoryRepository() = runTest(timeout = 60.minutes) {
        val engine = emptyTestEngine()
        val knowledge = WhoSuite.libraryResources + WhoSuite.valueSets
        var start = TimeSource.Monotonic.markNow()
        engine.create(*knowledge.toTypedArray())
        println("Created ${WhoSuite.libraryResources.size} Libraries and ${WhoSuite.valueSets.size} ValueSets in ${start.elapsedNow()}")
        val memory = InMemoryWorkflowRepository(knowledge)
        fun evaluator(repository: dev.ohs.fhir.workflow.WorkflowRepository) =
            WorkflowRepositoryCqlEvaluator(repository, MeaslesFixtures.modelInfoXml, listOf(WhoSuite.fhirHelpersSource))
        val fromEngine = evaluator(EngineWorkflowRepository(engine))
        val fromMemory = evaluator(memory)
        var loaded = emptyList<Resource>()
        var scenarios = 0
        var results = 0
        val orderOnly = mutableMapOf<String, Int>()
        var scenariosWithOrderOnly = 0
        val differences = mutableListOf<String>()
        start = TimeSource.Monotonic.markNow()
        for (scenario in WhoSuite.scenarios) {
            val bundle = runCatching { WhoSuite.bundleResources(scenario) }.getOrNull() ?: continue
            loaded.groupBy { ResourceType.fromCode(it::class.simpleName!!) }.forEach { (type, resources) ->
                engine.purge(type, resources.map { it.id!! }.toSet(), forcePurge = true)
            }
            engine.create(*bundle.toTypedArray())
            loaded = bundle
            memory.loadPatientData(bundle)
            suspend fun values(cql: WorkflowRepositoryCqlEvaluator): Map<String, Value?> {
                val libraries = cql.loadLibraries(WhoSuite.libraryUrl(scenario))
                val data = cql.readData(libraries, scenario.patient, WhoSuite.converter(scenario))
                return WhoSuite.evaluateLibrariesAs(scenario, libraries, { it }, { error(it) }) { library, parameters, dateTime ->
                    cql.evaluate(library, scenario.patient, data, null, parameters, dateTime)
                }
            }
            val engineValues = values(fromEngine)
            val memoryValues = values(fromMemory)
            scenarios++
            results += memoryValues.size
            var hasOrderOnly = false
            for (name in (memoryValues.keys + engineValues.keys)) {
                val a = memoryValues[name]
                val b = engineValues[name]
                if (MeaslesFixtures.renderFull(a) == MeaslesFixtures.renderFull(b)) continue
                if (a is CqlList && b is CqlList && a.map(MeaslesFixtures::renderFull).sortedBy { it } == b.map(MeaslesFixtures::renderFull).sortedBy { it }) {
                    orderOnly[name] = (orderOnly[name] ?: 0) + 1
                    hasOrderOnly = true
                } else {
                    differences += "${scenario.id} $name\n    in memory: ${MeaslesFixtures.render(a)}\n    engine:    ${MeaslesFixtures.render(b)}"
                }
            }
            if (hasOrderOnly) scenariosWithOrderOnly++
        }
        println("$scenarios scenarios, $results results, in ${start.elapsedNow()}; ${differences.size} differ other than in list order")
        differences.take(20).forEach(::println)
        println("Scenarios with a list in a different order: $scenariosWithOrderOnly; definitions, by number of scenarios:")
        orderOnly.entries.sortedByDescending { it.value }.forEach { (name, n) -> println("  $name: $n") }
        assertTrue(differences.isEmpty(), "${differences.size} values differ other than in list order")
    }
}
