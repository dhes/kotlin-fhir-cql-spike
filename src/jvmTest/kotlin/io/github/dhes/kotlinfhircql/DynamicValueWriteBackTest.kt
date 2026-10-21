package io.github.dhes.kotlinfhircql

import dev.ohs.fhir.model.r4.ActivityDefinition
import dev.ohs.fhir.model.r4.Canonical
import dev.ohs.fhir.model.r4.Code
import dev.ohs.fhir.model.r4.CodeableConcept
import dev.ohs.fhir.model.r4.Coding
import dev.ohs.fhir.model.r4.CommunicationRequest
import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.MedicationRequest
import dev.ohs.fhir.model.r4.Reference
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.String as FhirString
import dev.ohs.fhir.model.r4.Uri
import dev.ohs.fhir.model.r4.terminologies.MedicationRequestIntent
import dev.ohs.fhir.model.r4.terminologies.MedicationrequestStatus
import dev.ohs.fhir.model.r4.terminologies.RequestResourceType
import dev.ohs.fhir.model.r4.terminologies.RequestStatus
import dev.ohs.fhir.workflow.fhirJson
import dev.ohs.fhir.workflow.processor.DynamicValueApplier
import dev.ohs.fhir.workflow.processor.evaluatedValueToJson
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.hl7.elm.r1.VersionedIdentifier
import org.opencds.cqf.cql.engine.data.CompositeDataProvider
import org.opencds.cqf.cql.engine.execution.CqlEngine
import org.opencds.cqf.cql.engine.execution.Environment
import org.opencds.cqf.cql.engine.runtime.Date

/**
 * dynamicValue write-back: evaluates WHO's `text/cql-expression` dynamicValues from precompiled ELM (as in
 * [PrecompiledDynamicValueTest]), converts each CQL value to a kotlin-fhir type, and writes it into the request
 * resource with kotlin-fhir-workflow's own write-back code (`DynamicValueApplier`, `evaluatedValueToJson`, copied
 * unchanged from ohs-foundation/kotlin-fhir-workflow at 214c56d into `src/jvmTest/kotlin/dev/ohs/fhir/workflow`).
 * Reports, per dynamicValue path, whether the result decodes as a kotlin-fhir resource.
 *
 * Needs the cqframework build of [PrecompiledDynamicValueTest] (`-PcqlVersion=`).
 */
class DynamicValueWriteBackTest {
    /** CQL value to kotlin-fhir type: String to a Kotlin string, Code to Coding, Concept to CodeableConcept. */
    private fun toKotlinFhir(value: Any?): Any =
        when (value) {
            is org.opencds.cqf.cql.engine.runtime.String -> value.value
            is org.opencds.cqf.cql.engine.runtime.Code -> coding(value)
            is org.opencds.cqf.cql.engine.runtime.Concept ->
                CodeableConcept(
                    coding = value.codes.orEmpty().filterNotNull().map(::coding),
                    text = value.display?.let { FhirString(value = it) },
                )
            else -> error("No conversion for ${value?.let { it::class.simpleName }}")
        }

    private fun coding(code: org.opencds.cqf.cql.engine.runtime.Code) =
        Coding(
            system = code.system?.let { Uri(value = it) },
            version = code.version?.let { FhirString(value = it) },
            code = code.code?.let { Code(value = it) },
            display = code.display?.let { FhirString(value = it) },
        )

