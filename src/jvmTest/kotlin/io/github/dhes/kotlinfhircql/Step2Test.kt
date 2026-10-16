package io.github.dhes.kotlinfhircql

import ca.uhn.fhir.context.FhirContext
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.opencds.cqf.cql.engine.fhir.model.R4FhirModelResolver
import org.opencds.cqf.cql.engine.runtime.ClassInstance

/**
 * Step 2: every definition in the 11-library measles closure, for WHO's 7 example patients, compared
 * with cqframework's HAPI-based resolver. Records the HAPI results in
 * [MeaslesFixtures.EXPECTED_RESULTS] for [MeaslesClosureTest] to check on the other targets.
 */
class Step2Test {
    private fun hapiResources(patient: String, resolver: R4FhirModelResolver, context: FhirContext): List<ClassInstance> {
        val bundle =
            context.newJsonParser().parseResource(org.hl7.fhir.r4.model.Bundle::class.java, MeaslesFixtures.bundleJson(patient))
        return bundle.entry.map { resolver.toCqlValue(it.resource)!! }
    }

    @Test
    fun measlesClosureAgreesWithHapi() {
        val terminology = InMemoryTerminologyProvider(MeaslesFixtures.valueSets)
        val ours = FhirCqlEvaluator(MeaslesFixtures.librarySources, MeaslesFixtures.modelInfoXml, terminology)
        val hapiContext = FhirContext.forR4Cached()
        val hapiResolver = R4FhirModelResolver(hapiContext)
        val reference =
            FhirCqlEvaluator(MeaslesFixtures.librarySources, MeaslesFixtures.modelInfoXml, terminology, modelResolverFactory = { hapiResolver })

        val differences = mutableListOf<String>()
        val recorded = sortedMapOf<String, JsonObject>()
        var compared = 0
        for (patient in MeaslesFixtures.patients) {
            val oursResults = MeaslesFixtures.evaluateAll(ours, patient, MeaslesFixtures.kotlinFhirResources(patient))
            val referenceResults = MeaslesFixtures.evaluateAll(reference, patient, hapiResources(patient, hapiResolver, hapiContext))
            println(
                "$patient: ${oursResults.size} results (${oursResults.values.count { it != null && it != "[]" }} non-empty, " +
                    "${oursResults.values.count { it == "true" }} true)"
            )
            for (key in (oursResults.keys + referenceResults.keys).sorted()) {
                compared++
                if (oursResults[key] != referenceResults[key]) {
                    differences += "$patient $key\n    kotlin-fhir: ${oursResults[key]}\n    HAPI:        ${referenceResults[key]}"
                }
            }
            recorded[patient] = JsonObject(referenceResults.toSortedMap().mapValues { (_, v) -> v?.let(::JsonPrimitive) ?: JsonNull })
        }
        println("Compared $compared results; ${differences.size} differ")
        differences.forEach(::println)

        val file = File(PROJECT_DIR, MeaslesFixtures.EXPECTED_RESULTS)
        file.parentFile.mkdirs()
        file.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), JsonObject(recorded)) + "\n")

        assertTrue(differences.isEmpty(), "${differences.size} results differ from HAPI")
    }
}
