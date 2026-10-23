library DateRangeTests version '1.0.0'

// Step 10: each definition is a retrieve whose where clause tests a date against "Measurement Period".
// Compiled with EnableDateRangeOptimization, the compiler can move that test into the retrieve.

using FHIR version '4.0.1'

include FHIRHelpers version '4.0.1' called FHIRHelpers

codesystem "LOINC": 'http://loinc.org'

code "Heart rate": '8867-4' from "LOINC"

parameter "Measurement Period" Interval<DateTime>
  default Interval[@2024-01-01T00:00:00.000Z, @2025-01-01T00:00:00.000Z)

context Patient

define "Conditions recorded during":
  [Condition] C where C.recordedDate during "Measurement Period"

define "Conditions recorded in":
  [Condition] C where C.recordedDate in "Measurement Period"

define "Conditions recorded during day of":
  [Condition] C where C.recordedDate during day of "Measurement Period"

define "Conditions recorded during, active":
  [Condition] C
    where C.recordedDate during "Measurement Period"
      and C.clinicalStatus ~ "Active"

define "Encounters during":
  [Encounter] E where E.period during "Measurement Period"

define "Encounters during, finished":
  [Encounter] E where E.period during "Measurement Period" and E.status = 'finished'

define "Observations effective as dateTime during":
  [Observation] O where (O.effective as dateTime) during "Measurement Period"

define "Observations effective as Period during":
  [Observation] O where (O.effective as Period) during "Measurement Period"

define "Heart rates effective as dateTime during":
  [Observation: "Heart rate"] O where (O.effective as dateTime) during "Measurement Period"

define "Immunizations occurring as dateTime during":
  [Immunization] I where (I.occurrence as dateTime) during "Measurement Period"

define "Conditions recorded during, a day later":
  [Condition] C where (C.recordedDate + 1 day) during "Measurement Period"

define "Active":
  Code { code: 'active', system: 'http://terminology.hl7.org/CodeSystem/condition-clinical' }
