package io.github.dhes.kotlinfhircql

import org.cqframework.cql.cql2elm.ucum.UcumService
import org.cqframework.cql.shared.BigDecimal

/**
 * A stand-in UCUM service, since there is none on iOS: it accepts every unit, and converts, multiplies and divides
 * only where no conversion is needed. WHO's CQL needs units validated, not converted (step 14).
 */
actual fun platformUcumService(): Lazy<UcumService> = lazyOf(LenientUcumService)

object LenientUcumService : UcumService {
    override fun validate(unit: String): String? = null

    override fun convert(value: BigDecimal, sourceUnit: String, destUnit: String): BigDecimal =
        if (sourceUnit == destUnit) value else throw UnsupportedOperationException("No UCUM conversion on iOS: $sourceUnit to $destUnit")

    override fun multiply(left: Pair<BigDecimal, String>, right: Pair<BigDecimal, String>): Pair<BigDecimal, String> =
        throw UnsupportedOperationException("No UCUM multiplication on iOS")

    override fun divideBy(left: Pair<BigDecimal, String>, right: Pair<BigDecimal, String>): Pair<BigDecimal, String> =
        throw UnsupportedOperationException("No UCUM division on iOS")
}
