package io.github.dhes.kotlinfhircql

import dev.ohs.fhir.model.r4.Bundle
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.ValueSet
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.json.Json
import org.opencds.cqf.cql.engine.runtime.ClassInstance
import org.opencds.cqf.cql.engine.runtime.Date
import org.opencds.cqf.cql.engine.runtime.TemporalHelper
import org.opencds.cqf.cql.engine.runtime.Tuple
import org.opencds.cqf.cql.engine.runtime.Value
import org.opencds.cqf.cql.engine.util.ZonedDateTime
import org.opencds.cqf.cql.engine.runtime.List as CqlList
import org.opencds.cqf.cql.engine.runtime.String as CqlString

/** 2025-11-12 at noon in the system time zone: the date WHO's measles test suite evaluates against. */
expect fun whoTestDateTime(): ZonedDateTime

/** WHO's measles closure, value sets and example patients, read from the project directory. */
object MeaslesFixtures {
    const val LOGIC_LIBRARY = "IMMZD2DTMeaslesOngoingTransmissionLogic"
    private const val CQL_DIR = "third_party/smart-immunizations-0.2.0/cql"
    private const val TESTS_DIR = "fixtures/who-measles-tests"
    const val EXPECTED_RESULTS = "fixtures/expected/measles-closure-results.json"

    val json = Json { ignoreUnknownKeys = true }

    fun readText(relative: String): String =
        SystemFileSystem.source(Path(PROJECT_DIR, relative)).buffered().use { it.readString() }

    private fun list(relative: String): List<String> =
        SystemFileSystem.list(Path(PROJECT_DIR, relative)).map { it.name }.sorted()

    val modelInfoXml: String by lazy { readText("third_party/cqframework-quick-5.4.0/fhir-modelinfo-4.0.1.xml") }

    /** The logic library and the 10 libraries it includes, directly or not. */
    val libraries: List<String> by lazy { WhoSuite.closure(LOGIC_LIBRARY) }

    val librarySources: List<String> by lazy {
        libraries.map { readText("$CQL_DIR/$it.cql") } + readText("third_party/cqframework-quick-5.4.0/FHIRHelpers-4.0.1.cql")
    }

    val valueSets: List<ValueSet> by lazy {
        val dir = "third_party/smart-immunizations-0.2.0/valuesets"
        list(dir).map { json.decodeFromString<Resource>(readText("$dir/$it")) as ValueSet }
    }

    /** Patient id to the guidance WHO's test suite expects. */
    val expectedGuidance: Map<String, String> by lazy {
        Regex("""@patient=(\S+)[\s\S]*?contentString = "(.*?)"\n""")
            .findAll(readText("$TESTS_DIR/IMMZD2DTMeaslesOngoingTransmission.feature"))
            .associate { it.groupValues[1] to it.groupValues[2].replace("\\n", "\n") }
    }

    val patients: List<String> by lazy { expectedGuidance.keys.sorted() }

    val parameters: Map<String, Value?> = mapOf("Today" to Date(2025, 11, 12))

    fun bundleJson(patient: String): String = readText("$TESTS_DIR/$patient.json")

    fun kotlinFhirResources(patient: String): List<ClassInstance> {
        val bundle = json.decodeFromString<Resource>(bundleJson(patient)) as Bundle
        val converter = KotlinFhirToCql(TemporalHelper.zoneToOffset(whoTestDateTime().getOffset()))
        return bundle.entry.map { converter.convert(it.resource!!) }
    }

    /** Every definition of every library in the closure, as `Library.Definition` to its rendered result. */
    fun evaluateAll(evaluator: FhirCqlEvaluator, patient: String, resources: List<ClassInstance>): Map<String, String?> =
        libraries.flatMap { library ->
            try {
                evaluator.evaluate(library, patient, resources, null, parameters, whoTestDateTime())
                    .map { (name, value) -> "$library.$name" to render(value) }
            } catch (e: Exception) {
                listOf("$library (error)" to "${e::class.simpleName}: ${e.message}")
            }
        }.toMap()

    /** A comparable rendering of a result: resources by type and id; other values by their elements. */
    fun render(value: Value?): String? = normalize(value, full = false)?.toString()

    /** A comparable rendering of a result with every element of every value, resources and extensions included. */
    fun renderFull(value: Value?): String? = normalize(value, full = true)?.toString()

    /** The structure [renderFull] prints: maps, lists, (type, elements) pairs and strings. */
    fun normalizeFull(value: Value?): Any? = normalize(value, full = true)

    private fun normalize(value: Value?, full: Boolean): Any? =
        when (value) {
            null -> null
            is CqlList -> value.map { normalize(it, full) }
            is ClassInstance -> {
                val id = (value.elements["id"] as? ClassInstance)?.elements?.get("value") as? CqlString
                if (id != null && !full) {
                    "${value.type.getLocalPart()}/${id.value}"
                } else {
                    value.type.getLocalPart() to
                        value.elements
                            .filterKeys { full || (it != "extension" && it != "modifierExtension") }
                            .mapValues { normalize(it.value, full) }
                            .entries.sortedBy { it.key }.associate { it.key to it.value }
                }
            }
            is Tuple -> value.elements.mapValues { normalize(it.value, full) }.entries.sortedBy { it.key }.associate { it.key to it.value }
            else -> value.toString()
        }
}
