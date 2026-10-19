package io.github.dhes.kotlinfhircql

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.TimeSource
import org.hl7.elm.r1.VersionedIdentifier
import org.opencds.cqf.cql.engine.data.CompositeDataProvider
import org.opencds.cqf.cql.engine.execution.CqlEngine
import org.opencds.cqf.cql.engine.execution.Environment
import org.opencds.cqf.cql.engine.runtime.ClassInstance
import org.opencds.cqf.cql.engine.runtime.Date

/**
 * Precompiled dynamicValues: wraps each `text/cql-expression` dynamicValue of WHO's PlanDefinitions in a library, as
 * clinical-reasoning's `LibraryConstructor` does at run time, compiles the wrappers and WHO's libraries to ELM JSON
 * ahead of time, and checks that each scenario's dynamicValues evaluate from ELM alone to the same values as from
 * the wrappers compiled from CQL source at run time.
 *
 * Needs a cqframework build that loads ELM of libraries defining functions (cqframework#1874), e.g.
 * `-PcqlVersion=5.5.0-elm-functions-fb8055df-SNAPSHOT`. WHO's PlanDefinitions are read from `WHO_PACKAGE_DIR`.
 */
class PrecompiledDynamicValueTest {
    @Test
    fun precompiledDynamicValues() {
        val dynamicValues = WhoDynamicValues.all
        val librarySources = WhoDynamicValues.librarySources
        val wrappers = dynamicValues.associate { it.wrapper to WhoDynamicValues.wrapperCql(it) }
        println(
            "${dynamicValues.size} text/cql-expression dynamicValues in ${dynamicValues.map { it.planDefinition }.toSet().size} " +
                "PlanDefinitions; ${dynamicValues.map { it.expression }.toSet().size} distinct expressions; " +
                "paths ${dynamicValues.groupingBy { it.path }.eachCount()}"
        )

        // Build time: compile WHO's libraries and every wrapper to ELM JSON.
        val start = TimeSource.Monotonic.markNow()
        val elm = WhoDynamicValues.compileToElmJson(wrappers)
        println(
            "Compiled ${librarySources.size} libraries and ${wrappers.size} wrappers to ELM JSON in ${start.elapsedNow()}; " +
                "wrapper ELM ${wrappers.keys.sumOf { elm.getValue(it).length } / 1024} KB in all"
        )
        val outDir = File(PROJECT_DIR, "build/who-dynamic-values-elm-json").apply { deleteRecursively(); mkdirs() }
        wrappers.forEach { (name, cql) ->
            File(outDir, "$name.cql").writeText(cql)
            File(outDir, "$name.elm.json").writeText(elm.getValue(name))
        }

        // Run time: ELM alone (as on a phone), and CQL source alone (as clinical-reasoning does).
        val fromElm = WhoDynamicValues.Loader(elm, emptyMap())
        val fromCql = WhoDynamicValues.Loader(emptyMap(), librarySources + wrappers)
        val modelResolver = KotlinFhirModelResolver(fromElm.modelManager.resolveModel("FHIR", "4.0.1"))
        val terminology = InMemoryTerminologyProvider(WhoSuite.valueSets)
        val byPlan = dynamicValues.groupBy { it.planDefinition }

        fun evaluate(
            loader: WhoDynamicValues.Loader,
            scenario: WhoScenario,
            resources: List<ClassInstance>,
            dvs: List<WhoDynamicValue>,
        ): Map<String, String?> {
            val (y, m, d) = scenario.today.split("-").map(String::toInt)
            val environment = Environment(loader.libraryManager, null, terminology)
            environment.registerDataProvider(
                FHIR_MODEL_URI,
                CompositeDataProvider(modelResolver, InMemoryRetrieveProvider(resources, terminology)),
            )
            val results =
                CqlEngine(environment).evaluate {
                    dvs.forEach { library(it.wrapper) { expressions("return") } }
                    contextParameter = "Patient" to scenario.patient
                    parameters = mapOf("Today" to Date(y, m, d))
                    evaluationDateTime = WhoSuite.evaluationDateTime(scenario)
                }
            return dvs.associate { dv ->
                val id = VersionedIdentifier().apply { id = dv.wrapper }
                results.getExceptionFor(id)?.let { throw it }
                dv.wrapper to MeaslesFixtures.render(results.getResultFor(id)!!.expressionResults.getValue("return").value)
            }
        }

        val suiteStart = TimeSource.Monotonic.markNow()
        var scenarios = 0
        var evaluations = 0
        val differences = mutableListOf<String>()
        val samples = sortedMapOf<String, String>()
        var nulls = 0
        for (scenario in WhoSuite.scenarios) {
            val dvs = byPlan[scenario.planDefinition] ?: continue
            val resources = try { WhoSuite.kotlinFhirResources(scenario) } catch (e: Exception) { continue }
            val elmValues = evaluate(fromElm, scenario, resources, dvs)
            val cqlValues = evaluate(fromCql, scenario, resources, dvs)
            scenarios++
            evaluations += dvs.size
            dvs.forEach { dv ->
                if (elmValues[dv.wrapper] != cqlValues[dv.wrapper]) {
                    differences += "${scenario.id} ${dv.path}: ELM ${elmValues[dv.wrapper]} / CQL ${cqlValues[dv.wrapper]}"
                }
                if (elmValues[dv.wrapper] == null) nulls++
                samples.putIfAbsent(dv.path, "${dv.expression.take(60)} -> ${elmValues[dv.wrapper]?.replace(Regex("\\s+"), " ")?.take(100)}")
            }
        }
        println("$scenarios scenarios, $evaluations dynamicValue evaluations each way, in ${suiteStart.elapsedNow()}; ${differences.size} differ; $nulls null from ELM")
        differences.take(10).forEach { println("  $it") }
        println("One value per path, from ELM:")
        samples.forEach { (path, sample) -> println("  $path: $sample") }
        println("Compiled from CQL source when loading from ELM: ${fromElm.sourceRequests.sorted()}")
        println("Compiled from CQL source on the run-time side: ${fromCql.sourceRequests.size} libraries and wrappers")
        assertTrue(fromElm.sourceRequests.isEmpty())
        assertTrue(differences.isEmpty())
        assertTrue(nulls == 0)
    }
}
