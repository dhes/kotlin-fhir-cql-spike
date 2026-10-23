package io.github.dhes.kotlinfhircql

import dev.ohs.fhir.model.r4.ValueSet
import dev.ohs.fhir.workflow.WorkflowRepository
import org.cqframework.cql.cql2elm.CqlCompilerOptions
import org.cqframework.cql.cql2elm.LibraryContentType
import org.cqframework.cql.cql2elm.LibrarySourceProvider
import org.cqframework.cql.cql2elm.utils.asSource
import org.cqframework.cql.cql2elm.ucum.UcumService
import org.cqframework.cql.cql2elm.ucum.defaultLazyUcumService
import org.cqframework.cql.elm.visiting.BaseElmLibraryVisitor
import org.hl7.elm.r1.Element
import org.hl7.elm.r1.Retrieve
import org.hl7.elm.r1.ValueSetDef
import org.hl7.elm.r1.VersionedIdentifier
import org.opencds.cqf.cql.engine.runtime.ClassInstance
import org.opencds.cqf.cql.engine.runtime.Value
import org.opencds.cqf.cql.engine.terminology.TerminologyProvider
import org.opencds.cqf.cql.engine.util.ZonedDateTime

/**
 * Evaluates CQL libraries held as FHIR Library resources in a [WorkflowRepository], for one patient,
 * reading the libraries, value sets and patient data they need from the repository before evaluating,
 * because cqframework's CQL engine retrieves synchronously and the repository is read with `suspend` calls.
 * Each library is loaded from its ELM when cqframework can use it, and compiled from its CQL otherwise.
 *
 * @param otherLibrarySources CQL source text of libraries that are not in the repository, such as FHIRHelpers
 * @param compilerOptions the CQL compiler options, which ELM in the repository must have been compiled with
 */
