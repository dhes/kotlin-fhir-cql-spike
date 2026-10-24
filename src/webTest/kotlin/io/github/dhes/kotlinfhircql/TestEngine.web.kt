package io.github.dhes.kotlinfhircql

import dev.ohs.fhir.engine.FhirEngine
import dev.ohs.fhir.engine.FhirEngineConfiguration
import dev.ohs.fhir.engine.FhirEngineProvider

/**
 * kotlin-fhir-engine with its in-memory database emptied, under Node: its SQLite runs in a worker thread through
 * `web-worker.cjs` (in `src/webTest/npm/sqlite-wasm-worker`), since Node has no Web Worker.
 */
actual suspend fun emptyTestEngine(): FhirEngine {
    if (FhirEngineProvider.isNotInitialized()) {
        installNodeWorker()
        FhirEngineProvider.init(FhirEngineConfiguration(testMode = true, storageDirectory = "spike"))
    }
    return FhirEngineProvider.getInstance().apply { clearDatabase() }
}
