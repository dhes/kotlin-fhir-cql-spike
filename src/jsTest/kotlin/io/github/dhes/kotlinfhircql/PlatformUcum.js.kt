package io.github.dhes.kotlinfhircql

import org.cqframework.cql.cql2elm.ucum.UcumService

actual fun platformUcumService(): Lazy<UcumService> = lazyOf(UcumLhcService)
