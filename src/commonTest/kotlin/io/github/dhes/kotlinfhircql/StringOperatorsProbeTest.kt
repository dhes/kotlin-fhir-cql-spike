package io.github.dhes.kotlinfhircql

import kotlin.test.Test

/** Probe, not a check: each define of cqframework's engine/src/jvmTest/resources/.../CqlStringOperatorsTest.cql (v5.4.0), copied to build/probe; skipped when absent. */
class StringOperatorsProbeTest {
    @Test
    fun stringOperators() {
        val path = "build/probe/CqlStringOperatorsTest.cql"
        val source = runCatching { MeaslesFixtures.readText(path) }.getOrNull() ?: return
        val evaluator =
            FhirCqlEvaluator(
                { name -> if (name == "CqlStringOperatorsTest") source else null },
                MeaslesFixtures.modelInfoXml,
                ucumService = platformUcumService(),
            )
        val names = Regex("^define \"?([A-Za-z0-9_]+)", RegexOption.MULTILINE).findAll(source).map { it.groupValues[1] }.sorted()
        for (name in names) {
            val rendered =
                runCatching {
                        val value = evaluator.evaluate("CqlStringOperatorsTest", "p1", emptyList(), listOf(name), evaluationDateTime = whoTestDateTime())[name]
                        MeaslesFixtures.render(value).toString()
                    }
                    .getOrElse { "error: ${it::class.simpleName}" }
            println("STRING-OPS | $name = $rendered")
        }
    }
}
