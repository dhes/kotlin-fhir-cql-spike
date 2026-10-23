package io.github.dhes.kotlinfhircql

import dev.ohs.fhir.model.r4.Bundle
import dev.ohs.fhir.model.r4.Library
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.ValueSet
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.Serializable
import org.opencds.cqf.cql.engine.runtime.ClassInstance
import org.opencds.cqf.cql.engine.runtime.Date
import org.opencds.cqf.cql.engine.runtime.TemporalHelper
import org.opencds.cqf.cql.engine.runtime.Value
import org.opencds.cqf.cql.engine.util.ZonedDateTime
import org.opencds.cqf.cql.engine.runtime.String as CqlString

/** Noon in the system time zone on the given date. */
expect fun noonOn(year: Int, month: Int, day: Int): org.opencds.cqf.cql.engine.util.ZonedDateTime

@Serializable
data class WhoScenario(
    val id: String,
    val feature: String,
    val scenario: String,
    val planDefinition: String,
    val today: String,
    val patient: String,
    val bundle: String,
    val expectedGuidance: String? = null,
    val expectedContained: Int? = null,
    val twinOf: String? = null,
)

@Serializable data class WhoAction(val definition: String, val condition: String)

@Serializable data class WhoPlan(val library: String, val actions: List<WhoAction>)

/** What one scenario produced: a digest of every definition in the closure, and what WHO's test checks. */
@Serializable
data class WhoOutcome(
    val digest: String,
    val results: Int,
    val errors: List<String>,
    val guidance: String?,
    val contained: Int,
    val guidanceMatchesWho: Boolean?,
    val containedMatchesWho: Boolean?,
)

/** WHO's immunization test suite (smart-immunizations at 12ec2fc54) and package 0.2.0, read from the project. */
object WhoSuite {
    private const val CQL_DIR = "third_party/smart-immunizations-0.2.0/cql"
    const val EXPECTED = "fixtures/expected/who-suite-outcomes.json"

    private val json = MeaslesFixtures.json
    private fun read(relative: String) = MeaslesFixtures.readText(relative)

    val scenarios: List<WhoScenario> by lazy { json.decodeFromString(read("fixtures/who-suite/scenarios.json")) }
    val plans: Map<String, WhoPlan> by lazy { json.decodeFromString(read("fixtures/who-suite/plandefinitions.json")) }

    private val cqlByName: Map<String, String> by lazy {
        SystemFileSystem.list(Path(PROJECT_DIR, CQL_DIR)).map { it.name }.filter { it.endsWith(".cql") }
            .associate { it.removeSuffix(".cql") to read("$CQL_DIR/$it") }
    }

    val librarySources: List<String> by lazy {
        cqlByName.values.toList() + read("third_party/cqframework-quick-5.4.0/FHIRHelpers-4.0.1.cql")
    }

    val valueSets: List<ValueSet> by lazy {
        val dir = "third_party/smart-immunizations-0.2.0/valuesets"
        SystemFileSystem.list(Path(PROJECT_DIR, dir)).map { it.name }.sorted().map {
            json.decodeFromString<Resource>(read("$dir/$it")) as ValueSet
        }
    }

    private val includePattern = Regex("""^\s*include\s+"?([A-Za-z0-9_]+)"?""", RegexOption.MULTILINE)

    /** The library and every library it includes, directly or not, except FHIRHelpers. */
    fun closure(library: String): List<String> {
        val seen = linkedSetOf<String>()
        fun visit(name: String) {
            if (name == "FHIRHelpers" || !seen.add(name)) return
            includePattern.findAll(cqlByName.getValue(name)).forEach { visit(it.groupValues[1]) }
        }
        visit(library)
        return seen.sorted()
    }

    fun bundleJson(scenario: WhoScenario) = read("fixtures/who-suite/data/${scenario.bundle}")

    /** WHO's Library resources, with their CQL only (see tools/copy_who_libraries.py). */
    val libraryResources: List<Library> by lazy {
        val dir = "third_party/smart-immunizations-0.2.0/libraries"
        SystemFileSystem.list(Path(PROJECT_DIR, dir)).map { it.name }.sorted().map {
            json.decodeFromString<Resource>(read("$dir/$it")) as Library
        }
    }

