package io.github.dhes.kotlinfhircql

import kotlin.test.Test
import org.cqframework.cql.cql2elm.ucum.UcumService
import org.cqframework.cql.cql2elm.ucum.createUcumServiceReference
import org.cqframework.cql.shared.BigDecimal

/**
 * Probe, not a check: cqframework's Wasm export `createUcumService` (`UcumServiceWasmJs.kt`), given ucum-lhc's
 * multiply and divide, then used to divide one quantity by another, directly and in CQL.
 */
class UcumExportDivideTest {
    private val calls = mutableListOf<String>()

    private fun encode(result: Pair<BigDecimal, String>) = "${result.first}:${result.second}"

    @OptIn(ExperimentalWasmJsInterop::class)
    private val service: Lazy<UcumService> =
        createUcumServiceReference(
                convertUnit = { value, from, to -> UcumLhcService.convert(BigDecimal(value), from, to).toString() },
                validateUnit = { unit -> UcumLhcService.validate(unit) },
                multiply = { lv, lu, rv, ru ->
                    calls += "multiply"
                    encode(UcumLhcService.multiply(BigDecimal(lv) to lu, BigDecimal(rv) to ru))
                },
                divideBy = { lv, lu, rv, ru ->
                    calls += "divideBy"
                    encode(UcumLhcService.divideBy(BigDecimal(lv) to lu, BigDecimal(rv) to ru))
                },
            )
            .get()

    @Test
    fun divideThroughTheExport() {
        val left = BigDecimal("2") to "m"
        val right = BigDecimal("4") to "s"
        println("UCUM-EXPORT | ucum-lhc directly: divideBy(2 'm', 4 's') = ${UcumLhcService.divideBy(left, right)}")
        calls.clear()
        val viaExport = service.value.divideBy(left, right)
        println("UCUM-EXPORT | through the export: divideBy(2 'm', 4 's') = $viaExport; JS functions called: $calls")

        val source = "library DivideCheck version '1'\n\ndefine Q: 2 'm' / 4 's'\n"
        val evaluator = FhirCqlEvaluator({ name -> if (name == "DivideCheck") source else null }, MeaslesFixtures.modelInfoXml, ucumService = service)
        calls.clear()
        val q = evaluator.evaluate("DivideCheck", "p1", emptyList(), listOf("Q"), evaluationDateTime = whoTestDateTime())["Q"]
        println("UCUM-EXPORT | CQL 2 'm' / 4 's' with the export's service = $q; JS functions called: $calls")
    }
}
