package io.github.dhes.kotlinfhircql

import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.workflow.demo.data.EngineWorkflowRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/**
 * Records how kotlin-fhir-engine 2.0.0-alpha04 answers a patient's resources, over the resources of all WHO test
 * bundles: R4's `patient` search parameter finds nothing for the types where R4 defines it as
 * `subject.where(resolve() is Patient)`, because the engine skips indexing an expression kotlin-fhir-path cannot
 * evaluate, while `subject` finds them all.
 */
class EnginePatientSearchTest {
    @Test
    fun patientSearchOverResolveFindsNothing() = runTest {
        val engine = emptyTestEngine()
        val seen = mutableSetOf<String>()
        val all: List<Resource> =
            WhoSuite.scenarios.map { it.bundle }.distinct()
                .mapNotNull { b -> runCatching { WhoSuite.bundleResources(WhoSuite.scenarios.first { it.bundle == b }) }.getOrNull() }
                .flatten()
                .filter { seen.add("${it::class.simpleName}/${it.id}") }
        engine.create(*all.toTypedArray())
        val repository = EngineWorkflowRepository(engine)
        val patients = all.filter { it::class.simpleName == "Patient" }.map { it.id!! }
        suspend fun found(type: String, param: String) =
            patients.sumOf { repository.searchByReferenceParam(type, param, "Patient/$it").size }
        fun stored(type: String) = all.count { it::class.simpleName == type }
        for (type in listOf("Observation", "Condition", "MedicationRequest")) {
            println("$type: stored ${stored(type)}, found by patient ${found(type, "patient")}, by subject ${found(type, "subject")}")
            assertEquals(0, found(type, "patient"), type)
            assertEquals(stored(type), found(type, "subject"), type)
        }
        println("Immunization: stored ${stored("Immunization")}, found by patient ${found("Immunization", "patient")}")
        assertEquals(stored("Immunization"), found("Immunization", "patient"))
    }
}
