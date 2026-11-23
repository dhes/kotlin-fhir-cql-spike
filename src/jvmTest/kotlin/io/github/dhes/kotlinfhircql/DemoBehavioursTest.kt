package io.github.dhes.kotlinfhircql

import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.hl7.fhir.r4.model.RequestGroup
import org.opencds.cqf.cql.engine.runtime.Date

/**
 * Three behaviours of WHO's immunization content (smart-immunizations 1.0.0) that the full-stack demo note of
 * 2026-11-19 read in the CQL and did not run. Each case is applied twice: by clinical-reasoning's `$apply`
 * (cqf-fhir-cr) and by kotlin-fhir-workflow's (214c56d, PlanDefinitions as [WhoApply.conform] rewrites them).
 * Prints only; it asserts nothing.
 */
class DemoBehavioursTest {
    private val json = MeaslesFixtures.json
    private val dir = java.io.File(PROJECT_DIR, "build/demo-behaviours").apply { mkdirs() }

    private fun base(id: String) = WhoSuite.scenarios.single { it.id == id }

    /** [base] with [extra] resources added to its bundle, applied to [plan]. */
    private fun variant(
        base: WhoScenario,
        label: String,
        plan: String = base.planDefinition,
        extra: List<JsonObject> = emptyList(),
    ): WhoScenario {
        val bundle = json.parseToJsonElement(WhoSuite.bundleJson(base)).jsonObject
        val entries = bundle.getValue("entry").jsonArray + extra.map { JsonObject(mapOf("resource" to it)) }
        dir.resolve("$label.json").writeText(JsonObject(bundle + ("entry" to JsonArray(entries))).toString())
        return base.copy(
            id = label,
            planDefinition = plan,
            bundle = "../../../build/demo-behaviours/$label.json",
            expectedGuidance = null,
            expectedContained = null,
            twinOf = null,
        )
    }

    private fun immunization(patient: String, system: String, code: String, display: String, date: String) =
        buildJsonObject {
            put("resourceType", "Immunization")
            put("id", "added-$code-$date")
            put("status", "completed")
            putJsonObject("vaccineCode") {
                putJsonArray("coding") {
                    add(buildJsonObject { put("system", system); put("code", code); put("display", display) })
                }
            }
            putJsonObject("patient") { put("reference", "Patient/$patient") }
            put("occurrenceDateTime", date)
        }

    private fun summarize(resource: JsonObject): String =
        when (val type = resource.getValue("resourceType").jsonPrimitive.content) {
            "CommunicationRequest" ->
                "CommunicationRequest: " +
                    resource["payload"]?.jsonArray?.firstOrNull()?.jsonObject?.get("contentString")?.jsonPrimitive?.content
                        ?.replace("\n", " / ")
            "MedicationRequest" -> "MedicationRequest: " + JsonObject(resource - "resourceType" - "meta" - "text")
            else -> type
        }

    private data class Applied(val cr: List<JsonObject>, val kfw: List<JsonObject>, val text: String)

    /** The requests each route returns for [scenario], RequestGroup left out. */
    private suspend fun apply(scenario: WhoScenario): Applied {
        val parser = ClinicalReasoningApply.context.newJsonParser()
        val cr = ClinicalReasoningApply.apply(scenario, roundTrip = false)
        val crRequests =
            cr.carePlan?.contained.orEmpty().distinctBy { System.identityHashCode(it) }.filter { it !is RequestGroup }
                .map { json.parseToJsonElement(parser.encodeResourceToString(it)).jsonObject }
        val kfw = WhoApply.apply(scenario)
        val kfwRequests =
            kfw.carePlan?.let { plan ->
                json.encodeToJsonElement(dev.ohs.fhir.model.r4.Resource.serializer(), plan).jsonObject["contained"]
                    ?.jsonArray.orEmpty().map { it.jsonObject }
                    .filter { it.getValue("resourceType").jsonPrimitive.content != "RequestGroup" }
            }.orEmpty()
        val a = cr.error?.let { listOf("ERROR $it") } ?: crRequests.map(::summarize)
        val b = kfw.error?.let { listOf("ERROR $it") } ?: kfwRequests.map(::summarize)
        val text =
            "    clinical-reasoning (${a.size}):\n" + a.joinToString("") { "      $it\n" } +
                "    kotlin-fhir-workflow (${b.size}):\n" + b.joinToString("") { "      $it\n" }
        return Applied(crRequests, kfwRequests, text)
    }

    private fun cql(scenario: WhoScenario, library: String, vararg names: String): String {
        val (y, m, d) = scenario.today.split("-").map(String::toInt)
        val results =
            WhoApply.evaluator.first.evaluate(
                library,
                scenario.patient,
                WhoSuite.kotlinFhirResources(scenario),
                null,
                mapOf("Today" to Date(y, m, d)),
                WhoSuite.evaluationDateTime(scenario),
            )
        return names.joinToString("") { "    CQL $library.\"$it\" = ${MeaslesFixtures.render(results[it])}\n" }
    }

    private suspend fun show(title: String, scenario: WhoScenario, whoExpects: Int? = null): Applied {
        val applied = apply(scenario)
        println("--- $title")
        println("    ${scenario.planDefinition}, patient ${scenario.patient}, Today ${scenario.today}" +
            (whoExpects?.let { "; WHO's test expects ${it - 1} request(s)" } ?: ""))
        print(applied.text)
        return applied
    }

