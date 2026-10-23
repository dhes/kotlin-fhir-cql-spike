package io.github.dhes.kotlinfhircql

import ca.uhn.fhir.context.FhirContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.cqframework.cql.cql2elm.CqlCompilerOptions
import org.cqframework.cql.elm.serializing.ElmJsonLibraryWriter
import org.hl7.elm.r1.VersionedIdentifier
import org.opencds.cqf.cql.engine.fhir.model.R4FhirModelResolver

/**
 * Step 10: the retrieve provider's date range and profile filtering. [RetrieveDateRangeTest] and
 * [InMemoryRetrieveProviderTest] run on every target; this adds the HAPI-based resolver, and WHO's libraries.
 */
class Step10Test {
    /** WHO's libraries compiled with the date range optimization: no retrieve gets a date range or a profile. */
    @Test
    fun whoRetrievesGetNoDateRangeOrProfile() {
        val compiler =
            FhirCqlEvaluator(
                WhoSuite.librarySources,
                MeaslesFixtures.modelInfoXml,
                compilerOptions = FhirCqlEvaluator.compilerOptions(CqlCompilerOptions.Options.EnableDateRangeOptimization),
            )
        val retrieves = mutableListOf<JsonObject>()
        fun collect(e: JsonElement) {
            when (e) {
                is JsonObject -> {
                    if (e["type"]?.jsonPrimitive?.content == "Retrieve") retrieves += e
                    e.values.forEach(::collect)
                }
                is JsonArray -> e.forEach(::collect)
                else -> {}
            }
        }
        for (source in WhoSuite.librarySources) {
            val library = compiler.libraryManager.resolveLibrary(VersionedIdentifier().apply { id = FhirCqlEvaluator.libraryName(source) }).library!!
            collect(Json.parseToJsonElement(ElmJsonLibraryWriter().writeAsString(library)))
        }
        val withDateRange = retrieves.filter { it["dateRange"] != null || it["dateLowProperty"] != null || it["dateHighProperty"] != null }
        val profiled =
            retrieves.filter {
                val type = it["dataType"]!!.jsonPrimitive.content.substringAfter('}')
                it["templateId"]?.jsonPrimitive?.content != "http://hl7.org/fhir/StructureDefinition/$type"
            }
        println("WHO's libraries: ${retrieves.size} retrieves, ${withDateRange.size} with a date range, ${profiled.size} with a profile")
        assertEquals(321, retrieves.size)
        assertEquals(0, withDateRange.size)
        assertEquals(0, profiled.size)
    }

    /** As [RetrieveDateRangeTest], over resources converted by cqframework's HAPI-based resolver, and against kotlin-fhir. */
    @Test
    fun hapiResolverAgrees() {
        val context = FhirContext.forR4Cached()
        val resolver = R4FhirModelResolver(context)
        val bundle = context.newJsonParser().parseResource(org.hl7.fhir.r4.model.Bundle::class.java, RetrieveFixtures.bundleJson())
        val hapiResources = bundle.entry.map { resolver.toCqlValue(it.resource)!! }
        val kotlinFhirResources = RetrieveFixtures.kotlinFhirResources()

        val results =
            listOf(false, true).associateWith { optimized ->
                val hapi = RetrieveFixtures.evaluate(RetrieveFixtures.evaluator(optimized) { resolver }, hapiResources)
                val kotlinFhir = RetrieveFixtures.evaluate(RetrieveFixtures.evaluator(optimized), kotlinFhirResources)
                assertEquals(kotlinFhir, hapi, "HAPI and kotlin-fhir, optimized = $optimized")
                hapi
            }
        val plain = results.getValue(false)
        val optimized = results.getValue(true)
        val differing = plain.keys.filter { plain[it] != optimized[it] }.map { it.substringAfter('.') }.toSet()
        val ranged = RetrieveFixtures.EXPECTED_DATE_PATHS.size
        println("HAPI: of the $ranged definitions given a date range, ${ranged - differing.size} agree with the where clause; kotlin-fhir gives the same results both ways")
        assertEquals(RetrieveFixtures.LOST_IN_COMPILATION, differing)
    }
}
