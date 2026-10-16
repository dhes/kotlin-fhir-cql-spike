package io.github.dhes.kotlinfhircql

import java.time.ZoneId
import org.opencds.cqf.cql.engine.util.ZonedDateTime

actual fun whoTestDateTime(): ZonedDateTime =
    java.time.ZonedDateTime.of(2025, 11, 12, 12, 0, 0, 0, ZoneId.systemDefault())
