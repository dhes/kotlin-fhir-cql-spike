package io.github.dhes.kotlinfhircql

import kotlin.test.Test
import org.cqframework.cql.elm.serializing.ElmJsonLibraryWriter
import org.hl7.elm.r1.VersionedIdentifier

/** Probe, not a check: what the CQL string '.*\\d+' compiles to on this platform, and what Matches gives. */
class MatchesProbeTest {
    @Test
    fun matchesWithBackslashD() {
        val source =
            """
            library M

            define "Pattern": '.*\\d+'
            define "PatternLength": Length('.*\\d+')
            define "MatchesBackslashD": Matches('Not all who wander are lost - circa 2017', '.*\\d+')
            define "MatchesDigitClass": Matches('Not all who wander are lost - circa 2017', '.*[0-9]+')
            define "MatchesQuotedForm": Matches('abc', '\'abc\'')
            define "MatchesPlain": Matches('abc', 'abc')
            define "MatchesPrefixOfQuoted": Matches('abc', '\'ab')
            define "ReplaceMatches": ReplaceMatches('abc', 'b', 'X')
            define "ReplaceMatchesAll": ReplaceMatches('abc', '.*', 'X')
            define "SplitOnMatches": SplitOnMatches('a,b', ',')
            define "Quote": 'it\'s'
            define "Tab": Length('a\tb')
            """.trimIndent()
        val evaluator = FhirCqlEvaluator({ name -> if (name == "M") source else null }, MeaslesFixtures.modelInfoXml)
        val results = evaluator.evaluate("M", "p1", emptyList(), evaluationDateTime = whoTestDateTime())
        for ((name, value) in results) println("MATCHES-PROBE | $name = ${MeaslesFixtures.render(value)}")
        val elm = ElmJsonLibraryWriter().writeAsString(evaluator.libraryManager.resolveLibrary(VersionedIdentifier().withId("M")).library!!)
        Regex("\"value\"\\s*:\\s*\"([^\"]*(?:\\\\.[^\"]*)*)\"").findAll(elm).forEach { println("MATCHES-PROBE | ELM literal value (JSON-escaped) = ${it.groupValues[1]}") }
    }
}
