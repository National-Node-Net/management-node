/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.policy;

/**
 * Decision request sent to the PDP (OPA), following OPA's standard REST API
 * shape of {@code {"input": {...}}}.
 *
 * @param input the policy attributes for this request
 */
public record PolicyDecisionRequest(PolicyInput input) {}
