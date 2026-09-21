/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */
package uk.gov.dbt.ndtp.ia.node.management.service.discovery;

/**
 * The parts of a discovered product that are shown or withheld as a whole. Policy names them in
 * {@code visible_fields} and {@code masked_filtered_fields} exactly like a single field.
 */
public enum ProductBlock {
    /** The organisation offering the product. */
    ORGANISATION("organisation"),
    /** The producer offering the product. */
    PRODUCER("producer"),
    /** The consumers holding a grant on the product, with the terms of each grant. */
    CONSUMERS("consumers"),
    /** The organisations currently using the product. */
    SUBSCRIBED_BY("subscribedBy"),
    /** The product's own policy attributes. */
    POLICY_ATTRIBUTES("policyAttributes");

    private final String apiName;

    ProductBlock(String apiName) {
        this.apiName = apiName;
    }

    public String apiName() {
        return apiName;
    }
}
