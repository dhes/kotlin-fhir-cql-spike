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
