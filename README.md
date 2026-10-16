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

- **UCUM service on JS and Wasm.** Since cqframework #1819 (in 5.2.0 and later), the compiler asks the UCUM service
  about every quantity literal, calendar durations such as `4 weeks` included. On JS and Wasm cqframework's default
  service throws "No default UCUM service available", so every library with a quantity literal fails to compile
  unless the caller supplies a service through `createUcumService` or `LibraryManager`'s `lazyUcumService`. The tests
  use `UnvalidatedUcumService` there, which accepts every unit and converts only between identical units. There is no
  multiplatform UCUM implementation in cqframework; its `ucum` module is JVM only.
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
