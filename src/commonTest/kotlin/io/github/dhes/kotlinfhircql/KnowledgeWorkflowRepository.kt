package io.github.dhes.kotlinfhircql

import dev.ohs.fhir.knowledge.KnowledgeManager
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.workflow.WorkflowRepository

/** WHO's package `smart.who.int.immunizations` 0.2.0 in kotlin-fhir-knowledge, and how it got there. */
class WhoKnowledge(val manager: KnowledgeManager, val how: String)

/** The tarball of WHO's package, and the directory the build unpacks it into for the JS and Wasm tests. */
object WhoPackage {
    const val NAME = "smart.who.int.immunizations"
    const val VERSION = "0.2.0"
    const val TGZ = "third_party/smart-immunizations-0.2.0/smart.who.int.immunizations-0.2.0.tgz"
    const val UNPACKED = "build/who-package/package"
}

/** WHO's package indexed by a new kotlin-fhir-knowledge [KnowledgeManager], in the way each platform allows. */
expect suspend fun whoKnowledge(): WhoKnowledge

/**
 * A [WorkflowRepository] that reads knowledge artifacts by canonical url from kotlin-fhir-knowledge, and patient data
 * from [patientData].
 */
class KnowledgeWorkflowRepository(private val knowledge: KnowledgeManager, private val patientData: WorkflowRepository) :
    WorkflowRepository by patientData {
    /** Canonical lookups made, by resource type, and how many found nothing. */
    val lookups = mutableMapOf<String, Int>()
    val notFound = mutableMapOf<String, Int>()

    override suspend fun searchByUri(type: String, param: String, uri: String): List<Resource> {
        require(param == "url") { "Only the url search parameter is supported, not $param" }
        lookups[type] = (lookups[type] ?: 0) + 1
        return knowledge.loadResources(uri).filter { it::class.simpleName == type }.also {
            if (it.isEmpty()) notFound[type] = (notFound[type] ?: 0) + 1
        }
    }
}
