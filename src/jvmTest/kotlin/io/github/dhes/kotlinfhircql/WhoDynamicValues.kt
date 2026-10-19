package io.github.dhes.kotlinfhircql

import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.cqframework.cql.cql2elm.CqlCompilerException
import org.cqframework.cql.cql2elm.CqlCompilerOptions
import org.cqframework.cql.cql2elm.LibraryBuilder.SignatureLevel
import org.cqframework.cql.cql2elm.LibraryContentType
import org.cqframework.cql.cql2elm.LibraryManager
import org.cqframework.cql.cql2elm.LibrarySourceProvider
import org.cqframework.cql.cql2elm.utils.asSource
import org.cqframework.cql.elm.serializing.ElmJsonLibraryWriter
import org.hl7.elm.r1.VersionedIdentifier

/** One `text/cql-expression` dynamicValue of a WHO PlanDefinition action, with the library that wraps it. */
internal data class WhoDynamicValue(
    val planDefinition: String,
    val action: Int,
    val activityDefinition: String?,
    val path: String,
    val expression: String,
    val libraries: List<String>,
    val wrapper: String,
)

/** WHO's inline dynamicValues, their wrapper libraries, and a library manager that serves ELM and CQL. */
internal object WhoDynamicValues {
    val packageDir = File(System.getenv("WHO_PACKAGE_DIR") ?: "/Users/danheslinga/projects/km-probe/corpus-scan/smart-immunizations/package")

    private val declaration = Regex("""^\s*library\s+"?([A-Za-z0-9_]+)"?""", RegexOption.MULTILINE)
    val librarySources = WhoSuite.librarySources.associateBy { declaration.find(it)!!.groupValues[1] }

    val options =
        CqlCompilerOptions(
            CqlCompilerException.ErrorSeverity.Info,
            SignatureLevel.Overloads,
            CqlCompilerOptions.Options.DisableListDemotion,
            CqlCompilerOptions.Options.DisableListPromotion,
            CqlCompilerOptions.Options.EnableResultTypes,
        )

    private fun read(prefix: String) =
        packageDir.listFiles { f -> f.name.startsWith(prefix) && f.name.endsWith(".json") }!!.sorted()
            .map { MeaslesFixtures.json.parseToJsonElement(it.readText()).jsonObject }

    /** The ActivityDefinitions, by id. */
    val activityDefinitions: Map<String, JsonObject> by lazy {
        read("ActivityDefinition-").associateBy { it.getValue("id").jsonPrimitive.content }
    }

    /** The `text/cql-expression` dynamicValues of every PlanDefinition, at any depth of nested actions. */
    val all: List<WhoDynamicValue> by lazy {
        val found = mutableListOf<WhoDynamicValue>()
        read("PlanDefinition-").forEach { plan ->
            val id = plan.getValue("id").jsonPrimitive.content
            val libraries =
                (plan["library"] as JsonArray?).orEmpty().map {
                    it.jsonPrimitive.content.substringAfterLast("/").substringBefore("|")
                }
            var actionIndex = 0
            fun walk(actions: JsonArray?) {
                actions.orEmpty().forEach { element ->
                    val action = element.jsonObject
                    val index = actionIndex++
                    val definition = action["definitionCanonical"]?.jsonPrimitive?.content?.substringAfterLast("/")?.substringBefore("|")
                    (action["dynamicValue"] as JsonArray?).orEmpty().forEach { dv ->
                        val expression = dv.jsonObject.getValue("expression").jsonObject
                        if (expression["language"]?.jsonPrimitive?.content == "text/cql-expression") {
                            val wrapper = "DV_${id.replace(Regex("[^A-Za-z0-9]"), "_")}_${found.count { it.planDefinition == id }}"
                            found +=
                                WhoDynamicValue(
                                    id,
                                    index,
                                    definition,
                                    dv.jsonObject.getValue("path").jsonPrimitive.content,
                                    expression.getValue("expression").jsonPrimitive.content,
                                    libraries,
                                    wrapper,
                                )
                        }
                    }
                    walk(action["action"]?.jsonArray)
                }
            }
            walk(plan["action"]?.jsonArray)
        }
        found
    }

    /** The wrapper library's CQL, following clinical-reasoning's `LibraryConstructor.constructCqlLibrary`. */
    fun wrapperCql(dv: WhoDynamicValue) = buildString {
        append("library ${dv.wrapper} version '1.0.0'\n\n")
        append("using FHIR version '4.0.1'\n\n")
        append("include FHIRHelpers version '4.0.1' called FHIRHelpers\n")
        dv.libraries.forEach { append("include \"$it\" called \"$it\"\n") }
        append("\ncontext Patient\n\n")
        append("define \"return\":\n  ${dv.expression}\n")
    }

    /** Compiles WHO's libraries and the given wrappers to ELM JSON. */
    fun compileToElmJson(wrappers: Map<String, String>): Map<String, String> {
        val compiler = Loader(emptyMap(), librarySources + wrappers)
        return (librarySources.keys + wrappers.keys).associateWith { name ->
            ElmJsonLibraryWriter()
                .writeAsString(compiler.libraryManager.resolveLibrary(VersionedIdentifier().apply { id = name }).library!!)
        }
    }

    /** A library manager whose provider serves ELM JSON from [elm] and CQL source from [cql]. */
    class Loader(elm: Map<String, String>, cql: Map<String, String>) {
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
    }
}
