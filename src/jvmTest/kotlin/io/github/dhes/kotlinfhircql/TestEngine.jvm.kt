package io.github.dhes.kotlinfhircql

import dev.ohs.fhir.engine.FhirEngine
import dev.ohs.fhir.engine.FhirEngineConfiguration
import dev.ohs.fhir.engine.FhirEngineProvider
import java.nio.file.Files

/** kotlin-fhir-engine with its in-memory database emptied, shared by the JVM tests. */
suspend fun emptyTestEngine(): FhirEngine {
    if (FhirEngineProvider.isNotInitialized()) {
        val storage = Files.createTempDirectory("fhir-engine").toFile().apply { deleteOnExit() }
        FhirEngineProvider.init(FhirEngineConfiguration(testMode = true, storageDirectory = storage.absolutePath))
    }
    return FhirEngineProvider.getInstance().apply { clearDatabase() }
}
