library TerminologyTests version '1.0.0'

// Step 11: each definition uses the terminology provider: membership in a value set (in), expansion (expand), or
// membership in a code system (lookup). Value sets and code systems are in terminology.json.

using FHIR version '4.0.1'

include FHIRHelpers version '4.0.1' called FHIRHelpers

codesystem "Colors": 'http://example.org/fhir/CodeSystem/colors' version '1.0.0'
codesystem "Colors, any version": 'http://example.org/fhir/CodeSystem/colors'
codesystem "Fragment": 'http://example.org/fhir/CodeSystem/fragment'
codesystem "Code system not held": 'http://example.org/fhir/CodeSystem/not-held'

valueset "Single system": 'http://example.org/fhir/ValueSet/single-system'
valueset "Multi system": 'http://example.org/fhir/ValueSet/multi-system'
valueset "Versioned, 1": 'http://example.org/fhir/ValueSet/versioned' version '1'
valueset "Versioned, 2": 'http://example.org/fhir/ValueSet/versioned' version '2'
valueset "Versioned, unnamed": 'http://example.org/fhir/ValueSet/versioned'
valueset "Versioned, 3": 'http://example.org/fhir/ValueSet/versioned' version '3'
valueset "Compose only": 'http://example.org/fhir/ValueSet/compose-only'
valueset "Gender": 'http://example.org/fhir/ValueSet/gender'
valueset "Not held": 'http://example.org/fhir/ValueSet/not-held'

code "Red": 'red' from "Colors"

context Patient

// in

define "LOINC A in single system": Code { code: 'A', system: 'http://loinc.org' } in "Single system"
define "SNOMED A in single system": Code { code: 'A', system: 'http://snomed.info/sct' } in "Single system"
define "String A in single system": 'A' in "Single system"
define "String no-system in single system": 'no-system' in "Single system"
define "SNOMED 5678 in multi system": Code { code: '5678', system: 'http://snomed.info/sct' } in "Multi system"
define "String 1234 in multi system": '1234' in "Multi system"
define "v1-only in version 1": Code { code: 'v1-only', system: 'http://loinc.org' } in "Versioned, 1"
define "v2-only in version 1": Code { code: 'v2-only', system: 'http://loinc.org' } in "Versioned, 1"
define "v2-only in version 2": Code { code: 'v2-only', system: 'http://loinc.org' } in "Versioned, 2"
define "both in unnamed version": Code { code: 'both', system: 'http://loinc.org' } in "Versioned, unnamed"
define "both in version 3": Code { code: 'both', system: 'http://loinc.org' } in "Versioned, 3"
define "red in compose only": Code { code: 'red', system: 'http://example.org/fhir/CodeSystem/colors' } in "Compose only"
define "A in a value set not held": Code { code: 'A', system: 'http://loinc.org' } in "Not held"

// in, through a retrieve or a query

define "Conditions in single system": [Condition: "Single system"]
define "Observations in multi system": [Observation: "Multi system"]
define "Female": Patient.gender in "Gender"

// expand

define "Single system expanded": ExpandValueSet("Single system")
define "Compose only expanded": ExpandValueSet("Compose only")

// lookup (in a code system)

define "Red in Colors": "Red" in "Colors"
define "light-green in Colors": Code { code: 'light-green', system: 'http://example.org/fhir/CodeSystem/colors' } in "Colors"
define "purple in Colors": Code { code: 'purple', system: 'http://example.org/fhir/CodeSystem/colors' } in "Colors"
define "red, version 2.0.0, in Colors": Code { code: 'red', system: 'http://example.org/fhir/CodeSystem/colors', version: '2.0.0' } in "Colors"
define "red in Colors, any version": Code { code: 'red', system: 'http://example.org/fhir/CodeSystem/colors' } in "Colors, any version"
define "String red in Colors": 'red' in "Colors"
define "unlisted in Fragment": Code { code: 'unlisted', system: 'http://example.org/fhir/CodeSystem/fragment' } in "Fragment"
define "red in a code system not held": Code { code: 'red', system: 'http://example.org/fhir/CodeSystem/not-held' } in "Code system not held"
