package io.github.dhes.kotlinfhircql

import dev.ohs.fhir.knowledge.FhirNpmPackage
import dev.ohs.fhir.knowledge.KnowledgeManager
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem

/**
 * Imports WHO's package with [KnowledgeManager.import], from an in-memory file system holding the package as the
 * build unpacked it: [KnowledgeManager.install] can't unpack a package on the web targets. kotlin-fhir-knowledge's
 * SQLite runs in a worker thread through the spike's Node `Worker`, as kotlin-fhir-engine's does (step 13).
 */
actual suspend fun whoKnowledge(): WhoKnowledge {
    installNodeWorker()
    val fileSystem = FakeFileSystem()
    val root = "/package".toPath()
    fileSystem.createDirectories(root)
    val unpacked = Path(PROJECT_DIR, WhoPackage.UNPACKED)
    for (file in SystemFileSystem.list(unpacked)) {
        if (SystemFileSystem.metadataOrNull(file)?.isDirectory == true) continue
        val bytes = SystemFileSystem.source(file).buffered().use { it.readByteArray() }
        fileSystem.write(root / file.name) { write(bytes) }
    }
    val manager = KnowledgeManager.create(inMemory = true, fileSystem = fileSystem)
    val result = manager.import(FhirNpmPackage(WhoPackage.NAME, WhoPackage.VERSION), root)
    return WhoKnowledge(manager, "import() from an in-memory file system: $result")
}
