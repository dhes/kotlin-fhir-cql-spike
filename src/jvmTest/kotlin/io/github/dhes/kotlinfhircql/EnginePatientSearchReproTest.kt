package io.github.dhes.kotlinfhircql

import dev.ohs.fhir.engine.search.ReferenceClientParam
import dev.ohs.fhir.engine.search.Search
import dev.ohs.fhir.engine.search.revInclude
import dev.ohs.fhir.engine.search.search
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

/**
 * The smallest case of [EnginePatientSearchTest], quoted in a kotlin-fhir-engine issue: one Patient and one each of
 * Condition, Observation, Encounter and Immunization that point to it, searched by `patient` and by `subject`.
 */
class EnginePatientSearchReproTest {
    @Test
    fun repro() = runTest {
        val engine = emptyTestEngine() // FhirEngineConfiguration(testMode = true, storageDirectory = <temp dir>)
        val json = Json { ignoreUnknownKeys = true }
        engine.create(
            *listOf(
                """{"resourceType":"Patient","id":"p1"}""",
                """{"resourceType":"Condition","id":"c1","subject":{"reference":"Patient/p1"}}""",
                """{"resourceType":"Observation","id":"o1","status":"final","code":{"text":"x"},"subject":{"reference":"Patient/p1"}}""",
                """{"resourceType":"Encounter","id":"e1","status":"finished","class":{"code":"AMB"},"subject":{"reference":"Patient/p1"}}""",
                """{"resourceType":"Immunization","id":"i1","status":"completed","vaccineCode":{"text":"x"},"patient":{"reference":"Patient/p1"},"occurrenceDateTime":"2026-01-01"}""",
            ).map { json.decodeFromString<Resource>(it) }.toTypedArray(),
        )

        suspend fun count(type: ResourceType, param: String) =
            engine.search<Resource>(
                Search(type).apply { filter(ReferenceClientParam(param), { value = "Patient/p1" }) },
            ).size
        for (type in listOf(ResourceType.Condition, ResourceType.Observation, ResourceType.Encounter)) {
            println("$type: patient=Patient/p1 -> ${count(type, "patient")}, subject=Patient/p1 -> ${count(type, "subject")}")
        }
        println("Immunization: patient=Patient/p1 -> ${count(ResourceType.Immunization, "patient")}")

        // The revInclude example from NestedSearch.kt's KDoc, without its filters.
        for (param in listOf("patient", "subject")) {
            val result = engine.search<Patient> {
                revInclude(ResourceType.Encounter, ReferenceClientParam(param))
                revInclude(ResourceType.Condition, ReferenceClientParam(param))
            }
            println("revInclude by $param: " + result.map { it.revIncluded?.mapValues { (_, v) -> v.map { r -> r.id } } })
        }
    }
}
