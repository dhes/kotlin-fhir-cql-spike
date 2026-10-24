package io.github.dhes.kotlinfhircql

import org.cqframework.cql.cql2elm.ucum.UcumService

/** The UCUM service the tests use on this platform: cqframework's on the JVM, the spike's `UcumLhcService` on JS and Wasm. */
expect fun platformUcumService(): Lazy<UcumService>
