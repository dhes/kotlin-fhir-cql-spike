package io.github.dhes.kotlinfhircql

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest

/**
 * Step 8, on every target: WHO's immunization test suite through [whoSuiteThroughRepository], with an
 * [InMemoryWorkflowRepository] as the repository.
 */
class WorkflowRepositorySuiteTest {
    @Test
    fun matchesRecordedHapiOutcomes() = runTest(timeout = 60.minutes) {
        val repository = InMemoryWorkflowRepository(WhoSuite.libraryResources + WhoSuite.valueSets)
        val differences = whoSuiteThroughRepository(repository) { repository.loadPatientData(it) }
        println("Repository calls: ${repository.calls.entries.sortedBy { it.key }.joinToString()}")
        assertTrue(differences.isEmpty(), "${differences.size} scenarios differ from the recorded HAPI outcomes")
    }
}
