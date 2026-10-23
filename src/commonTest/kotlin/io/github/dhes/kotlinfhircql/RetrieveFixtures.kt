package io.github.dhes.kotlinfhircql

import dev.ohs.fhir.model.r4.Bundle
import dev.ohs.fhir.model.r4.Resource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.cqframework.cql.cql2elm.CqlCompilerOptions
import org.cqframework.cql.elm.serializing.ElmJsonLibraryWriter
import org.hl7.elm.r1.VersionedIdentifier
import org.opencds.cqf.cql.engine.runtime.ClassInstance
import org.opencds.cqf.cql.engine.runtime.TemporalHelper

/** Step 10's own CQL and data for the retrieve provider: [LIBRARY] over one patient's bundle. */
object RetrieveFixtures {
    const val LIBRARY = "DateRangeTests"
    private const val DIR = "fixtures/retrieve"

    /** The definitions whose retrieve the compiler gives a date range, with its date path. */
    val EXPECTED_DATE_PATHS =
        mapOf(
            "Conditions recorded during" to "recordedDate",
            "Conditions recorded in" to "recordedDate",
            "Conditions recorded during day of" to "recordedDate",
            "Conditions recorded during, active" to "recordedDate",
            "Encounters during" to "period",
            "Encounters during, finished" to "period",
            "Observations effective as dateTime during" to "effective",
            "Observations effective as Period during" to "effective",
            "Heart rates effective as dateTime during" to "effective",
            "Immunizations occurring as dateTime during" to "occurrence",
        )

    /**
     * The definitions no retrieve can answer as the where clause does, because the compiler drops part of the test
     * when it moves it: the precision of `during day of`, and the type of `as dateTime` or `as Period` on a choice
     * element. The retrieve has the date path and range only, and the where clause is gone.
     */
    val LOST_IN_COMPILATION =
        setOf(
            "Conditions recorded during day of",
            "Observations effective as dateTime during",
            "Observations effective as Period during",
            "Heart rates effective as dateTime during",
        )

    val librarySources: List<String> by lazy {
        listOf(MeaslesFixtures.readText("$DIR/$LIBRARY.cql"), WhoSuite.fhirHelpersSource)
    }

    fun bundleJson(): String = MeaslesFixtures.readText("$DIR/bundle.json")

    fun kotlinFhirResources(): List<ClassInstance> {
        val bundle = MeaslesFixtures.json.decodeFromString<Resource>(bundleJson()) as Bundle
        val converter = KotlinFhirToCql(TemporalHelper.zoneToOffset(whoTestDateTime().getOffset()))
        return bundle.entry.map { converter.convert(it.resource!!) }
    }

    /** An evaluator of [librarySources], with the compiler's date range optimization when [optimized]. */
    fun evaluator(optimized: Boolean, modelResolverFactory: ((org.cqframework.cql.cql2elm.ModelManager) -> org.opencds.cqf.cql.engine.model.ModelResolver)? = null) =
        if (modelResolverFactory == null)
            FhirCqlEvaluator(librarySources, MeaslesFixtures.modelInfoXml, ucumService = platformUcumService(), compilerOptions = options(optimized))
        else
            FhirCqlEvaluator(
                librarySources,
                MeaslesFixtures.modelInfoXml,
                modelResolverFactory = modelResolverFactory,
                ucumService = platformUcumService(),
                compilerOptions = options(optimized),
            )

    private fun options(optimized: Boolean) =
        if (optimized) FhirCqlEvaluator.compilerOptions(CqlCompilerOptions.Options.EnableDateRangeOptimization)
        else FhirCqlEvaluator.compilerOptions()

    /** Every definition of [LIBRARY], as the references of the resources it returns. */
    fun evaluate(evaluator: FhirCqlEvaluator, resources: List<ClassInstance>): Map<String, String?> =
        evaluator.evaluate(LIBRARY, "p1", resources, evaluationDateTime = whoTestDateTime())
            .mapValues { MeaslesFixtures.render(it.value) }

    /** For each definition of [LIBRARY] whose retrieve carries a date range, the retrieve's date path. */
    fun datePaths(evaluator: FhirCqlEvaluator): Map<String, String> {
        val library = evaluator.libraryManager.resolveLibrary(VersionedIdentifier().apply { id = LIBRARY }).library!!
        val elm = Json.parseToJsonElement(ElmJsonLibraryWriter().writeAsString(library))
        val defs = elm.jsonObject.getValue("library").jsonObject.getValue("statements").jsonObject.getValue("def").jsonArray
        return defs.associate { def ->
            def.jsonObject.getValue("name").jsonPrimitive.content to retrieveDatePath(def.jsonObject["expression"])
        }.filterValues { it != null }.mapValues { it.value!! }
    }

    private fun retrieveDatePath(e: JsonElement?): String? =
        when (e) {
            is JsonObject ->
                if (e["type"]?.jsonPrimitive?.content == "Retrieve" && e["dateRange"] != null)
                    e["dateProperty"]?.jsonPrimitive?.content ?: "(low/high paths)"
                else e.values.firstNotNullOfOrNull(::retrieveDatePath)
            is JsonArray -> e.firstNotNullOfOrNull(::retrieveDatePath)
            else -> null
        }
}
