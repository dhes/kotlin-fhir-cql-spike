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
        birthDate: String? = null,
    ): WhoScenario {
        val bundle = json.parseToJsonElement(WhoSuite.bundleJson(base)).jsonObject
        val original =
            bundle.getValue("entry").jsonArray.map { entry ->
                val resource = entry.jsonObject.getValue("resource").jsonObject
                if (birthDate != null && resource.getValue("resourceType").jsonPrimitive.content == "Patient") {
                    JsonObject(entry.jsonObject + ("resource" to JsonObject(resource + ("birthDate" to JsonPrimitive(birthDate)))))
                } else {
                    entry
                }
            }
        val entries = original + extra.map { JsonObject(mapOf("resource" to it)) }
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

    /** A completed dose; [primarySeries] adds the `protocolApplied.series` WHO's logic counts primary-series doses by. */
    private fun immunization(
        patient: String,
        system: String,
        code: String,
        display: String,
        date: String,
        primarySeries: Boolean = false,
        alsoCodedAs: Triple<String, String, String>? = null,
        series: String? = if (primarySeries) "Primary series" else null,
    ) =
        buildJsonObject {
            if (series != null) {
                putJsonArray("protocolApplied") {
                    add(buildJsonObject { put("series", series); put("doseNumberString", "1") })
                }
            }
            put("resourceType", "Immunization")
            put("id", "added-$code-$date")
            put("status", "completed")
            putJsonObject("vaccineCode") {
                putJsonArray("coding") {
                    add(buildJsonObject { put("system", system); put("code", code); put("display", display) })
                    alsoCodedAs?.let { (s, c, d) -> add(buildJsonObject { put("system", s); put("code", c); put("display", d) }) }
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

    /** Cases prompted by WHO's "Table 2: Summary of WHO Position Papers" (updated December 2025). */
    @Test
    fun oralPolioBeforeMeaslesAndLateStarters() = runTest(timeout = 30.minutes) {
        println("=== 4. Oral polio vaccine two weeks before a measles or yellow fever visit")
        val icd = "http://id.who.int/icd/release/11/mms"
        val opv = "Poliomyelitis oral, bivalent, live attenuated"
        val measles = base("IMMZD2DTMeaslesOngoingTransmission/Measles09.3")
        show("The child of ${measles.id}, plus $opv given 2025-10-29", variant(measles, "Measles09.3-opv-14d", extra = listOf(immunization(measles.patient, icd, "XM79H3", opv, "2025-10-29"))))
        val yellow = base("IMMZD2DTYellowfeverYellowFever/Yellowfever09.3")
        show("The child of ${yellow.id}, plus $opv given 2025-11-03", variant(yellow, "Yellowfever09.3-opv-14d", extra = listOf(immunization(yellow.patient, icd, "XM79H3", opv, "2025-11-03"))))

        println("=== 5. Measles schedules for a child who starts late (born 2023-11-12, aged 2 on 2025-11-12)")
        val mcv = "Measles, combinations with rubella, live attenuated"
        for (id in listOf("IMMZD18SMeaslesLowTransmission/Measles23.3", "IMMZD18SMeaslesOngoingTransmission/Measles09.3")) {
            val scenario = base(id)
            show("${scenario.planDefinition}: no measles dose", variant(scenario, "${scenario.patient}-late-none", birthDate = "2023-11-12"))
            show("${scenario.planDefinition}: first dose given today, 2025-11-12, recorded without protocolApplied",
                variant(scenario, "${scenario.patient}-late-mcv1-bare", birthDate = "2023-11-12", extra = listOf(immunization(scenario.patient, icd, "XM21H2", mcv, "2025-11-12"))))
            show("${scenario.planDefinition}: first dose given today, 2025-11-12, recorded as a primary-series dose",
                variant(scenario, "${scenario.patient}-late-mcv1", birthDate = "2023-11-12", extra = listOf(immunization(scenario.patient, icd, "XM21H2", mcv, "2025-11-12", primarySeries = true))))
        }
    }

    /** A measles visit with other doses recorded the same day: which must still block, and which need not. */
    @Test
    fun sameDayDosesAtAMeaslesVisit() = runTest(timeout = 30.minutes) {
        println("=== 6. Same-day doses at a measles visit (child born 2024-07-12, aged 16 months on 2025-11-12)")
        val icd = "http://id.who.int/icd/release/11/mms"
        val mr = "Measles, combinations with rubella, live attenuated"
        val yf = "Yellow fever, live attenuated"
        val scenario = base("IMMZD2DTMeaslesOngoingTransmission/Measles09.3")
        fun mcv1(date: String) = immunization(scenario.patient, icd, "XM21H2", mr, date, primarySeries = true)
        fun yellowFever(date: String) = immunization(scenario.patient, icd, "XM3418", yf, date)
        val cases =
            listOf(
                "no measles dose" to emptyList(),
                "no measles dose, yellow fever given today" to listOf(yellowFever("2025-11-12")),
                "no measles dose, yellow fever given yesterday" to listOf(yellowFever("2025-11-11")),
                "MCV1 given today" to listOf(mcv1("2025-11-12")),
                "MCV1 and yellow fever given today" to listOf(mcv1("2025-11-12"), yellowFever("2025-11-12")),
                "MCV1 given 27 days ago" to listOf(mcv1("2025-10-16")),
                "MCV1 given 28 days ago" to listOf(mcv1("2025-10-15")),
                "MCV1 given 28 days ago, yellow fever given today" to listOf(mcv1("2025-10-15"), yellowFever("2025-11-12")),
            )
        cases.forEachIndexed { index, (title, doses) ->
            val changed = variant(scenario, "${scenario.patient}-sameday-$index", birthDate = "2024-07-12", extra = doses)
            show(title, changed)
            print(cql(changed, "IMMZD2DTMeaslesOngoingTransmissionLogic", "Client is due for MCV1", "Client is due for MCV2"))
        }
    }

    /** The same-day question at yellow fever, rubella and varicella visits. */
    @Test
    fun sameDayDosesAtOtherLiveVaccineVisits() = runTest(timeout = 30.minutes) {
        println("=== 7. Same-day doses at yellow fever, rubella and varicella visits")
        val icd = "http://id.who.int/icd/release/11/mms"
        data class Dose(val code: String, val display: String, val date: String, val primary: Boolean = false)
        data class Visit(val base: String, val library: String, val due: String, val cases: List<Pair<String, List<Dose>>>)
        fun mr(date: String, primary: Boolean = false) = Dose("XM21H2", "Measles, combinations with rubella, live attenuated", date, primary)
        fun yf(date: String, primary: Boolean = false) = Dose("XM3418", "Yellow fever, live attenuated", date, primary)
        fun varicella(date: String) = Dose("XM0NS8", "Varicella, live attenuated", date, true)
        val visits =
            listOf(
                Visit("IMMZD2DTYellowfeverYellowFever/Yellowfever09.3", "IMMZD2DTYellowfeverYellowFeverLogic", "Client is due for yellow fever vaccination",
                    listOf(
                        "nothing added" to emptyList(),
                        "measles-rubella given today" to listOf(mr("2025-11-17")),
                        "measles-rubella given yesterday" to listOf(mr("2025-11-16")),
                        "yellow fever given today" to listOf(yf("2025-11-17", primary = true)),
                    )),
                Visit("IMMZD2DTRubellaHighIncidence/Rubella09.3", "IMMZD2DTRubellaHighIncidenceLogic", "Client is due for rubella vaccination",
                    listOf(
                        "nothing added" to emptyList(),
                        "yellow fever given today" to listOf(yf("2025-11-13")),
                        "yellow fever given yesterday" to listOf(yf("2025-11-12")),
                        "measles-rubella given today" to listOf(mr("2025-11-13", primary = true)),
                    )),
                Visit("IMMZD2DTVaricella2Doses/Varicella23.3", "IMMZD2DTVaricella2DosesLogic", "Client is due for varicella vaccination",
                    listOf(
                        "nothing added" to emptyList(),
                        "measles-rubella given today" to listOf(mr("2025-11-25")),
                        "measles-rubella given yesterday" to listOf(mr("2025-11-24")),
                        "first varicella dose given today" to listOf(varicella("2025-11-25")),
                        "first varicella dose given 29 days ago" to listOf(varicella("2025-10-27")),
                        "first varicella dose given 29 days ago, measles-rubella given today" to listOf(varicella("2025-10-27"), mr("2025-11-25")),
                        "yellow fever given today" to listOf(yf("2025-11-25")),
                        "yellow fever given yesterday" to listOf(yf("2025-11-24")),
                    )),
            )
        for (visit in visits) {
            val scenario = base(visit.base)
            visit.cases.forEachIndexed { index, (title, doses) ->
                val extra = doses.map { immunization(scenario.patient, icd, it.code, it.display, it.date, it.primary) }
                val changed = variant(scenario, "${scenario.patient}-otherlive-$index", extra = extra)
                show("${scenario.planDefinition}: $title", changed)
                print(cql(changed, visit.library, visit.due))
            }
        }
    }

    /** Whether the interval between MCV1 and MCV2 holds when MCV1 was recorded with a code outside the live set. */
    @Test
    fun measlesDoseRecordedWithAGeneralCode() = runTest(timeout = 30.minutes) {
        println("=== 8. MCV1 ten days ago, recorded with a specific or a general code (child aged 16 months on 2025-11-12)")
        val icd = "http://id.who.int/icd/release/11/mms"
        val scenario = base("IMMZD2DTMeaslesOngoingTransmission/Measles09.3")
        val codes = listOf("XM21H2" to "Measles, combinations with rubella, live attenuated", "XM28X5" to "Measles vaccines")
        for ((code, display) in codes) {
            val changed =
                variant(scenario, "${scenario.patient}-code-$code", birthDate = "2024-07-12",
                    extra = listOf(immunization(scenario.patient, icd, code, display, "2025-11-02", primarySeries = true)))
            show("MCV1 recorded as $display ($code)", changed)
            print(cql(changed, "IMMZD2DTMeaslesOngoingTransmissionLogic", "Client is due for MCV1", "Client is due for MCV2"))
        }
    }

    /** Whether a dose recorded with a WHO prequalification product identifier counts, alone or beside an ICD-11 code. */
    @Test
    fun measlesDoseRecordedWithAPrequalificationProductCode() = runTest(timeout = 30.minutes) {
        println("=== 9. MCV1 ten days ago, recorded with a WHO PreQual product identifier (child aged 16 months on 2025-11-12)")
        val prequal = "http://smart.who.int/pcmt-vaxprequal/CodeSystem/PreQualProductIds"
        val product = "a3K3X000006MiiEUAS"
        val productName = "Measles and Rubella Vaccine Live Attenuated (MR), Serum Institute of India, Vial, 1 dose [FVP-P-137]"
        val icd = Triple("http://id.who.int/icd/release/11/mms", "XM21H2", "Measles, combinations with rubella, live attenuated")
        val scenario = base("IMMZD2DTMeaslesOngoingTransmission/Measles09.3")
        val cases =
            listOf(
                "the product identifier only" to immunization(scenario.patient, prequal, product, productName, "2025-11-02", primarySeries = true),
                "the product identifier and ICD-11 XM21H2" to
                    immunization(scenario.patient, prequal, product, productName, "2025-11-02", primarySeries = true, alsoCodedAs = icd),
            )
        cases.forEachIndexed { index, (title, dose) ->
            val changed = variant(scenario, "${scenario.patient}-prequal-$index", birthDate = "2024-07-12", extra = listOf(dose))
            show("MCV1 recorded with $title", changed)
            print(cql(changed, "IMMZD2DTMeaslesOngoingTransmissionLogic", "Client is due for MCV1", "Client is due for MCV2"))
        }
    }

    /** One 9-month visit through the measles, rubella and yellow fever tables, as doses are recorded by product. */
    @Test
    fun nineMonthVisitThroughThreeTables() = runTest(timeout = 30.minutes) {
        println("=== 10. A 9-month visit on 2025-11-12 through three decision tables, doses recorded by product")
        val prequal = "http://smart.who.int/pcmt-vaxprequal/CodeSystem/PreQualProductIds"
        val icd = "http://id.who.int/icd/release/11/mms"
        val scenario = base("IMMZD2DTMeaslesOngoingTransmission/Measles09.3")
        val mr =
            immunization(scenario.patient, prequal, "a3K3X000006MiimUAC",
                "Measles and Rubella Vaccine Live Attenuated (MR), Serum Institute of India, Vial, 10 doses [FVP-P-140]", "2025-11-12",
                primarySeries = true, alsoCodedAs = Triple(icd, "XM21H2", "Measles, combinations with rubella, live attenuated"))
        val yf =
            immunization(scenario.patient, prequal, "a3K3X000006MihwUAC",
                "Stabilized Yellow Fever Vaccine (YF), Institut Pasteur de Dakar (IPD), Ampoule, 10 doses [FVP-P-66]", "2025-11-12",
                primarySeries = true, alsoCodedAs = Triple(icd, "XM3418", "Yellow fever, live attenuated"))
        val steps = listOf("nothing recorded yet" to emptyList(), "MR recorded" to listOf(mr), "MR and yellow fever recorded" to listOf(mr, yf))
        val tables = listOf("IMMZD2DTMeaslesOngoingTransmission", "IMMZD2DTRubellaHighIncidence", "IMMZD2DTYellowfeverYellowFever")
        steps.forEachIndexed { index, (title, doses) ->
            for (table in tables) {
                show("$title; $table", variant(scenario, "${scenario.patient}-visit-$index-$table", plan = table, extra = doses))
            }
        }
    }

    @Test
    fun rubellaTablesAfterTwoMeaslesRubellaDoses() = runTest(timeout = 30.minutes) {
        println("=== 11. The rubella tables for a child aged 15 months on 2025-11-13, after one and after two measles-rubella doses")
        val icd = "http://id.who.int/icd/release/11/mms"
        val scenario = base("IMMZD2DTRubellaHighIncidence/Rubella09.3")
        fun mr(date: String) = immunization(scenario.patient, icd, "XM21H2", "Measles, combinations with rubella, live attenuated", date, primarySeries = true)
        val steps =
            listOf(
                "one dose, at 9 months" to listOf(mr("2025-05-13")),
                "two doses, at 9 months and 6 weeks ago" to listOf(mr("2025-05-13"), mr("2025-10-02")),
                "two doses, at 9 months and today" to listOf(mr("2025-05-13"), mr("2025-11-13")),
            )
        for (table in listOf("IMMZD2DTRubellaHighIncidence", "IMMZD2DTRubellaLowIncidence")) {
            steps.forEachIndexed { index, (title, doses) ->
                val variant = variant(scenario, "${scenario.patient}-two-doses-$index-$table", plan = table, extra = doses, birthDate = "2024-08-13")
                show("$table: $title", variant)
                print(cql(variant, "${table}Logic", "Rubella immunization schedule is complete", "Client is due for rubella vaccination", "Client is not due for rubella vaccination"))
            }
        }
    }

    /** WHO's three tables for the vaccines given at birth, by the child's age and as doses are recorded. */
    @Test
    fun birthVisitThroughThreeTables() = runTest(timeout = 60.minutes) {
        val icd = "http://id.who.int/icd/release/11/mms"
        val scenario = base("IMMZD2DTBCG/BCG07.4")
        val tables = listOf("IMMZD2DTBCG", "IMMZD2DTHepatitisBBirthDose", "IMMZD2DTPolioBirthDose", "IMMZD2DTHepatitisBDelayedStart")
        println("=== 12. A child with nothing on record, seen on ${scenario.today}, through the birth-visit tables, by age")
        val born =
            listOf(0 to "2025-10-01", 1 to "2025-09-30", 6 to "2025-09-25", 7 to "2025-09-24", 28 to "2025-09-03", 29 to "2025-09-02",
                41 to "2025-08-21", 42 to "2025-08-20")
        for ((days, birthDate) in born) {
            for (table in tables) {
                show("aged $days days (born $birthDate); $table", variant(scenario, "${scenario.patient}-age-$days-$table", plan = table, birthDate = birthDate))
            }
        }

        println("=== 13. A child born ${scenario.today}, seen the same day, as the three birth doses are recorded")
        fun bcg(series: String? = "Primary series") = immunization(scenario.patient, icd, "XM8142", "Tuberculosis, live attenuated vaccines", scenario.today, series = series)
        fun hepB(series: String? = "Dose 0") = immunization(scenario.patient, icd, "XM9V38", "Hepatitis B, purified antigen", scenario.today, series = series)
        fun opv(series: String? = "Dose 0") = immunization(scenario.patient, icd, "XM79H3", "Poliomyelitis oral, bivalent, live attenuated", scenario.today, series = series)
        val steps =
            listOf(
                "BCG recorded" to listOf(bcg()),
                "oral polio recorded as dose 0" to listOf(opv()),
                "hepatitis B recorded as dose 0" to listOf(hepB()),
                "all three recorded" to listOf(bcg(), hepB(), opv()),
                "hepatitis B and oral polio recorded as primary-series doses" to listOf(hepB("Primary series"), opv("Primary series")),
                "hepatitis B and oral polio recorded with no series" to listOf(hepB(null), opv(null)),
            )
        steps.forEachIndexed { index, (title, doses) ->
            for (table in tables.take(3)) {
                show("$title; $table", variant(scenario, "${scenario.patient}-birth-$index-$table", plan = table, extra = doses, birthDate = scenario.today))
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
