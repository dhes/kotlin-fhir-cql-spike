package io.github.dhes.kotlinfhircql

/** Gives Node a `Worker` that runs kotlin-fhir-engine's SQLite worker in a worker thread. */
@OptIn(ExperimentalWasmJsInterop::class)
fun installNodeWorker(): Unit =
    js("process.getBuiltinModule('node:module').createRequire(process.cwd() + '/')('sqlite-wasm-worker/web-worker.cjs').install()")
