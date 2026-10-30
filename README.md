# kotlin-fhir-cql-spike

A spike: can cqframework/clinical_quality_language 5.4.0 evaluate WHO CQL over
ohs-foundation/kotlin-fhir R4 objects, with no HAPI, on the JVM and on a web target?

Background: ohs-foundation/kotlin-fhir-workflow's
[`docs/expression-support.md`](https://github.com/ohs-foundation/kotlin-fhir-workflow/blob/214c56d/docs/expression-support.md)
(Elly Kitoto, #1) routes expressions through an `ExpressionEvaluator` seam, so that CQL can be added by plugging in
an ELM evaluator, and defers CQL because the reference stack is JVM/HAPI-only. This spike tests the FHIR side of that
route over kotlin-fhir.

## Answer

Yes, on WHO's immunization test suite (`smart.who.int.immunizations` 0.2.0, 1,098 scenarios), with the conditions
below.

- **JVM:** for each of the 343,922 definition values (every definition in each scenario's library closure: its
  logic library and every library it includes, directly or indirectly), the spike gives the same value as the run
  with cqframework's HAPI-based `R4FhirModelResolver`, compared element by element, resources included. The only
  differences change no CQL value: HAPI adds an empty `meta` that isn't in the data, and names one backbone element
  by its Java class. The guidance text matches WHO's in all 634 scenarios that state one.
- **JS and Wasm (under Node):** the same outcomes, except 6 scenarios that reach CQL's `Split`, which cqframework 5.4.0
  does not implement there (cqframework/clinical_quality_language#1870). cqframework/clinical_quality_language#1876,
  merged 2026-10-28 and not yet released, implements it: with cqframework `main` at 2b5c7401, all 1,098 scenarios give
  the JVM's outcomes on JS and Wasm too (Status, 2026-10-28). Outside WHO's content, cqframework's decimal
  multiplication, division, `mod`, `div` and equivalence give wrong values or fail there (step 14, Findings).
- **What it takes:** a generated element table in place of reflection (`generated/R4Structures.kt`); a hand-written
  table of the element that links each resource type to its patient (seven types); on JS and Wasm, a UCUM service,
  which the spike supplies over the `@lhncbc/ucum-lhc` npm package (step 14; see also
  cqframework/clinical_quality_language#1869); and a 6 GB Gradle heap for the JS build.
- **`$apply` through kotlin-fhir-workflow** (step 15, JVM and Wasm; kotlin-fhir-workflow has no JS target): each
  scenario's PlanDefinition, run by kotlin-fhir-workflow's own `FhirOperator.generateCarePlan` with the spike's CQL
  evaluator behind its `ExpressionEvaluator`, gives step 4's resource count and guidance in every scenario that
  runs. That takes a local build of kotlin-fhir-workflow `main` on kotlin-fhir rc04, and a rewrite of WHO's expression
  languages and three dynamicValue paths that `main` doesn't take yet (ohs-foundation/kotlin-fhir-workflow#9).
- **WHO's package from kotlin-fhir-knowledge** (step 16, JVM, JS and Wasm): with the package as published installed
  (JVM) or imported (JS and Wasm) into kotlin-fhir-knowledge, and the suite's Libraries and ValueSets, and
  `$apply`'s PlanDefinitions and ActivityDefinitions, read from it, the outcomes are the same. That takes a local
  build of kotlin-fhir-knowledge `main` on kotlin-fhir rc04 and on kotlin-fhir-engine's Room and SQLite versions.
- **clinical-reasoning's `$apply`** (step 17, JVM): `PlanDefinitionProcessor` (cqf-fhir-cr 4.13.0) over WHO's
  package as published gives the same requests, by type and content, as kotlin-fhir-workflow's in all 1,098
  scenarios, and WHO's guidance in all 636 that state one (the 634 above, plus the 2 whose bundle kotlin-fhir rejects;
  clinical-reasoning reads it with HAPI). Its CarePlans differ in structure (step 17). As a server
  would return them, 49 lose a request, because two get one id.
- **Not tested:** a browser (JS and Wasm ran under Node only, so kotlin-fhir-engine's browser storage wasn't tried
  either), a terminology server, and `$apply` on iOS or Android. WHO's CQL never reaches date-range filtering in retrieve or
  code-system lookups; steps 10 and 11 test them with CQL of the spike's own.

## What a scenario is, and how it is tested

A scenario is one test case from WHO's Karate `.feature` files. Each one names a PlanDefinition, a patient, a test
bundle and a date, and states what `$apply` should return. The spike doesn't run `$apply`. It evaluates the CQL that
`$apply` would use, then checks the results three ways.

### What WHO's test does

Each scenario in `input/testing/features/plandefinition/**/*.feature`:
1. loads a test bundle (a Patient and their Immunizations and Observations) into a FHIR server;
2. POSTs `PlanDefinition/<id>/$apply` for that patient, with a `Today` date;
3. checks the response:
   - the number of resources it contains (`match response.contained == '#[2]'`);
   - often the guidance text in a CommunicationRequest (`payload.contentString = "…"`).

Against a HAPI server with clinical-reasoning, that runs the PlanDefinition's logic library and builds the resources.

*Note: where WHO's expected results come from* (read; smart-immunizations 12ec2fc54, unchanged at `main` bd7fa72).
WHO's `tools/node/processDAK.js` generates the feature files from the DAK spreadsheet; they are not recorded from a
server. The guidance text is the decision table row's guidance. The count is 3 when the row's output says "is due" or
"is contraindicated" and 2 otherwise; for a schedule, 2, or 1 for its last row. WHO's commit messages say the CQL was
run against the feature files and fixed where it failed, but no server output is checked in, and none of WHO's GitHub
workflows runs them (scan). So a match with WHO is a match with the DAK, not with clinical-reasoning's `$apply`; step
17 runs that.

### What the spike takes from each scenario

`tools/build_who_suite.py` reads the feature files at smart-immunizations 12ec2fc54. For each scenario it records the
PlanDefinition, its logic library, the patient, the bundle, Today, the expected guidance and the expected count, in
`fixtures/who-suite/scenarios.json`.
- WHO has 1,021 scenarios over 138 PlanDefinitions.
- The spike adds 79 twins of the contraindication scenarios, with the contraindication recorded as a Condition
  instead of an Observation, because WHO's data has no Conditions.
- That makes 1,100. One WHO bundle is invalid R4, so kotlin-fhir rejects it, and it and its twin are skipped. That
  leaves the **1,098**.

### Processing, per scenario

1. kotlin-fhir parses the bundle, and `KotlinFhirToCql` converts the resources to CQL values.
2. cqframework compiles the logic library and every library it includes, directly or not (its closure).
3. Every definition in every library of the closure is evaluated for the patient, with `Today` set and the evaluation
   time at noon that day. Over the 1,098 scenarios that gives **343,922 values**.

### The three checks

1. **Against HAPI (the main claim).** The same pipeline runs again with HAPI resources and cqframework's HAPI-based
   model resolver. Every one of the 343,922 values must be equal. On the JVM both runs are in the same process and
   are compared element by element; nothing from that comparison is kept. For JS and Wasm, the JVM records each
   scenario's HAPI outcome in `fixtures/expected/who-suite-outcomes.json`. The outcome holds a hash of all the
   scenario's values (each written as text, with a resource as its type and id) but not the values themselves. A
   matching hash means every value matches; a mismatch names the scenario, not the definition. Only step 2's seven
   measles patients have their values recorded as text, in `fixtures/expected/measles-closure-results.json`.
2. **Guidance against WHO.** The logic library's `Guidance` definition must equal WHO's expected text. 634 scenarios
   assert one, and all 634 match. All of them are decision-table scenarios (`IMMZD2DT…` and `IMMZD5DT…`, twins
   included), which state the whole text in the scenario. The 464 schedule scenarios (`IMMZD18S…`) aren't in this
   check. Their libraries have no `Guidance` definition: the text comes from a `… Create` definition, such as
   `"MCV2 Create"`, and ends with a due date. Their feature files give only its start, in the Background
   (`resultContent[22] = "The client is due for MCV2 if the client is at least 15 months."`), which the spike doesn't
   read. Check 1 still compares those definitions with HAPI.
3. **Resource count against WHO.** The spike predicts what `$apply` would return: 1 (the RequestGroup) plus one
   resource per PlanDefinition action whose condition is true. That prediction matches WHO's count in 1,082
   scenarios. The other 16 differ from WHO for HAPI too, so they don't come from kotlin-fhir. clinical-reasoning's
   `$apply` gives the same 16 (step 17).

### Example: `IMMZD2DTMeaslesLowTransmission/Measles25.2`

**WHO's scenario:** "MCV1 was administered and Client's age is less than 15 months: Client is not due for MCV2".
- **Bundle:** Patient Measles25.2, born 2024-10-12, and one completed MMR Immunization (`XM8TF3`) on 2025-10-12.
- **Today:** 2025-11-12, so the patient is 13 months old.
- **Expected:**
  - the guidance "Should not vaccinate client with MCV2 as client's age is less than 15 months. Check for any vaccines
    due…";
  - `contained == '#[2]'`.

**What the spike does:**
- The logic library is `IMMZD2DTMeaslesLowTransmissionLogic`. Its closure is 12 libraries (Measles elements, IMMZ and
  WHO common, concepts, FHIRHelpers…), and evaluating all of them gives 317 values.
- **Check 1:** all 317 equal the HAPI run's values.
- **Check 2:** `Guidance` gives WHO's text exactly.
- **Check 3:** the PlanDefinition has three actions:

  | Action condition | Value | Resource it adds |
  |---|---|---|
  | `"Client is due for MCV1"` | false (already given) | — |
  | `"Client is due for MCV2"` | false (under 15 months) | — |
  | `"Has Guidance"` | true | a CommunicationRequest |

  The prediction is 1 + 1 = 2, which equals WHO's `#[2]`: the RequestGroup and the CommunicationRequest carrying the
  guidance.

### What this doesn't show

- `$apply` itself isn't run in these checks. The guidance check compares the CQL output, not a CommunicationRequest
  that `$apply` built. Step 15 runs `$apply` through kotlin-fhir-workflow and gets the same counts and guidance.
- The count check rests on the spike's model of what `$apply` returns.
- "Yes" therefore means that the CQL inputs `$apply` needs come out the same as on the HAPI stack, and agree with
  WHO's expectations. It doesn't mean that `$apply` output was produced over kotlin-fhir.

## Follow-on questions

- **Can WHO's CQL, inline dynamicValues included, be compiled to ELM ahead of time?** Yes, once a cqframework
  defect is fixed (cqframework/clinical_quality_language#1873, fix in PR #1874): the libraries on the JVM, JS and
  Wasm, read as ELM from Library resources in a `WorkflowRepository`; the inline dynamicValues on the JVM.
  [Steps](https://github.com/dhes/kotlin-fhir-cql-spike/blob/main/README.md#status) 5, 6 and 12.
- **Can the results be written back with kotlin-fhir-workflow's own code?** Not as it stands: on the JVM, for 4 of
  WHO's 8 dynamicValue paths, the resources it writes don't decode (ohs-foundation/kotlin-fhir-workflow#9). [Step](https://github.com/dhes/kotlin-fhir-cql-spike/blob/main/README.md#status) 7.
- **Can the libraries, value sets and patient data come from kotlin-fhir-workflow's `WorkflowRepository`, which is
  read with `suspend` calls?** Yes, by reading what the libraries need before evaluating: the same outcomes on the
  JVM, JS and Wasm, with an in-memory repository. With kotlin-fhir-engine behind it, the same values up to list
  order on the JVM, and on JS and Wasm under Node, once two kotlin-fhir-engine behaviours are allowed for (see
  Findings); on JS and Wasm that needs a copy of kotlin-fhir-engine's SQLite worker, and under Node a stand-in for
  the browser's Web Worker. [Steps](https://github.com/dhes/kotlin-fhir-cql-spike/blob/main/README.md#status) 8, 9
  and 13.
- **Does the retrieve provider cover all of cqframework's `RetrieveProvider` interface?** Yes: date range and profile
  as well, on the JVM, JS and Wasm, tested with CQL of the spike's own. A date range reaches a retrieve only through a
  compiler option that can drop a test's precision or choice type; in the spike's tests, 4 of the 10 retrieves given
  a date range can't match their where clause for that reason (see Findings). [Step](https://github.com/dhes/kotlin-fhir-cql-spike/blob/main/README.md#status) 10.
- **Does the terminology provider give the same answers as clinical-reasoning's?** Yes, on WHO's whole suite (all
  343,922 values, JVM) and on CQL of the spike's own, except where the spike checks a code against a CodeSystem
  resource it holds, which clinical-reasoning's provider doesn't. [Step](https://github.com/dhes/kotlin-fhir-cql-spike/blob/main/README.md#status) 11.

## Code

The pieces are in `src/commonMain`:

- `KotlinFhirToCql`: converts kotlin-fhir resources to the engine's values, following the FHIR 4.0.1 model info.
  Its per-type element table, `generated/R4Structures.kt`, is written by `tools/generate_elements.py`.
- `KotlinFhirModelResolver`, `InMemoryRetrieveProvider`, `InMemoryTerminologyProvider`: implementations of the
  engine's `ModelResolver`, `RetrieveProvider` and `TerminologyProvider`. `InMemoryRetrieveProvider` filters by
  context, codes or value set, date range, and (when asked) declared profile. `InMemoryTerminologyProvider` answers
  from ValueSet resources by url and version, and from CodeSystem resources when it holds them.
- `FhirCqlEvaluator`: loads libraries from a `LibrarySourceProvider`, as ELM or compiled from CQL with the compiler
  options given, and evaluates them in Patient context.
- `WorkflowRepositoryLibrarySourceProvider` (step 12): cqframework's `LibrarySourceProvider` over Library resources
  read from kotlin-fhir-workflow's `WorkflowRepository` (`dev/ohs/fhir/workflow/WorkflowRepository.kt`, copied),
  serving their CQL, ELM JSON or ELM XML.
- `WorkflowRepositoryCqlEvaluator` (step 8): reads the libraries (through `WorkflowRepositoryLibrarySourceProvider`),
  value sets and patient data a library needs from the `WorkflowRepository`, then evaluates with `FhirCqlEvaluator`.

In `src/workflowMain`, compiled into the JVM and Wasm targets only (step 15):

- `CqlExpressionEvaluator`: kotlin-fhir-workflow's `ExpressionEvaluator` over `FhirCqlEvaluator`, for one patient and
  one PlanDefinition's library. A text that names a definition of the library gives its value; any other text is
  evaluated as a CQL expression, in a library `InlineCqlLibraries` writes for it.

In `src/webMain`, for JS and Wasm only:

- `UcumLhcService` (step 14): cqframework's `UcumService` over the `@lhncbc/ucum-lhc` npm package.

`src/jvmTest` checks each result against cqframework's HAPI-based `R4FhirModelResolver`, and records the HAPI
results in `fixtures/expected/`. `src/commonTest` checks the same scenarios against those files and against WHO's
expectations on every target.

Both runs in `src/jvmTest` use the same `FhirCqlEvaluator`, `InMemoryRetrieveProvider` and
`InMemoryTerminologyProvider`; only the converter and the model resolver differ. Agreement with HAPI therefore checks
`KotlinFhirToCql` and `KotlinFhirModelResolver`. The retrieve and terminology providers are checked by WHO's
expectations: the guidance text, the predicted `$apply` resource count and, for Conditions, the twins. The
retrieve's date-range and profile filtering, which WHO's CQL doesn't reach, is checked by step 10, and the
terminology provider against clinical-reasoning's by step 11.

kotlin-fhir-engine's tests (steps 9 and 13) are in `src/commonTest`; on JS and Wasm they run its SQLite worker under
Node through `src/webTest` (see step 13). So do step 16's, where `KnowledgeWorkflowRepository` reads WHO's Library and
ValueSet resources from kotlin-fhir-knowledge.

Run: `./gradlew allTests` (JVM, and JS and Wasm under Node)

Steps 15 and 16 need three local builds in Maven Local (`~/.m2`), each from a separate `git worktree` of the upstream
repository, with its patch from `patches/` applied:

- ohs-foundation/kotlin-fhirpath at 461a7b9 with `kotlin-fhirpath-461a7b9-local.patch` (signing off):
  `./gradlew :fhir-path-core:publishToMavenLocal :fhir-path-r4:publishToMavenLocal -PmavenVersion=1.0.0-spike-461a7b9`
  (an Android SDK must be set in `local.properties`).
- ohs-foundation/kotlin-fhir-workflow at 214c56d with `kotlin-fhir-workflow-214c56d-rc04.patch`:
  `./gradlew :workflow:publishKotlinMultiplatformPublicationToMavenLocal :workflow:publishDesktopPublicationToMavenLocal
  :workflow:publishWasmJsPublicationToMavenLocal -PmavenVersion=2.0.0-spike-214c56d-rc04`. The common metadata
  publication fails (see step 15); the JVM and Wasm ones are what the spike uses.
- ohs-foundation/kotlin-fhir-knowledge at 45b6960 with `kotlin-fhir-knowledge-45b6960-rc04.patch` (step 16; the patch
  sets the version): `./gradlew :knowledge:publishKotlinMultiplatformPublicationToMavenLocal
  :knowledge:publishDesktopPublicationToMavenLocal :knowledge:publishJsPublicationToMavenLocal
  :knowledge:publishWasmJsPublicationToMavenLocal` (an Android SDK must be set in `local.properties`).

The JVM tests of steps 5–7 also read WHO's package `smart.who.int.immunizations` 0.2.0, unpacked, from the directory
named by `WHO_PACKAGE_DIR`; they are skipped when it is not set.

## Status

- Step 1 (2026-10-15): `IMMZElements."Doses Administered to Patient"` agrees with the HAPI result on the JVM.
- Step 2 (2026-10-15): every definition in the 11-library closure of `IMMZD2DTMeaslesOngoingTransmissionLogic`
  (317 per patient), for WHO's 7 example patients of that decision table, agrees with the HAPI result on the JVM
  (2,219 results, 0 differences). For all 7, the logic library's `Guidance` equals the text WHO's test suite expects,
  and its `Test Validation` is true. Evaluated as of 2025-11-12, the date WHO's test suite uses.
- Step 3 (2026-10-15): the same 2,219 results, guidance and `Test Validation` on JS and on wasmJs, both under Node,
  with a stand-in UCUM service (see Findings). First patient, including compiling the 12 libraries: about 1.5 s on
  JS, 0.7 s on wasmJs, 0.2–0.6 s on a warm JVM; later patients 10–120 ms.
- Step 4 (2026-10-16): WHO's whole immunization test suite, 1,021 scenarios over 138 PlanDefinitions (decision
  tables, schedules and contraindications), plus 79 twins of the contraindication scenarios that record the
  contraindication as a Condition instead of an Observation. For each scenario, every definition in the closure of
  its logic library is evaluated (343,922 results on the JVM).
  - JVM: all 343,922 agree with the HAPI result. Of the 1,098 scenarios kotlin-fhir can parse (see Findings), the
    guidance matches WHO's expected text in all 634 that assert one, and the resource count `$apply` would return
    matches WHO's in 1,082. HAPI gives the same outcomes, so the 16 count differences do not come from kotlin-fhir.
    All 78 parseable Condition twins give the same outcome as their Observation originals.
  - JVM, element by element (2026-10-21): the comparison above writes a resource in a result as its type and id,
    and leaves out extensions. `whoSuiteAgreesWithHapiElementByElement` compares every element instead, resources
    and extensions included; 15,770 of the 343,922 results hold something the short form leaves out. All agree, apart
    from two differences that change no CQL value: where WHO's data has no `meta`, HAPI gives an empty `Meta` and
    kotlin-fhir gives null (20,910 places), and HAPI names `Immunization.protocolApplied` by its Java class,
    `ImmunizationProtocolAppliedComponent`, where the spike uses the model info's `Immunization.ProtocolApplied`
    (10,406 places; the elements inside agree). The test fails on any other difference.
  - JS and wasmJs: the same outcomes as the recorded HAPI ones for every scenario, except 6 Typhoid Ty21a scenarios
    that reach CQL's `Split`, which cqframework does not implement on JS or Wasm (see Findings). 1,098 scenarios take
    about 7 s on the JVM, 42 s on wasmJs and 2 min 27 s on JS, compiling included.
  - The resource count is predicted as 1 (the RequestGroup) plus one resource per action whose applicability
    condition is true; every action in this package has exactly one condition, a CQL definition. Counting one
    resource per ActivityDefinition instead matches WHO less often (978 of 1,021 against 1,005).
- Step 5 (2026-10-19, JVM only): precompiled ELM. `PrecompiledElmTest` compiles WHO's 279 libraries and FHIRHelpers
  to ELM JSON with cql-to-elm 5.4.0 (each records `translatorVersion` 5.4.0 and its compiler options), appends it as
  `application/elm+json` content to WHO's Library resources (written to `build/who-libraries-elm-json`), and loads
  them back. 276 of the 280 load from ELM alone. With FHIRHelpers, IMMZCommon and WHOCommon compiled from CQL source,
  the whole suite (1,098 scenarios) gives the recorded HAPI outcomes in every scenario. See Findings for the four.
  With a local cqframework build that fixes the two causes (`main` at fb8055df plus the fix, published to mavenLocal
  as `5.5.0-elm-functions-fb8055df-SNAPSHOT`; run with `-PcqlVersion=<that version>`), all 280 load from ELM alone
  and the whole suite runs from ELM, with no CQL compiled, giving the recorded HAPI outcomes in every scenario.
- Step 6 (2026-10-19, JVM only): precompiled `text/cql-expression` dynamicValues. These hold CQL text in the
  PlanDefinition rather than naming a definition in a Library, so compiling the Libraries doesn't cover them.
  `PrecompiledDynamicValueTest` wraps each of the 959 in its own library, as clinical-reasoning's `LibraryConstructor`
  does at run time (`using FHIR`, `include FHIRHelpers`, an `include` for each library the PlanDefinition names,
  `context Patient`, `define "return":` and the expression), but with a unique name instead of `expression`. It
  compiles the wrappers and WHO's libraries to ELM JSON (about 4 s; 1.7 MB of wrapper ELM, in
  `build/who-dynamic-values-elm-json` with each wrapper's CQL), then evaluates each scenario's dynamicValues twice:
  from ELM alone, with no CQL source available, and from CQL source alone, compiled at run time as clinical-reasoning
  does. Over the 1,098 scenarios (9,066 evaluations each way) the two give the same values, none null, and nothing is
  compiled on the ELM side. Needs the local cqframework build of step 5 (`-PcqlVersion=`), because the wrappers
  include libraries that define functions. Not covered: WHO's tests don't record these values, so they are compared
  between the two routes, not with WHO's expected output; and turning the CQL values into kotlin-fhir fields at
  each dynamicValue's `path`, which `$apply` would do next (step 7).
- Step 7 (2026-10-19, JVM only): dynamicValue write-back. `DynamicValueWriteBackTest` evaluates each action's
  `text/cql-expression` dynamicValues from precompiled ELM (as in step 6, for the first scenario of the PlanDefinition
  whose bundle loads), converts each CQL value to a kotlin-fhir type (String to a Kotlin string, Code to `Coding`,
  Concept to `CodeableConcept`), and writes it into the request resource with kotlin-fhir-workflow's own write-back
  code: `evaluatedValueToJson` and `DynamicValueApplier`, copied unchanged from ohs-foundation/kotlin-fhir-workflow
  `main` at 214c56d (compiled here against kotlin-fhir rc04; that `main` uses rc03). The resource before write-back is built as that `main`'s `PlanDefinitionProcessor` builds it
  for the ActivityDefinition's `kind`. Each result is then decoded as a kotlin-fhir resource. Of WHO's 329 actions
  with such dynamicValues, none decodes as written; `status` and `intent` write correctly, and three paths don't
  (see Findings). With those three rewritten by hand to the path contract stated in `DynamicValueApplier`'s KDoc,
  all 329 decode, e.g. `"category":[{"coding":[{…,"code":"alert"}]}]`, `"priority":"routine"`,
  `"medicationCodeableConcept":{…,"code":"DE1","display":"BCG vaccines"}`.
- Step 8 (2026-10-22): data read from kotlin-fhir-workflow's `WorkflowRepository`. `WorkflowRepositorySuiteTest`
  puts WHO's 279 Library resources and 192 ValueSets in an in-memory `WorkflowRepository`, with one scenario's
  bundle at a time. For each scenario, `WorkflowRepositoryCqlEvaluator` reads, before evaluating:
  - the logic library by url, and every library it includes through the Libraries' `depends-on` entries (the same
    closure as the CQL `include`s in all 279 libraries, FHIRHelpers aside, which is supplied as before);
  - from each library's ELM, the FHIR types it retrieves and the value sets it declares, found with cqframework's
    `BaseElmLibraryVisitor`;
  - the Patient by id, each other retrieved type with a patient link by an R4 search parameter on that link
    (`patient` for Immunization, `subject` for Observation, Condition and MedicationRequest; see step 9 for why not
    `patient` throughout), and the value sets by url.

  It then evaluates over what it read with the same providers as before. Reading first is needed because
  cqframework's CQL engine retrieves synchronously, while `WorkflowRepository` is read with `suspend` calls, which
  can't be waited on inside a retrieve on JS and Wasm. Result: on the JVM, JS and Wasm (under Node), the same outcomes as step 4 in all 1,098 scenarios: no differences
  from the recorded HAPI outcomes on the JVM (343,922 results), and on JS and Wasm the same 6 scenarios reaching
  `Split`. Every scenario reads Patient,
  Immunization, Observation, Condition and MedicationRequest. Medication is retrieved too (in one IMMZCommon
  function) but has no patient link, so it isn't read; a retrieve of it would fail, and no scenario reaches one.
  Three declared value sets aren't in WHO's package (from IPS, WHO core and FHIR core) and none is used, as before.
  The in-memory repository (`InMemoryWorkflowRepository`, in `src/commonTest`) searches only what this needs.
- Step 9 (2026-10-22, JVM only): kotlin-fhir-engine behind the `WorkflowRepository`. `EngineWorkflowRepositorySuiteTest`
  puts the same Libraries and ValueSets in kotlin-fhir-engine 2.0.0-alpha04's in-memory database, wrapped in
  kotlin-fhir-workflow's `EngineWorkflowRepository` (copied from its demo app), loads one scenario's bundle at a time,
  and compares each of the 343,922 values with the value from step 8's in-memory repository holding the same
  resources. Result: no value differs except in list order. In 285, 288 and 295 scenarios over three runs, lists of
  a patient's doses, such as `IMMZD2DTMeaslesElements."Measles Doses Administered to Patient"`, hold the same
  Immunizations in a different order (see Findings). Two findings came out of it:
  - kotlin-fhir-engine does not find Observations, Conditions or MedicationRequests by R4's `patient` search
    parameter. Step 9 first ran with `patient` for every type, and 556 scenarios differed because those resources
    were never read. `EnginePatientSearchTest` records this (see Findings). The spike now searches those three by
    `subject`, which finds the same resources for a reference to a Patient.
  - Its search results come back in no fixed order.

  Step 13 runs the same tests on JS and Wasm.
- Step 10 (2026-10-23): the rest of the `RetrieveProvider` interface: date range and profile (`templateId`).
  - **When the date-range arguments are filled in** (read, cql-to-elm 5.4.0 `Cql2ElmVisitor`): only with the
    compiler option `EnableDateRangeOptimization`. The compiler then takes a `during` or `in` test on an element of
    the retrieved resource out of the query's where clause and puts the element's path and the interval into the
    retrieve, so the retrieve provider must apply the test the where clause would have applied. It never fills in
    the separate low and high date paths. Compiled with that option, none of the 321 retrieves in WHO's libraries
    gets a date range, and every `templateId` is the base type's (ran, `Step10Test`).
  - **What the spike does:** `InMemoryRetrieveProvider` applies the test with cqframework's own `In` (for a FHIR
    dateTime or instant, or a date, which no test reaches) and `IncludedIn` (for a Period, converted as FHIRHelpers 4.0.1's `ToInterval` does,
    or for the interval between the low and high date paths); a null result excludes the resource. For a profile it
    follows clinical-reasoning's `RetrieveSettings.PROFILE_MODE`: by default the profile is ignored; `DECLARED` keeps
    resources whose `meta.profile` names it, and `OPTIONAL` also keeps those that name none.
  - **Test:** `fixtures/retrieve/DateRangeTests.cql` has 11 definitions, each a retrieve with a date test in its
    where clause, over one patient's 24 resources (dates inside and outside the period, on its boundaries, in another
    time zone, at year, month and day precision, missing; Periods with no start or no end; choice elements holding a
    dateTime, a Period, an instant or a string). Each definition is evaluated compiled with and without the option,
    and the results compared (`RetrieveDateRangeTest`). The compiler gives 10 of the 11 a date range; it leaves a
    test on a computed date (`recordedDate + 1 day`) in the where clause. On the JVM, JS and Wasm (under Node), 6 of
    those 10 return the same resources both ways; the other 4 can't, because the compiler drops part of the test
    (see Findings). On the JVM, cqframework's HAPI-based resolver gives the same results as kotlin-fhir for every
    definition, both ways (`Step10Test`).
    The low and high date paths and the profile modes are tested by calling the retrieve directly
    (`InMemoryRetrieveProviderTest`).
- Step 11 (2026-10-23): the rest of the `TerminologyProvider` interface, compared with clinical-reasoning's
  `RepositoryTerminologyProvider` (cqf-fhir-cql 4.13.0, built on cqframework 5.4.0; on the JVM, over the same
  resources read by HAPI 8.12.0 into its in-memory repository).
  - **What changed in `InMemoryTerminologyProvider`:**
    - `in` fails for a code with no system when the value set has codes from more than one system, as the
      interface's KDoc requires; it used to match on the code alone;
    - a value set is found by url and, when the CQL names one, version; an unknown version, or several versions with
      none named, fails;
    - codes with no system or no code are left out and the rest sorted by code, as clinical-reasoning does;
    - a compose that includes other value sets is expanded from the value sets the provider holds (it used to fail);
    - `lookup`, which CQL's `in` a code system uses, answers from a complete CodeSystem resource when the provider
      holds one, filling in the display; otherwise it checks the code's system and version only, as
      clinical-reasoning does for every code system. It used to return null always.
  - **WHO (ran, JVM):** WHO's CQL names no value set version and never tests a code against a code system (scan),
    so the change it can show is in `in` and `expand`. With clinical-reasoning's provider in place of the spike's,
    all 343,922 values of the whole suite are the same (`Step11Test`). Of WHO's 192 value sets, 188 expand to the
    same 1,825 codes, with the same versions, displays and order; the other four differ, and the suite reaches none
    of them (see Findings).
  - **The spike's own CQL** (`fixtures/terminology/TerminologyTests.cql`, 26 definitions, value sets with one system,
    several systems, two versions, and only a compose; a complete and a fragment CodeSystem): the expected values
    hold on the JVM, JS and Wasm (under Node) (`TerminologyTest`). With clinical-reasoning's provider, one value
    differs, as intended: it finds `purple` in a complete code system that doesn't list it. `lookup`'s display is
    tested directly (`InMemoryTerminologyProviderTest`).
- Step 12 (2026-10-23): library loading through cqframework's `LibrarySourceProvider`, and precompiled ELM on JS
  and Wasm.
  - **What changed:** `WorkflowRepositoryLibrarySourceProvider` reads a Library and, through its `depends-on`
    entries, every Library it depends on, from the `WorkflowRepository` before evaluation, as step 8 did; it then
    serves each one's CQL, ELM JSON or ELM XML attachment through `getLibraryContent`, by the Library's `name` and
    `version`. cqframework asks for ELM first and compiles the CQL only when there's no ELM it can use.
    `WorkflowRepositoryCqlEvaluator` and `FhirCqlEvaluator` now load libraries only this way; the spike's own
    lookup of CQL attachments is gone.
  - **When cqframework uses ELM** (read, cql-to-elm 5.4.0 `LibraryManager`): only when the ELM records the running
    compiler's version and exactly the compiler options in use, and, for a library with overloaded functions,
    signatures. WHO's published ELM records no compiler version, so it is never used (also in Dan's cqframework
    hub, `plan-ohs-cql.md`). The test therefore compiles the ELM itself.
  - **Test** (`ElmFromRepositoryTest`, ran on the JVM, JS and Wasm under Node): on each target, WHO's 279 libraries
    and FHIRHelpers are compiled to ELM JSON with the spike's options plus `EnableResultTypes` (2–5 s); the ELM is
    added to each Library resource beside its CQL, and FHIRHelpers is put in the repository as a Library too. WHO's
    whole suite, read through the repository, then gives the recorded HAPI outcomes in all 1,098 scenarios on all
    three targets. It needs 237 libraries. With cqframework 5.4.0, 234 load from ELM, and FHIRHelpers, IMMZCommon
    and WHOCommon are compiled from CQL, as in step 5 (cqframework/clinical_quality_language#1873). With the local
    cqframework build of step 5 that carries the fix (`-PcqlVersion=5.5.0-elm-functions-fb8055df-SNAPSHOT`), all 237
    load from ELM and no CQL is compiled, on all three targets. The suite takes about 8 s on the JVM, 41 s on Wasm
    and 2 min 40 s on JS.
  - The ELM is compiled on the target that loads it; ELM compiled on one target and loaded on another wasn't tried.

- Step 13 (2026-10-23): kotlin-fhir-engine on JS and Wasm, under Node. `EngineWorkflowRepositorySuiteTest` and
  `EnginePatientSearchTest` moved to `src/commonTest` and now run on all three targets.
  - **How kotlin-fhir-engine runs SQLite there** (read, 2.0.0-alpha04): in a Web Worker, through androidx.sqlite's
    `WebWorkerSQLiteDriver`. The worker script, `worker.js`, runs `@sqlite.org/sqlite-wasm` and is in a local npm
    module, `sqlite-wasm-worker`. Its JS and Wasm targets are built for the browser only (`browser()` in its
    `build.gradle.kts`).
  - **What it took under Node** (ran; Node 24.10.0, the version Gradle's Kotlin plugin downloads):
    - **ES modules for the spike's JS build.** kotlin-fhir-engine's JS code uses `import.meta`, so Node loads it as
      an ES module, and it then can't find the rest of the spike's UMD build ("Its dependency 'kotlin-kotlin-stdlib'
      was not found").
    - **A global `require`** for kotlinx-io, which reads files through `eval('require')`, missing in an ES module
      (Kotlin/kotlinx-io#345, open). Without it every JS test fails on its first file read.
      `src/jsTest/node/global-require.mjs` provides it, passed to Node with `--import`. Wasm doesn't need it.
    - **The worker module.** alpha04's published JS and Wasm artifacts don't carry `sqlite-wasm-worker`, so a
      consumer gets no `worker.js`. This is known: kotlin-fhir-engine's README tells JS and Wasm consumers to copy
      the module from its engine-app ("Required workaround for consumers targeting js/wasmJs", from
      ohs-foundation/kotlin-fhir-engine#76). The spike copies it unchanged to `src/webTest/npm/sqlite-wasm-worker/`
      and declares it as an npm dependency of its JS and Wasm tests, as the README says.
    - **A Web Worker for Node**, which has none: kotlin-fhir-engine fails with "Worker is not defined".
      `web-worker.cjs` (in the same directory) gives Node a `Worker` that runs `worker.js` in a Node worker thread,
      through `node-worker.mjs`, which supplies the worker globals `worker.js` uses (`postMessage`, `onmessage`).
      `node-worker.mjs` also turns off `worker.js`'s logging of every request with its SQL and bindings, already
      reported as ohs-foundation/kotlin-fhir-engine#111. The JS and Wasm `emptyTestEngine()` installs the `Worker`
      before starting kotlin-fhir-engine.
  - **Result** (ran): kotlin-fhir-engine's in-memory database (`testMode = true`), as on the JVM; each scenario is
    evaluated through both repositories.

    | | JS | Wasm | JVM (step 9) |
    |---|---|---|---|
    | Scenarios | 1,098 | 1,098 | 1,098 |
    | Values compared | 343,610 | 343,610 | 343,922 |
    | Differences other than list order | 0 | 0 | 0 |
    | Scenarios with a list in a different order | 292, 293 (two runs) | 287, 293 (two runs) | 285–295 over five runs |
    | Time, both repositories | 7 min 15–22 s | 1 min 47–48 s | 22 s |

    JS and Wasm have 312 fewer values because the 6 Typhoid Ty21a scenarios reach `Split`
    (cqframework/clinical_quality_language#1870): in each, 3 libraries fail there instead of giving their definitions,
    18 in all. Both
    repositories give the same error for each. The test now keeps a library's error as a value, compared like any
    other, and fails on any error other than `NotImplementedError`. `EnginePatientSearchTest` gives the JVM's counts
    on JS and Wasm (see Findings). Loading WHO's 279 Libraries and 192 ValueSets into kotlin-fhir-engine takes
    about 3.3 s on JS, 1.8 s on Wasm and 0.3–0.7 s on the JVM.
  - Not tested: a browser, and so kotlin-fhir-engine's browser storage (OPFS, which its README says needs the page
    served with cross-origin isolation headers); under Node only the in-memory database can run.

- Step 14 (2026-10-24): a UCUM service on JS and Wasm, `UcumLhcService` in `src/webMain`, in place of the stand-in
  the tests used until now.
  - **What ucum-lhc offers** (read, 7.1.10, the latest release more than two weeks old): unit validation
    (`validateUnitString`), conversion (`convertUnitTo`) and conversion to base units (`convertToBaseUnits`), all
    computed with JS numbers. It has no multiplication or division of quantities. cqframework's playground uses it
    only to validate units; its UCUM service there converts nothing (read, `playground/cql`, v5.5.0).
  - **What the service does:**
    - `validate` and `convert` call ucum-lhc.
    - `multiply` and `divideBy` take each unit to base units, combine the values and exponents, and give the result
      in base units, sorted, as the JVM's fhir-ucum does: `2 'cm' * 3 'cm'` gives `0.0006 'm2'`, `10 'mg' / 2 'mL'`
      gives `5000 'g.m-3'`.
    - The values are multiplied and divided as integers, because cqframework's `BigDecimal` on JS and Wasm rounds a
      product or quotient to the scale of an operand (see Findings). A quotient is cut off 20 decimal places beyond
      the dividend's; cqframework then rounds it to 8.
  - **The test cases:**
    - 142 cases from cqframework's copy of the CQL test suite (`engine-fhir/src/jvmTest/resources/org/hl7/fhirpath/cql`
      at v5.4.0): every test whose expression has a number followed by a quoted unit, or calls `ConvertQuantity` or
      `CanConvertQuantity`. `tools/extract_quantity_cases.py` writes them to
      `third_party/cqframework-cql-tests-5.4.0/quantity-cases.json`. Only a few reach UCUM arithmetic or conversion.
    - 52 cases of the spike's own (`fixtures/ucum/spike-quantity-cases.json`): conversions, comparisons across units,
      multiplication, division, invalid units, and five plain decimal expressions.
    - `Step14Test` records each case's value on the JVM, with cqframework's `DefaultUcumService`, in
      `fixtures/expected/quantity-results.json`. `QuantityCasesTest` evaluates the same cases on each target and
      compares, each case as its own library.
  - **Result** (ran):

    | Of 194 cases | JS | Wasm |
    |---|---|---|
    | The same as on the JVM | 149 | 147 |
    | The same when rounded to CQL's 8 decimal places | 21 | 23 |
    | An error on both, with a different message | 3 | 3 |
    | Different | 21 | 21 |

    - **Rounded to 8 places:** trailing zeros (`0.0050 'g'` on the JVM, `0.005 'g'` here) and the noise of JS
      numbers (`5.000000000000001 '10*3/uL'`; `154.3235835294143 '[lb_av]'` against the JVM's
      `154.323583529414306506082`).
    - **Errors on both:** the two invalid units, whose messages come from different UCUM libraries, and `Message`.
    - **The 21 differences**, each listed in `QuantityCasesTest` with its reason. 4 come from the UCUM libraries:
      - 3 conversions between `Cel`, `[degF]` and `K`: ucum-lhc converts them (`37 'Cel'` gives
        `98.59999999999997 '[degF]'`), while the JVM's fhir-ucum throws "Not handled yet (special unit with offset
        from 0 at intersect)" and cqframework returns null.
      - `2.5589 '{eskimo kisses}'`: ucum-lhc rejects the space in the annotation, and fhir-ucum accepts it.

      The other 17 are cqframework's on JS and Wasm, and need no UCUM service: 6 from its `BigDecimal` arithmetic
      (Wasm and JS), 10 on JS and 11 on Wasm from equivalence (`~`), and 1 on JS only from `Matches`, explained since (see Findings).
  - **WHO's suite with the new service** (ran): the same outcomes as step 13 on JS and Wasm. WHO's CQL needs no unit
    conversion.
  - **Not done:** the roadmap's check of `divideBy` in cqframework's Wasm export `createUcumService`, which calls the
    caller's `multiply` (read, v5.5.0 and `main`). Calling the export from the spike's Kotlin Wasm tests crashes
    the Kotlin 2.3.20 compiler (`ArrayIndexOutOfBoundsException` in `WasmIrFileMetadata.fromByteArray`), so this
    is still unconfirmed.

- Step 15 (2026-10-24): `$apply` through kotlin-fhir-workflow, on the JVM and Wasm.
  - **What runs:** kotlin-fhir-workflow's own `FhirOperator.generateCarePlan`, from its `main` at 214c56d, on each
    scenario's PlanDefinition and patient. Its `ExpressionEvaluatorRouter` gets the spike's `CqlExpressionEvaluator`
    as its CQL evaluator, in place of the `ElmExpressionEvaluator` stub. The PlanDefinitions and ActivityDefinitions
    are WHO's, copied into `third_party/smart-immunizations-0.2.0/definitions/` by `tools/copy_who_definitions.py`.
    `WhoApplyTest` compares each CarePlan's contained resources and its CommunicationRequest's `payload` with step 4's
    values and with WHO's expectations. JS is out: kotlin-fhir-workflow has no JS target.
  - **What it took:**
    - **A build of kotlin-fhir-workflow on kotlin-fhir rc04.** `main` is built on rc03. Run as it is with the spike's
      rc04, every scenario failed with `NoClassDefFoundError: dev/ohs/fhir/model/r4/PlanDefinition$ActionConditionKind`
      (ran): rc04 moved every ValueSet enum into a `terminologies` package (ohs-foundation/kotlin-fhir#153). The
      source change is mechanical, 25 files (`patches/kotlin-fhir-workflow-214c56d-rc04.patch`), and kotlin-fhir-workflow's
      70 JVM tests pass with it (ran). It also needs a kotlin-fhirpath on rc04: its `main` moved to rc04 on 2026-10-23
      (#146), with no release yet, so that is built locally too. These builds are in Maven Local only (see Code).
    - **WHO's expression languages.** kotlin-fhir-workflow routes `text/fhirpath` and `text/cql` only; WHO's
      conditions are `text/cql-identifier` and its dynamicValues `text/cql-identifier` or `text/cql-expression`. As
      published, a scenario of each PlanDefinition stops with "Unsupported expression language: text/cql-identifier"
      for 137 of the 138; the other scenario's bundle is one kotlin-fhir rejects (ran). `WhoApply.conform` rewrites both languages to `text/cql` before `$apply`.
    - **No library in the expression.** kotlin-fhir-workflow passes only the expression's text to its CQL evaluator,
      so the spike makes one `CqlExpressionEvaluator` per PlanDefinition library and patient. A text that names a
      definition of that library gives its value; any other text is compiled as a CQL expression in a library that
      includes it.
    - **Three dynamicValue paths and `priority`** (ohs-foundation/kotlin-fhir-workflow#9, open): `conform` also writes
      `category[0].coding[0]`, `payload[0].contentString` and `medicationCodeableConcept`, and gives `priority` the
      Code's `code`, as step 7 did by hand.
    - **A FHIR primitive as a value.** WHO's `MedicationRequest.id` dynamicValues are `First(…).id`, a FHIR `id`, not a
      CQL String; the evaluator writes its `value`.
  - **Result** (ran):

    | | JVM | Wasm |
    |---|---|---|
    | Scenarios through `$apply` | 1,098 of 1,100 | 1,092 of 1,100 |
    | Resource count and guidance different from step 4 | 0 | 0 |
    | Resource count matches WHO's | 1,082 | 1,076 |
    | Guidance matches WHO's | 634 of 634 | 631 of 631 |
    | Time | 9 s | 31 s |

    The 2 scenarios missing on both are the bundles kotlin-fhir rejects (see Findings). The other 6 on Wasm reach
    `Split` (cqframework/clinical_quality_language#1870). The 16 count differences from WHO are step 4's, so they don't
    come from `$apply`. Every request kotlin-fhir-workflow wrote back decoded as a kotlin-fhir resource; it decodes
    each one after its dynamicValues.
  - **Along the way:**
    - kotlin-fhir-workflow's common metadata did not compile against the local kotlin-fhirpath build ("Unresolved
      reference 'FhirPathEngine'"): its transformed metadata had fhir-path-r4 but not fhir-path-core. Not
      investigated. The spike uses its JVM and Wasm artifacts directly and compiles `src/workflowMain` and
      `src/workflowTest` into each target.
    - The Kotlin 2.3.20 Wasm compiler crashed twice on incremental builds of the spike, once for its main code and once
      for its tests (`ArrayIndexOutOfBoundsException` in `WasmIrFileMetadata.fromByteArray`), the same crash as in step
      14. A full recompile (`--rerun` on the compile task) succeeded each time. Not investigated.
  - **Not tested:** `$apply` on Android or iOS, kotlin-fhir-workflow's FHIRPath and CQL together in one
    PlanDefinition, and anything but WHO's immunization content.

- Step 16 (2026-10-25): WHO's package served by kotlin-fhir-knowledge, on the JVM, JS and Wasm.
  - **What runs:** ohs-foundation/kotlin-fhir-knowledge `main` at 45b6960 (its first KMP version, merged 2026-10-21;
    no release yet) holds WHO's package `smart.who.int.immunizations` 0.2.0 as published, from its tarball
    (`third_party/smart-immunizations-0.2.0/smart.who.int.immunizations-0.2.0.tgz`, WHO's CI build). `KnowledgeWorkflowRepository`
    answers the `WorkflowRepository`'s canonical lookups (`searchByUri`) with `KnowledgeManager.loadResources`, so
    the Library and ValueSet resources the suite reads come from kotlin-fhir-knowledge, and the patient data from
    `InMemoryWorkflowRepository` as before. `KnowledgeSuiteTest` runs WHO's suite through it, as step 8 does with the
    spike's copies. `KnowledgeApplyTest` (JVM and Wasm) runs step 15's `$apply` with the PlanDefinition and
    ActivityDefinitions resolved through the adapter in kotlin-fhir-knowledge's README, `KnowledgeCanonicalResolver`,
    and compares each outcome with step 15's from the spike's copies.
  - **How the package gets in:**
    - **JVM:** `install()`, from a package server on localhost that serves the tarball and answers 404 for anything
      else. kotlin-fhir-knowledge downloads, unpacks and indexes it, then asks for each of its 10 dependencies
      (`hl7.fhir.r4.core` and the rest), logs a warning with a stack trace for each 404 and for the missing
      `package.json` that follows, and returns normally. `install()` returns nothing, so a caller can't tell from the
      call which packages were installed. None of the dependencies is needed: FHIRHelpers comes from cqframework, as
      in every step.
    - **JS and Wasm:** `import()`, from an okio `FakeFileSystem` holding the package as the build unpacks it
      (`unpackWhoPackage`). `install()` can't be used there: kotlin-fhir-knowledge has no gzip on the web targets, and
      says so in its README. `import()` indexed 712 resources and skipped 3 files (`package.json` and two index
      files), with none failing to decode as kotlin-fhir rc04 resources.
  - **What it took:**
    - **A local build of kotlin-fhir-knowledge on kotlin-fhir rc04 and on kotlin-fhir-engine's Room and SQLite**
      (`patches/kotlin-fhir-knowledge-45b6960-rc04.patch`). `main` is built on rc03 and on Room 3.0.0-alpha06 and
      SQLite 2.7.0-alpha06; the spike's kotlin-fhir-engine alpha04 brings Room 3.0.2 and SQLite 2.7.0, and one
      classpath holds one version. Room 3.0 renamed `@TypeConverter`/`@TypeConverters` to
      `@ColumnTypeConverter`/`@ColumnTypeConverters` (kotlin-fhir-engine already uses the new names); with that
      rename kotlin-fhir-knowledge's 64 desktop tests pass (ran) and its web targets compile.
    - **Its SQLite worker, on JS and Wasm.** kotlin-fhir-knowledge, like kotlin-fhir-engine, runs SQLite in a Web
      Worker from a local npm module, `sqlite-wasm-worker`. Its published web artifacts don't declare that module
      (read: the `package.json` in the wasm-js klib lists `ws` and `@js-joda/core` only), so a consumer gets no
      `worker.js`, as with kotlin-fhir-engine (ohs-foundation/kotlin-fhir-engine#76). The spike's Node `Worker` (step
      13) runs kotlin-fhir-engine's copy of `worker.js` whatever URL it is given, so kotlin-fhir-knowledge ran on that
      copy, not its own. The two differ in one place (read): kotlin-fhir-knowledge's opens every database with
      `OpfsDb`, the Origin Private File System, including the `:memory:` database Room asks for with
      `inMemory = true`; kotlin-fhir-engine's opens `:memory:` in memory. What kotlin-fhir-knowledge's own worker does
      with `inMemory = true` in a browser was not run.
  - **Result** (ran):

    | | JVM | JS | Wasm |
    |---|---|---|---|
    | How the package got in | `install()` | `import()` | `import()` |
    | Suite: scenarios that give the recorded HAPI outcomes | 1,098 of 1,098 | 1,092 of 1,098 | 1,092 of 1,098 |
    | Suite: time, from kotlin-fhir-knowledge / from the spike's copies (step 8) | 13 s / 8 s | 4 min 1 s / 3 min 37 s | 62 s / 43 s |
    | `$apply`: scenarios that differ from step 15 | 0 (1,098 applied) | — | 0 (1,092 applied) |

    The 6 scenarios on JS and Wasm reach `Split` (cqframework/clinical_quality_language#1870), as in every step. The
    suite made 1,518 Library and 38,430 ValueSet lookups; the 3,294 that found nothing are three value sets WHO's CQL
    declares and the package doesn't hold, the same three as with the spike's copies. `$apply` resolved 1,100
    PlanDefinitions and 1,333 ActivityDefinitions on the JVM. The suite is slower from kotlin-fhir-knowledge: each
    `loadResources` call reads and decodes the resource's file again.
  - **Not tested:** a browser, Android and iOS; `install()` from packages.fhir.org (0.2.0 is not there, only 1.0.0)
    and of a package's dependencies; kotlin-fhir-knowledge's own `worker.js`.

- Step 17 (2026-10-27): clinical-reasoning's `$apply`, on the JVM, against WHO's expectations and kotlin-fhir-workflow's
  `$apply` (step 15).
  - **What runs:** `Step17Test`. For each of the 1,100 scenarios, clinical-reasoning's `PlanDefinitionProcessor`
    (cqf-fhir-cr 4.13.0, built on cqframework 5.4.0, as the spike is) applies the scenario's PlanDefinition, with
    the default `CrSettings`. It reads from an in-memory repository (`InMemoryFhirRepository`) holding every resource
    of WHO's package 0.2.0 as published, read by HAPI, and the scenario's bundle. The request is WHO's: subject
    `Patient/<id>`, and a `parameters` Parameters with `Today`. No server and no `WhoApply.conform`: WHO's expression
    languages and dynamicValue paths go in as published. The same test runs step 15's `$apply` on each scenario and
    compares the two CarePlans field by field (`build/step17/counts.tsv` has the counts per scenario).
  - **Now() at noon on Today.** WHO's CQL calls Now() 52 times in 19 libraries (scan), in definitions such as "The
    latest cholera dose was administered less than 14 days ago". clinical-reasoning's `$apply` gives cqframework no
    evaluation time, so Now() is the wall clock (read: `LibraryEngine` 4.13.0 passes `zonedDateTime` null on this
    path, and cqframework 5.4.0's `CqlEngine` then takes the current time). To compare with steps 4 and 15, which
    set Now() to noon on Today, the spike puts a copy of `LibraryEngine.kt` on the test classpath, ahead of the jar,
    with two lines changed to use that time (`src/jvmTest/kotlin/org/opencds/cqf/fhir/cql/`).
  - **Result** (ran):

    | | clinical-reasoning |
    |---|---|
    | Scenarios applied | 1,100 of 1,100 (the 2 bundles kotlin-fhir rejects included) |
    | Guidance matches WHO's | 636 of 636 |
    | Resource count matches WHO's, each resource counted once | 1,084 of 1,100: all but step 4's 16 |
    | Resource count matches WHO's, as a server would return it (JSON) | 1,057 of 1,100 |
    | Same requests as kotlin-fhir-workflow (type, and payload or medication) | 1,098 of 1,098 |
    | Time, clinical-reasoning alone | about 16 s |

  - **Why "as a server would return it" differs** (ran, and read in 4.13.0's `ProcessDefinition` and
    `ApplyProcessor`): for R4, `$apply` keeps the generated requests contained, and some appear in the CarePlan's
    `contained` twice (the same object). When two actions use one ActivityDefinition, both requests get its id, such
    as `IMMZD2DTCR`. clinical-reasoning numbers repeated ids (`IMMZD2DTCR2`, its fix for
    cqframework/clinical-reasoning#779), but it looks for earlier ones only among requests it doesn't contain, so on
    this path it finds none. HAPI writes one contained resource per id, so in JSON the second request is gone. That
    happens in 49 scenarios. In 38 of them the count then differs from WHO's; in 11 it then matches WHO's where the
    content doesn't (11 of step 4's 16). The code is the same in 4.14.0 and on `main` at 6735b150 (2026-10-26).
  - **How the CarePlans differ** (kotlin-fhir-workflow → clinical-reasoning; the number of scenarios of 1,098):

    | Where | Difference | Scenarios |
    |---|---|---|
    | CarePlan | `intent` `plan` → `proposal` | 1,098 |
    | CarePlan, MedicationRequest | `instantiatesCanonical` gains the version (`…|0.2.0`) | 1,098, 259 |
    | CarePlan | `activity` only in clinical-reasoning's when the RequestGroup has no action | 73 |
    | RequestGroup | `subject` and `instantiatesCanonical` only in clinical-reasoning's | 1,098 |
    | RequestGroup actions | `condition`, with its result (`cpg-action-condition-result`), only in clinical-reasoning's | 1,025 |
    | RequestGroup | the requests also in its own `contained`, only in clinical-reasoning's | 1,025 |
    | Requests | `basedOn` (the RequestGroup) only in kotlin-fhir-workflow's; `doNotPerform: false` only in clinical-reasoning's | 1,025 |
    | Contraindication actions (`IMMZD5DT…`) | `type` `update` only in clinical-reasoning's; the action's `resource` reference differs (below) | 67 |

    In the 67, a dynamicValue sets the MedicationRequest's `id` (`First(…).id`, an existing request of the
    patient's). kotlin-fhir-workflow points the action at the new id; clinical-reasoning's action keeps the
    ActivityDefinition's (`#IMMZD5DTMR`), which no contained resource has.
  - **With Now() on the wall clock** (ran 2026-10-27, `nowOnTheWallClock`, which only prints): 14 scenarios change,
    all DTP in pregnancy (`IMMZD2DTDTPPregnancy…`, `IMMZD18SDTPPregnancy…`), each with fewer requests and other
    guidance.
    The count then matches WHO's in 1,074 of 1,100 and the guidance in 629 of 636. The number depends on the day the
    test runs.
  - **With a fix** (ran 2026-10-27): dhes/clinical-reasoning branch `apply-contained-request-ids` (e73adbd5, off `main`
    at 6735b150) checks the ids already contained before it contains a request, and appends a count as #788 does.
    Built to mavenLocal (`4.15.0-apply-contained-request-ids-e73adbd5-SNAPSHOT`) and run with
    `-PcrVersion=<that> -PcqlVersion=5.5.0` (clinical-reasoning `main` is built on cqframework 5.5.0), against a copy
    of that version's `LibraryEngine.kt` with the same two lines changed (not committed; the copy in the repository is
    4.13.0's). No scenario loses a request in JSON, and the count as served matches WHO's in 1,084 of 1,100. Released
    4.14.0 run the same way: 49 and 1,057, as with 4.13.0.
  - **Not tested:** clinical-reasoning behind a HAPI server (as WHO's Karate files run), and its own settings beyond
    the defaults.
- 2026-10-28: cqframework `main` with `Split` on JS and Wasm.
  - **What runs:** the whole suite (`allTests`, JVM, JS and Wasm) against cqframework `main` at 2b5c7401, built to
    mavenLocal as `5.6.0-upstream-main-2b5c7401-SNAPSHOT` and run with `-PcqlVersion=<that>`. It includes
    cqframework/clinical_quality_language#1876 (merged 2026-10-28, closing #1870), which implements `Split` with a
    separator on JS and Wasm; no release has it yet (5.5.0 is from 2026-10-23).
  - **Result** (ran): no scenario reaches an unimplemented operation on any target. On JS and Wasm the suite gives
    343,922 results (343,610 with 5.4.0), 0 different from the recorded HAPI outcomes; guidance matches WHO's in
    634/634 and the resource count in 1,082/1,098, as on the JVM. Step 15's `$apply` on Wasm applies 1,098 scenarios
    (1,092 with 5.4.0), with 0 differences from step 4. The three `Split` cases of `StringOperatorsProbeTest` give
    the JVM's values. Everything else is as with 5.4.0: the quantity cases (step 14), the 49 repeated request ids,
    steps 16 and 17 on the JVM, and the list-order counts (282 to 293, within their run-to-run spread).
  - **Not run with it:** steps 6 and 7 (`PrecompiledDynamicValueTest`, `DynamicValueWriteBackTest`), which need the
    fix in #1874 (open); they fail the same way with 5.4.0.

- 2026-10-29: the iOS simulator, with a cqframework build that has iOS targets.
  - **What runs:** the common tests (`iosSimulatorArm64Test`, with `-Pios=true
    -PcqlVersion=5.6.0-ios-probe-7c018009-SNAPSHOT`) on an iPhone simulator on an Apple-silicon Mac. No cqframework
    release has iOS artifacts; the build is the `ios-probe` branch of dhes/clinical_quality_language (7c018009, from
    `main` at 441524c5), which adds `iosArm64` and `iosSimulatorArm64` to its five KMP modules, published to Maven
    Local. kotlin-fhir-knowledge is step 16's local build, republished with its iOS targets. iOS has no UCUM service, so
    `src/iosTest` supplies the stand-in of steps 1–13 (every unit accepted, conversion only between identical units).
  - **Result** (ran, 43 minutes in all): 18 tests, 17 pass. On iOS, WHO's suite gives what it gives on the JVM:
    `WhoSuiteTest` 1,098 scenarios, 0 different from the recorded HAPI outcomes, none reaching an unimplemented
    operation, guidance matching WHO's in 634/634 and the resource count in 1,082/1,098 (6 minutes).
    `WorkflowRepositorySuiteTest` and `KnowledgeSuiteTest` (kotlin-fhir-knowledge on iOS): 343,922 results, 0
    different. `EngineWorkflowRepositorySuiteTest` (kotlin-fhir-engine on iOS): 0 different other than list order (297
    scenarios; 282 to 293 on JS and Wasm).
  - **The one failure,** `QuantityCasesTest`: 67 of 194 cases differ from the JVM. 58 come from the UCUM stand-in.
    The other 9 are the decimal arithmetic of cqframework/clinical_quality_language#1894 (`mod`, `div`, `*`, `/`, `~`),
    since iOS uses the same `BigDecimal` as JS and Wasm; #1895 would fix those too. Not run: step 15's `$apply`
    (kotlin-fhir-workflow's local build has no iOS publication) and step 17 (JVM only).

## Findings so far

- **UCUM service on JS and Wasm.** On JS and Wasm the caller must supply a UCUM service, by design: cqframework's
  default there throws "No default UCUM service available". cqframework has no multiplatform UCUM implementation (its
  `ucum` module is JVM only), and its playground's service only validates units with the `@lhncbc/ucum-lhc` npm
  package. Until step 14 the tests used a stand-in that accepted every unit and converted only between identical
  units. That was exact for WHO's content: the closure's quantity literals use only calendar units (`months` 160
  times, `weeks` 70, `year` 4). Step 14 replaces it with `UcumLhcService`.
- **cqframework's decimal arithmetic on JS and Wasm** (ran, cqframework 5.4.0, under Node):

  | CQL | JVM | JS and Wasm |
  |---|---|---|
  | `0.02 * 0.03` | `0.0006` | `0.00` |
  | `1.0 / 3.0` | `0.33333333` | `0.3` |
  | `10.0 mod 3.0` | `1.00000000` | `0.10000000` |
  | `10.1 div -3.1` | `-3` | `-3.2` |
  | `1.0 ~ 1.00` | `true` | `NotImplementedError` |

  - **The cause** (read, `shared/.../BigDecimalJs.kt`, unchanged from v5.4.0 to `main` on 2026-10-23): its
    `BigDecimal` parses a string with a `DecimalMode` whose scale is the string's, and a product or quotient keeps
    that mode, so `0.02 * 0.03` is rounded to 2 places. `stripTrailingZeros()` is `TODO()`, and cqframework's
    decimal equivalence reaches it; quantities and ratios compared with `~` fail the same way.
  - **Scope:** WHO's suite gives the same outcomes on JS, Wasm and the JVM, so its CQL does not reach these or is not
    changed by them. Filed as cqframework/clinical_quality_language#1894 (2026-10-27), with the same results on
    cqframework 5.5.0.
  - **A fix**, offered as cqframework/clinical_quality_language#1895 (ran 2026-10-27): dhes/clinical_quality_language
    branch `decimal-js-exact` (75f3c9a0, off
    `main` at bc0c795e) rebuilds `BigDecimalJs` on an unscaled integer and a scale, with `java.math.BigDecimal`'s
    semantics, and compares it with `java.math.BigDecimal` on the JVM over generated values. With it
    (`-PcqlVersion=5.6.0-decimal-js-exact-75f3c9a0-SNAPSHOT`), the 194 quantity cases on Wasm leave 4 differences
    from the JVM, all from the UCUM libraries (offset units, an annotation with a space), against 21 on 5.4.0; WHO's
    suite gives the same outcomes on JS and Wasm as before. `UcumLhcService` now takes the integer quotient with
    `divideAndRemainder`, since an exact `divide` of two whole numbers throws when the quotient doesn't terminate, as
    on the JVM; that works with both versions.
- **`Matches`, `ReplaceMatches`, `SplitOnMatches` and `Combine` see the quoted CQL literal on JS, and
  `ReplaceMatches` and `Combine` on Wasm.** Found in step 14 as one suite case, `Matches('Not all who wander are lost - circa 2017', '.*\\d+')`: `true`
  on the JVM and Wasm, `false` on JS. Explained 2026-10-26 (`MatchesProbeTest`, `RegexOnCqlStringProbeTest`,
  cqframework 5.4.0):
  - **The cause** (read, the same in v5.5.0 and on `main` that day). The three evaluators hand the regex functions
    the CQL String object itself, not its text: `argument.matches(pattern.value.toRegex())`. That object is a
    `CharSequence` by delegation to its text, but its `toString()` gives the CQL literal, quotes included: `'abc'`.
    Kotlin/JVM's regex reads the characters through `CharSequence`. Kotlin/JS's appears to run on `toString()`
    while taking the length from `CharSequence` (inferred from the results, not read in Kotlin's source):
    `Matches('abc', 'abc')` is `false` and `Matches('abc', '\'ab')` is `true` (ran).
    `Regex("abc").matches(...)` on the object gives the same: `false` on JS, `true` on the JVM and Wasm (ran).
    Java's `CharSequence` contract says `toString()` returns the sequence's characters.
  - **Not the compiler:** the pattern compiles to `.*\d+` (5 characters) on all three targets (ran), and Node's
    regex engine matches it (ran).
  - **What differs from the JVM** (ran):

    | Expression | JVM | JS | Wasm |
    |---|---|---|---|
    | `Matches('abc', 'abc')` | true | false | true |
    | `Matches('abc', '\'ab')` | false | true | false |
    | `ReplaceMatches('abc', 'b', 'X')` | `'aXc'` | `'\'aXc\''` | `'\'Xb'` |
    | `ReplaceMatches('abc', '.*', 'X')` | `'XX'` | `'XX'` | `'X'` |
    | `SplitOnMatches('a,b', ',')` | `{'a', 'b'}` | `{'a,', ''}` | `{'a', 'b'}` |

    On Wasm only `ReplaceMatches` is affected; why `'.*'` gives `'X'` there, and whether that is the same cause or a
    difference in Kotlin/Wasm's own regex engine, was not looked into. A fix in cqframework would pass
    `argument.value` (not tried).
  - **The whole string library** (ran, 2026-10-26, `StringOperatorsProbeTest`): the 93 definitions of cqframework's
    `CqlStringOperatorsTest.cql` (v5.4.0), each evaluated alone. Against the JVM, JS differs in 15 and Wasm in 7:
    `Combine` on both (`Combine({'a', 'b', 'c'})` gives `'\'a\'\'\'\'b\'\'\'\'c\''`: `StringBuilder.append` of
    the String object), the three regex operators on JS, `ReplaceMatches` on Wasm, and the three `Split` cases on
    both (`NotImplementedError`, cqframework#1870). Under Kotlin 2.3.20, the spike's; cqframework builds with 2.3.10,
    and its own Wasm run of the new tests passed without the fix, so the Wasm results depend on the Kotlin version
    the application is built with.
  - **A fix** (ran): pass the String's `value` in the four evaluators, on dhes/clinical_quality_language branch
    `string-operators-value` (58cdce9a, off `main` at 1072c579), with a common test per evaluator. With it,
    published to mavenLocal and used by the spike, all 93 definitions give the JVM's value on JS and Wasm except
    the three `Split` cases. cqframework's engine tests pass on the JVM and JS (280 and 85), engine-fhir's on the
    JVM (3,318, 264 skipped as on `main`), and `spotlessCheck`. The new tests fail on JS without the fix.
  - **Scope:** none of WHO's 279 immunization CQL libraries calls the three regex operators or `Combine` (scan), so
    its suite is unaffected. Filed as cqframework/clinical_quality_language#1885 (2026-10-26); the fix is PR #1888.
- **Calendar-only CQL needs a UCUM service since cqframework 5.2.0.** Ran 2026-10-15: `define X: 4 weeks` compiles
  with no UCUM service under cql-to-elm 5.1.0 and fails under 5.2.0 and 5.4.0, on JS, Wasm and the JVM; `4 'mg'`
  fails in all three versions. The change is consistent with cqframework #1819 (first released in 5.2.0), whose
  check for UCUM arbitrary units consults the service for every quantity literal. So the WHO closure, whose only
  quantities are calendar durations, compiled on JS and Wasm with no UCUM service before 5.2.0 and does not now.
  The probe is in Dan's cqframework hub, `probes/2026-10-15-ucum-calendar-units`. Reported as
  cqframework/clinical_quality_language#1869 (2026-10-16).
- **`validateUnits` compiler option is not read** (read, not run). On cqframework `main` at fb8055df, and still at
  1072c579, the CLI and options files set it, but nothing in the compiler reads it. The compiler always validates a
  quantity's unit (`LibraryBuilder.createQuantity` calls `validateUnit`, which asks the UCUM service), so setting
  the option to `false` does not turn validation off.
- **Patient context paths.** The element linking each resource type to its patient is a hand-written table for seven
  types (`KotlinFhirModelResolver.PATIENT_CONTEXT_PATHS`, step 3). The FHIR 4.0.1 model info records a
  `contextRelationship` to Patient for each type, but its `relatedKeyElement` names a search parameter, not an
  element: for Condition it is `patient`, which the same model info lists as a search on
  `subject.where(resolve() is Patient)` (read, cqframework's quick 5.4.0 model info). So the table cannot simply be
  read from the model info. cqframework's HAPI-based `FhirModelResolver` does not use the model info either: it finds
  the reference element that can point to the context type in HAPI's resource definitions (read, `main` at 1072c579).

  *Note for later.* That pattern cannot be copied at runtime in KMP code: the model info gives Condition's `subject`
  only as `FHIR.Reference`, kotlin-fhir's `Condition.subject` is a plain `Reference` (read, local checkout
  d5d68b1c), and JS and Wasm have no reflection. The target types are in FHIR R4's StructureDefinitions, which
  kotlin-fhir's code generator already reads. So when this code moves into a library, the table could be generated
  from them at build time, taking the first reference element that can point to Patient, as HAPI does. Until then a retrieve of a type missing from the
  table fails with "No <type> resources were read for this retrieve" (read, not run).
- **dateTime precision.** kotlin-fhir does not record how many fractional-second digits a dateTime had, so a value
  with `.000` converts at second precision. Not exercised by these patients, whose dates are all date-only.
- **`Split` is not implemented on JS or Wasm.** In cqframework 5.4.0, and on `main` at fb8055df, the engine's
  `stringUtilsSplit` is `TODO()` on both targets, so CQL's `Split` throws `NotImplementedError`; on the JVM it calls
  Apache Commons `StringUtils.split`. WHO's `WHOCommon.references()` and `IMMZCommon`'s medication lookup use
  `Split`. In step 4 it is reached by 6 Typhoid Ty21a scenarios. Reported as
  cqframework/clinical_quality_language#1870 (2026-10-16). Fixed by cqframework/clinical_quality_language#1876
  (merged 2026-10-28, not yet released): with cqframework `main` at 2b5c7401 the 6 scenarios pass on JS and Wasm
  (Status, 2026-10-28).
- **An invalid WHO test bundle.** `tests-Typhoid41.1-bundle.json` has a MedicationRequest without `medication[x]`,
  which R4 requires. kotlin-fhir rejects the bundle; HAPI's parser accepts it. Its scenario and twin are skipped on
  the kotlin-fhir side. Not raised upstream.
- **Contraindications recorded as Conditions** count in WHO's CQL only while `prevalenceInterval()` includes Today,
  so a Condition needs an onset. The twins take the Observation's effective time as onset.
- **Precompiled ELM needs `EnableResultTypes`, and does not load for libraries that define functions.** Ran
  2026-10-19 on the JVM, cqframework 5.4.0 (`PrecompiledElmTest`). cqframework's CQL engine uses precompiled ELM only
  when every definition carries a result type (since cqframework #1830); without `EnableResultTypes` the compiler
  leaves them out, and 3 of 280 libraries load. With it, 276 load. The four that don't are the four that define
  functions:
  - FHIRHelpers, IMMZCommon and WHOCommon have overloaded functions. The check for function signatures looks only at
    definitions whose whole expression is a function call, finds none, and rejects the ELM; the CQL source is then
    compiled instead.
  - IMMZIndicatorElements (one function, no overloads) passes that check, but loading throws a
    `NullPointerException`: the types of a function's operands are not resolved from the ELM. The exception is not
    caught, so this Library fails to load even when its CQL source is also available.
  - Filed as cqframework/clinical_quality_language#1873, with a fix proposed in #1874 (see step 5).
- **WHO's inline dynamicValues are all constants.** Scan, 2026-10-19: the 959 `text/cql-expression` dynamicValues in
  the 138 PlanDefinitions are 32 distinct literals (`'active'`, `'proposal'`, `'draft'`, `Code { … }` for category and
  priority, `Concept { … }` for medication); none refers to a library definition or a `%` parameter. A wrapper still
  includes the PlanDefinition's library, as clinical-reasoning's does, so an expression could refer to one; no WHO
  expression exercises that.
- **WHO's dynamicValue paths and kotlin-fhir-workflow's write-back.** Ran 2026-10-19 (step 7). Three of WHO's five
  `text/cql-expression` paths don't decode after kotlin-fhir-workflow's `DynamicValueApplier` writes them:
  - `CommunicationRequest.category.coding` (229): written as objects, `"category": {"coding": {…}}`, where kotlin-fhir
    expects lists. The applier's KDoc asks for an explicit index on list segments (`category[0].coding[0]`).
  - `MedicationRequest.medication` (100): written as `"medication"`, an unknown key; the applier's KDoc asks for the
    concrete choice name (`medicationCodeableConcept`).
  - `CommunicationRequest.priority` (229): WHO's value is a CQL `Code`, converted to a `Coding` and written as an
    object into a FHIR `code`. The applier's contract doesn't cover this; something has to take the Coding's `code`.
  - `CommunicationRequest.payload.contentString` (229, a `text/cql-identifier` dynamicValue, so not in step 7's
    counts) fails the same way as `category.coding`: `"payload": {…}` where a list is expected.
    `WhoDynamicValuePathsTest` (in `src/jvmTest/kotlin/dev/ohs/fhir/workflow/processor`) writes one value at each of
    WHO's six paths, then at the four failing ones rewritten to the contract (`category[0].coding[0]`,
    `payload[0].contentString`, `medicationCodeableConcept`, `priority` ← the code), which all decode.
  - clinical-reasoning (read at d09f71d1; ran at 4.13.0 in step 17, where its requests match kotlin-fhir-workflow's in
    all 1,098 scenarios) handles all of these through HAPI's model definitions:
    `BaseAdapter.setNestedValue` takes a segment without an index as the first list element, creating it;
    `resolveChoiceProperty` finds a choice element by its name without the type; `getEnumValue` takes a Coding's
    `code` for a code-valued target.

  FHIR R4 defines `ActivityDefinition.dynamicValue.path` as "a FHIRPath resolveable on the specified target type"
  and says indexers are "allowed" (read, hl7.org/fhir/R4 activitydefinition-definitions, 2026-10-19), so WHO's
  paths look valid; they fall outside the narrower contract in the applier's KDoc. Also read on kotlin-fhir-workflow's
  `main` at 214c56d: `Expression.toProtocolExpression` routes only `text/fhirpath` and `text/cql`, so WHO's
  `text/cql-expression` and `text/cql-identifier` dynamicValues would not reach an evaluator there today.

  Raised as ohs-foundation/kotlin-fhir-workflow#9 (2026-10-20); open as of 2026-10-26, acknowledged by the
  maintainer on 2026-10-21. Until it is settled, the spike's `WhoApply.conform` (step 15) rewrites the paths and
  `priority` before `$apply`.
- **kotlin-fhir-engine skips search parameters that use `resolve()`.** Ran 2026-10-22, kotlin-fhir-engine
  2.0.0-alpha04 on the JVM (`EnginePatientSearchTest`), over every resource in WHO's test bundles: a `patient` search
  finds 0 of 195 Observations, 0 of 78 Conditions and 0 of 78 MedicationRequests, while a `subject` search finds all
  of them, and a `patient` search finds all 720 Immunizations. R4 defines `patient` on those three types as
  `subject.where(resolve() is Patient)`, and on Immunization as `Immunization.patient`. The engine's
  `ResourceIndexer` says why, in a comment (read, alpha04 and `main` at bde4b2b): kotlin-fhir-path doesn't implement
  `resolve()`, and an expression it can't evaluate is skipped, with no error, so the parameter is never indexed. A
  minimal case (`EnginePatientSearchReproTest`) shows the same for Encounter, and for `revInclude` by `patient`. By
  a scan of the engine's R4 search parameter file, `patient` is defined through `resolve()` on 41 types (Observation,
  Condition, Encounter, Procedure, MedicationRequest, DiagnosticReport, CarePlan, ServiceRequest, Task and others),
  and 8 more type/parameter pairs use it; only the four types named here were run. The same counts on JS and Wasm
  under Node (step 13). Reported as ohs-foundation/kotlin-fhir-engine#114 (2026-10-22).
- **kotlin-fhir-engine's search order varies.** Ran 2026-10-22 (step 9): for the same data, a search returns a
  patient's Immunizations in an order that differs from the bundle's and from one run to the next. A CQL query with
  no `sort` keeps its source's order, so lists such as "Measles Doses Administered to Patient" come out in that order
  too. FHIR search defines no order without `_sort`, so this is allowed. No WHO value other than a list's order
  changed: no `First`, `Last` or index over an unsorted list gave a different result in this suite. The same on JS
  and Wasm under Node (step 13).

  *Note: where WHO's CQL depends on that order.* Scan, 2026-10-26, of the 70 `First` and `Last` calls in WHO's CQL.
  Most take a list sorted first (by date or value), a list of elements within one resource, or a filter by `id`.
  Two take an unsorted list of retrieved resources:
  - `First("Rotavirus Doses Administered to Patient".getDose('1')).brand()`, and the same for dose `'2'`
    (IMMZD2DTRotavirusElements, IMMZD2DTRotavirusEncounterElements). A patient with two Immunizations recorded as
    the same Rotavirus dose, with different brands, can get either brand.
  - `First( First (Elements."Adverse Events for Immunization" ).seriousness.coding )` (IMMZIND44Logic, an
    indicator, outside the decision tables this suite runs).

  The sorted lists still keep search order among equal keys, such as two draft MedicationRequests with the same
  `authoredOn`. This is a property of WHO's CQL on any FHIR server that defines no order, not of kotlin-fhir-engine.
- **The compiler's date-range option drops part of the test it moves.** Ran 2026-10-23 (step 10), cql-to-elm 5.4.0
  with `EnableDateRangeOptimization`. The compiled retrieve has only the element's path and the interval, and the
  where clause is gone, so two kinds of test can't be reproduced by any retrieve provider:
  - a precision: `C.recordedDate during day of "Measurement Period"` becomes a test at full precision, which drops
    3 of the 5 Conditions the where clause keeps;
  - the type chosen from a choice element: `(O.effective as dateTime) during "Measurement Period"` becomes a test on
    `effective`, which also keeps Observations whose effective time is a Period or an instant in the period; `as
    Period` likewise keeps dateTimes and instants.

  Tests without a precision, on elements that aren't choices, or on a choice whose other types aren't dates, come
  out the same. WHO's CQL is not affected: none of its retrieves gets a date range. Already known in general (read,
  2026-10-23): cqframework/clinical_quality_language#544 (2020) reported that the option removes the where clause and
  asked that it be kept; it was closed in 2021 as addressed by the `DataRequirementsProcessor` (1.5.4), which reports
  date ranges for query planning without removing anything. Choice types were brought into the option in 1.4.9
  (#486). Neither issue mentions precision or the lost choice type. Not raised upstream.
- **clinical-reasoning's retrieve turns a date range into FHIR search parameters** (read at d09f71d1, not run):
  `BaseRetrieveProvider.populateDateSearchParams` searches the element's date search parameter with `ge` the start
  and `le` the end, in every search mode. FHIR's date search compares ranges differently from CQL's `In` and
  `IncludedIn`, so that may not keep the same resources as the where clause; not checked.
- **clinical-reasoning's terminology provider and four of WHO's value sets.** Ran 2026-10-23 (step 11),
  cqf-fhir-cql 4.13.0:
  - `IMMZD2DTVaricella1DoseVS` and `IMMZD2DTVaricella2DosesVS` have an expansion that lists no codes, and no compose.
    clinical-reasoning's provider fails ("Failed to expand ValueSet"); the spike gives no codes.
  - `IMMZ.Z.VS` and `IMMZ.D.DE19` have only a compose that includes other value sets. clinical-reasoning's provider
    gives no codes, with no error; the spike gives the 453 codes of the value sets they include. Its naive expansion
    reads only the concepts listed in a compose (read, `ValueSets.getCodesInCompose` at d09f71d1).
  - WHO's suite reaches none of the four. Not raised upstream.
- **clinical-reasoning's `lookup` doesn't check the code** (read at d09f71d1, and ran): it returns the code whenever
  its system matches, logging "Unvalidated CodeSystem lookup", so CQL's `in` a code system is true for any code in
  that system. Its KDoc says so.
- **clinical-reasoning's R4 `$apply` gives two requests one id** (ran 2026-10-27, step 17, cqf-fhir-cr 4.13.0; read
  in its `ProcessDefinition`). When two actions of a PlanDefinition use one ActivityDefinition, both contained
  requests get the ActivityDefinition's id, and some requests are in the CarePlan's `contained` twice. Written to JSON,
  one of the two is lost: 49 of WHO's 1,100 scenarios. The fix for cqframework/clinical-reasoning#779 numbers
  repeated ids only on the path that doesn't contain requests. Unchanged in 4.14.0 and on `main` at 6735b150. Filed as
  cqframework/clinical-reasoning#1137 (2026-10-27); the fix is PR #1138 (step 17).
- **kotlin-fhir-workflow's `$apply` gives two requests one id too** (ran 2026-10-28, step 15's setup,
  `DuplicateRequestIdsTest` and `DuplicateRequestIdsHapiTest`; read in its `PlanDefinitionProcessor` at 214c56d). A
  request's id is the action's `id`, or else the ActivityDefinition's, so two actions with no `id` that use one
  ActivityDefinition give two contained requests one id, and both RequestGroup actions reference it. 49 of WHO's 1,098
  scenarios, in 8 PlanDefinitions: two `IMMZD2DTCR` CommunicationRequests in five schedule PlanDefinitions
  (`IMMZD18S…`), two `IMMZD2DTMR` MedicationRequests in three decision tables (`IMMZD2DT…`). HAPI reads both from the
  CarePlan's JSON, but keeps one when it writes the CarePlan again: all 49.
- **clinical-reasoning's action reference after a dynamicValue sets `id`** (ran 2026-10-27, step 17): in WHO's 67
  contraindication scenarios that create a MedicationRequest, the RequestGroup action keeps pointing at
  `#IMMZD5DTMR` after the dynamicValue gives the request another id, so the reference resolves to nothing.
  kotlin-fhir-workflow updates it.
- **WHO's CQL reads the wall clock** (scan, and ran 2026-10-27, step 17): 52 calls to Now() in 19 libraries, beside the
  `Today` parameter WHO's tests send. clinical-reasoning's `$apply` gives cqframework no evaluation time, so on a
  server these definitions use the day the test runs. Run on 2026-10-27, 14 scenarios change, and 7 no longer give
  WHO's guidance. Steps 4 to 17 set Now() to noon on Today.
- **Coverage.** WHO's test suite has Immunization, Observation and MedicationRequest data but no Conditions; the
  twins cover Conditions. WHO's CQL never reaches date-range filtering in retrieve, a profile, a value set version or
  a code-system test; steps 10 and 11 cover them with CQL of the spike's own.

## Licence

The spike's own code is under the Apache License 2.0 (`LICENSE`). The third-party content below keeps its own
licence. WHO's licensing is mixed: the package `smart.who.int.immunizations` 0.2.0 declares CC0-1.0 (its
`package.json`), while WorldHealthOrganization/smart-immunizations' `LICENSE.md` puts WHO-authored guideline content
under CC BY-NC-SA 3.0 IGO and software under the 3-clause BSD licence (read 2026-10-29, at 12ec2fc54).

## Third-party content

- `third_party/cqframework-quick-5.4.0/`: `fhir-modelinfo-4.0.1.xml` and `FHIRHelpers-4.0.1.cql`, copied from
  `org.cqframework:quick:5.4.0` (Apache License 2.0).
- `third_party/smart-immunizations-0.2.0/cql/` and `valuesets/`: the CQL of all 279 Library resources and all 192
  ValueSet resources of the WHO SMART immunizations package `smart.who.int.immunizations` 0.2.0 (package licence
  CC0-1.0). The CQL is the same as in WorldHealthOrganization/smart-immunizations at 12ec2fc54, line endings aside.
- `third_party/smart-immunizations-0.2.0/libraries/`: the same package's 279 Library resources with their CQL only,
  written by `tools/copy_who_libraries.py`, which leaves out the ELM XML attachment and other elements and checks
  the CQL against `cql/`.
- `fixtures/who-measles-tests/` and `fixtures/who-suite/`: test bundles, `.feature` file and scenario list from
  WorldHealthOrganization/smart-immunizations at 12ec2fc54, `input/testing/features/plandefinition/`. These files
  are not in the package, so its CC0-1.0 may not cover them; the repo's `LICENSE.md` puts WHO's guideline content
  under CC BY-NC-SA 3.0 IGO and software under the 3-clause BSD licence. `fixtures/who-suite/` is written by
  `tools/build_who_suite.py`, which also writes the Condition twins.
- `src/commonMain/kotlin/dev/ohs/fhir/workflow/WorkflowRepository.kt`: copied unchanged from
  ohs-foundation/kotlin-fhir-workflow at 214c56d (Apache License 2.0), for step 8. The published
  `dev.ohs.fhir:fhir-workflow` 2.0.0-alpha01 has no JS build and depends on kotlin-fhir rc03.
- `src/commonTest/kotlin/dev/ohs/fhir/workflow/demo/data/EngineWorkflowRepository.kt`: copied unchanged from
  ohs-foundation/kotlin-fhir-workflow's demo app (`workflow-demo`) at 214c56d (Apache License 2.0), for step 9.
- `src/webTest/npm/sqlite-wasm-worker/worker.js` and `package.json`: copied unchanged from
  ohs-foundation/kotlin-fhir-engine's `engine-app/src/webMain/npm/sqlite-wasm-worker/` at v2.0.0-alpha04 (Apache
  License 2.0), for step 13. `node-worker.mjs` and `web-worker.cjs` beside them are the spike's own.
- `third_party/cqframework-cql-tests-5.4.0/quantity-cases.json`: test cases extracted from the CQL test suite files
  in cqframework/clinical_quality_language at v5.4.0, `engine-fhir/src/jvmTest/resources/org/hl7/fhirpath/cql/`
  (Apache License 2.0), by `tools/extract_quantity_cases.py`, for step 14.
- `third_party/smart-immunizations-0.2.0/definitions/`: the 138 PlanDefinition and 3 ActivityDefinition resources of
  the same package (CC0-1.0), without their narratives, written by `tools/copy_who_definitions.py`, for step 15.
- `patches/kotlin-fhir-workflow-214c56d-rc04.patch` and `patches/kotlin-fhirpath-461a7b9-local.patch`: changes to
  ohs-foundation/kotlin-fhir-workflow and ohs-foundation/kotlin-fhirpath (Apache License 2.0) for the spike's local
  builds of them (step 15). `patches/kotlin-fhir-knowledge-45b6960-rc04.patch`: the same for
  ohs-foundation/kotlin-fhir-knowledge (step 16).
- `third_party/smart-immunizations-0.2.0/smart.who.int.immunizations-0.2.0.tgz`: the package
  `smart.who.int.immunizations` 0.2.0 as published (CC0-1.0), WHO's CI build at build.fhir.org (its `package.json`
  is dated 2026-07-07), for step 16. Its 279
  Libraries' CQL and its 192 ValueSets are the same as in `cql/` and `valuesets/`.
- `@lhncbc/ucum-lhc` 7.1.10, an npm dependency of the JS and Wasm builds (step 14), is not in the repository. Its
  licence is NLM's, with the UCUM table's terms from Regenstrief Institute and the UCUM Organization (its
  `LICENSE.md`).
- `src/jvmTest/kotlin/org/opencds/cqf/fhir/cql/LibraryEngine.kt`: copied from cqframework/clinical-reasoning at
  v4.13.0, `cqf-fhir-cql/src/main/java/org/opencds/cqf/fhir/cql/LibraryEngine.kt` (Apache License 2.0), with two lines
  changed to set CQL's evaluation time, for step 17.
- `src/jvmTest/kotlin/dev/ohs/fhir/workflow/`: `FhirJson.kt`, `processor/DynamicValueApplier.kt` and
  `processor/EvaluatedValueJson.kt`, copied unchanged from ohs-foundation/kotlin-fhir-workflow at 214c56d
  (Apache License 2.0), for step 7.
