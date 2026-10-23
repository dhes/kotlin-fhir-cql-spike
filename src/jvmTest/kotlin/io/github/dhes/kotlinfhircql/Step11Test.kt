package io.github.dhes.kotlinfhircql

import ca.uhn.fhir.context.FhirContext
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.hl7.fhir.r4.model.Bundle
import org.hl7.fhir.r4.model.ValueSet
import org.opencds.cqf.cql.engine.terminology.TerminologyProvider
import org.opencds.cqf.cql.engine.terminology.ValueSetInfo
import org.opencds.cqf.fhir.cql.engine.terminology.RepositoryTerminologyProvider
import org.opencds.cqf.fhir.cql.engine.terminology.TerminologySettings
import org.opencds.cqf.fhir.utility.repository.InMemoryFhirRepository

/**
 * Step 11: the terminology provider compared with clinical-reasoning's `RepositoryTerminologyProvider` (cqf-fhir-cql
 * 4.13.0, built on cqframework 5.4.0) over the same resources, read by HAPI into its in-memory repository.
 */
class Step11Test {
    private val context = FhirContext.forR4Cached()

    /** clinical-reasoning's provider, expanding a value set with no expansion from its compose, as the spike does. */
    private fun clinicalReasoning(resources: List<org.hl7.fhir.r4.model.Resource>): TerminologyProvider {
        val bundle = Bundle().setType(Bundle.BundleType.COLLECTION)
        resources.forEach { bundle.addEntry().resource = it }
        val settings = TerminologySettings().setValuesetExpansionMode(TerminologySettings.VALUESET_EXPANSION_MODE.PERFORM_NAIVE_EXPANSION)
        return RepositoryTerminologyProvider(InMemoryFhirRepository(context, bundle), settings)
    }

    @Test
    fun terminologyTestsAgreeExceptForCompleteCodeSystems() {
        val hapi = context.newJsonParser().parseResource(Bundle::class.java, TerminologyFixtures.terminologyJson())
        val reference = clinicalReasoning(hapi.entry.map { it.resource })
        val evaluator = FhirCqlEvaluator(TerminologyFixtures.librarySources, MeaslesFixtures.modelInfoXml)
        val results = TerminologyFixtures.evaluate(evaluator, reference, TerminologyFixtures.kotlinFhirResources())
        val differing = results.filter { (k, v) -> v != TerminologyFixtures.EXPECTED[k] }
        differing.forEach { (k, v) -> println("clinical-reasoning differs: $k: $v (spike: ${TerminologyFixtures.EXPECTED[k]})") }
        assertEquals(TerminologyFixtures.EXPECTED_DIFFERENCES, differing)
    }

    private fun expansion(provider: TerminologyProvider, info: ValueSetInfo): List<String> =
        try {
            provider.expand(info).map(TerminologyFixtures::render)
        } catch (e: Exception) {
            listOf("error: ${e::class.simpleName}: ${e.message}")
        }

    /**
     * WHO's 192 value sets expand to the same codes, with the same versions and displays, in the same order, except
     * four that WHO's suite never expands: two whose expansion lists no codes, where clinical-reasoning fails and the
     * spike gives none, and two whose compose only includes other value sets, where clinical-reasoning gives no codes
     * and the spike gives the codes of those value sets, checked here against the union worked out from the files.
     */
    @Test
    fun whoValueSetsExpandTheSame() {
        val dir = File(PROJECT_DIR, "third_party/smart-immunizations-0.2.0/valuesets")
        val hapi = dir.listFiles()!!.filter { it.name.endsWith(".json") }.map { context.newJsonParser().parseResource(ValueSet::class.java, it.readText()) }
        val reference = clinicalReasoning(hapi)
        val ours = InMemoryTerminologyProvider(WhoSuite.valueSets)
        assertEquals(192, hapi.size)
        var codes = 0
        val differing = mutableListOf<String>()
        for (vs in hapi) {
            val info = ValueSetInfo().withId(vs.url)
            val expected = expansion(reference, info)
            val actual = expansion(ours, info)
            if (expected == actual) codes += expected.size
            if (expected != actual) differing += vs.url.substringAfterLast('/').also { println("$it\n    clinical-reasoning: $expected\n    spike:              $actual") }
        }
        println("WHO value sets: ${hapi.size}; ${hapi.size - differing.size} expand the same ($codes codes); ${differing.size} differently")
        // IMMZ.Z.VS includes value sets one by one, so it is their union; IMMZ.D.DE19 includes IMMZ.Z.VS alone.
        val byUrl = hapi.associateBy { it.url }
        val union =
            byUrl.getValue("http://smart.who.int/immunizations/ValueSet/IMMZ.Z.VS").compose.include
                .flatMap { include -> include.valueSet.flatMap { byUrl.getValue(it.value).expansion.contains.map { c -> "${c.system}|${c.code}" } } }
                .toSet()
        for (name in listOf("IMMZ.Z.VS", "IMMZ.D.DE19")) {
            val spike = ours.expand(ValueSetInfo().withId("http://smart.who.int/immunizations/ValueSet/$name")).map { "${it.system}|${it.code}" }
            assertEquals(union, spike.toSet(), name)
            println("$name: ${spike.size} codes, the union of the value sets it includes")
        }
        assertEquals(setOf("IMMZD2DTVaricella1DoseVS", "IMMZD2DTVaricella2DosesVS", "IMMZ.Z.VS", "IMMZ.D.DE19"), differing.toSet())
    }

    /** WHO's whole suite, every definition, with clinical-reasoning's provider in place of the spike's. */
    @Test
    fun whoSuiteAgreesWithClinicalReasoningTerminology() {
        val dir = File(PROJECT_DIR, "third_party/smart-immunizations-0.2.0/valuesets")
        val hapi = dir.listFiles()!!.filter { it.name.endsWith(".json") }.map { context.newJsonParser().parseResource(ValueSet::class.java, it.readText()) }
        val ours = FhirCqlEvaluator(WhoSuite.librarySources, MeaslesFixtures.modelInfoXml, InMemoryTerminologyProvider(WhoSuite.valueSets))
        val reference = FhirCqlEvaluator(WhoSuite.librarySources, MeaslesFixtures.modelInfoXml, clinicalReasoning(hapi))
        var compared = 0
        val differences = mutableListOf<String>()
        for (scenario in WhoSuite.scenarios) {
            val resources = try { WhoSuite.kotlinFhirResources(scenario) } catch (e: Exception) { continue }
            val a = WhoSuite.evaluateClosure(ours, scenario, resources)
            val b = WhoSuite.evaluateClosure(reference, scenario, resources)
            for (key in (a.keys + b.keys)) {
                compared++
                if (a[key] != b[key]) differences += "${scenario.id} $key\n    spike: ${a[key]}\n    clinical-reasoning: ${b[key]}"
            }
        }
        println("WHO suite with clinical-reasoning's terminology provider: $compared results compared; ${differences.size} differ")
        differences.take(20).forEach(::println)
        assertTrue(differences.isEmpty())
    }
}
