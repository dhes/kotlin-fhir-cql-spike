// The part of the Web Worker API that androidx.sqlite's WebWorkerSQLiteDriver uses, for Node, which has none. Whatever
// URL kotlin-fhir-engine gives it, it runs node-worker.mjs, and so kotlin-fhir-engine's worker.js, in a worker thread.
const { Worker: NodeWorker } = require('node:worker_threads');
const path = require('node:path');

class Worker extends EventTarget {
  constructor(url, options) {
    super();
    this.onmessage = null;
    this.onerror = null;
    this.thread = new NodeWorker(path.join(__dirname, 'node-worker.mjs'));
    this.thread.on('message', (data) => {
      const event = new MessageEvent('message', { data });
      this.onmessage?.(event);
      this.dispatchEvent(event);
    });
    this.thread.on('error', (error) => {
      const event = Object.assign(new Event('error'), { error, message: error.message });
      this.onerror?.(event);
      this.dispatchEvent(event);
    });
    // Lets Node exit once the tests are done, without closing the database. After the listeners: adding a 'message'
    // listener refs the thread again.
    this.thread.unref();
  }

  postMessage(message) {
    this.thread.postMessage(message);
  }

  terminate() {
    this.thread.terminate();
  }
}

exports.install = () => {
  globalThis.Worker ??= Worker;
};
