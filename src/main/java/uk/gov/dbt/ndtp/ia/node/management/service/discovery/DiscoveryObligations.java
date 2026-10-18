/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */
package uk.gov.dbt.ndtp.ia.node.management.service.discovery;

import java.util.List;
import java.util.Set;

/**
 * The obligations a {@code product.discover} decision may attach, and what the service does about
 * each. A Policy Enforcement Point that cannot fulfil an obligation must not grant access, so an
 * obligation that is not listed here refuses the search rather than being ignored.
 */
public final class DiscoveryObligations {

    /** Write an audit record of the search. Fulfilled by the discovery service's audit log line. */
    public static final String AUDIT_ACCESS = "audit_access";

    /** Withhold the masked fields and attributes. Always done, whether or not it is obliged. */
    public static final String MASK_RESPONSE = "mask_response";

    /**
     * Aggregate the data before releasing it. A duty on whoever later consumes the product's data,
     * not something a catalogue search can do, so it is passed on to the caller in the response.
     */
    public static final String AGGREGATE_BEFORE_RELEASE = "aggregate_before_release";

    private static final Set<String> SUPPORTED = Set.of(AUDIT_ACCESS, MASK_RESPONSE, AGGREGATE_BEFORE_RELEASE);

    private DiscoveryObligations() {}

    /** The obligations in {@code obligations} that this service does not know how to honour. */
    public static List<String> unsupported(List<String> obligations) {
        return obligations.stream()
                .filter(obligation -> !SUPPORTED.contains(obligation))
                .toList();
    }
}
