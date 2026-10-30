package io.github.dhes.kotlinfhircql

import kotlin.js.Promise
import kotlinx.coroutines.await

@OptIn(ExperimentalWasmJsInterop::class)
private fun inBrowser(): Boolean = js("typeof window !== 'undefined'")

@OptIn(ExperimentalWasmJsInterop::class)
private fun fetchText(url: String): Promise<JsString> =
    js("fetch(url).then(r => { if (!r.ok) throw new Error(r.status + ' ' + url); return r.text(); })")

/**
 * In a browser, fetches the fixtures that `bundleBrowserFixtures` wrote to one JSON file, which Karma serves (see
 * `karma.config.d/fixtures.js`); under Node, files are read from the project directory.
 */
@OptIn(ExperimentalWasmJsInterop::class)
actual suspend fun preloadFixtures() {
    if (!inBrowser() || MeaslesFixtures.preloaded != null) return
    val text = fetchText("/absolute$PROJECT_DIR/build/browser-fixtures/fixtures.json").await<JsString>().toString()
    MeaslesFixtures.preloaded = MeaslesFixtures.json.decodeFromString<Map<String, String>>(text)
}
