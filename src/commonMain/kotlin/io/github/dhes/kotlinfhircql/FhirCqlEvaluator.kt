package io.github.dhes.kotlinfhircql

import org.cqframework.cql.cql2elm.CqlCompilerException
import org.cqframework.cql.cql2elm.CqlCompilerOptions
import org.cqframework.cql.cql2elm.LibraryManager
import org.cqframework.cql.cql2elm.LibrarySourceProvider
import org.cqframework.cql.cql2elm.ModelManager
import org.hl7.cql.model.NamespaceManager
import org.cqframework.cql.cql2elm.createModelInfoProvider
import org.cqframework.cql.cql2elm.utils.asSource
import org.hl7.cql.model.SystemModelInfoProvider
import org.cqframework.cql.cql2elm.LibraryBuilder.SignatureLevel
import org.hl7.elm.r1.VersionedIdentifier
import org.opencds.cqf.cql.engine.data.CompositeDataProvider
import org.opencds.cqf.cql.engine.execution.CqlEngine
import org.opencds.cqf.cql.engine.execution.Environment
import org.opencds.cqf.cql.engine.model.ModelResolver
import org.opencds.cqf.cql.engine.runtime.ClassInstance
import org.opencds.cqf.cql.engine.runtime.Value
import org.opencds.cqf.cql.engine.terminology.TerminologyProvider
import org.opencds.cqf.cql.engine.util.ZonedDateTime

/**
 * Compiles CQL libraries from source against the FHIR 4.0.1 model info supplied by the caller, and
 * evaluates them in Patient context over resources the caller supplies.
 *
 * @param librarySources CQL source text of every library needed, FHIRHelpers included
 * @param fhirModelInfoXml the text of fhir-modelinfo-4.0.1.xml
 * @param modelResolverFactory makes the FHIR model resolver; the default answers from the model info
 */
class FhirCqlEvaluator(
    librarySources: List<String>,
    fhirModelInfoXml: String,
    private val terminologyProvider: TerminologyProvider? = null,
    modelResolverFactory: (ModelManager) -> ModelResolver = { KotlinFhirModelResolver(it.resolveModel("FHIR", "4.0.1")) },
) {
    val modelManager =
        ModelManager(NamespaceManager(), false).apply {
            modelInfoLoader.registerModelInfoProvider(SystemModelInfoProvider())
            modelInfoLoader.registerModelInfoProvider(
                createModelInfoProvider { id, _, version ->
                    if (id == "FHIR" && (version == null || version == "4.0.1")) fhirModelInfoXml.asSource() else null
                }
            )
        }

    private val sourcesByName: Map<String, String> =
        librarySources.associateBy { source ->
            LIBRARY_DECLARATION.find(source)?.groupValues?.get(1) ?: error("No library declaration in source")
        }

    val libraryManager =
        LibraryManager(
                modelManager,
                CqlCompilerOptions(
                    CqlCompilerException.ErrorSeverity.Info,
                    SignatureLevel.Overloads,
                    CqlCompilerOptions.Options.DisableListDemotion,
                    CqlCompilerOptions.Options.DisableListPromotion,
                ),
            )
            .apply {
                librarySourceLoader.registerProvider(
                    object : LibrarySourceProvider {
                        override fun getLibrarySource(libraryIdentifier: VersionedIdentifier) =
                            sourcesByName[libraryIdentifier.id]?.asSource()
                    }
                )
            }

    val modelResolver: ModelResolver = modelResolverFactory(modelManager)

    /** Evaluates [expressions] (all definitions when null) of [library] for one patient. */
    fun evaluate(
        library: String,
        patientId: String,
        resources: List<ClassInstance>,
        expressions: List<String>? = null,
        parameters: Map<String, Value?>? = null,
        evaluationDateTime: ZonedDateTime? = null,
    ): Map<String, Value?> {
        val environment = Environment(libraryManager, null, terminologyProvider)
        environment.registerDataProvider(
            FHIR_MODEL_URI,
            CompositeDataProvider(modelResolver, InMemoryRetrieveProvider(resources, terminologyProvider)),
        )
        val result =
            CqlEngine(environment)
                .evaluate {
                    library(library) { if (expressions != null) expressions(expressions) }
                    contextParameter = "Patient" to patientId
                    this.parameters = parameters
                    this.evaluationDateTime = evaluationDateTime
                }
                .onlyResultOrThrow
        return result.expressionResults.mapValues { it.value.value }
    }

    private companion object {
        val LIBRARY_DECLARATION = Regex("""(?m)^\s*library\s+"?([A-Za-z0-9_]+)"?""")
    }
}
