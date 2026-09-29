/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.policy;

/**
 * Decision response returned by the PDP (OPA), following OPA's standard REST API shape of
 * {@code {"result": ...}}. The result is the policy's decision document; a missing result
 * (an undefined rule) or one that cannot be read as a decision is treated as DENY by
 * {@link PolicyDecisionClient}.
 *
 * @param result the PDP's decision result
 */
public record PolicyDecisionResponse(PolicyDecision<PolicyDecisionDetails> result) {}
