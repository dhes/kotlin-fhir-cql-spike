package io.github.dhes.kotlinfhircql

import dev.ohs.fhir.model.r4.Enumeration
import dev.ohs.fhir.model.r4.ExtensibleEnumeration
import dev.ohs.fhir.model.r4.FhirChoice
import dev.ohs.fhir.model.r4.FhirDate
import dev.ohs.fhir.model.r4.FhirDateTime
import dev.ohs.fhir.model.r4.FhirDecimal
import dev.ohs.fhir.model.r4.Resource
import io.github.dhes.kotlinfhircql.generated.structureOf
import kotlinx.datetime.LocalTime
import org.cqframework.cql.shared.BigDecimal
import org.cqframework.cql.shared.QName
import org.opencds.cqf.cql.engine.runtime.ClassInstance
import org.opencds.cqf.cql.engine.runtime.Value
import org.opencds.cqf.cql.engine.runtime.Boolean as CqlBoolean
import org.opencds.cqf.cql.engine.runtime.Date as CqlDate
import org.opencds.cqf.cql.engine.runtime.DateTime as CqlDateTime
import org.opencds.cqf.cql.engine.runtime.Decimal as CqlDecimal
import org.opencds.cqf.cql.engine.runtime.Integer as CqlInteger
import org.opencds.cqf.cql.engine.runtime.List as CqlList
import org.opencds.cqf.cql.engine.runtime.String as CqlString
import org.opencds.cqf.cql.engine.runtime.Time as CqlTime

const val FHIR_MODEL_URI = "http://hl7.org/fhir"
const val FHIR_MODEL_ID = "FHIR"

fun fhirType(name: String) = QName(FHIR_MODEL_URI, name, FHIR_MODEL_ID)

/** One element of a model-info type: its name, its declared model-info type, and its kotlin-fhir value. */
internal class Slot(val name: String, val declared: String?, val value: Any?)

/** The model-info type name of a kotlin-fhir value and its elements. */
internal class Structure(val typeName: String, val slots: List<Slot>)

/**
 * Converts kotlin-fhir R4 resources to CQL engine values whose types and elements follow the
 * FHIR 4.0.1 model info.
 *
 * @param defaultOffsetHours the UTC offset, in hours, given to a FHIR dateTime that has none
 */
class KotlinFhirToCql(private val defaultOffsetHours: BigDecimal) {

    fun convert(resource: Resource): ClassInstance = convert(null, resource) as ClassInstance

    private fun convert(declared: String?, value: Any?): Value? =
        when (value) {
            null -> null
            is List<*> -> if (value.isEmpty()) null else CqlList(value.map { convert(declared, it) })
            is FhirChoice -> convert(null, value.value)
            is Enumeration<*> ->
                primitive(declaredName(declared) ?: "code", value.id, value.extension, value.value?.code?.let(::CqlString))
            is ExtensibleEnumeration<*> ->
                primitive(declaredName(declared) ?: "code", value.id, value.extension, CqlString(value.code))
            is String -> wrap(declared, CqlString(value))
            is Int -> wrap(declared, CqlInteger(value))
            is Boolean -> wrap(declared, CqlBoolean(value))
            is FhirDateTime -> dateTime(value)
            is FhirDate -> date(value)
            is LocalTime -> CqlTime(value.toString())
            is FhirDecimal -> CqlDecimal(BigDecimal(value.toString()))
            else -> structure(value)
        }

    private fun structure(value: Any): ClassInstance {
        val structure =
            structureOf(value)
                ?: error("No FHIR model-info type for kotlin-fhir class ${value::class.simpleName}")
        val elements = mutableMapOf<String, Value?>()
        for (slot in structure.slots) {
            elements[slot.name] = convert(slot.declared, slot.value)
        }
        return ClassInstance(fhirType(structure.typeName), elements)
    }

    /** A Kotlin value held directly by kotlin-fhir where the model info declares a FHIR primitive. */
    private fun wrap(declared: String?, value: Value): Value {
        val name = declaredName(declared) ?: return value
        return primitive(name, null, emptyList<Any>(), value)
    }

    private fun primitive(name: String, id: String?, extension: List<*>, value: Value?): ClassInstance =
        ClassInstance(
            fhirType(name),
            mutableMapOf(
                "id" to id?.let(::CqlString),
                "extension" to convert("FHIR.Extension", extension),
                "value" to value,
            ),
        )

    private fun declaredName(declared: String?): String? =
        declared?.takeIf { it.startsWith("FHIR.") }?.removePrefix("FHIR.")

    private fun dateTime(value: FhirDateTime): CqlDateTime =
        when (value) {
            is FhirDateTime.Year -> CqlDateTime(defaultOffsetHours, value.value)
            is FhirDateTime.YearMonth ->
                CqlDateTime(defaultOffsetHours, value.value.year, value.value.month.ordinal + 1)
            is FhirDateTime.Date ->
                CqlDateTime(defaultOffsetHours, value.date.year, value.date.month.ordinal + 1, value.date.day)
            is FhirDateTime.DateTime -> {
                val d = value.dateTime
                val offset = BigDecimal(value.utcOffset.totalSeconds).divide(BigDecimal(3600))
                // kotlin-fhir keeps no record of fractional-second digits, so precision is
                // millisecond only when the fraction is non-zero.
                if (d.nanosecond == 0) {
                    CqlDateTime(offset, d.year, d.month.ordinal + 1, d.day, d.hour, d.minute, d.second)
                } else {
                    CqlDateTime(
                        offset, d.year, d.month.ordinal + 1, d.day, d.hour, d.minute, d.second,
                        d.nanosecond / 1_000_000,
                    )
                }
            }
        }

    private fun date(value: FhirDate): CqlDate =
        when (value) {
            is FhirDate.Year -> CqlDate(value.value)
            is FhirDate.YearMonth -> CqlDate(value.value.year, value.value.month.ordinal + 1)
            is FhirDate.Date -> CqlDate(value.date.year, value.date.month.ordinal + 1, value.date.day)
        }
}
