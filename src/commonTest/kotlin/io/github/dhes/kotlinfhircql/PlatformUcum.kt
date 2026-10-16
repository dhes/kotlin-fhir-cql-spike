package io.github.dhes.kotlinfhircql

import org.cqframework.cql.cql2elm.ucum.UcumService
import org.cqframework.cql.shared.BigDecimal

/** The UCUM service the tests use on this platform. */
expect fun platformUcumService(): Lazy<UcumService>

/**
 * A stand-in for a UCUM service on targets where cqframework has none: it accepts every unit
 * without checking it, and converts only between identical units.
 */
object UnvalidatedUcumService : UcumService {
    override fun validate(unit: String): String? = null

    override fun convert(value: BigDecimal, sourceUnit: String, destUnit: String): BigDecimal {
        if (sourceUnit == destUnit) return value
        throw UnsupportedOperationException("No UCUM conversion from '$sourceUnit' to '$destUnit' on this target")
    }

    override fun multiply(left: Pair<BigDecimal, String>, right: Pair<BigDecimal, String>): Pair<BigDecimal, String> =
        throw UnsupportedOperationException("No UCUM multiplication on this target")

    override fun divideBy(left: Pair<BigDecimal, String>, right: Pair<BigDecimal, String>): Pair<BigDecimal, String> =
        throw UnsupportedOperationException("No UCUM division on this target")
}
