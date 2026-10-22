package io.github.dhes.kotlinfhircql

import ca.uhn.fhir.context.FhirContext
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.measureTime
import kotlinx.serialization.json.Json
import org.opencds.cqf.cql.engine.fhir.model.R4FhirModelResolver
import org.opencds.cqf.cql.engine.runtime.ClassInstance

/**
 * Step 4: WHO's whole immunization test suite (1,021 scenarios) and 79 twins recording a contraindication as a
 * Condition. For every scenario, every definition in the closure of its logic library is compared with
 * cqframework's HAPI-based resolver; WHO's expected guidance and resource count are checked on both sides.
 * Records the HAPI outcomes in [WhoSuite.EXPECTED] for [WhoSuiteTest] on the other targets.
 */
class Step4Test {
    private fun hapiResources(scenario: WhoScenario, resolver: R4FhirModelResolver, context: FhirContext): List<ClassInstance> {
        val bundle = context.newJsonParser().parseResource(org.hl7.fhir.r4.model.Bundle::class.java, WhoSuite.bundleJson(scenario))
        return bundle.entry.mapNotNull { it.resource }.map { resolver.toCqlValue(it)!! }
    }

    @Test
    fun whoSuiteAgreesWithHapi() {
        val terminology = InMemoryTerminologyProvider(WhoSuite.valueSets)
        val ours = FhirCqlEvaluator(WhoSuite.librarySources, MeaslesFixtures.modelInfoXml, terminology)
        val hapiContext = FhirContext.forR4Cached()
        val hapiResolver = R4FhirModelResolver(hapiContext)
        val reference =
            FhirCqlEvaluator(WhoSuite.librarySources, MeaslesFixtures.modelInfoXml, terminology, modelResolverFactory = { hapiResolver })

        val differences = mutableListOf<String>()
        val recorded = sortedMapOf<String, WhoOutcome>()
        val oursOutcomes = mutableMapOf<String, WhoOutcome>()
        val rejected = mutableListOf<String>()
        var compared = 0
        val elapsed = measureTime {
            for (scenario in WhoSuite.scenarios) {
                val referenceResults = WhoSuite.evaluateClosure(reference, scenario, hapiResources(scenario, hapiResolver, hapiContext))
                recorded[scenario.id] = WhoSuite.outcome(scenario, referenceResults)
                val oursResources =
                    try {
                        WhoSuite.kotlinFhirResources(scenario)
                    } catch (e: Exception) {
                        rejected += "${scenario.id}: ${e.message}"
                        continue
                    }
                val oursResults = WhoSuite.evaluateClosure(ours, scenario, oursResources)
                for (key in (oursResults.keys + referenceResults.keys).sorted()) {
                    compared++
                    if (oursResults[key] != referenceResults[key]) {
                        differences += "${scenario.id} $key\n    kotlin-fhir: ${oursResults[key]}\n    HAPI:        ${referenceResults[key]}"
                    }
                }
                oursOutcomes[scenario.id] = WhoSuite.outcome(scenario, oursResults)
            }
        }
        println("${WhoSuite.scenarios.size} scenarios, $compared results compared in $elapsed; ${differences.size} differ from HAPI")
        differences.take(40).forEach(::println)
        println("Bundles kotlin-fhir rejects (scenario skipped on the kotlin-fhir side): ${rejected.size}")
        rejected.forEach { println("  $it") }
        report("kotlin-fhir", oursOutcomes)
        report("HAPI", recorded)

        val file = File(PROJECT_DIR, WhoSuite.EXPECTED)
        file.parentFile.mkdirs()
        file.writeText(prettyJson.encodeToString<Map<String, WhoOutcome>>(recorded) + "\n")

        assertTrue(differences.isEmpty(), "${differences.size} results differ from HAPI")
    }

