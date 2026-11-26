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
        dose: Int = 1,
        seriesDoses: Int? = null,
        brand: String? = null,
        numbersAsIntegers: Boolean = false,
        typeOfDose: Pair<String, String>? = null,
    ) =
        buildJsonObject {
            if (brand != null || typeOfDose != null) {
                putJsonArray("extension") {
                    if (brand != null) add(buildJsonObject {
                        put("url", "http://smart.who.int/immunizations/StructureDefinition/IMMZVaccineBrand")
                        putJsonObject("valueCodeableConcept") {
                            putJsonArray("coding") { add(buildJsonObject { put("system", "http://example.org/Brands"); put("code", brand); put("display", brand) }) }
                        }
                    })
                    if (typeOfDose != null) add(buildJsonObject {
                        put("url", "http://smart.who.int/immunizations/StructureDefinition/IMMZTypeOfDose")
                        putJsonObject("valueCodeableConcept") {
                            putJsonArray("coding") {
                                add(buildJsonObject { put("system", "http://smart.who.int/immunizations/CodeSystem/IMMZ.D"); put("code", typeOfDose.first); put("display", typeOfDose.second) })
                            }
                        }
                    })
                }
            }
            if (series != null) {
                putJsonArray("protocolApplied") {
                    add(buildJsonObject {
                        put("series", series)
                        if (numbersAsIntegers) put("doseNumberPositiveInt", dose) else put("doseNumberString", dose.toString())
                        if (seriesDoses != null) {
                            if (numbersAsIntegers) put("seriesDosesPositiveInt", seriesDoses) else put("seriesDosesString", seriesDoses.toString())
                        }
                    })
                }
            }
            put("resourceType", "Immunization")
            put("id", "added-$code-$date-${series?.filter(Char::isLetterOrDigit)}-$dose")
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

    /** The BCG table for a child with no BCG dose, older than a newborn: by age, with other doses, and with results on record. */
    @Test
    fun bcgForAChildWhoMissedItAtBirth() = runTest(timeout = 60.minutes) {
        val icd = "http://id.who.int/icd/release/11/mms"
        val scenario = base("IMMZD2DTBCG/BCG07.4")
        val library = "IMMZD2DTBCGLogic"
        val outcomes = arrayOf("Client is due for BCG vaccination", "Client is not due for BCG vaccination", "Clinical judgement is required. Create clinical note.", "BCG immunization schedule is complete")
        suspend fun case(title: String, label: String, birthDate: String, extra: List<JsonObject> = emptyList()) {
            val variant = variant(scenario, "${scenario.patient}-late-bcg-$label", extra = extra, birthDate = birthDate)
            show(title, variant)
            print(cql(variant, library, *outcomes))
        }
        /** The observations of one of WHO's BCG test patients, moved to this child. */
        fun observationsOf(id: String): List<JsonObject> =
            json.parseToJsonElement(WhoSuite.bundleJson(base(id))).jsonObject.getValue("entry").jsonArray
                .map { it.jsonObject.getValue("resource").jsonObject }
                .filter { it.getValue("resourceType").jsonPrimitive.content == "Observation" }
                .map { JsonObject(it + ("subject" to buildJsonObject { put("reference", "Patient/${scenario.patient}") })) }
        fun rotavirus(date: String) = immunization(scenario.patient, icd, "XM4GV0", "Rota virus, live attenuated", date, primarySeries = true)
        fun mr(date: String) = immunization(scenario.patient, icd, "XM21H2", "Measles, combinations with rubella, live attenuated", date, primarySeries = true)
        fun yf(date: String) = immunization(scenario.patient, icd, "XM3418", "Yellow fever, live attenuated", date, primarySeries = true)
        fun opv(date: String) = immunization(scenario.patient, icd, "XM79H3", "Poliomyelitis oral, bivalent, live attenuated", date, primarySeries = true)
        fun bcg(date: String) = immunization(scenario.patient, icd, "XM8142", "Tuberculosis, live attenuated vaccines", date, primarySeries = true)

        println("=== 14. BCG for a child with nothing on record, seen on ${scenario.today}, by age")
        for ((age, birthDate) in listOf(
            "28 days" to "2025-09-03", "29 days" to "2025-09-02", "6 weeks" to "2025-08-20", "6 months" to "2025-04-01",
            "11 months and 29 days" to "2024-10-02", "12 months" to "2024-10-01", "13 months" to "2024-09-01", "4 years 11 months" to "2020-10-02", "5 years" to "2020-10-01",
        )) {
            case("aged $age (born $birthDate)", "age-$birthDate", birthDate)
        }

        println("=== 15. BCG for a child aged 6 months (born 2025-04-01) with no BCG dose, other live vaccines on record")
        val born = "2025-04-01"
        case("rotavirus given today", "rota-today", born, listOf(rotavirus("2025-10-01")))
        case("rotavirus given yesterday", "rota-yesterday", born, listOf(rotavirus("2025-09-30")))
        case("rotavirus given 27 days ago", "rota-27d", born, listOf(rotavirus("2025-09-04")))
        case("rotavirus given 28 days ago", "rota-28d", born, listOf(rotavirus("2025-09-03")))
        case("oral polio given yesterday", "opv-yesterday", born, listOf(opv("2025-09-30")))
        case("measles-rubella and yellow fever given today (child aged 9 months, born 2025-01-01)", "mr-yf-today", "2025-01-01", listOf(mr("2025-10-01"), yf("2025-10-01")))
        case("BCG given today", "bcg-today", born, listOf(bcg("2025-10-01")))

        println("=== 16. BCG for a child aged 6 months (born 2025-04-01) with no BCG dose, results on record")
        case("TB infection test negative", "tb-negative", born, observationsOf("IMMZD2DTBCG/BCG13.5"))
        case("TB infection test positive", "tb-positive", born, observationsOf("IMMZD2DTBCG/BCG16.4"))
        case("HIV-positive, on ART, immunologically stable, clinically well", "hiv-well", born, observationsOf("IMMZD2DTBCG/BCG19.7"))
        case("HIV-positive, on ART, not clinically well", "hiv-unwell", born, observationsOf("IMMZD2DTBCG/BCG18.5"))
        case("HIV-positive, not on ART", "hiv-no-art", born, observationsOf("IMMZD2DTBCG/BCG21.4"))
    }

    /** WHO's tables for the vaccines of the 6, 10 and 14 week visits, for one child as the visits go by. */
    @Test
    fun sixTenAndFourteenWeekVisits() = runTest(timeout = 60.minutes) {
        val icd = "http://id.who.int/icd/release/11/mms"
        val scenario = base("IMMZD2DTBCG/BCG07.4")
        val today = scenario.today
        val infant = listOf("IMMZD2DTDTPOnTimeStart", "IMMZD2DTHepatitisB4Doses", "IMMZD2DTHib3Doses", "IMMZD2DTPneumococcal3Doses", "IMMZD2DTPolioBOPVPlusIPV", "IMMZD2DTRotavirus")
        var n = 0
        suspend fun case(title: String, birthDate: String, doses: List<JsonObject>, tables: List<String> = infant) {
            for (table in tables) show("$title; $table", variant(scenario, "${scenario.patient}-infant-${n++}", plan = table, extra = doses, birthDate = birthDate))
        }
        fun bcg(date: String) = immunization(scenario.patient, icd, "XM8142", "Tuberculosis, live attenuated vaccines", date, primarySeries = true)
        fun hepB0(date: String) = immunization(scenario.patient, icd, "XM9V38", "Hepatitis B, purified antigen", date, series = "Dose 0")
        val oral = "DE214" to "bOPV"
        val injected = "DE213" to "IPV"
        fun opv0(date: String) = immunization(scenario.patient, icd, "XM79H3", "Poliomyelitis oral, bivalent, live attenuated", date, series = "Dose 0", typeOfDose = oral)
        fun atBirth(date: String) = listOf(bcg(date), hepB0(date), opv0(date))
        fun penta(date: String, dose: Int) = immunization(scenario.patient, icd, "XM7JP3", "Diphtheria, hemophilus influenzae B, pertussis, tetanus, hepatitis B vaccines", date, primarySeries = true, dose = dose)
        fun opv(date: String, dose: Int, typed: Boolean = true) =
            immunization(scenario.patient, icd, "XM79H3", "Poliomyelitis oral, bivalent, live attenuated", date, primarySeries = true, dose = dose, typeOfDose = oral.takeIf { typed })
        fun ipv(date: String, dose: Int) =
            immunization(scenario.patient, icd, "XM5V19", "Poliomyelitis, trivalent, inactivated, whole virus", date, primarySeries = true, dose = dose, typeOfDose = injected)
        fun pcv(date: String, dose: Int) = immunization(scenario.patient, icd, "XM96S7", "Pneumococcus, purified polysaccharides antigen conjugated vaccines", date, primarySeries = true, dose = dose)
        fun rota(date: String, dose: Int, of: Int = 2, brand: String? = "Rotarix", code: String = "XM4GV0", integers: Boolean = false) =
            immunization(scenario.patient, icd, code, if (code == "XM4GV0") "Rota virus, live attenuated" else "Rota virus, pentavalent, live, reassorted", date,
                primarySeries = true, dose = dose, seriesDoses = of, brand = brand, numbersAsIntegers = integers)
        fun visit(date: String, dose: Int) = listOf(penta(date, dose), opv(date, dose), pcv(date, dose), rota(date, dose))

        println("=== 17. The infant tables by age, for a child seen on $today with the three birth doses on record")
        case("aged 4 weeks", "2025-09-03", atBirth("2025-09-03"))
        case("aged 41 days", "2025-08-21", atBirth("2025-08-21"))
        case("aged 6 weeks", "2025-08-20", atBirth("2025-08-20"))

        println("=== 18. The 6-week visit (born 2025-08-20) as doses are recorded")
        val six = "2025-08-20"
        case("rotavirus and oral polio recorded", six, atBirth(six) + rota(today, 1) + opv(today, 1))
        case("all four recorded", six, atBirth(six) + visit(today, 1))
        case("no BCG on record; BCG recorded today", six, listOf(hepB0(six), opv0(six), bcg(today)), listOf("IMMZD2DTRotavirus"))
        case("no BCG on record; rotavirus recorded today", six, listOf(hepB0(six), opv0(six), rota(today, 1)), listOf("IMMZD2DTBCG"))
        case("BCG given late, 3 weeks ago", six, listOf(hepB0(six), opv0(six), bcg("2025-09-10")), listOf("IMMZD2DTRotavirus"))
        case("hepatitis B birth dose given late, 7 days ago", six, listOf(bcg(six), opv0(six), hepB0("2025-09-24")), listOf("IMMZD2DTHepatitisB4Doses", "IMMZD2DTDTPOnTimeStart"))

        println("=== 19. The 10-week visit (born 2025-07-23), the 6-week doses given 2025-09-03")
        val ten = "2025-07-23"
        case("nothing recorded yet today", ten, atBirth(ten) + visit("2025-09-03", 1))

        println("=== 20. The 14-week visit (born 2025-06-25), doses given 2025-08-06 and 2025-09-03")
        val fourteen = "2025-06-25"
        val before = atBirth(fourteen) + visit("2025-08-06", 1) + visit("2025-09-03", 2)
        case("nothing recorded yet today", fourteen, before)
        case("pentavalent, oral polio, IPV and pneumococcal recorded", fourteen, before + penta(today, 3) + opv(today, 3) + ipv(today, 4) + pcv(today, 3))

        println("=== 21. How a rotavirus dose is recorded")
        fun rotaCase(title: String, birthDate: String, doses: List<JsonObject>) = suspend { case(title, birthDate, doses, listOf("IMMZD2DTRotavirus")) }
        rotaCase("aged 10 weeks; dose 1 yesterday, coded 'live attenuated' (XM4GV0)", ten, listOf(rota("2025-09-30", 1)))()
        rotaCase("aged 10 weeks; dose 1 yesterday, coded 'pentavalent, live, reassorted' (XM4VG1)", ten, listOf(rota("2025-09-30", 1, of = 3, brand = "ROTASIIL", code = "XM4VG1")))()
        rotaCase("aged 14 weeks; two doses of a 2-dose product, brand and series length recorded", fourteen, listOf(rota("2025-08-06", 1), rota("2025-09-03", 2)))()
        rotaCase("aged 14 weeks; the same two doses with no brand recorded", fourteen, listOf(rota("2025-08-06", 1, brand = null), rota("2025-09-03", 2, brand = null)))()
        rotaCase("aged 14 weeks; the same two doses, dose number and series length recorded as integers", fourteen, listOf(rota("2025-08-06", 1, integers = true), rota("2025-09-03", 2, integers = true)))()
        rotaCase("aged 14 weeks; two doses of a 3-dose product, brand and series length recorded", fourteen, listOf(rota("2025-08-06", 1, of = 3, brand = "ROTASIIL"), rota("2025-09-03", 2, of = 3, brand = "ROTASIIL")))()

        println("=== 23. How a polio dose is recorded (the 6-week visit, born 2025-08-20)")
        case("oral polio recorded today with the type of dose", six, atBirth(six) + opv(today, 1), listOf("IMMZD2DTPolioBOPVPlusIPV"))
        case("oral polio recorded today without the type of dose", six, atBirth(six) + opv(today, 1, typed = false), listOf("IMMZD2DTPolioBOPVPlusIPV"))

        println("=== 22. Later: the same tables at 9 and 12 months, and for a child who never started")
        val complete = { born: String, d6: String, d10: String, d14: String ->
            atBirth(born) + visit(d6, 1) + visit(d10, 2) + listOf(penta(d14, 3), opv(d14, 3), ipv(d14, 4), pcv(d14, 3))
        }
        case("aged 9 months (born 2025-01-01), infant doses complete", "2025-01-01", complete("2025-01-01", "2025-02-12", "2025-03-12", "2025-04-09"))
        case("aged 12 months (born 2024-10-01), infant doses complete, IPV2 given at 9 months", "2024-10-01",
            complete("2024-10-01", "2024-11-12", "2024-12-10", "2025-01-07") + ipv("2025-07-01", 5))
        case("aged 13 months (born 2024-09-01), nothing on record", "2024-09-01", emptyList(), infant + "IMMZD2DTDTPDelayedOrInterruptedSeries")
    }

    /** WHO's DTP and Hib tables for a child whose infant doses are complete, around the boosters. */
    @Test
    fun boosters() = runTest(timeout = 60.minutes) {
        val icd = "http://id.who.int/icd/release/11/mms"
        val scenario = base("IMMZD2DTBCG/BCG07.4")
        val today = scenario.today
        var n = 0
        suspend fun case(title: String, birthDate: String, doses: List<JsonObject>, tables: List<String>) {
            for (table in tables) show("$title; $table", variant(scenario, "${scenario.patient}-booster-${n++}", plan = table, extra = doses, birthDate = birthDate))
        }
        fun after(born: String, days: Long) = java.time.LocalDate.parse(born).plusDays(days).toString()
        fun penta(date: String, dose: Int, series: String = "Primary series") =
            immunization(scenario.patient, icd, "XM7JP3", "Diphtheria, hemophilus influenzae B, pertussis, tetanus, hepatitis B vaccines", date, series = series, dose = dose)
        fun infantDoses(born: String) = listOf(penta(after(born, 42), 1), penta(after(born, 70), 2), penta(after(born, 98), 3))
        fun booster(code: String, display: String, date: String, dose: Int = 1) = immunization(scenario.patient, icd, code, display, date, series = "Booster dose", dose = dose)
        val dtp = listOf("IMMZD2DTDTPOnTimeStart")
        val hib = listOf("IMMZD2DTHib3Doses", "IMMZD2DTHib3DosesWithBoosterDose")

        println("=== 24. A child with three pentavalent doses on record, seen on $today, by age")
        case("aged 9 months", "2025-01-01", infantDoses("2025-01-01"), dtp + hib)
        case("aged 11 months and 29 days", "2024-10-02", infantDoses("2024-10-02"), dtp)
        case("aged 12 months", "2024-10-01", infantDoses("2024-10-01"), dtp + hib)
        case("aged 15 months", "2024-07-01", infantDoses("2024-07-01"), dtp + hib)

        println("=== 25. The same child aged 15 months (born 2024-07-01), as a booster is recorded today")
        val born = "2024-07-01"
        val all = dtp + hib + "IMMZD2DTHepatitisB4Doses"
        case("pentavalent recorded as a booster dose", born, infantDoses(born) + penta(today, 1, "Booster dose"), all)
        case("pentavalent recorded as a fourth primary-series dose", born, infantDoses(born) + penta(today, 4), all)
        case("whole-cell DTP (XM2TK2) recorded as a booster dose", born,
            infantDoses(born) + booster("XM2TK2", "Pertussis, inactivated, whole cell, combinations with toxoids vaccines", today), dtp)
        case("tetanus-diphtheria (XM32Q5) recorded as a booster dose", born, infantDoses(born) + booster("XM32Q5", "Tetanus and diphtheria vaccines", today), dtp)

        println("=== 26. Later boosters: the pentavalent booster was given at 15 months")
        fun withFirstBooster(b: String) = infantDoses(b) + penta(after(b, 457), 1, "Booster dose")
        case("aged 3 years 11 months", "2021-10-02", withFirstBooster("2021-10-02"), dtp)
        case("aged 4 years", "2021-10-01", withFirstBooster("2021-10-01"), dtp)
        case("aged 9 years, second booster (tetanus-diphtheria) given at 4 years", "2016-10-01",
            withFirstBooster("2016-10-01") + booster("XM32Q5", "Tetanus and diphtheria vaccines", "2020-10-01", 2), dtp)
        println("=== 27. If the Hib table with a booster were used: a pentavalent booster before 12 months")
        case("aged 10 months (born 2024-12-01), three pentavalent doses", "2024-12-01", infantDoses("2024-12-01"), dtp + hib)
        case("aged 13 months (born 2024-09-01), pentavalent booster given at 10 months", "2024-09-01",
            infantDoses("2024-09-01") + penta("2025-07-01", 1, "Booster dose"), dtp + hib)
    }

    @Test
    fun lateStarters() = runTest(timeout = 120.minutes) {
        val icd = "http://id.who.int/icd/release/11/mms"
        val scenario = base("IMMZD2DTBCG/BCG07.4")
        val today = scenario.today
        val programme = listOf(
            "IMMZD2DTBCG", "IMMZD2DTHepatitisBBirthDose", "IMMZD2DTPolioBirthDose", "IMMZD2DTDTPOnTimeStart", "IMMZD2DTHepatitisB4Doses", "IMMZD2DTHib3Doses",
            "IMMZD2DTPneumococcal3Doses", "IMMZD2DTPolioBOPVPlusIPV", "IMMZD2DTRotavirus", "IMMZD2DTMeaslesOngoingTransmission", "IMMZD2DTRubellaHighIncidence",
            "IMMZD2DTYellowfeverYellowFever",
        )
        val delayed = listOf("IMMZD2DTDTPDelayedOrInterruptedSeries", "IMMZD2DTHepatitisBDelayedStart")
        var n = 0
        suspend fun case(title: String, birthDate: String, doses: List<JsonObject>, tables: List<String> = programme + delayed) {
            for (table in tables) show("$title; $table", variant(scenario, "${scenario.patient}-late-${n++}", plan = table, extra = doses, birthDate = birthDate))
        }
        fun after(born: String, days: Long) = java.time.LocalDate.parse(born).plusDays(days).toString()
        val oral = "DE214" to "bOPV"
        val injected = "DE213" to "IPV"
        fun bcg(date: String) = immunization(scenario.patient, icd, "XM8142", "Tuberculosis, live attenuated vaccines", date, primarySeries = true)
        fun hepB0(date: String) = immunization(scenario.patient, icd, "XM9V38", "Hepatitis B, purified antigen", date, series = "Dose 0")
        fun opv0(date: String) = immunization(scenario.patient, icd, "XM79H3", "Poliomyelitis oral, bivalent, live attenuated", date, series = "Dose 0", typeOfDose = oral)
        fun atBirth(date: String) = listOf(bcg(date), hepB0(date), opv0(date))
        fun penta(date: String, dose: Int) = immunization(scenario.patient, icd, "XM7JP3", "Diphtheria, hemophilus influenzae B, pertussis, tetanus, hepatitis B vaccines", date, primarySeries = true, dose = dose)
        fun opv(date: String, dose: Int) = immunization(scenario.patient, icd, "XM79H3", "Poliomyelitis oral, bivalent, live attenuated", date, primarySeries = true, dose = dose, typeOfDose = oral)
        fun ipv(date: String, dose: Int) = immunization(scenario.patient, icd, "XM5V19", "Poliomyelitis, trivalent, inactivated, whole virus", date, primarySeries = true, dose = dose, typeOfDose = injected)
        fun pcv(date: String, dose: Int) = immunization(scenario.patient, icd, "XM96S7", "Pneumococcus, purified polysaccharides antigen conjugated vaccines", date, primarySeries = true, dose = dose)
        fun rota(date: String, dose: Int) = immunization(scenario.patient, icd, "XM4GV0", "Rota virus, live attenuated", date, primarySeries = true, dose = dose, seriesDoses = 2, brand = "Rotarix")
        fun mr(date: String, dose: Int) = immunization(scenario.patient, icd, "XM21H2", "Measles, combinations with rubella, live attenuated", date, primarySeries = true, dose = dose)
        fun yf(date: String) = immunization(scenario.patient, icd, "XM3418", "Yellow fever, live attenuated", date, primarySeries = true)
        fun visit(date: String, dose: Int) = listOf(penta(date, dose), opv(date, dose), pcv(date, dose)) + (if (dose <= 2) listOf(rota(date, dose)) else emptyList())

        println("=== 28. A child with nothing on record, seen on $today, by age")
        case("aged 4 months", "2025-06-01", emptyList())
        case("aged 9 months", "2025-01-01", emptyList())
        case("aged 12 months", "2024-10-01", emptyList())
        case("aged 15 months", "2024-07-01", emptyList())
        case("aged 2 years", "2023-10-01", emptyList())
        case("aged 3 years", "2022-10-01", emptyList())
        case("aged 5 years", "2020-10-01", emptyList())
        case("aged 7 years", "2018-10-01", emptyList())

        println("=== 29. A child aged 14 months (born 2024-08-01) whose infant visits were interrupted")
        val b = "2024-08-01"
        case("birth doses only", b, atBirth(b))
        case("birth doses and the 6-week visit", b, atBirth(b) + visit(after(b, 42), 1))
        case("birth doses, the 6-week and 10-week visits", b, atBirth(b) + visit(after(b, 42), 1) + visit(after(b, 70), 2))
        case("everything to 14 weeks (with the first IPV), nothing since", b, atBirth(b) + visit(after(b, 42), 1) + visit(after(b, 70), 2) + visit(after(b, 98), 3) + ipv(after(b, 98), 4))

        // A child first seen at 15 months with nothing on record, followed forward. Today is fixed, so each case moves
        // the birth date and the earlier visits back. Every dose WHO's tables proposed at a visit is recorded there.
        fun ago(days: Long) = java.time.LocalDate.parse(today).minusDays(days).toString()
        fun bornFor(firstVisit: String) = java.time.LocalDate.parse(firstVisit).minusMonths(15).toString()
        fun first(date: String) = listOf(bcg(date), penta(date, 1), opv(date, 1), ipv(date, 1), pcv(date, 1), rota(date, 1), mr(date, 1), yf(date))
        fun second(date: String) = listOf(penta(date, 2), opv(date, 2), pcv(date, 2), rota(date, 2), mr(date, 2))

        println("=== 30. A child first seen at 15 months with nothing on record: the first visit and 4 weeks later")
        case("first visit today, everything proposed recorded", bornFor(today), first(today))
        case("first visit 27 days ago", bornFor(ago(27)), first(ago(27)))
        case("first visit 28 days ago", bornFor(ago(28)), first(ago(28)))
        case("first visit 28 days ago; second pentavalent, oral polio, pneumococcal, rotavirus, measles-rubella recorded today", bornFor(ago(28)), first(ago(28)) + second(today))

        println("=== 31. The same child after two visits 4 weeks apart: when is the third pentavalent dose due?")
        case("second visit 28 days ago", bornFor(ago(56)), first(ago(56)) + second(ago(28)))
        case("second visit 28 days ago; oral polio 3 recorded today", bornFor(ago(56)), first(ago(56)) + second(ago(28)) + opv(today, 3))
        case("second visit 5 months ago (150 days)", bornFor(ago(178)), first(ago(178)) + second(ago(150)) + opv(ago(122), 3))
        case("second visit 6 months ago (184 days)", bornFor(ago(212)), first(ago(212)) + second(ago(184)) + opv(ago(156), 3))

        println("=== 32. Third pentavalent dose 6 months after the second (the 0, 1, 7 month course), and after")
        fun slow(thirdDaysAgo: Long) = first(ago(thirdDaysAgo + 212)) + second(ago(thirdDaysAgo + 184)) + opv(ago(thirdDaysAgo + 156), 3) + penta(ago(thirdDaysAgo), 3) + ipv(ago(thirdDaysAgo), 2)
        case("third dose recorded today", bornFor(ago(212)), slow(0))
        case("third dose 6 months ago (183 days)", bornFor(ago(395)), slow(183))
        case("third dose 364 days ago", bornFor(ago(576)), slow(364))
        case("third dose 1 year ago (366 days)", bornFor(ago(578)), slow(366))
        case("third dose 1 year ago; pentavalent recorded today as a booster", bornFor(ago(578)), slow(366) + immunization(scenario.patient, icd, "XM7JP3", "Diphtheria, hemophilus influenzae B, pertussis, tetanus, hepatitis B vaccines", today, series = "Booster dose", dose = 1))

        println("=== 33. Third pentavalent dose 4 weeks after the second (a 0, 1, 2 month course), and after")
        fun fast(thirdDaysAgo: Long) = first(ago(thirdDaysAgo + 56)) + second(ago(thirdDaysAgo + 28)) + penta(ago(thirdDaysAgo), 3) + opv(ago(thirdDaysAgo), 3)
        case("third dose recorded today", bornFor(ago(56)), fast(0))
        case("third dose 6 months ago (183 days)", bornFor(ago(239)), fast(183))

        println("=== 34. A child first seen at 3 years with nothing on record")
        val three = java.time.LocalDate.parse(today).minusYears(3).toString()
        fun firstAtThree(date: String) = listOf(bcg(date), penta(date, 1), opv(date, 1), ipv(date, 1), pcv(date, 1), mr(date, 1), yf(date))
        case("first visit today, everything proposed recorded", three, firstAtThree(today))
        case("first visit 28 days ago", java.time.LocalDate.parse(ago(28)).minusYears(3).toString(), firstAtThree(ago(28)))
    }

    /** WHO's contraindication tables for the ten vaccines of Freedonia's programme, one recorded answer at a time. */
    @Test
    fun screening() = runTest(timeout = 180.minutes) {
        val scenario = base("IMMZD2DTBCG/BCG07.4")
        val today = scenario.today
        val immzD = "http://smart.who.int/immunizations/CodeSystem/IMMZ.D"
        val immzZ = "http://smart.who.int/immunizations/CodeSystem/IMMZ.Z"
        fun table(vaccine: String) = "IMMZD5DT${vaccine}Contraindications"
        val tables = listOf("BCG", "HepatitisB", "Polio", "DTP", "Hib", "Pneumococcal", "Rotavirus", "Measles", "Rubella", "YellowFever").map(::table)
        val answers = listOf(
            "DE162" to "Currently pregnant", "DE163" to "Immunocompromised", "DE200" to "Severely immunocompromised",
            "DE164" to "Exposed to immunosuppressive treatment", "DE165" to "Severely immunosuppressed", "DE187" to "Immunodeficiency syndromes",
            "DE166" to "History of anaphylactic reactions", "DE167" to "Severe allergic reactions", "DE168" to "Symptomatic HIV infection",
            "DE169" to "Planning to get pregnant in the next month", "DE170" to "Receiving blood products", "DE188" to "Severe acute illness",
            "DE190" to "TB disease", "DE191" to "Breastfeeding", "DE192" to "Spina bifida", "DE193" to "Bladder exstrophy",
            "DE194" to "Acute gastroenteritis", "DE195" to "Moderate to severe fever", "DE196" to "Prior history of intussusception",
            "DE197" to "Altered immunocompetence", "DE198" to "Hypersensitivity to vaccine components", "DE199" to "Taking antibiotics",
        ).toMap()
        /** The categories WHO's decision tables propose for Freedonia's programme, as they return them. */
        val proposed = listOf(
            "DE1" to "BCG vaccines", "DE6" to "Hepatitis B-containing vaccines", "DE30" to "Oral polio vaccines", "DE31" to "Inactivated polio vaccines",
            "DE24" to "DTP-containing vaccines", "DE4" to "Hib-containing vaccines", "DE13" to "Pneumococcal vaccines", "DE16" to "Rotavirus vaccines",
            "DE9" to "Measles-containing vaccines", "DE17" to "Rubella-containing vaccines", "DE23" to "Yellow fever vaccines",
        )
        fun answer(code: String, date: String = today) =
            buildJsonObject {
                put("resourceType", "Observation")
                put("id", "answer-$code-$date")
                put("status", "final")
                putJsonObject("code") {
                    putJsonArray("coding") { add(buildJsonObject { put("system", immzD); put("code", "DE161"); put("display", "Potential contraindications") }) }
                }
                putJsonObject("subject") { put("reference", "Patient/${scenario.patient}") }
                put("effectiveDateTime", date)
                putJsonObject("valueCodeableConcept") {
                    putJsonArray("coding") { add(buildJsonObject { put("system", immzD); put("code", code); put("display", answers.getValue(code)) }) }
                }
            }
        fun proposal(code: String, display: String) =
            buildJsonObject {
                put("resourceType", "MedicationRequest")
                put("id", "proposal-$code")
                put("status", "draft")
                put("intent", "proposal")
                putJsonObject("medicationCodeableConcept") {
                    putJsonArray("coding") { add(buildJsonObject { put("system", immzZ); put("code", code); put("display", display) }) }
                    put("text", display)
                }
                putJsonObject("subject") { put("reference", "Patient/${scenario.patient}") }
                put("authoredOn", today)
            }
        val proposals = proposed.map { (code, display) -> proposal(code, display) }
        var n = 0
        suspend fun case(title: String, birthDate: String, extra: List<JsonObject>, which: List<String> = tables) {
            for (t in which) show("$title; $t", variant(scenario, "${scenario.patient}-screen-${n++}", plan = t, extra = extra, birthDate = birthDate))
        }
        val nine = "2025-01-01"

        println("=== 35. A child aged 9 months (born $nine), a proposal dated today on record for each of eleven kinds of vaccine, one answer recorded today")
        case("no answer recorded", nine, proposals)
        for ((code, display) in answers) case("answer $code $display", nine, proposals + answer(code))

        println("=== 36. Rows that depend on age")
        for ((age, born) in listOf("11 months" to "2024-10-02", "12 months" to "2024-10-01")) {
            case("aged $age, answer DE164 Exposed to immunosuppressive treatment", born, proposals + answer("DE164"), listOf(table("BCG")))
        }
        for ((age, born) in listOf("5 months" to "2025-04-02", "6 months" to "2025-04-01", "8 months" to "2025-01-02", "9 months" to "2025-01-01")) {
            case("aged $age, no answer recorded", born, proposals, listOf(table("YellowFever")))
        }

        println("=== 37. An answer recorded at an earlier visit and nothing recorded today")
        case("aged 9 months, DE195 Moderate to severe fever recorded 2025-05-01", nine, proposals + answer("DE195", "2025-05-01"), listOf(table("Rotavirus")))
        case("aged 9 months, DE194 Acute gastroenteritis recorded 2025-05-01", nine, proposals + answer("DE194", "2025-05-01"), listOf(table("Rotavirus")))
        case("aged 9 months, DE188 Severe acute illness recorded 2025-09-03", nine, proposals + answer("DE188", "2025-09-03"), listOf(table("DTP")))
        case("aged 15 months (born 2024-07-01), DE170 Receiving blood products recorded 2024-10-01", "2024-07-01", proposals + answer("DE170", "2024-10-01"), listOf(table("Rubella")))

        println("=== 38. Two answers recorded today")
        case("aged 9 months, DE187 Immunodeficiency syndromes and DE167 Severe allergic reactions", nine, proposals + answer("DE187") + answer("DE167"), listOf(table("BCG"), table("Polio"), table("Rubella")))
        case("aged 9 months, DE195 Moderate to severe fever and DE196 Prior history of intussusception", nine, proposals + answer("DE195") + answer("DE196"), listOf(table("Rotavirus")))

        println("=== 39. An answer recorded today and no proposal on record")
        case("aged 9 months, DE196 Prior history of intussusception", nine, listOf(answer("DE196")), listOf(table("Rotavirus")))
        case("aged 9 months, DE192 Spina bifida", nine, listOf(answer("DE192")), listOf(table("Rotavirus")))
        case("aged 9 months, DE167 Severe allergic reactions", nine, listOf(answer("DE167")), listOf(table("HepatitisB")))
        case("aged 9 months, DE165 Severely immunosuppressed", nine, listOf(answer("DE165")), listOf(table("Measles")))
    }

    /**
     * Freedonia's screening questions that go through WHO's contraindication tables: the WHO answers a "yes" stands
     * for, with the proposals of one visit on record, applied to the table of each vaccine proposed at that visit.
     */
    @Test
    fun freedoniaScreening() = runTest(timeout = 180.minutes) {
        val scenario = base("IMMZD2DTBCG/BCG07.4")
        val today = scenario.today
        val immzD = "http://smart.who.int/immunizations/CodeSystem/IMMZ.D"
        val immzZ = "http://smart.who.int/immunizations/CodeSystem/IMMZ.Z"
        val tableOf = mapOf(
            "DE1" to "BCG", "DE6" to "HepatitisB", "DE30" to "Polio", "DE31" to "Polio", "DE24" to "DTP", "DE28" to "DTP", "DE12" to "DTP",
            "DE4" to "Hib", "DE13" to "Pneumococcal", "DE16" to "Rotavirus", "DE9" to "Measles", "DE17" to "Rubella", "DE23" to "YellowFever",
        )
        val questions = listOf(
            "S2 diarrhoea or vomiting today" to listOf("DE194"),
            "S3 severe allergic reaction" to listOf("DE166", "DE167"),
            "S4 immune weakness or treatment" to listOf("DE187", "DE200", "DE165", "DE164"),
            "S6 intussusception" to listOf("DE196"),
            "S7 spina bifida, bladder exstrophy or bowel disease" to listOf("DE192", "DE193"),
            "S8 blood product in the past 3 months" to listOf("DE170"),
            "S9 TB" to listOf("DE190"),
        )
        val visits = listOf(
            Triple("birth", "2025-10-01", listOf("DE1", "DE6", "DE30")),
            Triple("6 weeks", "2025-08-20", listOf("DE24", "DE6", "DE4", "DE13", "DE30", "DE16")),
            Triple("14 weeks", "2025-06-25", listOf("DE24", "DE6", "DE4", "DE13", "DE30", "DE31")),
            Triple("second IPV dose (aged 8 months)", "2025-02-01", listOf("DE31")),
            Triple("9 months", "2025-01-01", listOf("DE9", "DE17", "DE23")),
            Triple("15 months", "2024-07-01", listOf("DE9", "DE17", "DE28", "DE12")),
            Triple("catch-up at 15 months, nothing on record", "2024-07-01", listOf("DE1", "DE24", "DE6", "DE4", "DE13", "DE30", "DE31", "DE16", "DE9", "DE17", "DE23")),
        )
        fun answer(code: String) =
            buildJsonObject {
                put("resourceType", "Observation")
                put("id", "answer-$code")
                put("status", "final")
                putJsonObject("code") {
                    putJsonArray("coding") { add(buildJsonObject { put("system", immzD); put("code", "DE161"); put("display", "Potential contraindications") }) }
                }
                putJsonObject("subject") { put("reference", "Patient/${scenario.patient}") }
                put("effectiveDateTime", today)
                putJsonObject("valueCodeableConcept") { putJsonArray("coding") { add(buildJsonObject { put("system", immzD); put("code", code) }) } }
            }
        fun proposal(code: String) =
            buildJsonObject {
                put("resourceType", "MedicationRequest")
                put("id", "proposal-$code")
                put("status", "draft")
                put("intent", "proposal")
                putJsonObject("medicationCodeableConcept") { putJsonArray("coding") { add(buildJsonObject { put("system", immzZ); put("code", code) }) } }
                putJsonObject("subject") { put("reference", "Patient/${scenario.patient}") }
                put("authoredOn", today)
            }
        var n = 0
        println("=== 40. Freedonia's screening questions: the WHO answers of one \"yes\", the proposals of one visit, each table with a proposal")
        for ((visit, born, proposed) in visits) {
            for ((question, codes) in questions) {
                for (t in proposed.map(tableOf::getValue).distinct()) {
                    show(
                        "$visit, proposed ${proposed.joinToString(" ")}; yes to $question (${codes.joinToString(" ")}); IMMZD5DT${t}Contraindications",
                        variant(scenario, "${scenario.patient}-fscreen-${n++}", plan = "IMMZD5DT${t}Contraindications", extra = proposed.map(::proposal) + codes.map(::answer), birthDate = born),
                    )
                }
            }
        }
    }

    /** What WHO's tables do with a child's HIV status, preterm birth and pneumococcal risk, for Freedonia's programme. */
    @Test
    fun riskAnswers() = runTest(timeout = 180.minutes) {
        val icd = "http://id.who.int/icd/release/11/mms"
        val immzD = "http://smart.who.int/immunizations/CodeSystem/IMMZ.D"
        val immzZ = "http://smart.who.int/immunizations/CodeSystem/IMMZ.Z"
        val scenario = base("IMMZD2DTBCG/BCG07.4")
        val today = scenario.today
        fun ago(days: Long) = java.time.LocalDate.parse(today).minusDays(days).toString()
        fun observation(code: String, display: String, value: JsonObject.() -> Map<String, kotlinx.serialization.json.JsonElement>) =
            buildJsonObject {
                put("resourceType", "Observation")
                put("id", "risk-$code")
                put("status", "final")
                putJsonObject("code") { putJsonArray("coding") { add(buildJsonObject { put("system", immzD); put("code", code); put("display", display) }) } }
                putJsonObject("subject") { put("reference", "Patient/${scenario.patient}") }
                put("effectiveDateTime", ago(14))
            }.let { JsonObject(it + it.value()) }
        fun flag(code: String, display: String, value: Boolean) = observation(code, display) { mapOf("valueBoolean" to JsonPrimitive(value)) }
        fun hiv(code: String, display: String) =
            observation("DE204", "HIV status") {
                mapOf("valueCodeableConcept" to buildJsonObject { putJsonArray("coding") { add(buildJsonObject { put("system", immzD); put("code", code); put("display", display) }) } })
            }
        val positive = hiv("DE205", "HIV-positive")
        fun art(v: Boolean) = flag("DE210", "Currently on ART", v)
        fun stable(v: Boolean) = flag("DE249", "Immunologically stable", v)
        fun well(v: Boolean) = flag("DE250", "Clinically well", v)
        val preterm = flag("DE208", "Preterm birth", true)
        val highRisk = flag("DE251", "At high risk for pneumococcal infection", true)
        fun pcv(date: String, dose: Int, series: String = "Primary series") =
            immunization(scenario.patient, icd, "XM96S7", "Pneumococcus, purified polysaccharides antigen conjugated vaccines", date, series = series, dose = dose)
        var n = 0
        suspend fun case(title: String, table: String, birthDate: String, extra: List<JsonObject>) =
            show("$title; $table", variant(scenario, "${scenario.patient}-risk-${n++}", plan = table, extra = extra, birthDate = birthDate))

        println("=== 41. BCG table for a child with no BCG dose, by what is on record about HIV, and by age")
        val states = listOf(
            "HIV status negative" to listOf(hiv("DE206", "HIV-negative")),
            "HIV status unknown" to listOf(hiv("DE207", "Unknown")),
            "HIV-positive, nothing else recorded" to listOf(positive),
            "HIV-positive, on ART, nothing else recorded" to listOf(positive, art(true)),
            "HIV-positive, on ART, immunologically stable, clinically well" to listOf(positive, art(true), stable(true), well(true)),
            "HIV-positive, on ART, not immunologically stable, clinically well" to listOf(positive, art(true), stable(false), well(true)),
            "HIV-positive, on ART, immunologically stable, not clinically well" to listOf(positive, art(true), stable(true), well(false)),
            "HIV-positive, not on ART" to listOf(positive, art(false)),
        )
        for ((age, born) in listOf("newborn (2 days)" to ago(2), "6 weeks" to ago(42), "9 months" to "2025-01-01")) {
            // The newborn's observations are dated 14 days ago in every case, before birth; WHO's logic takes any dated today or earlier.
            for ((state, observations) in states) case("aged $age, $state", "IMMZD2DTBCG", born, observations)
        }

        println("=== 42. Pneumococcal 3-dose table after three doses at 6, 10 and 14 weeks, by time since the third dose and by risk")
        fun primed(thirdDaysAgo: Long) = listOf(pcv(ago(thirdDaysAgo + 56), 1), pcv(ago(thirdDaysAgo + 28), 2), pcv(ago(thirdDaysAgo), 3))
        fun bornFor(thirdDaysAgo: Long) = ago(thirdDaysAgo + 98)
        val risks = listOf("nothing recorded" to emptyList(), "HIV-positive" to listOf(positive), "born preterm" to listOf(preterm), "at high risk" to listOf(highRisk))
        for ((since, days) in listOf("today" to 0L, "55 days ago" to 55L, "56 days ago" to 56L, "57 days ago" to 57L, "6 months ago (aged 9 months)" to 183L, "aged 15 months" to 359L, "aged 23 months" to 600L, "aged 24 months" to 633L)) {
            for ((risk, observations) in risks) case("third dose $since, $risk", "IMMZD2DTPneumococcal3Doses", bornFor(days), primed(days) + observations)
        }
        case("third dose 57 days ago, HIV-positive, a fourth dose recorded today as a booster", "IMMZD2DTPneumococcal3Doses", bornFor(57), primed(57) + positive + pcv(today, 1, "Booster dose"))
        case("third dose 57 days ago, HIV-positive, a fourth dose recorded today in the primary series", "IMMZD2DTPneumococcal3Doses", bornFor(57), primed(57) + positive + pcv(today, 4))

        println("=== 43. Pneumococcal 3-dose table for a late starter, by risk")
        val lateRisks = listOf("nothing recorded" to emptyList(), "at high risk" to listOf(highRisk), "HIV-positive" to listOf(positive))
        fun bornAt(months: Long, firstDaysAgo: Long) = java.time.LocalDate.parse(ago(firstDaysAgo)).minusMonths(months).toString()
        for (days in listOf(27L, 28L, 55L, 56L, 57L)) {
            for ((risk, observations) in lateRisks) case("first dose at 15 months, $days days ago, $risk", "IMMZD2DTPneumococcal3Doses", bornAt(15, days), listOf(pcv(ago(days), 1)) + observations)
        }
        for ((risk, observations) in lateRisks) {
            case("first dose at 15 months and second 56 days later, 57 days ago, $risk", "IMMZD2DTPneumococcal3Doses", bornAt(15, 113), listOf(pcv(ago(113), 1), pcv(ago(57), 2)) + observations)
            case("first dose at 3 years, 57 days ago, $risk", "IMMZD2DTPneumococcal3Doses", bornAt(36, 57), listOf(pcv(ago(57), 1)) + observations)
            case("aged 3 years, no dose, $risk", "IMMZD2DTPneumococcal3Doses", bornAt(36, 0), observations)
        }

        println("=== 44. A child aged 9 months with nothing on record: every table of the programme, with no HIV status and HIV-positive")
        val programme = listOf(
            "IMMZD2DTBCG", "IMMZD2DTHepatitisBBirthDose", "IMMZD2DTPolioBirthDose", "IMMZD2DTDTPOnTimeStart", "IMMZD2DTHepatitisB4Doses", "IMMZD2DTHib3Doses",
            "IMMZD2DTPneumococcal3Doses", "IMMZD2DTPolioBOPVPlusIPV", "IMMZD2DTRotavirus", "IMMZD2DTMeaslesOngoingTransmission", "IMMZD2DTRubellaHighIncidence",
            "IMMZD2DTYellowfeverYellowFever",
        )
        for (table in programme) {
            case("no HIV status", table, "2025-01-01", emptyList())
            case("HIV-positive", table, "2025-01-01", listOf(positive))
        }

        println("=== 45. The same child, HIV-positive, a proposal dated today for each kind of vaccine: the ten contraindication tables")
        val proposals = listOf("DE1", "DE6", "DE30", "DE31", "DE24", "DE4", "DE13", "DE16", "DE9", "DE17", "DE23").map { code ->
            buildJsonObject {
                put("resourceType", "MedicationRequest")
                put("id", "proposal-$code")
                put("status", "draft")
                put("intent", "proposal")
                putJsonObject("medicationCodeableConcept") { putJsonArray("coding") { add(buildJsonObject { put("system", immzZ); put("code", code) }) } }
                putJsonObject("subject") { put("reference", "Patient/${scenario.patient}") }
                put("authoredOn", today)
            }
        }
        for (t in listOf("BCG", "HepatitisB", "Polio", "DTP", "Hib", "Pneumococcal", "Rotavirus", "Measles", "Rubella", "YellowFever")) {
            case("HIV-positive, no answer to \"Potential contraindications\"", "IMMZD5DT${t}Contraindications", "2025-01-01", proposals + positive)
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
