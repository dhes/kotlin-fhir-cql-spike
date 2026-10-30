package io.github.dhes.kotlinfhircql

/**
 * Gives Node a `Worker` that runs kotlin-fhir-engine's SQLite worker in a worker thread; a browser has its own `Worker`,
 * and the bundler serves the worker script there.
 */
@OptIn(ExperimentalWasmJsInterop::class)
fun installNodeWorker(): Unit =
    js("typeof window !== 'undefined' ? undefined : process.getBuiltinModule('node:module').createRequire(process.cwd() + '/')('sqlite-wasm-worker/web-worker.cjs').install()")