class WorkflowRepositoryCqlEvaluator(
    private val repository: WorkflowRepository,
    fhirModelInfoXml: String,
    otherLibrarySources: List<String> = emptyList(),
    ucumService: Lazy<UcumService> = defaultLazyUcumService,
    compilerOptions: CqlCompilerOptions = FhirCqlEvaluator.compilerOptions(),
) {
    private val otherSources = otherLibrarySources.associateBy(FhirCqlEvaluator::libraryName)
    private val requirements = mutableMapOf<String, Requirements>()

    /** The Library resources read from the repository, served to cqframework's compiler. */
    val libraries = WorkflowRepositoryLibrarySourceProvider(repository)

    val evaluator =
        FhirCqlEvaluator(
            object : LibrarySourceProvider {
                override fun getLibrarySource(libraryIdentifier: VersionedIdentifier) =
                    getLibraryContent(libraryIdentifier, LibraryContentType.CQL)

                override fun getLibraryContent(libraryIdentifier: VersionedIdentifier, type: LibraryContentType) =
                    libraries.getLibraryContent(libraryIdentifier, type)
                        ?: otherSources[libraryIdentifier.id]?.takeIf { type == LibraryContentType.CQL }?.asSource()
            },
            fhirModelInfoXml,
            ucumService = ucumService,
            compilerOptions = compilerOptions,
        )

    /**
     * Reads the Library with canonical [url] and, through its `depends-on` related artifacts, every
     * library it includes, directly or not, and returns their names.
     */
    suspend fun loadLibraries(url: String): List<String> = libraries.load(url).map { it.id!! }.sorted()

    /**
     * Reads the patient data that [libraries] retrieve and the value sets they declare: the Patient by
     * id, and resources of each other retrieved type that has a patient link, by the search parameter in
     * [PATIENT_SEARCH_PARAMETERS]. Retrieved types without a patient link are not read, and a retrieve of
     * one fails.
     */
    suspend fun readData(libraries: List<String>, patientId: String, converter: KotlinFhirToCql): PatientData {
        val needed = libraries.map(::requirementsOf)
        val types = needed.flatMap { it.types }.toSet()
        val readTypes = types.filter { it == "Patient" || it in PATIENT_SEARCH_PARAMETERS }.toSet()
        val resources =
            readTypes.flatMap { type ->
                if (type == "Patient") {
                    listOfNotNull(repository.read("Patient", patientId))
                } else {
                    repository.searchByReferenceParam(type, PATIENT_SEARCH_PARAMETERS.getValue(type), "Patient/$patientId")
                }
            }
        val valueSetIds = needed.flatMap { it.valueSets }.toSet()
        val valueSets =
            valueSetIds.mapNotNull { id ->
                repository.searchByUri("ValueSet", "url", id.substringBefore("|")).firstOrNull() as? ValueSet
            }
        return PatientData(
            resources.map(converter::convert),
            readTypes,
            InMemoryTerminologyProvider(valueSets),
            unreadTypes = types - readTypes,
            missingValueSets = valueSetIds - valueSets.mapNotNull { it.url?.value }.toSet(),
        )
    }

    /** Evaluates [expressions] (all definitions when null) of [library] over [data]. */
    fun evaluate(
        library: String,
        patientId: String,
        data: PatientData,
        expressions: List<String>? = null,
        parameters: Map<String, Value?>? = null,
        evaluationDateTime: ZonedDateTime? = null,
    ): Map<String, Value?> =
        evaluator.evaluate(
            library, patientId, data.resources, expressions, parameters, evaluationDateTime,
            data.terminologyProvider, data.types,
        )

    private fun requirementsOf(library: String): Requirements =
        requirements.getOrPut(library) {
            val elm = evaluator.libraryManager.resolveLibrary(VersionedIdentifier().withId(library)).library!!
            Requirements(mutableSetOf(), mutableSetOf()).also { RequirementsVisitor.visitLibrary(elm, it) }
        }

    companion object {
        /**
         * The FHIR R4 search parameter on the element that links each type to its patient (the elements of
         * [KotlinFhirModelResolver.PATIENT_CONTEXT_PATHS]). It is `subject`, not `patient`, where R4 defines
         * `patient` as `subject.where(resolve() is Patient)`: kotlin-fhir-engine 2.0.0-alpha04 does not index
         * that, because kotlin-fhir-path does not implement `resolve()`.
         */
        val PATIENT_SEARCH_PARAMETERS =
            mapOf(
                "Immunization" to "patient",
                "AllergyIntolerance" to "patient",
                "Condition" to "subject",
                "Observation" to "subject",
                "MedicationRequest" to "subject",
                "Encounter" to "subject",
                "Procedure" to "subject",
            )
    }

    /**
     * The patient data and value sets read for one evaluation.
     *
     * @param types the retrieved types whose resources were read
     * @param unreadTypes the retrieved types that were not read, because they have no patient link
     * @param missingValueSets declared value sets that the repository does not hold
     */
    class PatientData(
        val resources: List<ClassInstance>,
        val types: Set<String>,
        val terminologyProvider: TerminologyProvider,
        val unreadTypes: Set<String> = emptySet(),
        val missingValueSets: Set<String> = emptySet(),
    )

    private class Requirements(val types: MutableSet<String>, val valueSets: MutableSet<String>)

    /** Collects the FHIR types a library retrieves and the value sets it declares. */
    private object RequirementsVisitor : BaseElmLibraryVisitor<Unit, Requirements>() {
        override fun defaultResult(elm: Element, context: Requirements) = Unit

        override fun visitRetrieve(elm: Retrieve, context: Requirements) {
            elm.dataType?.let { if (it.getNamespaceURI() == FHIR_MODEL_URI) context.types += it.getLocalPart() }
            super.visitRetrieve(elm, context)
        }

        override fun visitValueSetDef(elm: ValueSetDef, context: Requirements) {
            elm.id?.let { context.valueSets += it }
            super.visitValueSetDef(elm, context)
        }
    }
}
