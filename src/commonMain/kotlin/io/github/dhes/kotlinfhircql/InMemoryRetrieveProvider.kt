package io.github.dhes.kotlinfhircql

import org.opencds.cqf.cql.engine.elm.executing.InEvaluator
import org.opencds.cqf.cql.engine.elm.executing.IncludedInEvaluator
import org.opencds.cqf.cql.engine.elm.executing.PropertyEvaluator
import org.opencds.cqf.cql.engine.retrieve.RetrieveProvider
import org.opencds.cqf.cql.engine.runtime.ClassInstance
import org.opencds.cqf.cql.engine.runtime.BaseTemporal
import org.opencds.cqf.cql.engine.runtime.Code
import org.opencds.cqf.cql.engine.runtime.Interval
import org.opencds.cqf.cql.engine.runtime.Value
import org.opencds.cqf.cql.engine.terminology.TerminologyProvider
import org.opencds.cqf.cql.engine.terminology.ValueSetInfo
import org.opencds.cqf.cql.engine.runtime.List as CqlList
import org.opencds.cqf.cql.engine.runtime.String as CqlString

/**
 * A [RetrieveProvider] over FHIR resources already converted to CQL values, filtering by type,
 * context, profile, codes or value set, and date range in memory.
 *
 * @param retrievableTypes when not null, the types [resources] holds all the needed resources of; a
 *   retrieve of any other type fails
 * @param profileMode how a retrieve's profile (its `templateId`) selects resources
 */
class InMemoryRetrieveProvider(
    private val resources: List<ClassInstance>,
    private val terminologyProvider: TerminologyProvider? = null,
    private val retrievableTypes: Set<String>? = null,
    private val profileMode: ProfileMode = ProfileMode.OFF,
) : RetrieveProvider {

    /** The profile modes of clinical-reasoning's `RetrieveSettings.PROFILE_MODE` that apply in memory. */
    enum class ProfileMode {
        /** Ignores the profile. */
        OFF,

        /** Keeps resources whose `meta.profile` declares the profile. */
        DECLARED,

        /** As [DECLARED], also keeping resources that declare no profile. */
        OPTIONAL,
    }

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
        check(retrievableTypes == null || dataType in retrievableTypes) { "No $dataType resources were read for this retrieve" }
        check(dateRange == null || datePath != null || dateLowPath != null || dateHighPath != null) {
            "A date range was given with no date path (dataType $dataType)"
        }
        return resources
            .filter { it.type.getLocalPart() == dataType }
            .filter { contextPath == null || contextValue == null || inContext(it, context, contextPath, contextValue) }
            .filter { hasProfile(it, dataType, templateId) }
            .filter { codePath == null || (codes == null && valueSet == null) || hasCode(it, codePath, codes, valueSet) }
            .filter { dateRange == null || inDateRange(it, datePath, dateLowPath, dateHighPath, dateRange) }
    }

    /** Whether [resource] passes the profile test of [profileMode], as clinical-reasoning's `filterByTemplateId` does. */
    private fun hasProfile(resource: ClassInstance, dataType: String, templateId: String?): Boolean {
        if (profileMode == ProfileMode.OFF || templateId == null || templateId.substringBefore("|") == "$BASE_PROFILE$dataType") return true
        val declared = values(PropertyEvaluator.resolvePath(resource, "meta.profile")).mapNotNull(::primitiveValue)
        return if (declared.isEmpty()) profileMode == ProfileMode.OPTIONAL else templateId in declared
    }

    /**
     * Whether the date of [resource] lies in [dateRange], as the `where` clause the compiler's date range optimization
     * removed would decide: `In` for a point in time, `IncludedIn` for a Period or for the interval between
     * [dateLowPath] and [dateHighPath], each converted as FHIRHelpers 4.0.1 converts it. Null counts as false.
     */
    private fun inDateRange(
        resource: ClassInstance,
        datePath: String?,
        dateLowPath: String?,
        dateHighPath: String?,
        dateRange: Interval,
    ): Boolean {
        val dates =
            if (datePath != null) values(PropertyEvaluator.resolvePath(resource, datePath)).map(::temporal)
            else {
                val low = dateLowPath?.let { pointValue(PropertyEvaluator.resolvePath(resource, it)) }
                val high = dateHighPath?.let { pointValue(PropertyEvaluator.resolvePath(resource, it)) }
                if (low == null && high == null) emptyList() else listOf(period(low, high))
            }
        return dates.any { date ->
            when (date) {
                is Interval -> IncludedInEvaluator.intervalIncludedIn(date, dateRange, null, null)
                is BaseTemporal -> InEvaluator.`in`(date, dateRange, null, null)
                else -> null
            }?.value == true
        }
    }

    /** A FHIR date, dateTime or instant as a CQL Date or DateTime; a FHIR Period as FHIRHelpers' `ToInterval` makes it. */
    private fun temporal(value: ClassInstance): Value? =
        when (value.type.getLocalPart()) {
            "date", "dateTime", "instant" -> value.elements["value"]
            "Period" -> period(pointValue(value.elements["start"]), pointValue(value.elements["end"]))
            else -> null
        }

    private fun pointValue(value: Value?): Value? = (value as? ClassInstance)?.let(::temporal)

    /** FHIRHelpers 4.0.1 `ToInterval(Period)`: open at a null start, closed otherwise. */
    private fun period(start: Value?, end: Value?): Interval = Interval(start, start != null, end, true)

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

    private companion object {
        const val BASE_PROFILE = "http://hl7.org/fhir/StructureDefinition/"
    }
}
