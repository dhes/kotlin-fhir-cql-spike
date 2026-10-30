@file:OptIn(ExperimentalWasmJsInterop::class)

package io.github.dhes.kotlinfhircql

import dev.ohs.fhir.model.r4.Resource
import kotlin.js.Promise
import kotlin.time.TimeSource
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private fun fetchText(url: String): Promise<JsString> =
    js("fetch(url).then(r => { if (!r.ok) throw new Error(r.status + ' ' + url); return r.text(); })")

/** Fetches WHO's files, which the build put next to the page as one JSON object (`fixtures` in build.gradle.kts). */
actual suspend fun preloadFixtures() {
    if (MeaslesFixtures.preloaded != null) return
    val text = fetchText("fixtures.json").await<JsString>().toString()
    MeaslesFixtures.preloaded = MeaslesFixtures.json.decodeFromString<Map<String, String>>(text)
}

// The page's functions (index.html).
private fun onRun(start: () -> Unit): Unit = js("window.whoPage.onRun(start)")
private fun showVersions(cql: String, fhir: String, workflow: String): Unit = js("window.whoPage.versions(cql, fhir, workflow)")
private fun showStatus(text: String): Unit = js("window.whoPage.status(text)")
private fun showCase(json: String): Unit = js("window.whoPage.testCase(JSON.parse(json))")
private fun showDone(passed: Int, total: Int, seconds: Double): Unit = js("window.whoPage.done(passed, total, seconds)")
private fun showError(text: String): Unit = js("window.whoPage.error(text)")

fun main() {
    showVersions(CQL_VERSION, KOTLIN_FHIR_VERSION, WORKFLOW_VERSION)
    onRun { MainScope().launch { runSuite() } }
}

/**
 * Applies each of WHO's test cases' PlanDefinition to its patient (the spike's [WhoApply]) and reports whether the
 * CarePlan passes WHO's test: the number of resources it contains and, where the test gives one, the guidance text.
 */
private suspend fun runSuite() {
    try {
        showStatus("Fetching WHO's test files")
        preloadFixtures()
        // The spike's twins (Condition copies of WHO's test patients) aren't WHO's test cases.
        val cases = WhoSuite.scenarios.filter { it.twinOf == null }
        showStatus("Compiling WHO's CQL and running ${cases.size} test cases")
        delay(1)
        val start = TimeSource.Monotonic.markNow()
        var passed = 0
        for (case in cases) {
            val outcome = WhoApply.apply(case)
            val result =
                when {
                    outcome.error != null -> outcome.error
                    case.expectedContained != null && case.expectedContained != outcome.contained ->
                        "Resource count differs from WHO's"
                    case.expectedGuidance != null && case.expectedGuidance != outcome.guidance -> "Guidance differs from WHO's"
                    else -> null
                }
            if (result == null) passed++
            showCase(
                buildJsonObject {
                    put("id", case.id)
                    put("feature", case.feature)
                    put("scenario", case.scenario)
                    put("planDefinition", case.planDefinition)
                    put("today", case.today)
                    put("pass", result == null)
                    put("result", result ?: "Passes")
                    put("expectedContained", case.expectedContained)
                    put("contained", outcome.contained)
                    put("expectedGuidance", case.expectedGuidance)
                    put("guidance", outcome.guidance)
                    put(
                        "carePlan",
                        outcome.carePlan?.let { JsonPrimitive(MeaslesFixtures.json.encodeToString(Resource.serializer(), it)) }
                            ?: JsonPrimitive(null as String?),
                    )
                }.toString()
            )
            // Lets the page redraw.
            delay(1)
        }
        showDone(passed, cases.size, start.elapsedNow().inWholeMilliseconds / 1000.0)
    } catch (e: Throwable) {
        showError("${e::class.simpleName}: ${e.message}")
    }
}
