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
package dev.ohs.fhir.workflow

import dev.ohs.fhir.model.r4.Resource

/**
 * Persistence abstraction for the workflow library. The consumer supplies an implementation over
 * dev.ohs.fhir:fhir-engine; tests use InMemoryWorkflowRepository. Replaces the cqframework
 * org.opencds.cqf.fhir.api.Repository.
 */
interface WorkflowRepository {
  /** @param type resource type name, e.g. "CommunicationRequest". */
  suspend fun read(type: String, id: String): Resource?

  /** @return the logical id of the created resource. */
  suspend fun create(resource: Resource): String

  suspend fun update(resource: Resource)

  /** @param type resource type name, e.g. "MedicationRequest". */
  suspend fun delete(type: String, id: String)

  /**
   * Search a resource type by a reference search param, e.g. type="Immunization", param="patient",
   * referenceValue="Patient/p1".
   */
  suspend fun searchByReferenceParam(
    type: String,
    param: String,
    referenceValue: String,
  ): List<Resource>

  /**
   * Search a resource type by a uri/canonical search param, e.g. type="PlanDefinition",
   * param="url".
   */
  suspend fun searchByUri(type: String, param: String, uri: String): List<Resource>
}
