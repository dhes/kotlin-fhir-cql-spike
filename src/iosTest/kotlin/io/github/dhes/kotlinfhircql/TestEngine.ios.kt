package io.github.dhes.kotlinfhircql

import dev.ohs.fhir.engine.FhirEngine
import dev.ohs.fhir.engine.FhirEngineConfiguration
import dev.ohs.fhir.engine.FhirEngineProvider

/** kotlin-fhir-engine with its in-memory database emptied, on the iOS simulator. */
actual suspend fun emptyTestEngine(): FhirEngine {
    if (FhirEngineProvider.isNotInitialized()) {
        FhirEngineProvider.init(FhirEngineConfiguration(testMode = true))
    }
    return FhirEngineProvider.getInstance().apply { clearDatabase() }
}
