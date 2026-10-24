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
package dev.ohs.fhir.workflow.demo.data

import dev.ohs.fhir.engine.FhirEngine
import dev.ohs.fhir.engine.search.ReferenceClientParam
import dev.ohs.fhir.engine.search.Search
import dev.ohs.fhir.engine.search.UriClientParam
import dev.ohs.fhir.model.r4.Resource
import dev.ohs.fhir.model.r4.terminologies.ResourceType
import dev.ohs.fhir.workflow.WorkflowRepository

/** Adapts the workflow library's [WorkflowRepository] onto `dev.ohs.fhir:fhir-engine`. */
class EngineWorkflowRepository(private val engine: FhirEngine) : WorkflowRepository {
  override suspend fun read(type: String, id: String): Resource? =
    runCatching { engine.get(ResourceType.fromCode(type), id) }.getOrNull()

  override suspend fun create(resource: Resource): String = engine.create(resource).first()

  override suspend fun update(resource: Resource) = engine.update(resource)

  override suspend fun delete(type: String, id: String) =
    engine.delete(ResourceType.fromCode(type), id)

  override suspend fun searchByReferenceParam(
    type: String,
    param: String,
    referenceValue: String,
  ): List<Resource> {
    val search =
      Search(ResourceType.fromCode(type)).apply {
        filter(ReferenceClientParam(param), { value = referenceValue })
      }
    return engine.search<Resource>(search).map { it.resource }
  }

  override suspend fun searchByUri(type: String, param: String, uri: String): List<Resource> {
    val search =
      Search(ResourceType.fromCode(type)).apply { filter(UriClientParam(param), { value = uri }) }
    return engine.search<Resource>(search).map { it.resource }
  }
}
