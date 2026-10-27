package io.github.dhes.kotlinfhircql

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.hl7.fhir.r4.model.CarePlan

/**
 * Step 17: WHO's suite through clinical-reasoning's `$apply` (cqf-fhir-cr 4.13.0), against WHO's expected resource
 * count and guidance, and field by field against kotlin-fhir-workflow's `$apply` (step 15).
 */
class Step17Test {
    private val json = MeaslesFixtures.json

    @Test
    fun whoSuiteThroughClinicalReasoningApply() = runTest(timeout = 60.minutes) {
        val recorded: Map<String, WhoOutcome> = json.decodeFromString(MeaslesFixtures.readText(WhoSuite.EXPECTED))
        val errors = mutableMapOf<String, String>()
        val onceDiffersFromWho = mutableSetOf<String>()
        var servedDiffersFromOnce = 0
        var counted = 0
        var countMatchesWho = 0
        var distinctMatchesWho = 0
        var guided = 0
        var guidanceMatchesWho = 0
        val countDiffers = mutableListOf<String>()
        val guidanceDiffers = mutableListOf<String>()
        var compared = 0
        var sameRequests = 0
        val requestsDiffer = mutableListOf<String>()
        val fieldDiffs = sortedMapOf<String, MutableList<String>>()
        val rows = mutableListOf("scenario\tWHO\tCR as served\tCR each resource once\tkotlin-fhir-workflow")
        for (scenario in WhoSuite.scenarios) {
            val cr = ClinicalReasoningApply.apply(scenario, roundTrip = false)
            if (cr.error != null) {
                errors[scenario.id] = cr.error
                continue
            }
            val inMemory = cr.carePlan!!
            val asServed = ClinicalReasoningApply.roundTrip(inMemory)
            val distinct = inMemory.contained.distinctBy { System.identityHashCode(it) }
            if (distinct.size != asServed.contained.size) servedDiffersFromOnce++
            scenario.expectedContained?.let {
                counted++
                if (it == asServed.contained.size) countMatchesWho++
                else countDiffers += "${scenario.id}: CR ${asServed.contained.size} (${distinct.size} before JSON), WHO $it"
                if (it == distinct.size) distinctMatchesWho++ else onceDiffersFromWho += scenario.id
            }
            scenario.expectedGuidance?.let {
                guided++
                val guidance = ClinicalReasoningApply.guidance(asServed)
                if (it == guidance) guidanceMatchesWho++ else guidanceDiffers += "${scenario.id}:\n    CR:  $guidance\n    WHO: $it"
            }

            // Field by field against kotlin-fhir-workflow, on the scenarios it runs (not the 2 bundles kotlin-fhir rejects).
            val kfwPlan = WhoApply.apply(scenario).carePlan
            rows += "${scenario.id}\t${scenario.expectedContained}\t${asServed.contained.size}\t${distinct.size}\t${kfwPlan?.contained?.size ?: ""}"
            if (kfwPlan == null) continue
            compared++
            val ours = json.encodeToJsonElement(dev.ohs.fhir.model.r4.Resource.serializer(), kfwPlan).jsonObject
            val theirs = crJson(inMemory, distinct)
            val ourContained = byType(ours["contained"] as JsonArray)
            val theirContained = byType(theirs.getValue("contained") as JsonArray)
            val ourRequests = ourContained.filterKeys { it != "RequestGroup" }.mapValues { (_, v) -> v.map(::content) }
            val theirRequests = theirContained.filterKeys { it != "RequestGroup" }.mapValues { (_, v) -> v.map(::content) }
            if (ourRequests == theirRequests) sameRequests++ else requestsDiffer += "${scenario.id}:\n    KFW: $ourRequests\n    CR:  $theirRequests"
            val paths = mutableSetOf<String>()
            diff("CarePlan", JsonObject(ours - "contained"), JsonObject(theirs - "contained"), paths)
            for (type in (ourContained.keys + theirContained.keys).sorted()) {
                val a = ourContained[type].orEmpty()
                val b = theirContained[type].orEmpty()
                if (a.size != b.size) paths += "$type: ${a.size} vs ${b.size}"
                for (i in 0 until minOf(a.size, b.size)) diff(type, a[i], b[i], paths)
            }
            paths.forEach { fieldDiffs.getOrPut(it) { mutableListOf() } += scenario.id }
        }
        java.io.File(PROJECT_DIR, "build/step17").apply { mkdirs() }.resolve("counts.tsv").writeText(rows.joinToString("\n") + "\n")
        println("Scenarios applied: ${WhoSuite.scenarios.size - errors.size} of ${WhoSuite.scenarios.size}")
        errors.entries.groupBy({ it.value }, { it.key }).forEach { (error, ids) -> println("  ${ids.size} × $error (e.g. ${ids.first()})") }
        println("Resource count matches WHO: $countMatchesWho/$counted as served (JSON); $distinctMatchesWho/$counted before JSON, each resource once")
        println("Scenarios that lose a request in JSON (two with one id): $servedDiffersFromOnce")
        println("Guidance matches WHO: $guidanceMatchesWho/$guided")
        println("Count differences from WHO, as served: ${countDiffers.size} (per scenario in build/step17/counts.tsv)")
        guidanceDiffers.forEach(::println)
        println("Compared with kotlin-fhir-workflow: $compared; the same requests (type and content): $sameRequests")
        requestsDiffer.take(10).forEach(::println)
        println("Fields that differ (KFW → CR), in how many of the $compared scenarios:")
        fieldDiffs.forEach { (path, ids) -> println("  ${ids.size}\t$path\t(e.g. ${ids.first()})") }
        assertEquals(emptyMap(), errors, "clinical-reasoning errors")
        assertEquals(guided, guidanceMatchesWho, "Scenarios whose guidance differs from WHO's")
        assertEquals(compared, sameRequests, "Scenarios whose requests differ from kotlin-fhir-workflow's")
        val step4 = recorded.filterValues { it.containedMatchesWho == false }.keys
        assertEquals(step4, onceDiffersFromWho, "Count differences from WHO, each resource once, against step 4's")
    }

