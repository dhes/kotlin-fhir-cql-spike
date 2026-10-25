package io.github.dhes.kotlinfhircql

import dev.ohs.fhir.model.r4.Code as FhirCode
import dev.ohs.fhir.model.r4.CodeableConcept
import dev.ohs.fhir.model.r4.Coding
import dev.ohs.fhir.model.r4.String as FhirString
import dev.ohs.fhir.model.r4.Uri
import dev.ohs.fhir.workflow.expression.EvaluationContext
import dev.ohs.fhir.workflow.expression.EvaluationResult
import dev.ohs.fhir.workflow.expression.ExpressionEvaluator
import dev.ohs.fhir.workflow.expression.ProtocolExpression
import org.cqframework.cql.cql2elm.LibrarySourceProvider
import kotlinx.io.Source
import org.cqframework.cql.cql2elm.utils.asSource
import org.hl7.elm.r1.VersionedIdentifier
import org.opencds.cqf.cql.engine.runtime.ClassInstance
import org.opencds.cqf.cql.engine.runtime.Value
import org.opencds.cqf.cql.engine.util.ZonedDateTime
import org.opencds.cqf.cql.engine.runtime.Boolean as CqlBoolean
import org.opencds.cqf.cql.engine.runtime.Code as CqlCode
import org.opencds.cqf.cql.engine.runtime.Concept as CqlConcept
import org.opencds.cqf.cql.engine.runtime.Decimal as CqlDecimal
import org.opencds.cqf.cql.engine.runtime.Integer as CqlInteger
import org.opencds.cqf.cql.engine.runtime.List as CqlList
import org.opencds.cqf.cql.engine.runtime.String as CqlString

/**
 * Evaluates CQL behind kotlin-fhir-workflow's [ExpressionEvaluator], for one patient and one PlanDefinition's library.
 *
 * kotlin-fhir-workflow passes a `text/cql` expression as [ProtocolExpression.Elm] with its text. A text that names a
 * definition of [library] gives that definition's value; any other text is evaluated as a CQL expression in a library
 * that includes [library] under its own name. Values are converted to the types kotlin-fhir-workflow's write-back
 * takes: a CQL String to a Kotlin string, a Code to a [Coding], a Concept to a [CodeableConcept], and a FHIR primitive
 * (such as the `id` of a resource) to its value.
 */
class CqlExpressionEvaluator(
    private val evaluator: FhirCqlEvaluator,
    private val expressions: InlineCqlLibraries,
    private val library: String,
    private val patientId: String,
    private val resources: List<ClassInstance>,
    private val parameters: Map<String, Value?>,
    private val evaluationDateTime: ZonedDateTime,
) : ExpressionEvaluator {
    private val definitions: Map<String, Value?> by lazy {
        evaluator.evaluate(library, patientId, resources, null, parameters, evaluationDateTime)
    }

    override suspend fun evaluate(expression: ProtocolExpression, context: EvaluationContext): EvaluationResult {
        val text = (expression as? ProtocolExpression.Elm)?.elmJson ?: return EvaluationResult.Failure("Not CQL: $expression")
        return try {
            val value =
                if (text in definitions) {
                    definitions[text]
                } else {
                    val name = expressions.nameFor(text, library)
                    evaluator.evaluate(name, patientId, resources, listOf(InlineCqlLibraries.DEFINITION), parameters, evaluationDateTime)[
                        InlineCqlLibraries.DEFINITION]
                }
            result(value)
        } catch (e: Throwable) {
            // Throwable, not Exception: on Wasm the engine's Split throws NotImplementedError.
            EvaluationResult.Failure("${e::class.simpleName}: ${e.message}")
        }
    }

    private fun result(value: Value?): EvaluationResult =
        when (value) {
            null -> EvaluationResult.Values(emptyList())
            is CqlBoolean -> EvaluationResult.Bool(value.value)
            is CqlList -> EvaluationResult.Values(value.filterNotNull().map(::workflowValue))
            else -> EvaluationResult.Values(listOf(workflowValue(value)))
        }

    private fun workflowValue(value: Value): Any =
        when (value) {
            is CqlString -> value.value
            is CqlBoolean -> value.value
            is CqlInteger -> value.value
            is CqlDecimal -> value.value.toDouble()
            is CqlCode -> coding(value)
            is CqlConcept ->
                CodeableConcept(
                    coding = value.codes.orEmpty().filterNotNull().map(::coding),
                    text = value.display?.let { FhirString(value = it) },
                )
            // A FHIR primitive, such as `id` from `Resource.id`: its value.
            is ClassInstance ->
                if (value.type.getLocalPart().first().isLowerCase()) {
                    workflowValue(value.elements["value"] ?: throw IllegalStateException("FHIR ${value.type.getLocalPart()} with no value"))
                } else {
                    throw IllegalStateException("No conversion for FHIR ${value.type.getLocalPart()}")
                }
            else -> throw IllegalStateException("No conversion for a CQL ${value::class.simpleName}")
        }

    private fun coding(code: CqlCode) =
        Coding(
            system = code.system?.let { Uri(value = it) },
            version = code.version?.let { FhirString(value = it) },
            code = code.code?.let { FhirCode(value = it) },
            display = code.display?.let { FhirString(value = it) },
        )
}

/**
 * Serves one library per CQL expression, each defining [DEFINITION] as the expression, in Patient context, with
 * FHIRHelpers and the given library included.
 */
class InlineCqlLibraries : LibrarySourceProvider {
    private val names = mutableMapOf<Pair<String, String>, String>()
    private val sources = mutableMapOf<String, String>()

    /** The name of the library that defines [expression], with [library] included under its own name. */
    fun nameFor(expression: String, library: String): String =
        names.getOrPut(expression to library) {
            val name = "InlineExpression${names.size + 1}"
            sources[name] =
                """
                |library $name
                |using FHIR version '4.0.1'
                |include FHIRHelpers version '4.0.1'
                |include $library called $library
                |context Patient
                |define "$DEFINITION": $expression
                |""".trimMargin()
            name
        }

    override fun getLibrarySource(libraryIdentifier: VersionedIdentifier): Source? =
        sources[libraryIdentifier.id]?.asSource()

    companion object {
        const val DEFINITION = "Value"
    }
}
