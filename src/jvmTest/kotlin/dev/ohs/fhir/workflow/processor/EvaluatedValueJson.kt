/*
 * Copyright 2026 Open Health Stack Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.ohs.fhir.workflow.processor

import dev.ohs.fhir.fhirpath.types.FhirPathDate
import dev.ohs.fhir.fhirpath.types.FhirPathDateTime
import dev.ohs.fhir.fhirpath.types.FhirPathQuantity
import dev.ohs.fhir.fhirpath.types.FhirPathTime
import dev.ohs.fhir.model.r4.Address
import dev.ohs.fhir.model.r4.Annotation
import dev.ohs.fhir.model.r4.Attachment
import dev.ohs.fhir.model.r4.Boolean as FhirBoolean
import dev.ohs.fhir.model.r4.Canonical
import dev.ohs.fhir.model.r4.Code
import dev.ohs.fhir.model.r4.CodeableConcept
import dev.ohs.fhir.model.r4.Coding
import dev.ohs.fhir.model.r4.ContactPoint
import dev.ohs.fhir.model.r4.Date
import dev.ohs.fhir.model.r4.DateTime
import dev.ohs.fhir.model.r4.Decimal
import dev.ohs.fhir.model.r4.HumanName
import dev.ohs.fhir.model.r4.Id
import dev.ohs.fhir.model.r4.Identifier
import dev.ohs.fhir.model.r4.Integer
import dev.ohs.fhir.model.r4.Markdown
import dev.ohs.fhir.model.r4.Oid
import dev.ohs.fhir.model.r4.Period
import dev.ohs.fhir.model.r4.PositiveInt
import dev.ohs.fhir.model.r4.Quantity
import dev.ohs.fhir.model.r4.Reference
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.String as FhirString
import dev.ohs.fhir.model.r4.Time
import dev.ohs.fhir.model.r4.Uri
import dev.ohs.fhir.model.r4.Url
import dev.ohs.fhir.model.r4.Uuid
import dev.ohs.fhir.workflow.fhirJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Converts an evaluated FHIRPath result into a [JsonElement] for dynamicValue write-back.
 *
 * The `when` dispatches per FHIR datatype rather than resolving a serializer generically because
 * KMP has no reflection to do `value::class.serializer()` on wasm/native. The datatype set is
 * finite, so this is bounded, mechanical boilerplate, kept self-contained so the library depends
 * only on `fhir-model` + `fhir-path`. An unsupported result type throws rather than being silently
 * coerced.
 */
internal fun evaluatedValueToJson(value: Any): JsonElement =
  primitiveOrNull(value)
    ?: quantityOrNull(value)
    ?: structuredOrNull(value)
    ?: throw IllegalStateException(
      "Unsupported dynamicValue result type ${value::class.simpleName}"
    )

private fun primitiveOrNull(value: Any): JsonElement? =
  when (value) {
    is String -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Int -> JsonPrimitive(value)
    is Long -> JsonPrimitive(value)
    is Float -> JsonPrimitive(value)
    is Double -> JsonPrimitive(value)
    is FhirPathDate -> JsonPrimitive(value.toString())
    is FhirPathDateTime -> JsonPrimitive(value.toString())
    is FhirPathTime -> JsonPrimitive(value.toString())
    is FhirString -> JsonPrimitive(value.value)
    is FhirBoolean -> JsonPrimitive(value.value)
    is Integer -> JsonPrimitive(value.value)
    is PositiveInt -> JsonPrimitive(value.value)
    is Decimal -> JsonPrimitive(value.value?.toString())
    is Date -> JsonPrimitive(value.value?.toString())
    is DateTime -> JsonPrimitive(value.value?.toString())
    is Time -> JsonPrimitive(value.value?.toString())
    is Uri -> JsonPrimitive(value.value)
    is Url -> JsonPrimitive(value.value)
    is Canonical -> JsonPrimitive(value.value)
    is Code -> JsonPrimitive(value.value)
    is Markdown -> JsonPrimitive(value.value)
    is Id -> JsonPrimitive(value.value)
    is Oid -> JsonPrimitive(value.value)
    is Uuid -> JsonPrimitive(value.value)
    else -> null
  }

private fun quantityOrNull(value: Any): JsonElement? =
  when (value) {
    is FhirPathQuantity ->
      buildJsonObject {
        value.value?.let { put("value", JsonPrimitive(it.toString())) }
        value.unit?.let {
          put("code", JsonPrimitive(it))
          put("unit", JsonPrimitive(it))
        }
      }

    else -> null
  }

private fun structuredOrNull(value: Any): JsonElement? =
  when (value) {
    is Quantity -> fhirJson.encodeToJsonElement(Quantity.serializer(), value)
    is Coding -> fhirJson.encodeToJsonElement(Coding.serializer(), value)
    is CodeableConcept -> fhirJson.encodeToJsonElement(CodeableConcept.serializer(), value)
    is Reference -> fhirJson.encodeToJsonElement(Reference.serializer(), value)
    is Attachment -> fhirJson.encodeToJsonElement(Attachment.serializer(), value)
    is Identifier -> fhirJson.encodeToJsonElement(Identifier.serializer(), value)
    is HumanName -> fhirJson.encodeToJsonElement(HumanName.serializer(), value)
    is Address -> fhirJson.encodeToJsonElement(Address.serializer(), value)
    is ContactPoint -> fhirJson.encodeToJsonElement(ContactPoint.serializer(), value)
    is Period -> fhirJson.encodeToJsonElement(Period.serializer(), value)
    is Annotation -> fhirJson.encodeToJsonElement(Annotation.serializer(), value)
    is Resource -> fhirJson.encodeToJsonElement(Resource.serializer(), value)
    else -> null
  }
