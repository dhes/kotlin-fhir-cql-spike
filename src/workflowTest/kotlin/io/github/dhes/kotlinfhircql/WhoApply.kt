package io.github.dhes.kotlinfhircql

import dev.ohs.fhir.model.r4.CarePlan
import dev.ohs.fhir.model.r4.CommunicationRequest
import dev.ohs.fhir.model.r4.Patient
import dev.ohs.fhir.model.r4.PlanDefinition
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import dev.ohs.fhir.workflow.CanonicalResolver
import dev.ohs.fhir.workflow.FhirOperator
import dev.ohs.fhir.workflow.expression.ExpressionEvaluatorRouter
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.opencds.cqf.cql.engine.runtime.Date

/** What kotlin-fhir-workflow's `$apply` returned for one WHO scenario, and what WHO's test expects. */
data class ApplyOutcome(val contained: Int?, val guidance: String?, val error: String?, val carePlan: CarePlan? = null)

/** WHO's PlanDefinitions run through kotlin-fhir-workflow's `FhirOperator.generateCarePlan`, with CQL behind it. */
object WhoApply {
    private const val DEFINITIONS_DIR = "third_party/smart-immunizations-0.2.0/definitions"

    /** WHO's PlanDefinitions and ActivityDefinitions, as JSON, by canonical url. */
    val definitionsJson: Map<String, JsonObject> by lazy {
        MeaslesFixtures.list(DEFINITIONS_DIR).associate { name ->
            val resource = MeaslesFixtures.json.parseToJsonElement(MeaslesFixtures.readText("$DEFINITIONS_DIR/$name")).jsonObject
            resource.getValue("url").jsonPrimitive.content to resource
        }
    }

    /** The same resources, conformed to what kotlin-fhir-workflow `main` takes (see [conform]) and decoded. */
    val definitions: Map<String, Resource> by lazy {
        definitionsJson.mapValues { (_, json) -> MeaslesFixtures.json.decodeFromString(Resource.serializer(), conform(json).toString()) }
    }

    /**
     * Rewrites what kotlin-fhir-workflow `main` (214c56d) doesn't take yet, in each PlanDefinition action:
     * - the expression languages `text/cql-identifier` and `text/cql-expression` to `text/cql`, the only CQL language
     *   its `toProtocolExpression` routes; [CqlExpressionEvaluator] tells a definition name from an expression;
     * - three dynamicValue paths to `DynamicValueApplier`'s path contract (ohs-foundation/kotlin-fhir-workflow#9):
     *   `category.coding` to `category[0].coding[0]`, `payload.contentString` to `payload[0].contentString`, and
     *   `medication` to `medicationCodeableConcept`;
     * - a CQL Code written to `priority`, a FHIR `code`, to the Code's `code`.
     */
    fun conform(json: JsonObject): JsonObject = rewrite(json) as JsonObject

    private val PATHS =
        mapOf(
            "category.coding" to "category[0].coding[0]",
            "payload.contentString" to "payload[0].contentString",
            "medication" to "medicationCodeableConcept",
        )

    private fun rewrite(element: JsonElement, key: String? = null): JsonElement =
        when (element) {
            is JsonArray -> JsonArray(element.map { rewrite(it, key) })
            is JsonObject ->
                if (key == "dynamicValue") {
                    val path = element["path"]?.jsonPrimitive?.content
                    val expression = element.getValue("expression").jsonObject
                    val text = expression.getValue("expression").jsonPrimitive.content
                    JsonObject(
                        element +
                            mapOf(
                                "path" to JsonPrimitive(PATHS[path] ?: path),
                                "expression" to
                                    cqlExpression(expression, if (path == "priority") "($text).code" else text),
                            )
                    )
                } else if (key == "condition") {
                    val expression = element.getValue("expression").jsonObject
                    JsonObject(element + ("expression" to cqlExpression(expression, expression.getValue("expression").jsonPrimitive.content)))
                } else {
                    JsonObject(element.mapValues { (k, v) -> rewrite(v, k) })
                }
            else -> element
        }