    val fhirHelpersSource: String by lazy { read("third_party/cqframework-quick-5.4.0/FHIRHelpers-4.0.1.cql") }

    /** The canonical url of the scenario's logic library, as WHO's PlanDefinitions give it. */
    fun libraryUrl(scenario: WhoScenario) =
        "http://smart.who.int/immunizations/Library/${plans.getValue(scenario.planDefinition).library}"

    fun bundleResources(scenario: WhoScenario): List<Resource> =
        (json.decodeFromString<Resource>(bundleJson(scenario)) as Bundle).entry.mapNotNull { it.resource }

    fun converter(scenario: WhoScenario) =
        KotlinFhirToCql(TemporalHelper.zoneToOffset(evaluationDateTime(scenario).getOffset()))

    fun kotlinFhirResources(scenario: WhoScenario): List<ClassInstance> {
        val converter = converter(scenario)
        return bundleResources(scenario).map { converter.convert(it) }
    }

    fun evaluationDateTime(scenario: WhoScenario) =
        scenario.today.split("-").map(String::toInt).let { (y, m, d) -> noonOn(y, m, d) }

    /** Every definition of every library in the closure of the scenario's logic library. */
    fun evaluateClosure(
        evaluator: FhirCqlEvaluator,
        scenario: WhoScenario,
        resources: List<ClassInstance>,
    ): Map<String, String?> = evaluateClosureAs(evaluator, scenario, resources, MeaslesFixtures::render) { it }

    /** As [evaluateClosure], with each value passed through [convert] and each library error through [error]. */
    fun <T> evaluateClosureAs(
        evaluator: FhirCqlEvaluator,
        scenario: WhoScenario,
        resources: List<ClassInstance>,
        convert: (Value?) -> T,
        error: (String) -> T,
    ): Map<String, T> =
        evaluateLibrariesAs(scenario, closure(plans.getValue(scenario.planDefinition).library), convert, error) {
            library, parameters, dateTime ->
            evaluator.evaluate(library, scenario.patient, resources, null, parameters, dateTime)
        }

    /** Every definition of each of [libraries], evaluated by [evaluate] with the scenario's parameters. */
    fun <T> evaluateLibrariesAs(
        scenario: WhoScenario,
        libraries: List<String>,
        convert: (Value?) -> T,
        error: (String) -> T,
        evaluate: (library: String, parameters: Map<String, Value?>, dateTime: ZonedDateTime) -> Map<String, Value?>,
    ): Map<String, T> {
        val (y, m, d) = scenario.today.split("-").map(String::toInt)
        val parameters = mapOf("Today" to Date(y, m, d))
        val dateTime = evaluationDateTime(scenario)
        return libraries.flatMap { library ->
            try {
                evaluate(library, parameters, dateTime).map { (name, value) -> "$library.$name" to convert(value) }
            } catch (e: Throwable) {
                // Throwable, not Exception: on JS and Wasm the engine's Split throws NotImplementedError.
                listOf("$library (error)" to error("${e::class.simpleName}: ${e.message}"))
            }
        }.toMap()
    }

    fun outcome(scenario: WhoScenario, results: Map<String, String?>): WhoOutcome {
        val plan = plans.getValue(scenario.planDefinition)
        val guidance = results["${plan.library}.Guidance"]
        // $apply returns a RequestGroup plus one resource per action whose applicability condition is true.
        val contained = 1 + plan.actions.count { results["${plan.library}.${it.condition}"] == "true" }
        return WhoOutcome(
            digest = digest(results),
            results = results.size,
            errors = results.filterKeys { it.endsWith("(error)") }.map { (k, v) -> "$k $v" },
            guidance = guidance,
            contained = contained,
            guidanceMatchesWho = scenario.expectedGuidance?.let { CqlString(it).toString() == guidance },
            containedMatchesWho = scenario.expectedContained?.let { it == contained },
        )
    }

    /** FNV-1a (64-bit) over the sorted results, as hex. */
    fun digest(results: Map<String, String?>): String {
        var hash = 0xcbf29ce484222325uL
        for ((key, value) in results.entries.sortedBy { it.key }) {
            for (ch in "$key\u0000${value ?: "\u0001"}\n") {
                hash = (hash xor ch.code.toULong()) * 0x100000001b3uL
            }
        }
        return hash.toString(16)
    }
}
