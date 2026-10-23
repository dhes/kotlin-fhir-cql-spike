package io.github.dhes.kotlinfhircql

import kotlin.test.Test
import kotlin.test.assertEquals

/** Runs on every target: each definition of [TerminologyFixtures.LIBRARY] against [TerminologyFixtures.EXPECTED]. */
class TerminologyTest {
    @Test
    fun terminologyOperations() {
        val evaluator = FhirCqlEvaluator(TerminologyFixtures.librarySources, MeaslesFixtures.modelInfoXml, ucumService = platformUcumService())
        val terminology = InMemoryTerminologyProvider(TerminologyFixtures.valueSets, TerminologyFixtures.codeSystems)
        val results = TerminologyFixtures.evaluate(evaluator, terminology, TerminologyFixtures.kotlinFhirResources())
        results.forEach { (k, v) -> println("$k: $v") }
        assertEquals(TerminologyFixtures.EXPECTED, results)
    }
}
