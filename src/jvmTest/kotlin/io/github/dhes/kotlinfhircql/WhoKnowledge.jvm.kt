package io.github.dhes.kotlinfhircql

import com.sun.net.httpserver.HttpServer
import dev.ohs.fhir.knowledge.FhirNpmPackage
import dev.ohs.fhir.knowledge.KnowledgeManager
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import okio.Path.Companion.toPath

/**
 * Installs WHO's package with [KnowledgeManager.install], from a package server on localhost that serves the
 * package's tarball and answers 404 for anything else, such as the package's dependencies.
 */
actual suspend fun whoKnowledge(): WhoKnowledge {
    val tgz = File(PROJECT_DIR, WhoPackage.TGZ).readBytes()
    val requests = mutableListOf<String>()
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { exchange ->
        val path = exchange.requestURI.path
        val found = path == "/${WhoPackage.NAME}/${WhoPackage.VERSION}"
        synchronized(requests) { requests += "$path ${if (found) 200 else 404}" }
        if (found) {
            exchange.sendResponseHeaders(200, tgz.size.toLong())
            exchange.responseBody.use { it.write(tgz) }
        } else {
            exchange.sendResponseHeaders(404, -1)
            exchange.close()
        }
    }
    server.start()
    try {
        val manager =
            KnowledgeManager.create(
                platformContext = "kotlin-fhir-cql-spike-test",
                inMemory = true,
                downloadedNpmDir = Files.createTempDirectory("spike-knowledge").toString().toPath(),
                packageServer = "http://127.0.0.1:${server.address.port}/",
            )
        manager.install(FhirNpmPackage(WhoPackage.NAME, WhoPackage.VERSION))
        return WhoKnowledge(manager, "install() from a package server on localhost; requests: $requests")
    } finally {
        server.stop(0)
    }
}
