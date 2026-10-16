package io.github.dhes.kotlinfhircql

import ca.uhn.fhir.context.FhirContext
import dev.ohs.fhir.model.r4.Bundle
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.ValueSet
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.measureTimedValue
import kotlinx.serialization.json.Json
import org.cqframework.cql.shared.BigDecimal
import org.opencds.cqf.cql.engine.fhir.model.R4FhirModelResolver
import org.opencds.cqf.cql.engine.runtime.ClassInstance
import org.opencds.cqf.cql.engine.runtime.Date
import org.opencds.cqf.cql.engine.runtime.Tuple
import org.opencds.cqf.cql.engine.runtime.Value
import org.opencds.cqf.cql.engine.runtime.List as CqlList
import org.opencds.cqf.cql.engine.runtime.String as CqlString

/**
 * Step 2: every definition in the 11-library measles closure, for WHO's 7 example patients of the
 * IMMZ.D2.DT.Measles.Ongoing transmission decision table, compared with cqframework's HAPI-based
 * resolver, and the logic library's guidance compared with what WHO's test suite expects.
 */
class Step2Test {
    private val root = File(System.getProperty("user.dir"))
    private val modelInfoXml = File(root, "third_party/cqframework-quick-5.4.0/fhir-modelinfo-4.0.1.xml").readText()
    private val cqlDir = File(root, "third_party/smart-immunizations-0.2.0/cql")
    private val librarySources =
        cqlDir.listFiles()!!.filter { it.extension == "cql" }.map { it.readText() } +
            File(root, "third_party/cqframework-quick-5.4.0/FHIRHelpers-4.0.1.cql").readText()
    private val libraries = cqlDir.listFiles()!!.filter { it.extension == "cql" }.map { it.nameWithoutExtension }.sorted()
    private val testsDir = File(root, "fixtures/who-measles-tests")
    private val json = Json { ignoreUnknownKeys = true }

    private val valueSets =
        File(root, "third_party/smart-immunizations-0.2.0/valuesets").listFiles()!!.map {
            json.decodeFromString<Resource>(it.readText()) as ValueSet
        }
    private val terminology = InMemoryTerminologyProvider(valueSets)

    // The date WHO's test suite evaluates against (Background of the .feature file).
    private val today = ZonedDateTime.of(2025, 11, 12, 12, 0, 0, 0, ZoneId.systemDefault())
    private val parameters = mapOf<String, Value?>("Today" to Date(2025, 11, 12))
    private val offsetHours = BigDecimal(today.offset.totalSeconds).divide(BigDecimal(3600))

    /** Patient id to the guidance WHO's test suite expects. */
    private val expectedGuidance: Map<String, String> =
        Regex("""@patient=(\S+).*?contentString = "(.*?)"\n""", RegexOption.DOT_MATCHES_ALL)
            .findAll(File(testsDir, "IMMZD2DTMeaslesOngoingTransmission.feature").readText())
            .associate { it.groupValues[1] to it.groupValues[2].replace("\\n", "\n") }

    private fun bundleJson(patient: String) = File(testsDir, "$patient.json").readText()

    private fun kotlinFhirResources(patient: String): List<ClassInstance> {
        val bundle = json.decodeFromString<Resource>(bundleJson(patient)) as Bundle
        val converter = KotlinFhirToCql(offsetHours)
        return bundle.entry.map { converter.convert(it.resource!!) }
    }

    private fun hapiResources(patient: String, resolver: R4FhirModelResolver, context: FhirContext): List<ClassInstance> {
        val bundle = context.newJsonParser().parseResource(org.hl7.fhir.r4.model.Bundle::class.java, bundleJson(patient))
        return bundle.entry.map { resolver.toCqlValue(it.resource)!! }
    }

    /** A comparable form of a result: resources by type and id; other values by their elements. */
    private fun normalize(value: Value?): Any? =
        when (value) {
            null -> null
            is CqlList -> value.map(::normalize)
            is ClassInstance -> {
                val id = (value.elements["id"] as? ClassInstance)?.elements?.get("value") as? CqlString
                if (id != null) {
                    "${value.type.getLocalPart()}/${id.value}"
                } else {
                    value.type.getLocalPart() to
                        value.elements.filterKeys { it != "extension" && it != "modifierExtension" }
                            .mapValues { normalize(it.value) }.toSortedMap()
                }
            }
            is Tuple -> value.elements.mapValues { normalize(it.value) }.toSortedMap()
            else -> value.toString()
        }

    private fun evaluateAll(evaluator: FhirCqlEvaluator, patient: String, resources: List<ClassInstance>): Map<String, Any?> =
        libraries.flatMap { library ->
            try {
                evaluator.evaluate(library, patient, resources, null, parameters, today)
                    .map { (name, value) -> "$library.$name" to normalize(value) }
            } catch (e: Exception) {
                listOf("$library (error)" to "${e::class.simpleName}: ${e.message}")
            }
        }.toMap()

    @Test
    fun measlesClosureAgreesWithHapiAndWhoExpectations() {
        val patients = expectedGuidance.keys.sorted()
        assertEquals(7, patients.size)

        val (ours, oursTime) = measureTimedValue { FhirCqlEvaluator(librarySources, modelInfoXml, terminology) }
        val hapiContext = FhirContext.forR4Cached()
        val hapiResolver = R4FhirModelResolver(hapiContext)
        val reference = FhirCqlEvaluator(librarySources, modelInfoXml, terminology, modelResolverFactory = { hapiResolver })
        println("Set-up (model info load): $oursTime")

        val differences = mutableListOf<String>()
        var compared = 0
        for (patient in patients) {
            val (oursResults, firstTime) = measureTimedValue { evaluateAll(ours, patient, kotlinFhirResources(patient)) }
            val referenceResults = evaluateAll(reference, patient, hapiResources(patient, hapiResolver, hapiContext))
            println("$patient: ${oursResults.size} results (${oursResults.values.count { it != null && it != emptyList<Any>() }} non-empty, ${oursResults.values.count { it == "true" }} true) in $firstTime")
            for (key in (oursResults.keys + referenceResults.keys).sorted()) {
                compared++
                if (oursResults[key] != referenceResults[key]) {
                    differences += "$patient $key\n    kotlin-fhir: ${oursResults[key]}\n    HAPI:        ${referenceResults[key]}"
                }
            }
            val guidance = oursResults["IMMZD2DTMeaslesOngoingTransmissionLogic.Guidance"]
            val validation = oursResults["IMMZD2DTMeaslesOngoingTransmissionLogic.Test Validation"]
            println("    Guidance matches WHO: ${guidance == CqlString(expectedGuidance[patient]!!).toString()}  Test Validation: $validation")
            oursResults.filterKeys { it.endsWith("(error)") }.forEach { (k, v) -> println("    $k $v") }
        }
        println("Compared $compared results; ${differences.size} differ")
        differences.forEach(::println)

        assertTrue(differences.isEmpty(), "${differences.size} results differ from HAPI")
        for (patient in patients) {
            val results = evaluateAll(ours, patient, kotlinFhirResources(patient))
            assertEquals("true", results["IMMZD2DTMeaslesOngoingTransmissionLogic.Test Validation"], "Test Validation for $patient")
        }
    }
}
