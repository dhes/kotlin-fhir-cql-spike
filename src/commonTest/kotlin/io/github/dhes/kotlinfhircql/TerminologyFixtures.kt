package io.github.dhes.kotlinfhircql

import dev.ohs.fhir.model.r4.Bundle
import dev.ohs.fhir.model.r4.CodeSystem
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.ValueSet
import org.opencds.cqf.cql.engine.runtime.ClassInstance
import org.opencds.cqf.cql.engine.runtime.Code
import org.opencds.cqf.cql.engine.runtime.List as CqlList
import org.opencds.cqf.cql.engine.runtime.TemporalHelper
import org.opencds.cqf.cql.engine.terminology.TerminologyProvider

/** Step 11's own CQL, value sets, code systems and data for the terminology provider. */
object TerminologyFixtures {
    const val LIBRARY = "TerminologyTests"
    private const val DIR = "fixtures/terminology"

    val librarySources: List<String> by lazy { listOf(MeaslesFixtures.readText("$DIR/$LIBRARY.cql"), WhoSuite.fhirHelpersSource) }

    fun terminologyJson(): String = MeaslesFixtures.readText("$DIR/terminology.json")

    fun bundleJson(): String = MeaslesFixtures.readText("$DIR/bundle.json")

    private fun resources(json: String): List<Resource> =
        (MeaslesFixtures.json.decodeFromString<Resource>(json) as Bundle).entry.map { it.resource!! }

    val valueSets: List<ValueSet> by lazy { resources(terminologyJson()).filterIsInstance<ValueSet>() }
    val codeSystems: List<CodeSystem> by lazy { resources(terminologyJson()).filterIsInstance<CodeSystem>() }

    fun kotlinFhirResources(): List<ClassInstance> {
        val converter = KotlinFhirToCql(TemporalHelper.zoneToOffset(whoTestDateTime().getOffset()))
        return resources(bundleJson()).map(converter::convert)
    }

    /** The definitions of [LIBRARY], in the order the source declares them. */
    val definitions: List<String> by lazy {
        Regex("""^define "([^"]+)"""", RegexOption.MULTILINE).findAll(librarySources.first()).map { it.groupValues[1] }.toList()
    }

    /**
     * Each definition of [LIBRARY] evaluated on its own with [terminology], rendered, or as "error: " and the exception
     * class when it throws.
     */
    fun evaluate(evaluator: FhirCqlEvaluator, terminology: TerminologyProvider, resources: List<ClassInstance>): Map<String, String?> =
        definitions.associateWith { name ->
            try {
                val value = evaluator.evaluate(LIBRARY, "p1", resources, listOf(name), terminologyProvider = terminology).getValue(name)
                if (value is CqlList && value.all { it is Code }) value.joinToString(", ", "[", "]") { render(it as Code) }
                else MeaslesFixtures.render(value)
            } catch (e: Exception) {
                "error: ${generateSequence<Throwable>(e) { it.cause }.last()::class.simpleName}"
            }
        }

    /** A code as `system|code`, with `|version` and ` 'display'` when it has them. */
    fun render(code: Code): String =
        "${code.system}|${code.code}" + (code.version?.let { "|$it" } ?: "") + (code.display?.let { " '$it'" } ?: "")

    /** What the spike's provider gives for each definition; [EXPECTED_DIFFERENCES] says where clinical-reasoning's differs. */
    val EXPECTED: Map<String, String?> =
        mapOf(
            "LOINC A in single system" to "true",
            "SNOMED A in single system" to "false",
            "String A in single system" to "true",
            "String no-system in single system" to "false",
            "SNOMED 5678 in multi system" to "true",
            "String 1234 in multi system" to "error: IllegalArgumentException",
            "v1-only in version 1" to "true",
            "v2-only in version 1" to "false",
            "v2-only in version 2" to "true",
            "both in unnamed version" to "error: IllegalArgumentException",
            "both in version 3" to "error: IllegalArgumentException",
            "red in compose only" to "true",
            "A in a value set not held" to "error: IllegalArgumentException",
            "Conditions in single system" to "[Condition/c-loinc-a, Condition/c-no-system-a]",
            "Observations in multi system" to "error: IllegalArgumentException",
            "Female" to "true",
            "Single system expanded" to "[http://loinc.org|A 'Ay', http://loinc.org|B 'Bee']",
            "Compose only expanded" to "[http://example.org/fhir/CodeSystem/colors|blue, http://example.org/fhir/CodeSystem/colors|red 'Red']",
            "Red in Colors" to "true",
            "light-green in Colors" to "true",
            "purple in Colors" to "false",
            "red, version 2.0.0, in Colors" to "false",
            "red in Colors, any version" to "true",
            "String red in Colors" to "false",
            "unlisted in Fragment" to "true",
            "red in a code system not held" to "true",
        )

    /**
     * Where clinical-reasoning's `RepositoryTerminologyProvider` answers differently: it checks a code against a code
     * system by system and version only, so it finds a code a complete CodeSystem resource doesn't list.
     */
    val EXPECTED_DIFFERENCES: Map<String, String?> = mapOf("purple in Colors" to "true")
}
