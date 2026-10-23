package io.github.dhes.kotlinfhircql

import dev.ohs.fhir.model.r4.AllergyIntolerance
import dev.ohs.fhir.model.r4.Condition
import dev.ohs.fhir.model.r4.Encounter
import dev.ohs.fhir.model.r4.Immunization
import dev.ohs.fhir.model.r4.Library
import dev.ohs.fhir.model.r4.MedicationRequest
import dev.ohs.fhir.model.r4.Observation
import dev.ohs.fhir.model.r4.Procedure
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.ValueSet
import dev.ohs.fhir.workflow.WorkflowRepository

/**
 * A [WorkflowRepository] in memory: knowledge resources that stay, and one scenario's patient data at a
 * time. Search covers what [WorkflowRepositoryCqlEvaluator] asks for: Library and ValueSet by `url`, and
 * the seven types with a patient link by `patient`.
 */
class InMemoryWorkflowRepository(knowledge: List<Resource>) : WorkflowRepository {
    private val knowledge: Map<String, List<Resource>> = knowledge.groupBy(::typeOf)
    private var patientData: Map<String, List<Resource>> = emptyMap()

    /** Calls made, by method and type, for reporting. */
    val calls = mutableMapOf<String, Int>()

    /** Replaces the patient data with [resources]. */
    fun loadPatientData(resources: List<Resource>) {
        patientData = resources.groupBy(::typeOf)
    }

    private fun ofType(type: String) = knowledge[type].orEmpty() + patientData[type].orEmpty()

    private fun count(call: String) {
        calls[call] = (calls[call] ?: 0) + 1
    }

    override suspend fun read(type: String, id: String): Resource? {
        count("read $type")
        return ofType(type).singleOrNull { it.id == id }
    }

    override suspend fun create(resource: Resource): String = throw UnsupportedOperationException("read only")

    override suspend fun update(resource: Resource) = throw UnsupportedOperationException("read only")

    override suspend fun delete(type: String, id: String) = throw UnsupportedOperationException("read only")

    override suspend fun searchByReferenceParam(type: String, param: String, referenceValue: String): List<Resource> {
        count("searchByReferenceParam $type $param")
        require(param == "patient") { "Only the patient search parameter is supported, not $param" }
        return ofType(type).filter { patientReference(it) == referenceValue }
    }

    override suspend fun searchByUri(type: String, param: String, uri: String): List<Resource> {
        count("searchByUri $type $param")
        require(param == "url") { "Only the url search parameter is supported, not $param" }
        return ofType(type).filter {
            when (it) {
                is Library -> it.url?.value == uri
                is ValueSet -> it.url?.value == uri
                else -> error("No url search for $type")
            }
        }
    }

    private fun patientReference(resource: Resource): String? =
        when (resource) {
            is Immunization -> resource.patient.reference?.value
            is Condition -> resource.subject.reference?.value
            is Observation -> resource.subject?.reference?.value
            is MedicationRequest -> resource.subject.reference?.value
            is Encounter -> resource.subject?.reference?.value
            is Procedure -> resource.subject.reference?.value
            is AllergyIntolerance -> resource.patient.reference?.value
            else -> error("No patient search for ${typeOf(resource)}")
        }

    private fun typeOf(resource: Resource): String = resource::class.simpleName ?: error("No type name")
}
