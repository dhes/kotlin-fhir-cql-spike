package io.github.dhes.kotlinfhircql

import dev.ohs.fhir.engine.FhirEngine

/** kotlin-fhir-engine with its in-memory database emptied, shared by the tests. */
expect suspend fun emptyTestEngine(): FhirEngine
