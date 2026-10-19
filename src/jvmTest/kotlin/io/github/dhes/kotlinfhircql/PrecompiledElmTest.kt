package io.github.dhes.kotlinfhircql

import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.TimeSource
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.cqframework.cql.cql2elm.CompilerOptions
import org.cqframework.cql.cql2elm.CqlCompilerException
import org.cqframework.cql.cql2elm.CqlCompilerOptions
import org.cqframework.cql.cql2elm.LibraryBuilder.SignatureLevel
import org.cqframework.cql.cql2elm.LibraryContentType
import org.cqframework.cql.cql2elm.LibraryManager
import org.cqframework.cql.cql2elm.LibrarySourceProvider
import org.cqframework.cql.cql2elm.utils.asSource
import org.cqframework.cql.elm.serializing.ElmJsonLibraryReader
import org.cqframework.cql.elm.serializing.ElmJsonLibraryWriter
import org.hl7.elm.r1.VersionedIdentifier
import org.opencds.cqf.cql.engine.data.CompositeDataProvider
import org.opencds.cqf.cql.engine.execution.CqlEngine
import org.opencds.cqf.cql.engine.execution.Environment
import org.opencds.cqf.cql.engine.runtime.Date

/**
 * Precompiled ELM: compiles WHO's libraries and FHIRHelpers to ELM JSON with cql-to-elm 5.4.0, adds it to WHO's
 * Library resources as `application/elm+json` content, and checks which libraries cqframework's CQL engine then
 * loads from ELM alone and whether WHO's test suite gives the recorded HAPI outcomes from them.
 *
 * WHO's Library resources are read from `WHO_PACKAGE_DIR` (package smart.who.int.immunizations 0.2.0); the
 * Libraries with ELM JSON added are written to `build/who-libraries-elm-json`.
 */
class PrecompiledElmTest {
    private val declaration = Regex("""^\s*library\s+"?([A-Za-z0-9_]+)"?""", RegexOption.MULTILINE)
    private val sources = WhoSuite.librarySources.associateBy { declaration.find(it)!!.groupValues[1] }
    private val names = sources.keys.sorted()

    private fun options(resultTypes: Boolean) =
        CqlCompilerOptions(
            CqlCompilerException.ErrorSeverity.Info,
            SignatureLevel.Overloads,
            *listOfNotNull(
                    CqlCompilerOptions.Options.DisableListDemotion,
                    CqlCompilerOptions.Options.DisableListPromotion,
                    CqlCompilerOptions.Options.EnableResultTypes.takeIf { resultTypes },
                )
                .toTypedArray(),
        )

    /** A library manager whose provider serves ELM JSON from [elm] and CQL source for the names in [cql]. */
    private class Loader(options: CqlCompilerOptions, elm: Map<String, String>, cql: Map<String, String>) {
        val sourceRequests = mutableSetOf<String>()
        val modelManager = FhirCqlEvaluator(emptyList(), MeaslesFixtures.modelInfoXml).modelManager
        val libraryManager =
            LibraryManager(modelManager, options, lazyUcumService = platformUcumService()).apply {
                librarySourceLoader.registerProvider(
                    object : LibrarySourceProvider {
                        override fun getLibrarySource(libraryIdentifier: VersionedIdentifier) =
                            cql[libraryIdentifier.id]?.also { sourceRequests += libraryIdentifier.id!! }?.asSource()

                        override fun getLibraryContent(libraryIdentifier: VersionedIdentifier, type: LibraryContentType) =
                            when (type) {
                                LibraryContentType.JSON -> elm[libraryIdentifier.id]?.asSource()
                                LibraryContentType.CQL -> getLibrarySource(libraryIdentifier)
                                else -> null
                            }
                    }
                )
            }

        /** Resolves each library; returns the failures as "name: exception". */
        fun resolveAll(names: List<String>) =
            names.mapNotNull { name ->
                try {
                    libraryManager.resolveLibrary(VersionedIdentifier().apply { id = name })
                    null
                } catch (e: Exception) {
                    "$name: ${e::class.simpleName}: ${e.message?.lineSequence()?.first()?.take(120)}"
                }
            }
    }

    private fun compileToElmJson(options: CqlCompilerOptions): Map<String, String> {
        val compiler = Loader(options, emptyMap(), sources)
        return names.associateWith { name ->
            ElmJsonLibraryWriter()
                .writeAsString(compiler.libraryManager.resolveLibrary(VersionedIdentifier().apply { id = name }).library!!)
        }
    }

