@file:OptIn(ExperimentalWasmJsInterop::class)

package io.github.dhes.kotlinfhircql

import org.cqframework.cql.cql2elm.ucum.UcumService
import org.cqframework.cql.shared.BigDecimal

@JsModule("@lhncbc/ucum-lhc")
private external object UcumLhcPackage : JsAny

private fun utilsOf(pkg: JsAny): JsAny = js("(pkg.UcumLhcUtils || pkg.default.UcumLhcUtils).getInstance()")

private fun validateUnitString(utils: JsAny, unit: String): JsAny = js("utils.validateUnitString(unit)")

private fun convertUnitTo(utils: JsAny, from: String, value: Double, to: String): JsAny = js("utils.convertUnitTo(from, value, to)")

private fun convertToBaseUnits(utils: JsAny, unit: String): JsAny = js("utils.convertToBaseUnits(unit, 1)")

private fun status(result: JsAny): String = js("result.status")

private fun messages(result: JsAny): String = js("(result.msg || []).join(' ')")

private fun toVal(result: JsAny): Double = js("result.toVal")

private fun magnitude(result: JsAny): String = js("String(result.magnitude)")

private fun isSpecial(result: JsAny): Boolean = js("result.fromUnitIsSpecial === true")

private fun exponents(result: JsAny): String = js("Object.keys(result.unitToExp).map(function (u) { return u + ' ' + result.unitToExp[u]; }).join(',')")

/**
 * A UCUM service over the `@lhncbc/ucum-lhc` npm package, for JS and Wasm, where cqframework has none.
 *
 * Products and quotients are given in base units, as the JVM's fhir-ucum gives them. Their values are computed on
 * integers, because cqframework's `BigDecimal` on these targets rounds a product or quotient to the scale of an
 * operand.
 */
object UcumLhcService : UcumService {
    private val utils: JsAny by lazy { utilsOf(UcumLhcPackage) }

    override fun validate(unit: String): String? {
        val result = validateUnitString(utils, unit)
        return if (status(result) == "valid") null else messages(result).ifEmpty { "Invalid UCUM unit '$unit'" }
    }

    override fun convert(value: BigDecimal, sourceUnit: String, destUnit: String): BigDecimal {
        if (sourceUnit == destUnit) return value
        val result = convertUnitTo(utils, sourceUnit, value.toDouble(), destUnit)
        if (status(result) != "succeeded") throw IllegalArgumentException(messages(result))
        return BigDecimal(toVal(result).toString())
    }

    override fun multiply(left: Pair<BigDecimal, String>, right: Pair<BigDecimal, String>): Pair<BigDecimal, String> =
        combine(left, right, 1)

    override fun divideBy(left: Pair<BigDecimal, String>, right: Pair<BigDecimal, String>): Pair<BigDecimal, String> =
        combine(left, right, -1)

    /** [left] times [right] raised to [sign], in base units. */
    private fun combine(left: Pair<BigDecimal, String>, right: Pair<BigDecimal, String>, sign: Int): Pair<BigDecimal, String> {
        val (leftFactor, leftExponents) = baseUnits(left.second)
        val (rightFactor, rightExponents) = baseUnits(right.second)
        val leftValue = Exact.of(left.first.toPlainString()) * leftFactor
        val rightValue = Exact.of(right.first.toPlainString()) * rightFactor
        val value = if (sign > 0) leftValue * rightValue else leftValue / rightValue
        val exponents = leftExponents.toMutableMap()
        for ((unit, exponent) in rightExponents) exponents[unit] = (exponents[unit] ?: 0) + sign * exponent
        val unit =
            exponents.filterValues { it != 0 }.entries.sortedBy { it.key }.joinToString(".") { (u, e) -> if (e == 1) u else "$u$e" }
        return value.toBigDecimal() to unit
    }

    /** The factor that takes a value in [unit] to base units, and the base units' exponents. */
    private fun baseUnits(unit: String): Pair<Exact, Map<String, Int>> {
        val result = convertToBaseUnits(utils, unit)
        if (status(result) != "succeeded") throw IllegalArgumentException(messages(result))
        if (isSpecial(result)) throw IllegalArgumentException("Unit '$unit' has no multiplicative conversion to base units")
        val exponents =
            exponents(result).split(',').filter { it.isNotEmpty() }.associate { entry ->
                entry.substringBefore(' ') to entry.substringAfter(' ').toInt()
            }
        return Exact.of(magnitude(result)) to exponents
    }
}

/** A decimal as an integer [digits] (with its sign) divided by 10 to the power [scale]. */
private class Exact(val digits: String, val scale: Int) {
    operator fun times(other: Exact) = Exact(integer(digits).multiply(integer(other.digits)), scale + other.scale)

    /** The quotient, truncated after [QUOTIENT_PLACES] more decimal places than this value has. */
    operator fun div(other: Exact) =
        Exact(
            integer(digits + "0".repeat(QUOTIENT_PLACES + other.scale)).divideAndRemainder(integer(other.digits))[0],
            scale + QUOTIENT_PLACES,
        )

    fun toBigDecimal(): BigDecimal {
        val negative = digits.startsWith("-")
        val magnitude = digits.removePrefix("-").padStart(scale + 1, '0')
        val plain = if (scale <= 0) magnitude + "0".repeat(-scale) else magnitude.dropLast(scale) + "." + magnitude.takeLast(scale)
        return BigDecimal((if (negative) "-" else "") + plain)
    }

    companion object {
        private const val QUOTIENT_PLACES = 20

        /** Parses a decimal written plainly or with an exponent, such as `-12.5` or `1e-7`. */
        fun of(text: String): Exact {
            val mantissa = text.substringBefore('e').substringBefore('E')
            val exponent = if (mantissa.length < text.length) text.substring(mantissa.length + 1).toInt() else 0
            val fraction = mantissa.substringAfter('.', "")
            return Exact(mantissa.replace(".", "").ifEmpty { "0" }, fraction.length - exponent)
        }

        /** [text], an integer, as cqframework's `BigDecimal`: with scale 0, products and quotients stay whole numbers. */
        private fun integer(text: String) = BigDecimal(text)

        /** The integer digits of [value], which has scale 0. */
        private fun Exact(value: BigDecimal, scale: Int): Exact {
            val parsed = of(value.toPlainString())
            return Exact(parsed.digits, parsed.scale + scale)
        }
    }
}
