package io.github.dhes.kotlinfhircql

import dev.ohs.fhir.knowledge.FhirNpmPackage
import dev.ohs.fhir.knowledge.KnowledgeManager
import okio.Path.Companion.toPath

/**
 * Imports WHO's package with [KnowledgeManager.import] from the package as the build unpacked it, which the iOS
 * simulator reads from the Mac's file system.
 */
actual suspend fun whoKnowledge(): WhoKnowledge {
    val manager =
        KnowledgeManager.create(
            inMemory = true,
            downloadedNpmDir = "$PROJECT_DIR/build/ios-knowledge".toPath(),
        )
    val result = manager.import(FhirNpmPackage(WhoPackage.NAME, WhoPackage.VERSION), "$PROJECT_DIR/${WhoPackage.UNPACKED}".toPath())
    return WhoKnowledge(manager, "import() from the unpacked package: $result")
}
