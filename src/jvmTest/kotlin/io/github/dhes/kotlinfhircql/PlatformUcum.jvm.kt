package io.github.dhes.kotlinfhircql

import org.cqframework.cql.cql2elm.ucum.UcumService
import org.cqframework.cql.cql2elm.ucum.defaultLazyUcumService

// cqframework's ucum module, on the test classpath through engine-fhir.
actual fun platformUcumService(): Lazy<UcumService> = defaultLazyUcumService