    /**
     * The request resource before dynamicValues, built as kotlin-fhir-workflow's
     * `PlanDefinitionProcessor.instantiateRequest` does at 214c56d for these two kinds.
     */
    private fun baseRequest(ad: ActivityDefinition, id: String, patient: String): Resource {
        val subject = Reference(reference = FhirString(value = "Patient/$patient"))
        val basedOn = Reference(reference = FhirString(value = "#plan"))
        return when (ad.kind?.value) {
            RequestResourceType.MedicationRequest ->
                MedicationRequest(
                    id = id,
                    instantiatesCanonical = listOf(Canonical(value = ad.url?.value)),
                    status = Enumeration(value = MedicationrequestStatus.Active),
                    intent = Enumeration(value = MedicationRequestIntent.Proposal),
                    medication =
                        when (val product = ad.product) {
                            is ActivityDefinition.Product.CodeableConcept -> MedicationRequest.Medication.CodeableConcept(product.value)
                            is ActivityDefinition.Product.Reference -> MedicationRequest.Medication.Reference(product.value)
                            null -> MedicationRequest.Medication.CodeableConcept(ad.code ?: CodeableConcept())
                        },
                    subject = subject,
                    basedOn = listOf(basedOn),
                    dosageInstruction = ad.dosage,
                )
            RequestResourceType.CommunicationRequest ->
                CommunicationRequest(
                    id = id,
                    status = Enumeration(value = RequestStatus.Active),
                    subject = subject,
                    basedOn = listOf(basedOn),
                )
            else -> error("Unexpected kind ${ad.kind?.value}")
        }
    }

    /**
     * Rewrites WHO's three non-conforming writes to `DynamicValueApplier`'s path contract, by hand for these paths
     * only: indexes on the lists in `category.coding`, the concrete name of the `medication` choice, and the code of
     * a CQL Code written to `priority` (a FHIR `code`).
     */
    private fun conform(path: String, value: JsonElement): Pair<String, JsonElement> =
        when (path) {
            "category.coding" -> "category[0].coding[0]" to value
            "medication" -> "medicationCodeableConcept" to value
            "priority" -> path to value.jsonObject.getValue("code")
            else -> path to value
        }

    private fun encode(resource: Resource) = fhirJson.encodeToJsonElement(Resource.serializer(), resource).jsonObject

    /** Decodes [json] as a kotlin-fhir resource; returns null on success, else the first line of the error. */
    private fun decodeError(json: JsonObject): String? =
        try {
            fhirJson.decodeFromJsonElement(Resource.serializer(), json)
            null
        } catch (e: Exception) {
            "${e::class.simpleName}: ${e.message?.lineSequence()?.first()?.take(160)}"
        }

    /** The part of [json] under the first segment of [path]. */
    private fun fragment(json: JsonObject, path: String): String {
        val field = path.substringBefore(".").substringBefore("[")
        return "\"$field\": ${json[field]}".take(220)
    }

