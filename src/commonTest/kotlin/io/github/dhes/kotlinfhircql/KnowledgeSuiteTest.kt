package io.github.dhes.kotlinfhircql

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest

/**
 * Step 16, on every target: WHO's immunization test suite through [whoSuiteThroughRepository], with WHO's Library and
 * ValueSet resources read from kotlin-fhir-knowledge, where WHO's package was installed or imported as published.
 */
class KnowledgeSuiteTest {
    @Test
    fun whoSuiteFromKnowledge() = runTest(timeout = 60.minutes) {
        preloadFixtures()
        val knowledge = whoKnowledge()
        println(knowledge.how)
        val patients = InMemoryWorkflowRepository(emptyList())
        val repository = KnowledgeWorkflowRepository(knowledge.manager, patients)
        val differences = whoSuiteThroughRepository(repository) { patients.loadPatientData(it) }
        println("Canonical lookups in kotlin-fhir-knowledge, by type: ${repository.lookups}; found nothing: ${repository.notFound}")
        knowledge.manager.close()
        assertTrue(differences.isEmpty(), "${differences.size} scenarios differ from the recorded HAPI outcomes")
    }
}