    /**
     * The same suite with CQL's Now() on the wall clock, as clinical-reasoning's `$apply` evaluates it (it gives the
     * engine no evaluation time). Prints only: the result depends on the day the test runs.
     */
    @Test
    fun nowOnTheWallClock() {
        var differ = 0
        var countMatchesWho = 0
        var guided = 0
        var guidanceMatchesWho = 0
        for (scenario in WhoSuite.scenarios) {
            val pinned = ClinicalReasoningApply.apply(scenario, roundTrip = false)
            val wall = ClinicalReasoningApply.apply(scenario, roundTrip = false, pinNow = false)
            val pinnedCount = pinned.carePlan!!.contained.distinctBy { System.identityHashCode(it) }.size
            val wallCount = wall.carePlan!!.contained.distinctBy { System.identityHashCode(it) }.size
            if (pinnedCount != wallCount || pinned.guidance != wall.guidance) {
                differ++
                println("${scenario.id}: $pinnedCount → $wallCount resources${if (pinned.guidance != wall.guidance) ", guidance differs" else ""}")
            }
            if (scenario.expectedContained == wallCount) countMatchesWho++
            scenario.expectedGuidance?.let { guided++; if (it == wall.guidance) guidanceMatchesWho++ }
        }
        println("On ${java.time.LocalDate.now()}: $differ scenarios differ from Now() at noon on Today; " +
            "count matches WHO in $countMatchesWho/${WhoSuite.scenarios.size}, guidance in $guidanceMatchesWho/$guided")
    }

    /** clinical-reasoning's CarePlan as JSON, with each contained resource once by identity, not deduplicated by id. */
    private fun crJson(carePlan: CarePlan, distinct: List<org.hl7.fhir.r4.model.Resource>): JsonObject {
        val parser = ClinicalReasoningApply.context.newJsonParser()
        // Not copy(): clinical-reasoning's RequestGroup can hold a null action, which copy() can't take.
        val saved = carePlan.contained
        carePlan.contained = mutableListOf()
        val top = json.parseToJsonElement(parser.encodeResourceToString(carePlan)).jsonObject
        carePlan.contained = saved
        val contained = distinct.map { json.parseToJsonElement(parser.encodeResourceToString(it)) }
        return JsonObject(top + ("contained" to JsonArray(contained)))
    }

    /** Contained resources by type, each list ordered by content, so requests pair up whatever their ids. */
    private fun byType(contained: JsonArray): Map<String, List<JsonObject>> =
        contained.map { it.jsonObject }.groupBy { it.getValue("resourceType").jsonPrimitive.content }
            .mapValues { (_, v) -> v.sortedBy { content(it) } }

    /** What a request says: its payload text or medication code. */
    private fun content(resource: JsonObject): String =
        (resource["payload"] ?: resource["medicationCodeableConcept"])?.toString() ?: ""

    /** Collects the paths where [a] (kotlin-fhir-workflow) and [b] (clinical-reasoning) differ, array indices as []. */
    private fun diff(path: String, a: JsonElement?, b: JsonElement?, out: MutableSet<String>) {
        when {
            a == b -> return
            a == null -> out += "$path: only CR"
            b == null -> out += "$path: only KFW"
            a is JsonObject && b is JsonObject -> (a.keys + b.keys).forEach { diff("$path.$it", a[it], b[it], out) }
            a is JsonArray && b is JsonArray ->
                if (a.size != b.size) out += "$path: ${a.size} vs ${b.size} items"
                else a.indices.forEach { diff("$path[]", a[it], b[it], out) }
            a is JsonPrimitive && b is JsonPrimitive -> out += "$path: value"
            else -> out += "$path: shape"
        }
    }
}
