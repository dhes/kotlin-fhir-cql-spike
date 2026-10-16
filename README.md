# kotlin-fhir-cql-spike

A spike: can cqframework/clinical_quality_language 5.4.0 evaluate WHO CQL over
ohs-foundation/kotlin-fhir R4 objects, with no HAPI, on the JVM and on a web target?

The pieces are in `src/commonMain`:

- `KotlinFhirToCql`: converts kotlin-fhir resources to the engine's values, following the FHIR 4.0.1 model info.
  Its per-type element table, `generated/R4Structures.kt`, is written by `tools/generate_elements.py`.
- `KotlinFhirModelResolver`, `InMemoryRetrieveProvider`: implementations of the engine's `ModelResolver` and
  `RetrieveProvider`.
- `FhirCqlEvaluator`: compiles CQL from source and evaluates it in Patient context.

`src/jvmTest` checks each result against cqframework's HAPI-based `R4FhirModelResolver`.

Run: `./gradlew jvmTest`

## Status

- Step 1 (2026-10-15): `IMMZElements."Doses Administered to Patient"` agrees with the HAPI result on the JVM.

## Third-party content

- `third_party/cqframework-quick-5.4.0/`: `fhir-modelinfo-4.0.1.xml` and `FHIRHelpers-4.0.1.cql`, copied from
  `org.cqframework:quick:5.4.0` (Apache License 2.0).
- `third_party/smart-immunizations-0.2.0/cql/`: CQL from the Library resources of the WHO SMART immunizations
  package `smart.who.int.immunizations` 0.2.0 (package licence CC0-1.0).