    /**
     * The same comparison with every element of every value, so a resource in a result is compared element by
     * element instead of by type and id, and extensions are included. Differences are grouped by element path.
     */
    @Test
    fun whoSuiteAgreesWithHapiElementByElement() {
        val terminology = InMemoryTerminologyProvider(WhoSuite.valueSets)
        val ours = FhirCqlEvaluator(WhoSuite.librarySources, MeaslesFixtures.modelInfoXml, terminology)
        val hapiContext = FhirContext.forR4Cached()
        val hapiResolver = R4FhirModelResolver(hapiContext)
        val reference =
            FhirCqlEvaluator(WhoSuite.librarySources, MeaslesFixtures.modelInfoXml, terminology, modelResolverFactory = { hapiResolver })

        val differingResults = mutableListOf<String>()
        val byPath = sortedMapOf<String, Int>()
        val examples = mutableMapOf<String, String>()
        var compared = 0
        var newlyCovered = 0
        for (scenario in WhoSuite.scenarios) {
            val oursResources =
                try {
                    WhoSuite.kotlinFhirResources(scenario)
                } catch (e: Exception) {
                    continue
                }
            val hapiResources = hapiResources(scenario, hapiResolver, hapiContext)
            val referenceResults = WhoSuite.evaluateClosureAs(reference, scenario, hapiResources, MeaslesFixtures::normalizeFull) { it }
            val referenceShort = WhoSuite.evaluateClosure(reference, scenario, hapiResources)
            val oursResults = WhoSuite.evaluateClosureAs(ours, scenario, oursResources, MeaslesFixtures::normalizeFull) { it }
            for (key in (oursResults.keys + referenceResults.keys).sorted()) {
                compared++
                // Results whose short rendering leaves something out: a resource, or an extension.
                if (referenceShort[key] != referenceResults[key]?.toString()) newlyCovered++
                val paths = mutableListOf<Triple<String, Any?, Any?>>()
                diff("", oursResults[key], referenceResults[key], paths)
                if (paths.any { (path, o, h) -> !representationOnly(path, o, h) }) differingResults += "${scenario.id} $key"
                for ((path, o, h) in paths) {
                    val kind = "$path  kotlin-fhir: ${shape(o)}  HAPI: ${shape(h)}"
                    byPath[kind] = (byPath[kind] ?: 0) + 1
                    examples.getOrPut(kind) { "${scenario.id} $key: kotlin-fhir ${o.toString().take(200)} | HAPI ${h.toString().take(200)}" }
                }
            }
        }
        println("Element by element: $compared results compared, $newlyCovered of them holding a resource or an extension; ${differingResults.size} differ from HAPI beyond the two representation differences below")
        byPath.entries.sortedByDescending { it.value }.forEach { (kind, n) -> println("  $n x $kind\n      e.g. ${examples[kind]}") }
        differingResults.take(20).forEach { println("  differs: $it") }
        assertTrue(differingResults.isEmpty(), "${differingResults.size} results differ from HAPI, element by element")
    }

    /**
     * The two differences that change no CQL value: HAPI gives an element with no content (such as an empty `meta`
     * that is not in the data) where kotlin-fhir gives null, and HAPI names a backbone element by its Java class.
     */
    private fun representationOnly(path: String, ours: Any?, hapi: Any?): Boolean =
        (ours == null && hapi is Pair<*, *> && (hapi.second as? Map<*, *>)?.values?.all { it == null } == true) ||
            (path.endsWith("(type name)") && hapi == (ours as? String)?.replace(".", "") + "Component")

    /** Collects the paths where [ours] and [hapi] differ; list indexes are dropped from the path. */
    private fun diff(path: String, ours: Any?, hapi: Any?, out: MutableList<Triple<String, Any?, Any?>>) {
        when {
            ours == hapi -> {}
            ours is Pair<*, *> && hapi is Pair<*, *> -> {
                if (ours.first != hapi.first) out += Triple("$path (type name)", ours.first, hapi.first)
                diff("$path/${ours.first}", ours.second, hapi.second, out)
            }
            ours is Map<*, *> && hapi is Map<*, *> ->
                (ours.keys + hapi.keys).forEach { k -> diff("$path.$k", ours[k], hapi[k], out) }
            ours is List<*> && hapi is List<*> && ours.size == hapi.size ->
                ours.indices.forEach { i -> diff("$path[]", ours[i], hapi[i], out) }
            else -> out += Triple(path, ours, hapi)
        }
    }

    private fun shape(v: Any?): String =
        when (v) {
            null -> "null"
            is Pair<*, *> -> "(${v.first}${if ((v.second as? Map<*, *>)?.values?.all { it == null } == true) ", all elements null" else ""})"
            is List<*> -> "list of ${v.size}"
            else -> "'$v'"
        }

    companion object {
        private val prettyJson = Json { prettyPrint = true }

        fun report(label: String, outcomes: Map<String, WhoOutcome>) {
            val byId = WhoSuite.scenarios.associateBy { it.id }
            val guidance = outcomes.values.mapNotNull { it.guidanceMatchesWho }
            val contained = outcomes.values.mapNotNull { it.containedMatchesWho }
            val withErrors = outcomes.filterValues { it.errors.isNotEmpty() }
            println("$label: guidance matches WHO ${guidance.count { it }}/${guidance.size}; resource count matches WHO ${contained.count { it }}/${contained.size}; scenarios with errors ${withErrors.size}")
            val twins = outcomes.filterKeys { byId.getValue(it).twinOf != null }
            println("$label: Condition twins matching WHO on both checks ${twins.values.count { it.guidanceMatchesWho != false && it.containedMatchesWho != false }}/${twins.size}")
            outcomes.filterValues { it.guidanceMatchesWho == false || it.containedMatchesWho == false }.entries.take(25).forEach { (id, o) ->
                val s = byId.getValue(id)
                println("  $label mismatch $id: contained ${o.contained} (WHO ${s.expectedContained}); guidance ${o.guidance}\n      WHO guidance: ${s.expectedGuidance}")
            }
            withErrors.entries.take(10).forEach { (id, o) -> println("  $label error $id: ${o.errors.first().take(400)}") }
        }
    }
}
