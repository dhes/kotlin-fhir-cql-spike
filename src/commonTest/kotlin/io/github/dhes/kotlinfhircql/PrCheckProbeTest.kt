package io.github.dhes.kotlinfhircql

import kotlin.test.Test

/**
 * Probe, not a check: expressions from open cqframework/clinical_quality_language pull requests (#1948, #1923, #1920,
 * #1913, #1904), each compiled and evaluated on its own, so that one target's output can be compared with another's.
 */
class PrCheckProbeTest {
    private val expressions =
        listOf(
            // #1948: Matches becomes partial matching.
            "M01" to """Matches('1,2three', '\\w+')""",
            "M02" to """Matches('abc', 'b')""",
            "M03" to """Matches('http://fhir.org/guides/cqf/common/Library/FHIR-ModelInfo|4.0.1', 'Library')""",
            "M04" to """Matches('1,2three', '^\\w+$')""",
            "M05" to """Matches('abc', '^b$')""",
            "M06" to """Matches('abc', 'abc')""",
            "M07" to """Matches('abc', '')""",
            "M08" to """Matches('abc', 'x')""",
            "M09" to """Matches('a\nb', 'a.b')""",
            "M10" to """Matches('Not all who wander are lost - circa 2017', '.*\\d+')""",
            "M11" to """Matches('abc', null as String)""",
            "M12" to """Matches('xbz', 'a|b')""",
            // #1923: SplitOnMatches with no match.
            "S01" to """SplitOnMatches('ABC', ',')""",
            "S02" to """Length(SplitOnMatches('ABC', ',')[0])""",
            "S03" to """SplitOnMatches('A,B', ',')""",
            "S04" to """SplitOnMatches('A1B22C', '[0-9]+')""",
            "S05" to """SplitOnMatches('it\'s', ',')""",
            "S06" to """SplitOnMatches(null as String, ',')""",
            // #1920: Combine with a null separator or null elements.
            "C01" to """Combine({'a', null, 'c'})""",
            "C02" to """Combine({'a', null, 'c'}, '-')""",
            "C03" to """Combine({'a', 'b'}, null as String)""",
            "C04" to """Combine({null as String, null as String})""",
            "C05" to """Combine({'a', 'b'}, ',')""",
            "C06" to """Combine(List<String>{})""",
            "C07" to """Combine({'it\'s', 'b'}, '\'')""",
            // #1913 and #1904: Integer and Long overflow.
            "O01" to """maximum Integer + 1""",
            "O02" to """minimum Integer - 1""",
            "O03" to """maximum Integer * 2""",
            "O04" to """-(minimum Integer)""",
            "O05" to """Abs(minimum Integer)""",
            "O06" to """maximum Long + 1L""",
            "O07" to """minimum Long - 1L""",
            "O08" to """maximum Long * 2L""",
            "O09" to """-(minimum Long)""",
            "O10" to """Abs(minimum Long)""",
            "O11" to """46341 * 46341""",
            "O12" to """46340 * 46340""",
            "O13" to """3037000500L * 3037000500L""",
            "O14" to """3037000499L * 3037000499L""",
            "O15" to """Power(2, 31)""",
            "O16" to """Power(2, 30)""",
            "O17" to """Power(2L, 63)""",
            "O18" to """Power(2L, 62)""",
            "O19" to """minimum Integer div -1""",
            "O20" to """minimum Long div -1L""",
            "O21" to """maximum Integer + 0""",
            "O22" to """maximum Long - 1L""",
            "O23" to """-9223372036854775807L - 1L""",
            "O24" to """9007199254740993L + 1L""",
            "O25" to """9007199254740993L * 3L""",
            // #1904: the other operators.
            "T01" to """ToInteger('1.0')""",
            "T02" to """ToInteger('+1')""",
            "T03" to """ToLong('1.0')""",
            "T04" to """ToInteger(5L)""",
            "T05" to """ToInteger(3000000000L)""",
            "T06" to """ConvertsToInteger(3000000000L)""",
            "T07" to """IndexOf({'a', 'B'}, 'b')""",
            "T08" to """LastPositionOf('', 'abc')""",
            "T09" to """Product({5, 4, null})""",
            "T10" to """convert '10.2' to Integer""",
            "T11" to """ToLong('9223372036854775808')""",
            "T12" to """Product({2.0, null, 3.0})""",
            "T13" to """ConvertsToInteger('1.0')""",
            "T14" to """ToInteger('2147483648')""",
        )

    @Test
    fun expressions() {
        for ((name, expression) in expressions) {
            val source = "library P\n\ndefine \"X\": $expression\n"
            val rendered =
                runCatching {
                        val evaluator = FhirCqlEvaluator({ library -> if (library == "P") source else null }, MeaslesFixtures.modelInfoXml)
                        val value = evaluator.evaluate("P", "p1", emptyList(), listOf("X"), evaluationDateTime = whoTestDateTime())["X"]
                        MeaslesFixtures.render(value).toString()
                    }
                    .getOrElse { "error: ${it::class.simpleName}: ${it.message?.lineSequence()?.firstOrNull()?.take(90)}" }
            println("PRCHECK | $CQL_VERSION | $name | $expression = $rendered")
        }
    }
}
