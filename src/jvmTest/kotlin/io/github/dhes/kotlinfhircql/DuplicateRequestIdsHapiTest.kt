package io.github.dhes.kotlinfhircql

import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest

/**
 * For the WHO scenarios whose CarePlan from kotlin-fhir-workflow's `$apply` contains two resources with one id:
 * how many contained resources HAPI keeps when it reads that CarePlan's JSON, and which references the
 * RequestGroup's actions make, and how many remain after HAPI writes it again (as a HAPI server would return it).
 * Prints only.
 */
class DuplicateRequestIdsHapiTest {
    @Test
    fun hapiReadsTheJson() = runTest(timeout = 60.minutes) {
        var affected = 0
        var shorter = 0
        var reencodedShorter = 0
        var example: String? = null
        for (scenario in WhoSuite.scenarios) {
            val carePlan = WhoApply.apply(scenario).carePlan ?: continue
            val ids = carePlan.contained.mapNotNull { it.id }
            if (ids.size == ids.toSet().size) continue
            affected++
            val text = MeaslesFixtures.json.encodeToString(dev.ohs.fhir.model.r4.Resource.serializer(), carePlan)
            val hapi = ClinicalReasoningApply.context.newJsonParser().parseResource(org.hl7.fhir.r4.model.CarePlan::class.java, text)
            if (hapi.contained.size < carePlan.contained.size) shorter++
            val reencoded = ClinicalReasoningApply.context.newJsonParser().encodeResourceToString(hapi)
            val again = ClinicalReasoningApply.context.newJsonParser().parseResource(org.hl7.fhir.r4.model.CarePlan::class.java, reencoded)
            if (again.contained.size < carePlan.contained.size) reencodedShorter++
            if (example == null) {
                val refs = Regex("\"reference\":\"(#[^\"]+)\"").findAll(text).map { it.groupValues[1] }.toList()
                example = "${scenario.id}: kotlin-fhir contained ${ids}; HAPI contained ${hapi.contained.map { it.idElement.idPart }}; references $refs"
            }
        }
        println("Scenarios with a repeated contained id: $affected; HAPI keeps fewer contained resources in $shorter when it reads the JSON, and in $reencodedShorter after it writes it again")
        println(example)
    }
}
