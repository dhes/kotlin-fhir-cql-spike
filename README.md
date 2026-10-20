# kotlin-fhir-cql-spike

A spike: can cqframework/clinical_quality_language 5.4.0 evaluate WHO CQL over
ohs-foundation/kotlin-fhir R4 objects, with no HAPI, on the JVM and on a web target?

The pieces are in `src/commonMain`:

- `KotlinFhirToCql`: converts kotlin-fhir resources to the engine's values, following the FHIR 4.0.1 model info.
  Its per-type element table, `generated/R4Structures.kt`, is written by `tools/generate_elements.py`.
- `KotlinFhirModelResolver`, `InMemoryRetrieveProvider`, `InMemoryTerminologyProvider`: implementations of the
  engine's `ModelResolver`, `RetrieveProvider` and `TerminologyProvider`.
- `FhirCqlEvaluator`: compiles CQL from source and evaluates it in Patient context.

`src/jvmTest` checks each result against cqframework's HAPI-based `R4FhirModelResolver`, and records the HAPI
results in `fixtures/expected/`. `src/commonTest` checks the same scenarios against those files and against WHO's
expectations on every target.

Both runs in `src/jvmTest` use the same `FhirCqlEvaluator`, `InMemoryRetrieveProvider` and
`InMemoryTerminologyProvider`; only the converter and the model resolver differ. Agreement with HAPI therefore checks
`KotlinFhirToCql` and `KotlinFhirModelResolver`. The retrieve and terminology providers are checked only by WHO's
expectations: the guidance text, the predicted `$apply` resource count and, for Conditions, the twins.

Run: `./gradlew allTests` (JVM, and JS and Wasm under Node)

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
- **Coverage.** WHO's test suite has Immunization, Observation and MedicationRequest data but no Conditions; the
  twins cover Conditions. Date-range filtering in retrieve is not implemented and was not needed.

## Third-party content

- `third_party/cqframework-quick-5.4.0/`: `fhir-modelinfo-4.0.1.xml` and `FHIRHelpers-4.0.1.cql`, copied from
  `org.cqframework:quick:5.4.0` (Apache License 2.0).
- `third_party/smart-immunizations-0.2.0/cql/` and `valuesets/`: the CQL of all 279 Library resources and all 192
  ValueSet resources of the WHO SMART immunizations package `smart.who.int.immunizations` 0.2.0 (package licence
  CC0-1.0). The CQL is the same as in WorldHealthOrganization/smart-immunizations at 12ec2fc54, line endings aside.
- `fixtures/who-measles-tests/` and `fixtures/who-suite/`: test bundles, `.feature` file and scenario list from
  WorldHealthOrganization/smart-immunizations at 12ec2fc54, `input/testing/features/plandefinition/` (CC0-1.0).
  `fixtures/who-suite/` is written by `tools/build_who_suite.py`, which also writes the Condition twins.
- `src/jvmTest/kotlin/dev/ohs/fhir/workflow/`: `FhirJson.kt`, `processor/DynamicValueApplier.kt` and
  `processor/EvaluatedValueJson.kt`, copied unchanged from ohs-foundation/kotlin-fhir-workflow at 214c56d
  (Apache License 2.0), for step 7.
