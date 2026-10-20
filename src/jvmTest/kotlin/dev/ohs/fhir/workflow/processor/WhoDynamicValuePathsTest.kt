package dev.ohs.fhir.workflow.processor

import dev.ohs.fhir.model.r4.Code
import dev.ohs.fhir.model.r4.CodeableConcept
import dev.ohs.fhir.model.r4.Coding
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.String as FhirString
import dev.ohs.fhir.model.r4.Uri
import dev.ohs.fhir.workflow.fhirJson
import kotlin.test.Test
import kotlinx.serialization.json.jsonObject

/**
 * Writes one value at each dynamicValue path that WHO's immunization PlanDefinitions use (smart.who.int.immunizations
 * 0.2.0), with `evaluatedValueToJson` and `DynamicValueApplier` from kotlin-fhir-workflow 214c56d, and decodes the
 * result as a kotlin-fhir resource.
 */
class WhoDynamicValuePathsTest {
    private val communicationRequest =
        """{"resourceType":"CommunicationRequest","status":"active","subject":{"reference":"Patient/p"}}"""
    private val medicationRequest =
        """{"resourceType":"MedicationRequest","status":"active","intent":"proposal",""" +
            """"medicationCodeableConcept":{"text":"from the ActivityDefinition"},"subject":{"reference":"Patient/p"}}"""

    private val alert =
        Coding(
            system = Uri(value = "http://terminology.hl7.org/CodeSystem/communication-category"),
            code = Code(value = "alert"),
        )
    private val routine =
        Coding(system = Uri(value = "http://hl7.org/fhir/request-priority"), code = Code(value = "routine"))
    private val bcg =
        CodeableConcept(
            coding =
                listOf(
                    Coding(
                        system = Uri(value = "http://smart.who.int/immunizations/CodeSystem/IMMZ.Z"),
                        code = Code(value = "DE1"),
                        display = FhirString(value = "BCG vaccines"),
                    )
                ),
            text = FhirString(value = "BCG vaccines"),
        )

    /** (resource, path, value): WHO's path, and the value its expression gives, as a kotlin-fhir type. */
    private val writes =
        listOf(
            Triple(communicationRequest, "status", "active"),
            Triple(communicationRequest, "category.coding", alert),
            Triple(communicationRequest, "priority", routine),
            Triple(communicationRequest, "payload.contentString", "Guidance text"),
            Triple(medicationRequest, "status", "draft"),
            Triple(medicationRequest, "intent", "proposal"),
            Triple(medicationRequest, "medication", bcg),
            Triple(medicationRequest, "id", "IMMZD5DTMR-example"),
        )

    private fun writeAndDecode(resource: String, path: String, value: Any): String {
        val json = fhirJson.parseToJsonElement(resource).jsonObject
        val written = DynamicValueApplier.set(json, path, evaluatedValueToJson(value))
        val field = path.substringBefore(".").substringBefore("[")
        return try {
            fhirJson.decodeFromJsonElement(Resource.serializer(), written)
            "decodes      \"$field\": ${written[field]}"
        } catch (e: Exception) {
            "DOES NOT DECODE  \"$field\": ${written[field]}\n      ${e.message?.lineSequence()?.first()}"
        }
    }

    /** The same writes, with the paths rewritten to the contract in `DynamicValueApplier`'s KDoc, by hand. */
    private val writesInContract =
        listOf(
            Triple(communicationRequest, "category[0].coding[0]", alert),
            Triple(communicationRequest, "priority", Code(value = "routine")),
            Triple(communicationRequest, "payload[0].contentString", "Guidance text"),
            Triple(medicationRequest, "medicationCodeableConcept", bcg),
        )

    @Test
    fun whoPathsInContract() {
        for ((resource, path, value) in writesInContract) {
            val type = if (resource === communicationRequest) "CommunicationRequest" else "MedicationRequest"
            println("in contract: $type.$path: ${writeAndDecode(resource, path, value)}")
        }
    }

    @Test
    fun whoPaths() {
        for ((resource, path, value) in writes) {
            val type = if (resource === communicationRequest) "CommunicationRequest" else "MedicationRequest"
            println("$type.$path: ${writeAndDecode(resource, path, value)}")
        }
    }
}
