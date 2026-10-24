package io.github.dhes.kotlinfhircql

import kotlin.test.Test
import kotlin.test.assertEquals
import org.cqframework.cql.shared.BigDecimal
import org.cqframework.cql.shared.RoundingMode

/**
 * Runs on every target: each quantity case with this platform's UCUM service, against the JVM's results. On JS and
 * Wasm, the cases in [KNOWN_DIFFERENCES] differ, for the reasons given there.
 */
class QuantityCasesTest {
    @Test
    fun quantityCasesAgreeWithJvm() {
        val expected: Map<String, String> = MeaslesFixtures.json.decodeFromString(MeaslesFixtures.readText(QuantityCases.EXPECTED))
        val actual = QuantityCases.evaluate(platformUcumService())
        val outcomes = expected.keys.associateWith { compare(expected.getValue(it), actual.getValue(it)) }
        for ((key, outcome) in outcomes.entries.sortedBy { it.key }) {
            if (outcome != Outcome.SAME) {
                println("$outcome $key: ${QuantityCases.cases.getValue(key).expression}\n    JVM:  ${expected[key]}\n    here: ${actual[key]}")
            }
        }
        val counts = outcomes.values.groupingBy { it }.eachCount()
        println("${outcomes.size} cases: " + Outcome.entries.joinToString(", ") { "${counts[it] ?: 0} ${it.label}" })
        val different = outcomes.filterValues { it == Outcome.DIFFERENT }.keys
        if (different.isNotEmpty()) {
            KNOWN_DIFFERENCES.entries.groupBy({ it.value }, { it.key }).forEach { (reason, keys) ->
                println("Known: ${keys.count { it in different }} of ${keys.size}: $reason")
            }
        }
        assertEquals(emptySet(), different - KNOWN_DIFFERENCES.keys, "Cases whose value differs from the JVM's for no known reason")
    }

    enum class Outcome(val label: String) {
        SAME("the same"),
        SAME_AT_8_PLACES("the same when rounded to 8 decimal places"),
        BOTH_ERRORS("errors on both, with different messages"),
        DIFFERENT("different"),
    }

    private fun compare(jvm: String, here: String): Outcome =
        when {
            jvm == here -> Outcome.SAME
            jvm.startsWith("error: ") && here.startsWith("error: ") -> Outcome.BOTH_ERRORS
            rounded(jvm) == rounded(here) -> Outcome.SAME_AT_8_PLACES
            else -> Outcome.DIFFERENT
        }

    /** [rendered] with each quantity's value rounded to CQL's 8 decimal places. */
    private fun rounded(rendered: String): String =
        QUANTITY_VALUE.replace(rendered) { m -> BigDecimal(m.groupValues[1]).setScale(8, RoundingMode.HALF_UP).toString() + " '" }

    private companion object {
        const val DECIMAL_SCALE = "cqframework's JS and Wasm BigDecimal rounds a product or quotient to an operand's scale"
        const val STRIP_ZEROS = "cqframework's JS and Wasm BigDecimal.stripTrailingZeros() is not implemented (TODO())"
        const val OFFSET_UNITS = "the JVM's fhir-ucum does not convert units with an offset (Cel, [degF]); ucum-lhc does"
        const val ANNOTATION = "ucum-lhc rejects a space in an annotation; the JVM's fhir-ucum accepts it"
        const val MATCHES = "on JS only, cqframework's Matches gives false for the pattern '.*\\\\d+' (no unit involved)"

        /** The cases that differ on JS and Wasm, and why. */
        val KNOWN_DIFFERENCES =
            listOf(
                    "CqlArithmeticFunctionsTest.Modulo10By3Quantity",
                    "CqlArithmeticFunctionsTest.TruncatedDivide10d1ByNeg3D1Quantity",
                    "spike.DecimalMultiply",
                    "spike.DecimalDivide",
                    "spike.DecimalModulo",
                    "spike.DecimalTruncatedDivide",
                ).associateWith { DECIMAL_SCALE } +
                listOf(
                    "CqlComparisonOperatorsTest.EquivQuantityCM1M01",
                    "CqlComparisonOperatorsTest.RatioEquivalent",
                    "CqlComparisonOperatorsTest.RatioEquivalent2",
                    "CqlComparisonOperatorsTest.RatioEquivalent3",
                    "CqlComparisonOperatorsTest.RatioEquivalentWithDifferentUnits",
                    "CqlComparisonOperatorsTest.RatioNotEquivalent",
                    "CqlComparisonOperatorsTest.RatioNotEquivalentWithDifferentUnits",
                    "CqlIntervalOperatorsTest.QuantityIntervalEquivalentFalse",
                    "CqlIntervalOperatorsTest.QuantityIntervalEquivalentTrue",
                    "spike.EquivalentAcrossUnits",
                    "spike.DecimalEquivalent",
                ).associateWith { STRIP_ZEROS } +
                listOf(
                    "spike.ConvertCelsiusToFahrenheit",
                    "spike.ConvertCelsiusToKelvin",
                    "spike.ConvertFahrenheitToCelsius",
                ).associateWith { OFFSET_UNITS } +
                mapOf("CqlTypesTest.QuantityTest2" to ANNOTATION, "CqlStringOperatorsTest.MatchesNumberTrue" to MATCHES)

        val QUANTITY_VALUE = Regex("""(-?\d+(?:\.\d+)?(?:E-?\d+)?) '""")
    }
}
