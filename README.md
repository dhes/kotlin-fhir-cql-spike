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
results in `fixtures/expected/measles-closure-results.json`. `src/commonTest` checks the same closure against that
file and against WHO's expected guidance on every target.

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
- **Coverage.** WHO's example patients contain only Patient and Immunization resources, so the Condition,
  Observation and MedicationRequest retrieves run but return nothing. Date-range filtering in retrieve is not
  implemented.

## Third-party content

- `third_party/cqframework-quick-5.4.0/`: `fhir-modelinfo-4.0.1.xml` and `FHIRHelpers-4.0.1.cql`, copied from
  `org.cqframework:quick:5.4.0` (Apache License 2.0).
- `third_party/smart-immunizations-0.2.0/cql/`: CQL from the Library resources of the WHO SMART immunizations
  package `smart.who.int.immunizations` 0.2.0 (package licence CC0-1.0).
- `third_party/smart-immunizations-0.2.0/valuesets/`: the 32 ValueSet resources, from the same package, that the
  closure's CQL declares. Three more it declares are not in the package and are not needed by these patients.
- `fixtures/who-measles-tests/`: the test bundles and `.feature` file for the decision table, from
  WorldHealthOrganization/smart-immunizations at 12ec2fc54, `input/testing/features/plandefinition/IMMZMeasles/`
  (CC0-1.0).
