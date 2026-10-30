package io.github.dhes.kotlinfhircql

import dev.ohs.fhir.knowledge.KnowledgeManager
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import dev.ohs.fhir.workflow.CanonicalResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest

/** The adapter in kotlin-fhir-knowledge's README, "Serving the workflow library's CanonicalResolver". */
class KnowledgeCanonicalResolver(private val knowledgeManager: KnowledgeManager) : CanonicalResolver {
    override suspend fun resolve(type: ResourceType, canonical: String): Resource? =
        knowledgeManager.loadResources(canonical).firstOrNull { it::class.simpleName == type.code }
}

/**
 * Step 16, on the JVM and Wasm: `$apply` on each WHO scenario as in step 15, with the PlanDefinition and its
 * ActivityDefinitions resolved from kotlin-fhir-knowledge through [KnowledgeCanonicalResolver], against step 15's
 * outcome from the spike's copies of the same resources.
 */
class KnowledgeApplyTest {
    @Test
    fun applyWithDefinitionsFromKnowledge() = runTest(timeout = 60.minutes) {
        preloadFixtures()
        val knowledge = whoKnowledge()
        println(knowledge.how)
        val fromKnowledge = KnowledgeCanonicalResolver(knowledge.manager)
        val resolved = mutableMapOf<String, Int>()
        val resolver =
            CanonicalResolver { type, canonical ->
                resolved[type.code] = (resolved[type.code] ?: 0) + 1
                fromKnowledge.resolve(type, canonical)?.let(WhoApply::conformed)
            }
        val differ = mutableListOf<String>()
        var applied = 0
        for (scenario in WhoSuite.scenarios) {
            val withKnowledge = WhoApply.apply(scenario, resolver = resolver)
            val withCopies = WhoApply.apply(scenario)
            if (withKnowledge != withCopies) differ += "${scenario.id}: knowledge $withKnowledge, copies $withCopies"
            if (withKnowledge.error == null) applied++
        }
        println("Scenarios applied: $applied of ${WhoSuite.scenarios.size}; resolved from kotlin-fhir-knowledge, by type: $resolved")
        println("Differ from the spike's copies: ${differ.size}")
        differ.take(20).forEach(::println)
        knowledge.manager.close()
        assertEquals(emptyList(), differ)
    }
}