    private fun cqlExpression(expression: JsonObject, text: String): JsonObject {
        val language = expression.getValue("language").jsonPrimitive.content
        return if (language == "text/cql-identifier" || language == "text/cql-expression") {
            JsonObject(expression + mapOf("language" to JsonPrimitive("text/cql"), "expression" to JsonPrimitive(text)))
        } else {
            expression
        }
    }

    /** One evaluator for every scenario, so each library is compiled once. */
    val evaluator: Pair<FhirCqlEvaluator, InlineCqlLibraries> by lazy {
        val inline = InlineCqlLibraries()
        val evaluator =
            FhirCqlEvaluator(
                WhoSuite.librarySources,
                MeaslesFixtures.modelInfoXml,
                InMemoryTerminologyProvider(WhoSuite.valueSets),
                ucumService = platformUcumService(),
            )
        evaluator.libraryManager.librarySourceLoader.registerProvider(inline)
        evaluator to inline
    }

    /** WHO's resources as published, decoded without [conform]. */
    val publishedDefinitions: Map<String, Resource> by lazy {
        definitionsJson.mapValues { (_, json) -> MeaslesFixtures.json.decodeFromString(Resource.serializer(), json.toString()) }
    }

    /** [resource] as [conform] rewrites it. */
    fun conformed(resource: Resource): Resource {
        val json = MeaslesFixtures.json.encodeToJsonElement(Resource.serializer(), resource).jsonObject
        return MeaslesFixtures.json.decodeFromJsonElement(Resource.serializer(), conform(json))
    }

    /**
     * Applies the scenario's PlanDefinition to its patient with kotlin-fhir-workflow's `FhirOperator`: as [conform]
     * rewrites it, or as published when [published] is true. The PlanDefinition and the ActivityDefinitions its
     * actions name come from [resolver] when one is given, and from the spike's copies otherwise.
     */
    suspend fun apply(scenario: WhoScenario, published: Boolean = false, resolver: CanonicalResolver? = null): ApplyOutcome =
        try {
            val (evaluator, inline) = evaluator
            val source = if (published) publishedDefinitions else definitions
            val planUrl = "http://smart.who.int/immunizations/PlanDefinition/${scenario.planDefinition}"
            val plan = (resolver?.resolve(ResourceType.PlanDefinition, planUrl) ?: source.getValue(planUrl)) as PlanDefinition
            val bundle = WhoSuite.bundleResources(scenario)
            val patient = bundle.filterIsInstance<Patient>().single { it.id == scenario.patient }
            val (y, m, d) = scenario.today.split("-").map(String::toInt)
            val cql =
                CqlExpressionEvaluator(
                    evaluator,
                    inline,
                    WhoSuite.plans.getValue(scenario.planDefinition).library,
                    scenario.patient,
                    WhoSuite.kotlinFhirResources(scenario),
                    mapOf("Today" to Date(y, m, d)),
                    WhoSuite.evaluationDateTime(scenario),
                )
            val definitions = resolver ?: CanonicalResolver { _, canonical -> source[canonical.substringBefore("|")] }
            val operator = FhirOperator(InMemoryWorkflowRepository(emptyList()), ExpressionEvaluatorRouter(elm = cql), definitions)
            val carePlan: CarePlan = operator.generateCarePlan(plan, patient, today = LocalDate(y, m, d))
            val guidance =
                carePlan.contained.filterIsInstance<CommunicationRequest>().firstNotNullOfOrNull { request ->
                    request.payload.firstOrNull()?.content?.asString()?.value?.value
                }
            ApplyOutcome(carePlan.contained.size, guidance, null, carePlan)
        } catch (e: Throwable) {
            ApplyOutcome(null, null, "${e::class.simpleName}: ${e.message?.lineSequence()?.first()}")
        }
}
