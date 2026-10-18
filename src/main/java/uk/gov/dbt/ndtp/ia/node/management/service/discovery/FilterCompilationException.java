/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */
package uk.gov.dbt.ndtp.ia.node.management.service.discovery;

/**
 * A {@code FilterNode} that cannot be translated to SQL: an unknown field, or an operator that does
 * not apply to its target. Whoever asked for the translation decides what it means - an unusable
 * policy filter refuses the search, an unusable caller filter is a bad request.
 */
public class FilterCompilationException extends RuntimeException {

    public FilterCompilationException(String message) {
        super(message);
    }
}