    /** Copies each WHO Library resource with the ELM JSON appended to its content; returns the ELM read back. */
    private fun writeLibraries(elm: Map<String, String>): Map<String, String> {
        val packageDir = File(System.getenv("WHO_PACKAGE_DIR") ?: DEFAULT_PACKAGE_DIR)
        val outDir = File(PROJECT_DIR, "build/who-libraries-elm-json").apply { deleteRecursively(); mkdirs() }
        val readBack = mutableMapOf<String, String>()
        packageDir.listFiles { f -> f.name.startsWith("Library-") && f.name.endsWith(".json") }!!.sorted().forEach { file ->
            val library = MeaslesFixtures.json.parseToJsonElement(file.readText()).jsonObject
            val name = library.getValue("name").jsonPrimitive.content
            val attachment =
                JsonObject(
                    mapOf(
                        "contentType" to JsonPrimitive("application/elm+json"),
                        "data" to JsonPrimitive(Base64.getEncoder().encodeToString(elm.getValue(name).toByteArray())),
                    )
                )
            val content = JsonArray(library.getValue("content").jsonArray + attachment)
            File(outDir, file.name).writeText(JsonObject(library + ("content" to content)).toString())
            readBack[name] = String(Base64.getDecoder().decode(attachment.getValue("data").jsonPrimitive.content))
        }
        // FHIRHelpers is not in WHO's package: write its ELM JSON on its own.
        File(outDir, "FHIRHelpers-4.0.1.elm.json").writeText(elm.getValue("FHIRHelpers"))
        readBack["FHIRHelpers"] = elm.getValue("FHIRHelpers")
        println("Wrote ${readBack.size - 1} Library resources with ELM JSON, and FHIRHelpers' ELM JSON, to $outDir")
        return readBack
    }

    @Test
    fun precompiledElm() {
        // 1. With the spike's compiler options: ELM alone.
        val plain = compileToElmJson(options(resultTypes = false))
        val plainFailures = Loader(options(false), plain, emptyMap()).resolveAll(names)
        println("Spike's options: ${names.size - plainFailures.size}/${names.size} libraries load from ELM alone")

        // 2. With EnableResultTypes added, through WHO's Library resources.
        val start = TimeSource.Monotonic.markNow()
        val compiled = compileToElmJson(options(resultTypes = true))
        println("Compiled ${compiled.size} libraries to ELM JSON in ${start.elapsedNow()}")
        val stamps = compiled.values.map { ElmJsonLibraryReader().read(it) }
            .map { CompilerOptions.getCompilerVersion(it) to CompilerOptions.getCompilerOptions(it) }.toSet()
        println("translatorVersion and options recorded: $stamps")
        val elm = writeLibraries(compiled)
        val failures = Loader(options(true), elm, emptyMap()).resolveAll(names)
        println("EnableResultTypes: ${names.size - failures.size}/${names.size} load from ELM alone; failures:")
        failures.forEach { println("  $it") }

        // 3. ELM and CQL source both available, as in a Library carrying both.
        val both = Loader(options(true), elm, sources)
        val bothFailures = both.resolveAll(names)
        println("ELM and CQL both available: ${bothFailures.size} fail; compiled from CQL instead: ${both.sourceRequests.sorted()}")
        bothFailures.forEach { println("  $it") }

        // 4. WHO's test suite: ELM for every library that loads from it, CQL source for the rest.
        val elmOnly = failures.map { it.substringBefore(":") }.toSet()
        val loader = Loader(options(true), elm.filterKeys { it !in elmOnly }, sources.filterKeys { it in elmOnly })
        val modelResolver = KotlinFhirModelResolver(loader.modelManager.resolveModel("FHIR", "4.0.1"))
        val terminology = InMemoryTerminologyProvider(WhoSuite.valueSets)
        val expected: Map<String, WhoOutcome> =
            MeaslesFixtures.json.decodeFromString(MeaslesFixtures.readText(WhoSuite.EXPECTED))
        val suiteStart = TimeSource.Monotonic.markNow()
        var evaluated = 0
        val differences = mutableListOf<String>()
        for (scenario in WhoSuite.scenarios) {
            val resources = try { WhoSuite.kotlinFhirResources(scenario) } catch (e: Exception) { continue }
            val (y, m, d) = scenario.today.split("-").map(String::toInt)
            val results =
                WhoSuite.closure(WhoSuite.plans.getValue(scenario.planDefinition).library).flatMap { library ->
                    val environment = Environment(loader.libraryManager, null, terminology)
                    environment.registerDataProvider(
                        FHIR_MODEL_URI,
                        CompositeDataProvider(modelResolver, InMemoryRetrieveProvider(resources, terminology)),
                    )
                    CqlEngine(environment)
                        .evaluate {
                            library(library)
                            contextParameter = "Patient" to scenario.patient
                            parameters = mapOf("Today" to Date(y, m, d))
                            evaluationDateTime = WhoSuite.evaluationDateTime(scenario)
                        }
                        .onlyResultOrThrow.expressionResults
                        .map { (name, r) -> "$library.$name" to MeaslesFixtures.render(r.value) }
                }.toMap()
            evaluated++
            val outcome = WhoSuite.outcome(scenario, results)
            if (outcome != expected.getValue(scenario.id)) differences += scenario.id
        }
        println("Suite from ELM: $evaluated scenarios in ${suiteStart.elapsedNow()}; ${differences.size} differ from the recorded HAPI outcomes ${differences.take(10)}")
        println("Compiled from CQL source during the suite: ${loader.sourceRequests.sorted()}")
        assertTrue(differences.isEmpty())
    }

    private companion object {
        const val DEFAULT_PACKAGE_DIR = "/Users/danheslinga/projects/km-probe/corpus-scan/smart-immunizations/package"
    }
}
