package io.github.dhes.kotlinfhircql

import java.io.File
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.builtins.serializer

/**
 * Writes every definition's result for every scenario of WHO's suite to `build/suite-results.json`, so two runs
 * over different copies of the CQL can be compared definition by definition. It asserts nothing.
 */
class SuiteResultsDumpTest {
    @Test
    fun writesEveryResult() = runTest(timeout = 120.minutes) {
        preloadFixtures()
        val evaluator =
            FhirCqlEvaluator(
                WhoSuite.librarySources,
                MeaslesFixtures.modelInfoXml,
                InMemoryTerminologyProvider(WhoSuite.valueSets),
                ucumService = platformUcumService(),
            )
        val all = sortedMapOf<String, Map<String, String?>>()
        for (scenario in WhoSuite.scenarios) {
            val resources =
                try {
                    WhoSuite.kotlinFhirResources(scenario)
                } catch (e: Exception) {
                    all[scenario.id] = mapOf("(bundle rejected)" to e.message)
                    continue
                }
            all[scenario.id] = WhoSuite.evaluateClosure(evaluator, scenario, resources).toSortedMap()
        }
        val serializer = MapSerializer(String.serializer(), MapSerializer(String.serializer(), String.serializer().nullable))
        File("build/suite-results.json").writeText(MeaslesFixtures.json.encodeToString(serializer, all))
        println("${all.size} scenarios written to build/suite-results.json")
    }
}
