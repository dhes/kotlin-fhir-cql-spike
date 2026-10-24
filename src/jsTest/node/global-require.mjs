// kotlinx-io reads files on Node through `eval('require')`, which an ES module doesn't have (Kotlin/kotlinx-io#345).
import { createRequire } from 'node:module';

globalThis.require = createRequire(import.meta.url);
