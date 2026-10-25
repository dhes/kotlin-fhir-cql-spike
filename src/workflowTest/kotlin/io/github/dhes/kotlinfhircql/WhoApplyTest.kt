package io.github.dhes.kotlinfhircql

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest

/**
 * Runs on the JVM and Wasm (kotlin-fhir-workflow has no JS target): `$apply` on each WHO scenario through
 * kotlin-fhir-workflow's `FhirOperator`, against WHO's expected guidance and resource count, and against the count
 * and guidance step 4 derived from the logic library's definitions.
 */
class WhoApplyTest {
    @Test
    fun whoSuiteThroughWorkflowApply() = runTest(timeout = 60.minutes) {
        val recorded: Map<String, WhoOutcome> = MeaslesFixtures.json.decodeFromString(MeaslesFixtures.readText(WhoSuite.EXPECTED))
        val errors = mutableMapOf<String, String>()
        val countDiffers = mutableListOf<String>()
        val guidanceDiffers = mutableListOf<String>()
        var counted = 0
        var countMatchesWho = 0
        var guided = 0
        var guidanceMatchesWho = 0
        for (scenario in WhoSuite.scenarios) {
            if (recorded[scenario.id] == null) continue
            val step4 = recorded.getValue(scenario.id)
            val outcome = WhoApply.apply(scenario)
            if (outcome.error != null) {
                errors[scenario.id] = outcome.error
                continue
            }
            if (outcome.contained != step4.contained) countDiffers += "${scenario.id}: \$apply ${outcome.contained}, step 4 ${step4.contained}"
            val step4Guidance = step4.guidance?.let { MeaslesFixtures.json.decodeFromString<String>("\"" + it.removeSurrounding("'").replace("\\'", "'") + "\"") }
            // Schedule PlanDefinitions (IMMZD18S…) have no Guidance definition; their payload comes from `… Create`.
            if (step4Guidance != null && outcome.guidance != step4Guidance) guidanceDiffers += "${scenario.id}:\n    \$apply: ${outcome.guidance}\n    step 4: $step4Guidance"
            scenario.expectedContained?.let { counted++; if (it == outcome.contained) countMatchesWho++ }
            scenario.expectedGuidance?.let { guided++; if (it == outcome.guidance) guidanceMatchesWho++ }
        }
        println("Scenarios applied: ${recorded.size - errors.size} of ${recorded.size}; errors: ${errors.size}")
        errors.entries.groupBy({ it.value }, { it.key }).forEach { (error, ids) -> println("  ${ids.size} × $error (e.g. ${ids.first()})") }
        println("Resource count matches WHO: $countMatchesWho/$counted; guidance matches WHO: $guidanceMatchesWho/$guided")
        println("Differ from step 4: count ${countDiffers.size}, guidance ${guidanceDiffers.size}")
        (countDiffers + guidanceDiffers).take(20).forEach(::println)
        assertEquals(emptyList(), countDiffers + guidanceDiffers, "Scenarios whose \$apply result differs from step 4's")
        val unexpected = errors.filterValues { !it.startsWith("SerializationException") && !it.contains("NotImplementedError") }
        assertEquals(emptyMap(), unexpected, "Errors other than bundles kotlin-fhir rejects and Split on Wasm")
    }

    /** WHO's PlanDefinitions as published, without [WhoApply.conform]: what kotlin-fhir-workflow `main` does with them. */
    @Test
    fun publishedPlanDefinitionsStopAtTheirExpressionLanguage() = runTest(timeout = 10.minutes) {
        val errors =
            WhoSuite.scenarios.filter { it.twinOf == null }.distinctBy { it.planDefinition }.map { scenario ->
                WhoApply.apply(scenario, published = true).error ?: "no error"
            }
        val counts = errors.groupingBy { it }.eachCount()
        counts.forEach { (error, n) -> println("$n PlanDefinitions: $error") }
        assertEquals(setOf("IllegalStateException: Unsupported expression language: text/cql-identifier"), counts.keys - "SerializationException: Missing required property 'medication' on MedicationRequest")
    }
}
