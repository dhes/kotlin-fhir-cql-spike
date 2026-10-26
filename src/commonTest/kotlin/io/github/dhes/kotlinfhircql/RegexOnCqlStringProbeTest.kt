package io.github.dhes.kotlinfhircql

import kotlin.test.Test
import org.opencds.cqf.cql.engine.runtime.String as CqlString

/** Probe, not a check: Kotlin's Regex on cqframework's CQL String, which is a CharSequence by delegation. */
class RegexOnCqlStringProbeTest {
    @Test
    fun regexOnCqlString() {
        val s = CqlString("abc")
        val cs: CharSequence = s
        println("REGEX-PROBE | toString() = ${s.toString()} | length = ${cs.length} | subSequence = ${cs.subSequence(0, cs.length)}")
        println("REGEX-PROBE | Regex(\"abc\").matches(cqlString) = ${Regex("abc").matches(cs)}")
        println("REGEX-PROBE | Regex(\"abc\").matches(cqlString.value) = ${Regex("abc").matches(s.value)}")
        println("REGEX-PROBE | Regex(\"abc\").containsMatchIn(cqlString) = ${Regex("abc").containsMatchIn(cs)}")
        println("REGEX-PROBE | Regex(\"'abc'\").matches(cqlString) = ${Regex("'abc'").matches(cs)}")
        println("REGEX-PROBE | Regex(\".*\").find(cqlString)?.value = ${Regex(".*").find(cs)?.value}")
        println("REGEX-PROBE | Regex(\"abc\").matches(StringBuilder(\"abc\")) = ${Regex("abc").matches(StringBuilder("abc"))}")
    }
}
