package io.github.dhes.kotlinfhircql

import org.opencds.cqf.cql.engine.elm.executing.PropertyEvaluator
import org.opencds.cqf.cql.engine.retrieve.RetrieveProvider
import org.opencds.cqf.cql.engine.runtime.ClassInstance
import org.opencds.cqf.cql.engine.runtime.Code
import org.opencds.cqf.cql.engine.runtime.Interval
import org.opencds.cqf.cql.engine.runtime.Value
import org.opencds.cqf.cql.engine.terminology.TerminologyProvider
import org.opencds.cqf.cql.engine.terminology.ValueSetInfo
import org.opencds.cqf.cql.engine.runtime.List as CqlList
import org.opencds.cqf.cql.engine.runtime.String as CqlString

/**
 * A [RetrieveProvider] over FHIR resources already converted to CQL values, filtering by type,
 * context, and codes or value set in memory.
 *
 * @param retrievableTypes when not null, the types [resources] holds all the needed resources of; a
 *   retrieve of any other type fails
 */
class InMemoryRetrieveProvider(
    private val resources: List<ClassInstance>,
    private val terminologyProvider: TerminologyProvider? = null,
    private val retrievableTypes: Set<String>? = null,
) : RetrieveProvider {

    override fun retrieve(
        context: String?,
        contextPath: String?,
        contextValue: String?,
        dataType: String,
        templateId: String?,
        codePath: String?,
        codes: Iterable<Code>?,
        valueSet: String?,
        datePath: String?,
        dateLowPath: String?,
        dateHighPath: String?,
        dateRange: Interval?,
    ): Iterable<Value?> {
        check(dateRange == null) { "Date filtering in retrieve is not implemented (dataType $dataType)" }
        check(retrievableTypes == null || dataType in retrievableTypes) { "No $dataType resources were read for this retrieve" }
        return resources
            .filter { it.type.getLocalPart() == dataType }
            .filter { contextPath == null || contextValue == null || inContext(it, context, contextPath, contextValue) }
            .filter { codePath == null || (codes == null && valueSet == null) || hasCode(it, codePath, codes, valueSet) }
    }

    private fun inContext(resource: ClassInstance, context: String?, path: String, contextValue: String): Boolean =
        values(PropertyEvaluator.resolvePath(resource, path)).any { value ->
            when (value.type.getLocalPart()) {
                "Reference" -> {
                    val reference = primitiveValue(value.elements["reference"]) ?: return@any false
                    reference == "$context/$contextValue" || reference.endsWith("/$context/$contextValue")
                }
                else -> primitiveValue(value) == contextValue
            }
        }

    private fun hasCode(resource: ClassInstance, path: String, codes: Iterable<Code>?, valueSet: String?): Boolean {
        val resourceCodes = values(PropertyEvaluator.resolvePath(resource, path)).flatMap(::codesOf)
        if (codes != null) {
            return resourceCodes.any { rc -> codes.any { c -> c.code == rc.code && (c.system == null || c.system == rc.system) } }
        }
        val terminology = checkNotNull(terminologyProvider) { "A value set filter needs a terminology provider" }
        val info = ValueSetInfo().withId(valueSet)
        return resourceCodes.any { terminology.`in`(it, info) }
    }

    private fun codesOf(value: ClassInstance): List<Code> =
        when (value.type.getLocalPart()) {
            "CodeableConcept" -> values(value.elements["coding"]).flatMap(::codesOf)
            "Coding" ->
                listOf(
                    Code()
                        .withCode(primitiveValue(value.elements["code"]))
                        .withSystem(primitiveValue(value.elements["system"]))
                        .withVersion(primitiveValue(value.elements["version"]))
                        .withDisplay(primitiveValue(value.elements["display"]))
                )
            else -> listOfNotNull(primitiveValue(value)?.let { Code().withCode(it) })
        }

    private fun values(value: Value?): List<ClassInstance> =
        when (value) {
            null -> emptyList()
            is CqlList -> value.filterIsInstance<ClassInstance>()
            is ClassInstance -> listOf(value)
            else -> emptyList()
        }

    private fun primitiveValue(value: Value?): String? =
        ((value as? ClassInstance)?.elements?.get("value") as? CqlString)?.value
}
