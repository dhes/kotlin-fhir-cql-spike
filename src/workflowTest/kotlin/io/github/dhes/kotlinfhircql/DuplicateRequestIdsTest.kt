package io.github.dhes.kotlinfhircql

import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest

/**
 * Counts the WHO scenarios whose CarePlan from kotlin-fhir-workflow's `$apply` contains two resources with one id
 * (two actions that use one ActivityDefinition). Prints only.
 */
class DuplicateRequestIdsTest {
    @Test
    fun containedIdsRepeated() = runTest(timeout = 60.minutes) {
        val byPlan = sortedMapOf<String, MutableSet<String>>()
        val scenarios = mutableListOf<String>()
        var applied = 0
        for (scenario in WhoSuite.scenarios) {
            val carePlan = WhoApply.apply(scenario).carePlan ?: continue
            applied++
            val repeated = carePlan.contained.mapNotNull { it.id }.groupingBy { it }.eachCount().filterValues { it > 1 }
            if (repeated.isEmpty()) continue
            scenarios += "${scenario.id}: ${repeated.entries.joinToString { "${it.key} ×${it.value}" }}"
            byPlan.getOrPut(scenario.planDefinition) { sortedSetOf() } += repeated.keys
        }
        println("Scenarios applied: $applied; with a repeated contained id: ${scenarios.size}")
        byPlan.forEach { (plan, ids) -> println("  $plan: $ids") }
        scenarios.take(10).forEach { println("  $it") }
    }
}
