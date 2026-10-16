package io.github.dhes.kotlinfhircql

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.opencds.cqf.cql.engine.util.ZonedDateTime
import org.opencds.cqf.cql.engine.util.ZonedDateTimeJs

actual fun whoTestDateTime(): ZonedDateTime {
    val zone = TimeZone.currentSystemDefault()
    return ZonedDateTimeJs(LocalDateTime(2025, 11, 12, 12, 0).toInstant(zone), zone)
}

actual fun noonOn(year: Int, month: Int, day: Int): ZonedDateTime {
    val zone = TimeZone.currentSystemDefault()
    return ZonedDateTimeJs(LocalDateTime(year, month, day, 12, 0).toInstant(zone), zone)
}