    @Test
    fun dynamicValueWriteBack() {
        whoPackageDir()
        val dynamicValues = WhoDynamicValues.all
        val wrappers = dynamicValues.associate { it.wrapper to WhoDynamicValues.wrapperCql(it) }
        val elm = WhoDynamicValues.compileToElmJson(wrappers)
        val loader = WhoDynamicValues.Loader(elm, emptyMap())
        val modelResolver = KotlinFhirModelResolver(loader.modelManager.resolveModel("FHIR", "4.0.1"))
        val terminology = InMemoryTerminologyProvider(WhoSuite.valueSets)
        val activityDefinitions =
            WhoDynamicValues.activityDefinitions.mapValues { (_, json) ->
                fhirJson.decodeFromJsonElement(Resource.serializer(), json) as ActivityDefinition
            }

        class PathResult(var applied: Int = 0, var decoded: Int = 0, var error: String? = null, var written: String? = null)
        val byPath = sortedMapOf<String, PathResult>()
        var actions = 0
        var wholeDecoded = 0
        val wholeErrors = mutableMapOf<String, Int>()
        var example: String? = null
        var conformedDecoded = 0
        val conformedErrors = mutableMapOf<String, Int>()
        var conformedExample: Resource? = null
        val groups = dynamicValues.groupBy { it.planDefinition to it.action }

        for ((key, dvs) in groups) {
            val (planDefinition, action) = key
            // The first scenario of the PlanDefinition whose bundle loads (Typhoid41.1's does not; see Findings).
            val (scenario, resources) =
                WhoSuite.scenarios.asSequence().filter { it.planDefinition == planDefinition }.mapNotNull { sc ->
                    try { sc to WhoSuite.kotlinFhirResources(sc) } catch (e: Exception) { null }
                }.firstOrNull() ?: continue
            val ad = activityDefinitions.getValue(dvs.first().activityDefinition!!)

            // Evaluate the action's dynamicValues from ELM, for that scenario.
            val (y, m, d) = scenario.today.split("-").map(String::toInt)
            val environment = Environment(loader.libraryManager, null, terminology)
            environment.registerDataProvider(
                FHIR_MODEL_URI,
                CompositeDataProvider(modelResolver, InMemoryRetrieveProvider(resources, terminology)),
            )
            val results =
                CqlEngine(environment).evaluate {
                    dvs.forEach { library(it.wrapper) { expressions("return") } }
                    contextParameter = "Patient" to scenario.patient
                    parameters = mapOf("Today" to Date(y, m, d))
                    evaluationDateTime = WhoSuite.evaluationDateTime(scenario)
                }
            val values: List<Pair<WhoDynamicValue, JsonElement>> =
                dvs.map { dv ->
                    val id = VersionedIdentifier().apply { id = dv.wrapper }
                    results.getExceptionFor(id)?.let { throw it }
                    val value = results.getResultFor(id)!!.expressionResults.getValue("return").value
                    dv to evaluatedValueToJson(toKotlinFhir(value))
                }

            // Each dynamicValue on its own, then all of the action's together, as $apply would write them.
            val base = encode(baseRequest(ad, "$planDefinition-$action", scenario.patient))
            values.forEach { (dv, value) ->
                val json = DynamicValueApplier.set(base, dv.path, value)
                val result = byPath.getOrPut("${ad.kind?.value?.code}.${dv.path}") { PathResult() }
                result.applied++
                val error = decodeError(json)
                if (error == null) result.decoded++ else result.error = result.error ?: error
                result.written = result.written ?: fragment(json, dv.path)
            }
            val whole = values.fold(base) { json, (dv, value) -> DynamicValueApplier.set(json, dv.path, value) }
            actions++
            when (val error = decodeError(whole)) {
                null -> { wholeDecoded++; example = example ?: whole.toString() }
                else -> wholeErrors.merge(error.take(90), 1, Int::plus)
            }
            val conformed =
                values.fold(base) { json, (dv, value) ->
                    val (path, v) = conform(dv.path, value)
                    DynamicValueApplier.set(json, path, v)
                }
            when (val error = decodeError(conformed)) {
                null -> {
                    conformedDecoded++
                    val resource = fhirJson.decodeFromJsonElement(Resource.serializer(), conformed)
                    if (conformedExample == null || resource is MedicationRequest && conformedExample !is MedicationRequest) {
                        if (conformedExample == null) conformedExample = resource
                        else println("Conformed CommunicationRequest: ${encode(conformedExample!!)}")
                        conformedExample = resource
                    }
                }
                else -> conformedErrors.merge(error.take(90), 1, Int::plus)
            }
        }

        println("$actions of ${groups.size} actions with text/cql-expression dynamicValues (any missing have no scenario whose bundle loads); per path (resource type.path):")
        byPath.forEach { (path, r) ->
            println("  $path: ${r.decoded}/${r.applied} decode")
            println("    written: ${r.written}")
            r.error?.let { println("    error: $it") }
        }
        println("Whole request resources: $wholeDecoded/$actions decode; errors: $wholeErrors")
        example?.let { println("Example: ${it.take(600)}") }
        println("With WHO's three writes rewritten to the path contract: $conformedDecoded/$actions decode; errors: $conformedErrors")
        conformedExample?.let { println("Conformed ${it::class.simpleName}: ${encode(it)}") }
        assertTrue(actions > 0)
    }
}