    @Test
    fun scheduleListsSecondDoseBeforeFirst() = runTest(timeout = 30.minutes) {
        println("=== 1. Measles schedule for a child with no measles dose")
        for (id in listOf(
            "IMMZD18SMeaslesLowTransmission/Measles22.1",
            "IMMZD18SMeaslesLowTransmission/Measles23.3",
            "IMMZD18SMeaslesLowTransmission/Measles25.2",
            "IMMZD18SMeaslesOngoingTransmission/Measles09.3",
        )) {
            val scenario = base(id)
            show("WHO's scenario ${scenario.id}: ${scenario.scenario}", scenario, scenario.expectedContained)
        }
    }

    @Test
    fun liveVaccineRecordedTheSameDay() = runTest(timeout = 30.minutes) {
        println("=== 2. A live vaccine recorded today, then the decision table for another live vaccine applied again")
        val icd = "http://id.who.int/icd/release/11/mms"
        data class Case(val base: String, val library: String, val due: String, val doses: List<Triple<String, String, String>>)
        val cases =
            listOf(
                Case("IMMZD2DTRotavirus/Rotavirus08.3", "IMMZD2DTRotavirusLogic", "Client is due for rotavirus vaccination",
                    listOf(
                        Triple("XM79H3", "Poliomyelitis oral, bivalent, live attenuated", "2025-11-11"),
                        Triple("XM79H3", "Poliomyelitis oral, bivalent, live attenuated", "2025-10-15"),
                        Triple("XM79H3", "Poliomyelitis oral, bivalent, live attenuated", "2025-10-14"),
                        Triple("XM7JP3", "DTP-HepB-Hib (not in the live set)", "2025-11-11"),
                    )),
                Case("IMMZD2DTYellowfeverYellowFever/Yellowfever09.3", "IMMZD2DTYellowfeverYellowFeverLogic",
                    "Client is due for yellow fever vaccination",
                    listOf(Triple("XM21H2", "Measles, combinations with rubella, live attenuated", "2025-11-17"))),
                Case("IMMZD2DTMeaslesOngoingTransmission/Measles09.3", "IMMZD2DTMeaslesOngoingTransmissionLogic",
                    "Client is due for MCV1",
                    listOf(Triple("XM3418", "Yellow fever, live attenuated", "2025-11-12"))),
                Case("IMMZD2DTBCG/BCG07.4", "IMMZD2DTBCGLogic", "Client is due for BCG vaccination",
                    listOf(Triple("XM79H3", "Poliomyelitis oral, bivalent, live attenuated", "2025-10-01"))),
            )
        for (case in cases) {
            val scenario = base(case.base)
            show("As WHO's scenario ${scenario.id}", scenario, scenario.expectedContained)
            for ((code, display, date) in case.doses) {
                val changed =
                    variant(scenario, "${scenario.patient}-$code-$date", extra = listOf(immunization(scenario.patient, icd, code, display, date)))
                show("The same child, plus $display ($code) given $date", changed)
                try {
                    print(cql(changed, case.library, case.due))
                } catch (e: Throwable) {
                    println("    CQL ${case.library}: ${e::class.simpleName}: ${e.message}")
                }
            }
        }
    }

    @Test
    fun contraindicationTableNeedsADatedProposal() = runTest(timeout = 30.minutes) {
        println("=== 3. The measles decision table's proposal, stored, then the measles contraindication table applied")
        val child = base("IMMZD2DTMeaslesLowTransmission/Measles23.3")
        val proposed = show("Decision table, WHO's scenario ${child.id}", child, child.expectedContained)
        val observation =
            buildJsonObject {
                put("resourceType", "Observation")
                put("id", "immunosuppressed-${child.patient}")
                put("status", "final")
                putJsonObject("code") {
                    putJsonArray("coding") {
                        add(buildJsonObject {
                            put("system", "http://smart.who.int/immunizations/CodeSystem/IMMZ.D")
                            put("code", "DE161")
                            put("display", "Potential contraindications")
                        })
                    }
                }
                putJsonObject("subject") { put("reference", "Patient/${child.patient}") }
                put("effectiveDateTime", "2025-06-12")
                putJsonObject("valueCodeableConcept") {
                    putJsonArray("coding") {
                        add(buildJsonObject {
                            put("system", "http://smart.who.int/immunizations/CodeSystem/IMMZ.D")
                            put("code", "DE165")
                            put("display", "Severely immunosuppressed")
                        })
                    }
                }
            }
        for ((route, requests) in listOf("clinical-reasoning" to proposed.cr, "kotlin-fhir-workflow" to proposed.kfw)) {
            val proposal = requests.firstOrNull { it.getValue("resourceType").jsonPrimitive.content == "MedicationRequest" }
            if (proposal == null) {
                println("--- $route returned no MedicationRequest; nothing to store")
                continue
            }
            val tag = route.substringBefore("-")
            val stored = JsonObject(proposal + ("id" to JsonPrimitive("stored-proposal")))
            val stamped = JsonObject(stored + ("authoredOn" to JsonPrimitive(child.today)))
            for ((what, request) in listOf("as $route returned it" to stored, "with authoredOn ${child.today} added" to stamped)) {
                val label = "chain-$tag-${if (request === stored) "asis" else "stamped"}"
                val scenario = variant(child, label, plan = "IMMZD5DTMeaslesContraindications", extra = listOf(observation, request))
                show("Contraindication table; stored proposal (id stored-proposal) $what; child severely immunosuppressed", scenario)
                try {
                    print(cql(scenario, "IMMZD5DTMeaslesContraindicationsLogic", "Draft Medication Request ID for Measles dose", "Measles vaccination contraindicated"))
                } catch (e: Throwable) {
                    println("    CQL: ${e::class.simpleName}: ${e.message}")
                }
            }
        }
    }
}
