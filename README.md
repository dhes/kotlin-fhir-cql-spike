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
- **JS and Wasm (under Node):** the same outcomes, except 6 scenarios that reach CQL's `Split`, which cqframework does
  not implement there (cqframework/clinical_quality_language#1870).
- **What it takes:** a generated element table in place of reflection (`generated/R4Structures.kt`); a hand-written
  table of the element that links each resource type to its patient (seven types); on JS and Wasm, a UCUM service
  supplied by the caller (cqframework/clinical_quality_language#1869); and a 6 GB Gradle heap for the JS build.
- **Not tested:** data from kotlin-fhir-engine on JS and Wasm, a terminology server, `$apply` end to end, and
  precompiled ELM on JS and Wasm. WHO's CQL never reaches date-range filtering in retrieve or code-system lookups;
  steps 10 and 11 test them with CQL of the spike's own.

## Follow-on questions

- **Can WHO's CQL, inline dynamicValues included, be compiled to ELM ahead of time?** On the JVM, yes, once a
  cqframework defect is fixed (cqframework/clinical_quality_language#1873, fix in PR #1874). [Steps](https://github.com/dhes/kotlin-fhir-cql-spike/blob/main/README.md#status) 5 and 6.
- **Can the results be written back with kotlin-fhir-workflow's own code?** Not as it stands: on the JVM, for 4 of
  WHO's 8 dynamicValue paths, the resources it writes don't decode (ohs-foundation/kotlin-fhir-workflow#9). [Step](https://github.com/dhes/kotlin-fhir-cql-spike/blob/main/README.md#status) 7.
- **Can the libraries, value sets and patient data come from kotlin-fhir-workflow's `WorkflowRepository`, which is
  read with `suspend` calls?** Yes, by reading what the libraries need before evaluating: the same outcomes on the
  JVM, JS and Wasm, with an in-memory repository. With kotlin-fhir-engine behind it, on the JVM, the same values up to
  list order, once two kotlin-fhir-engine behaviours are allowed for (see Findings). [Steps](https://github.com/dhes/kotlin-fhir-cql-spike/blob/main/README.md#status) 8 and 9.
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
- `FhirCqlEvaluator`: compiles CQL from source, with the compiler options given, and evaluates it in Patient context.
- `WorkflowRepositoryCqlEvaluator` (step 8): reads the libraries, value sets and patient data a library needs from
  kotlin-fhir-workflow's `WorkflowRepository` (`dev/ohs/fhir/workflow/WorkflowRepository.kt`, copied), then
  evaluates with `FhirCqlEvaluator`.

`src/jvmTest` checks each result against cqframework's HAPI-based `R4FhirModelResolver`, and records the HAPI
results in `fixtures/expected/`. `src/commonTest` checks the same scenarios against those files and against WHO's
expectations on every target.

Both runs in `src/jvmTest` use the same `FhirCqlEvaluator`, `InMemoryRetrieveProvider` and
`InMemoryTerminologyProvider`; only the converter and the model resolver differ. Agreement with HAPI therefore checks
`KotlinFhirToCql` and `KotlinFhirModelResolver`. The retrieve and terminology providers are checked by WHO's
expectations: the guidance text, the predicted `$apply` resource count and, for Conditions, the twins. The
retrieve's date-range and profile filtering, which WHO's CQL doesn't reach, is checked by step 10, and the
terminology provider against clinical-reasoning's by step 11.

Run: `./gradlew allTests` (JVM, and JS and Wasm under Node)

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

  Not tested: kotlin-fhir-engine on JS and Wasm, where it runs SQLite in a web worker.
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

## Findings so far

- **UCUM service on JS and Wasm.** On JS and Wasm the caller must supply a UCUM service, by design: cqframework's
  default there throws "No default UCUM service available", and its playground supplies one by wrapping the
  `@lhncbc/ucum-lhc` npm package with `createUcumService`. cqframework has no multiplatform UCUM implementation (its
  `ucum` module is JVM only), so a Kotlin consumer on a web target has nothing to plug in short of wrapping
  `ucum-lhc` through JS interop. The tests use `UnvalidatedUcumService` there, which accepts every unit and converts
  only between identical units. That is exact for this content: the closure's quantity literals use only calendar
  units (`months` 160 times, `weeks` 70, `year` 4).
- **Calendar-only CQL needs a UCUM service since cqframework 5.2.0.** Ran 2026-10-15: `define X: 4 weeks` compiles
  with no UCUM service under cql-to-elm 5.1.0 and fails under 5.2.0 and 5.4.0, on JS, Wasm and the JVM; `4 'mg'`
  fails in all three versions. The change is consistent with cqframework #1819 (first released in 5.2.0), whose
  check for UCUM arbitrary units consults the service for every quantity literal. So the WHO closure, whose only
  quantities are calendar durations, compiled on JS and Wasm with no UCUM service before 5.2.0 and does not now.
  The probe is in Dan's cqframework hub, `probes/2026-10-15-ucum-calendar-units`. Reported as
  cqframework/clinical_quality_language#1869 (2026-10-16).
- **`validateUnits` compiler option is not read** (read, not run). On cqframework `main` at fb8055df the CLI and
  options files set it, but nothing in the compiler reads it; quantity units are validated regardless.
- **Patient context paths.** The element linking each resource type to its patient is a hand-written table for seven
  types (`KotlinFhirModelResolver.PATIENT_CONTEXT_PATHS`); the model info does not record it.
- **dateTime precision.** kotlin-fhir does not record how many fractional-second digits a dateTime had, so a value
  with `.000` converts at second precision. Not exercised by these patients, whose dates are all date-only.
- **`Split` is not implemented on JS or Wasm.** In cqframework 5.4.0, and on `main` at fb8055df, the engine's
  `stringUtilsSplit` is `TODO()` on both targets, so CQL's `Split` throws `NotImplementedError`; on the JVM it calls
  Apache Commons `StringUtils.split`. WHO's `WHOCommon.references()` and `IMMZCommon`'s medication lookup use
  `Split`. In step 4 it is reached by 6 Typhoid Ty21a scenarios. Reported as
  cqframework/clinical_quality_language#1870 (2026-10-16).
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
  - clinical-reasoning (read at d09f71d1, not run) handles all of these through HAPI's model definitions:
    `BaseAdapter.setNestedValue` takes a segment without an index as the first list element, creating it;
    `resolveChoiceProperty` finds a choice element by its name without the type; `getEnumValue` takes a Coding's
    `code` for a code-valued target.

  FHIR R4 defines `ActivityDefinition.dynamicValue.path` as "a FHIRPath resolveable on the specified target type"
  and says indexers are "allowed" (read, hl7.org/fhir/R4 activitydefinition-definitions, 2026-10-19), so WHO's
  paths look valid; they fall outside the narrower contract in the applier's KDoc. How clinical-reasoning handles
  these three was not checked. Also read on that `main`: `Expression.toProtocolExpression` routes only
  `text/fhirpath` and `text/cql`, so WHO's `text/cql-expression` and `text/cql-identifier` dynamicValues would not
  reach an evaluator there today.
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
  and 8 more type/parameter pairs use it; only the four types named here were run. No issue tracks this in
  ohs-foundation/kotlin-fhir-engine or ohs-foundation/kotlin-fhirpath (searched 2026-10-22). Not raised upstream.
- **kotlin-fhir-engine's search order varies.** Ran 2026-10-22 (step 9): for the same data, a search returns a
  patient's Immunizations in an order that differs from the bundle's and from one run to the next. A CQL query with
  no `sort` keeps its source's order, so lists such as "Measles Doses Administered to Patient" come out in that order
  too. FHIR search defines no order without `_sort`, so this is allowed. No WHO value other than a list's order
  changed: no `First`, `Last` or index over an unsorted list gave a different result in this suite.
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
- **Coverage.** WHO's test suite has Immunization, Observation and MedicationRequest data but no Conditions; the
  twins cover Conditions. WHO's CQL never reaches date-range filtering in retrieve, a profile, a value set version or
  a code-system test; steps 10 and 11 cover them with CQL of the spike's own.

## Licence

The spike's own code is under the Apache License 2.0 (`LICENSE`). The third-party content below keeps its own
licence.

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
  WorldHealthOrganization/smart-immunizations at 12ec2fc54, `input/testing/features/plandefinition/` (CC0-1.0).
  `fixtures/who-suite/` is written by `tools/build_who_suite.py`, which also writes the Condition twins.
- `src/commonMain/kotlin/dev/ohs/fhir/workflow/WorkflowRepository.kt`: copied unchanged from
  ohs-foundation/kotlin-fhir-workflow at 214c56d (Apache License 2.0), for step 8. The published
  `dev.ohs.fhir:fhir-workflow` 2.0.0-alpha01 has no JS build and depends on kotlin-fhir rc03.
- `src/jvmTest/kotlin/dev/ohs/fhir/workflow/demo/data/EngineWorkflowRepository.kt`: copied unchanged from
  ohs-foundation/kotlin-fhir-workflow's demo app (`workflow-demo`) at 214c56d (Apache License 2.0), for step 9.
- `src/jvmTest/kotlin/dev/ohs/fhir/workflow/`: `FhirJson.kt`, `processor/DynamicValueApplier.kt` and
  `processor/EvaluatedValueJson.kt`, copied unchanged from ohs-foundation/kotlin-fhir-workflow at 214c56d
  (Apache License 2.0), for step 7.
