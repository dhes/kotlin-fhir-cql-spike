package io.github.dhes.kotlinfhircql

import kotlinx.serialization.Serializable
import org.cqframework.cql.cql2elm.ucum.UcumService

/** One CQL expression that uses a quantity with a unit. */
@Serializable
data class QuantityCase(val name: String, val expression: String, val file: String? = null, val expected: List<String?>? = null)

/** Quantity expressions from cqframework's copy of the CQL test suite and from the spike, evaluated with a UCUM service. */
object QuantityCases {
    const val EXPECTED = "fixtures/expected/quantity-results.json"

    /** The cases, keyed by test file (or `spike`) and name. */
    val cases: Map<String, QuantityCase> by lazy {
        val suite: List<QuantityCase> =
            MeaslesFixtures.json.decodeFromString(MeaslesFixtures.readText("third_party/cqframework-cql-tests-5.4.0/quantity-cases.json"))
        val spike: List<QuantityCase> = MeaslesFixtures.json.decodeFromString(MeaslesFixtures.readText("fixtures/ucum/spike-quantity-cases.json"))
        (suite.map { "${it.file}.${it.name}" to it } + spike.map { "spike.${it.name}" to it }).toMap()
    }

    /** Each case's value with [ucum], or the error it raises, compiling or evaluating. */
    fun evaluate(ucum: Lazy<UcumService>): Map<String, String> {
        val names = cases.keys.withIndex().associate { (i, key) -> "Q$i" to key }
        val evaluator =
            FhirCqlEvaluator(
                { name -> names[name]?.let { "library $name\n\ndefine \"Value\": ${cases.getValue(it).expression}\n" } },
                MeaslesFixtures.modelInfoXml,
                ucumService = ucum,
            )
        return names.entries.associate { (library, key) ->
            key to
                try {
                    MeaslesFixtures.render(evaluator.evaluate(library, "p1", emptyList(), evaluationDateTime = whoTestDateTime())["Value"]).toString()
                } catch (e: Throwable) {
                    "error: ${rootCause(e)}"
                }
        }
    }

    private fun rootCause(e: Throwable): String {
        var cause = e
        while (cause.cause != null && cause.cause !== cause) cause = cause.cause!!
        return "${cause::class.simpleName}: ${cause.message?.lineSequence()?.first()}"
    }
}
