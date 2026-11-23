package io.github.dhes.kotlinfhircql

import kotlin.test.Test

/**
 * Probe, not a check: expressions from open cqframework/clinical_quality_language pull requests (#1941, #1949, #1927,
 * #1886, #1946, #1944, #1931, #1924, #1922, #1921), each compiled and evaluated on its own with this target's UCUM
 * service, so that one target's output can be compared with another's. On JS and Wasm that service is
 * [UcumLhcService], which converts through doubles; the V expressions show what it returns.
 */
class PrCheck2ProbeTest {
    private val expressions =
        listOf(
            // #1941: div and mod on Quantities convert units.
            "D01" to """250.0 'cm' div 1.0 'm'""",
            "D02" to """7.0 'kg' div 2.0 'g'""",
            "D03" to """10.0 'g' div 5.0 'g'""",
            "D04" to """1.0 'm' mod 30.0 'cm'""",
            "D05" to """1.0 'kg' mod 300.0 'g'""",
            "D06" to """7.0 'g' div 2.0 'm'""",
            "D07" to """7.0 'g' mod 2.0 'm'""",
            "D08" to """300 'g' mod 1 'kg'""",
            "D09" to """14 days div 1 week""",
            "D10" to """10.0 'mg' div 3.0 'mg'""",
            "D11" to """10.0 'mg' mod 3.0 'mg'""",
            // #1949: calendar duration keywords in quantity Equal and Equivalent.
            "Q01" to """1 week = 7 days""",
            "Q02" to """1 day = 24 hours""",
            "Q03" to """1 hour = 60 minutes""",
            "Q04" to """1 minute = 60 seconds""",
            "Q05" to """1 second = 1000 milliseconds""",
            "Q06" to """1 year ~ 12 months""",
            "Q07" to """1 year ~ 365 days""",
            "Q08" to """1 month ~ 30 days""",
            "Q09" to """7 days = 1 'wk'""",
            "Q10" to """1 year = 12 months""",
            "Q11" to """1 'g' = 1000 'mg'""",
            "Q12" to """1 week = 8 days""",
            "Q13" to """1 week ~ 7 days""",
            // #1927 and #1886: units in multiplication, division and variance.
            "U01" to """10 'mg' / 2 'd'""",
            "U02" to """2 'm' * 3 'm'""",
            "U03" to """10 'mg' * 2 'd'""",
            "U04" to """1 'm' / 100 'cm'""",
            "U05" to """2 'm' * 30 'cm'""",
            "U06" to """5 'm' * 4 '/d'""",
            "U07" to """Variance({1.0 'ml', 2.0 'ml', 3.0 'ml', 4.0 'ml', 5.0 'ml'})""",
            "U08" to """PopulationVariance({1.0 'ml', 2.0 'ml', 3.0 'ml', 4.0 'ml', 5.0 'ml'})""",
            "U09" to """Variance({1.0 'ml', 2.0 'ml', 0.003 'l', 4.0 'ml', 5.0 'ml'})""",
            "U10" to """2 'm' / 4 's'""",
            "U11" to """10 'g' / 5 'g'""",
            "U12" to """6 'mg' * 2""",
            "U13" to """10 'mg' / 2 'mg'""",
            // #1946: Tuple Equal with null elements.
            "E01" to """{ x: 1, y: 1 } = { x: null, y: 2 }""",
            "E02" to """{ y: 1, x: 1 } = { y: 2, x: null }""",
            "E03" to """Tuple { Id : null, Name : 'John' } = Tuple { Id : 1, Name : 'James' }""",
            "E04" to """Tuple{ Id : null, Name : 'John' } != Tuple{ Id : 1, Name : 'Joe' }""",
            "E05" to """{ x: 1, y: 1 } = { x: null, y: 1 }""",
            "E06" to """{ x: 1, y: null } = { x: 1, y: null }""",
            "E07" to """{ x: 1, y: null } = { x: 2, y: null }""",
            "E08" to """{ x: 1, y: 'a', z: 2 } = { x: null, y: 'b', z: null }""",
            // #1944: list properly includes and properly included in.
            "L01" to """{ null, null } properly includes null as String""",
            "L02" to """{ 'a', 'a' } properly includes 'a'""",
            "L03" to """{ 'a', null } properly includes 'a'""",
            "L04" to """{ @T15:59:59.999, @T20:59:59.999, @T20:59:49.999 } properly includes @T15:59:59""",
            "L05" to """null as List<Integer> properly includes {2}""",
            "L06" to """{'s', 'u', 'n'} properly included in null as List<String>""",
            "L07" to """{ 1, 3, 5, null } properly includes null""",
            "L08" to """null properly included in { 1, 3, 5, null }""",
            "L09" to """{ null } properly includes null as Integer""",
            "L10" to """{1, 2, 3} properly includes {1, 2}""",
            // #1931: an Interval's start is not after its end.
            "I01" to """Interval[5, 1]""",
            "I02" to """Interval[1, 5]""",
            "I03" to """Interval(2, 3)""",
            "I04" to """Interval[1 'g', 500 'mg']""",
            "I05" to """Interval[1 'g', 2000 'mg']""",
            "I06" to """Interval[@2020-01-02, @2020-01-01]""",
            "I07" to """Interval[1, null]""",
            "I08" to """Interval[2.0, 1.0]""",
            "I09" to """width of Interval[1, 5]""",
            // #1924: Skip with a null or negative count.
            "K01" to """Skip({ 1, 2, 3 }, null as Integer)""",
            "K02" to """Skip({ 1, 2, 3 }, -1)""",
            "K03" to """Skip({ 1, 2, 3 }, 1)""",
            "K04" to """Skip({ 1, 2, 3 }, 5)""",
            "K05" to """Skip(null as List<Integer>, 1)""",
            "K06" to """Tail({ 1, 2, 3 })""",
            "K07" to """Take({ 1, 2, 3 }, -1)""",
            // #1922: ToDate ignores a time portion.
            "A01" to """ToDate('2014-01-01T12:30:00')""",
            "A02" to """ToDate('2014-01-01')""",
            "A03" to """ToDate('2014-01')""",
            "A04" to """ToDate('2014-01-01T12:30:00.000Z')""",
            "A05" to """ToDate('2014-01-01T12:30:00+07:00')""",
            "A06" to """ToDate('2014-01-01T25:00')""",
            "A07" to """ToDate('2014-01-01Tabc')""",
            "A08" to """ConvertsToDate('2014-01-01T12:30:00')""",
            "A09" to """ToDate('2014-02-30')""",
            "A10" to """ToDate('2014-01-01T12')""",
            // #1921: LastPositionOf with an empty pattern.
            "P01" to """LastPositionOf('', 'ABCDE')""",
            "P02" to """LastPositionOf('', '')""",
            "P03" to """LastPositionOf('E', 'ABCDE')""",
            "P04" to """LastPositionOf('B', 'ABCDEB')""",
            "P05" to """PositionOf('', 'ABCDE')""",
            // #1931 again: arithmetic on uncertain durations, which builds Intervals without the literal's check.
            "N01" to """(days between DateTime(2014, 1, 15) and DateTime(2014, 2)) div (months between DateTime(2005) and DateTime(2006, 5))""",
            "N02" to """(days between DateTime(2014, 1, 15) and DateTime(2014, 2)) - (days between DateTime(2014, 1, 1) and DateTime(2014, 3))""",
            "N03" to """(days between DateTime(2014, 1, 15) and DateTime(2014, 2)) - (months between DateTime(2005) and DateTime(2006, 5))""",
            "N04" to """(days between DateTime(2014, 1, 15) and DateTime(2014, 2)) + (days between DateTime(2014, 1, 15) and DateTime(2014, 2))""",
            "N05" to """days between DateTime(2014, 1, 15) and DateTime(2014, 2)""",
            // What this target's UCUM service returns from a conversion, to read the U results by.
            "V01" to """convert 1.0 'ml' to 'l'""",
            "V02" to """convert 2 'm' to 'cm'""",
            "V03" to """convert 1 'm' to 'cm'""",
            "V04" to """convert 0.003 'l' to 'ml'""",
            "V05" to """convert 5.0 'ml' to 'l'""",
        )

    @Test
    fun expressions() {
        for ((name, expression) in expressions) {
            val source = "library P\n\ndefine \"X\": $expression\n"
            val rendered =
                runCatching {
                        val evaluator =
                            FhirCqlEvaluator(
                                { library -> if (library == "P") source else null },
                                MeaslesFixtures.modelInfoXml,
                                ucumService = platformUcumService(),
                            )
                        val value = evaluator.evaluate("P", "p1", emptyList(), listOf("X"), evaluationDateTime = whoTestDateTime())["X"]
                        MeaslesFixtures.render(value).toString()
                    }
                    .getOrElse { "error: ${it::class.simpleName}: ${it.message?.lineSequence()?.firstOrNull()?.take(90)}" }
            println("PRCHECK | $CQL_VERSION | $name | $expression = $rendered")
        }
    }
}
