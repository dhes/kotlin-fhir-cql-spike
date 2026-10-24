// Runs kotlin-fhir-engine's SQLite worker (worker.js, copied from its engine-app) in a Node worker thread: Node has
// no Web Worker, whose globals `postMessage` and `onmessage` worker.js uses.
import { parentPort } from 'node:worker_threads';

globalThis.postMessage = (message) => parentPort.postMessage(message);
// worker.js assigns `onmessage` without declaring it, which fails in a module unless the global exists.
globalThis.onmessage = null;
// worker.js logs every request, with its SQL and bindings (kotlin-fhir-engine #111).
console.log = () => {};

await import('./worker.js');

// A MessagePort holds messages until a listener is added, so none sent before worker.js loaded is lost.
parentPort.on('message', (data) => globalThis.onmessage({ data }));
