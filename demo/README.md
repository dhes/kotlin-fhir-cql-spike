# A page that runs WHO's immunization test cases

A web page that runs each of WHO's 1,021 test cases for the SMART Guidelines immunizations guide (smart-immunizations
at 12ec2fc54, package 0.2.0) in the browser tab, and shows how many pass. For each case, ohs-foundation/kotlin-fhir-workflow
applies the case's PlanDefinition to its patient (`FhirOperator.generateCarePlan`, its `$apply`), and cqframework's CQL
evaluator evaluates the CQL the PlanDefinition calls over ohs-foundation/kotlin-fhir resources. Everything is Kotlin
compiled to WebAssembly; no server takes part beyond serving the files.

A case passes when the CarePlan contains the number of resources WHO's test expects and, where the test gives one, the
same guidance text: the rule of the conformance test on dhes/kotlin-fhir-workflow (branch `conformance`).

## What it reuses

The page compiles the spike's own code: its main sources, `CqlExpressionEvaluator` (step 15) and the test files that
read and apply WHO's suite (`WhoSuite`, `MeaslesFixtures`, `WhoApply`, `InMemoryWorkflowRepository`), copied into the
build by `build.gradle.kts`. So it applies the PlanDefinitions as `WhoApplyTest` does, including `WhoApply.conform`:
the expression languages `text/cql-identifier` and `text/cql-expression` become `text/cql`, and three dynamicValue
paths are given in `DynamicValueApplier`'s form (ohs-foundation/kotlin-fhir-workflow#9). The page says so.

Versions: cqframework 5.5.0 (`-PcqlVersion` to change), kotlin-fhir 1.0.0-rc04, kotlin-fhir-workflow `main` at 214c56d
built locally (see the spike's step 15).

WHO's files (the scenarios and their bundles, the CQL, value sets and definitions, the model info and FHIRHelpers) go
next to the page as one JSON file, `fixtures.json` (17.5 MB), which it fetches when the run starts.

## Build and open

```sh
./gradlew :demo:wasmJsBrowserDistribution
cd demo/build/dist/wasmJs/productionExecutable && python3 -m http.server 8765
```

Then open http://localhost:8765/. The page needs a server, not a `file://` URL, for the browser to fetch the Wasm and
the JSON. `?run` starts the run when the page loads. `./gradlew :demo:wasmJsBrowserDevelopmentRun` serves a
development build instead.

Hosted at https://dhes.github.io/kotlin-fhir-cql-spike/ (GitHub Pages, from the `gh-pages` branch, which holds only the
built files). To update it, build, then copy `demo/build/dist/wasmJs/productionExecutable/*` into a worktree of
`gh-pages` (keeping its `.nojekyll`), commit and push. GitHub Actions can't build it, since kotlin-fhir-workflow is a
local build.

Clicking a test case shows what WHO expects, what the CarePlan has, and the CarePlan itself.

## Result

Ran 2026-10-30 in headless Firefox 159, production build: 998 of 1,021 pass in 55 seconds. The others: 16 whose
resource count differs from WHO's (HAPI and clinical-reasoning give the same, steps 4 and 17), 6 Typhoid cases whose CQL
calls `Split`, which cqframework 5.5.0 doesn't implement on JS and Wasm (cqframework/clinical_quality_language#1870),
and Typhoid41.1, whose bundle isn't valid R4. See also the spike README, Status, 2026-10-30.

## Gotcha

When only the JavaScript side of the Kotlin code changes (a `js("…")` body), the production build's optimizer step
keeps its old copy of the JavaScript, because the Wasm it optimizes didn't change. Rebuild with
`./gradlew :demo:compileProductionExecutableKotlinWasmJsOptimize --rerun :demo:wasmJsBrowserDistribution`.
