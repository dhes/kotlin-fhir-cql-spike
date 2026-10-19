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

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Sets a value at a FHIR dynamicValue [path] within a resource's JSON tree, creating any missing
 * intermediate objects/arrays.
 *
 * The workflow library cannot set a value at a runtime path string with a reflective mutator —
 * there is no HAPI `TerserUtil` equivalent, and KMP has no reflection on wasm/native — so `$apply`
 * applies dynamicValues by round-tripping the generated resource through JSON.
 *
 * Path contract:
 * - list segments carry an explicit index: `dosageInstruction[0].timing.repeat.frequency`
 * - choice types use the concrete element name: `medicationCodeableConcept` (not `medication`)
 * - a leading resource-type segment is stripped: `MedicationRequest.priority` -> `priority`
 */
internal object DynamicValueApplier {
  fun set(root: JsonObject, path: String, value: JsonElement): JsonObject {
    val segments = parse(path)
    require(segments.isNotEmpty()) { "dynamicValue path resolves to no segments: '$path'" }
    return setIn(root, segments, value) as JsonObject
  }

  private sealed interface Segment

  private data class Field(val name: String) : Segment

  private data class Index(val at: Int) : Segment

  private fun parse(path: String): List<Segment> {
    val parts = path.split(".")
    val segments = mutableListOf<Segment>()
    parts.forEachIndexed { i, part ->
      val bracket = part.indexOf('[')
      val name = if (bracket >= 0) part.take(bracket) else part
      val isResourceTypePrefix =
        i == 0 && parts.size > 1 && name.firstOrNull()?.isUpperCase() == true
      if (name.isNotEmpty() && !isResourceTypePrefix) segments.add(Field(name))
      if (bracket >= 0) {
        segments.add(Index(part.substring(bracket + 1, part.indexOf(']')).toInt()))
      }
    }
    return segments
  }

  private fun setIn(
    current: JsonElement?,
    segments: List<Segment>,
    value: JsonElement,
  ): JsonElement =
    when (val head = segments.first()) {
      is Field -> {
        val obj = (current as? JsonObject)?.toMutableMap() ?: mutableMapOf()
        val tail = segments.drop(1)
        obj[head.name] = if (tail.isEmpty()) value else setIn(obj[head.name], tail, value)
        JsonObject(obj)
      }

      is Index -> {
        val arr = (current as? JsonArray)?.toMutableList() ?: mutableListOf()
        while (arr.size <= head.at) arr.add(JsonObject(emptyMap()))
        val tail = segments.drop(1)
        arr[head.at] = if (tail.isEmpty()) value else setIn(arr[head.at], tail, value)
        JsonArray(arr)
      }
    }
}
