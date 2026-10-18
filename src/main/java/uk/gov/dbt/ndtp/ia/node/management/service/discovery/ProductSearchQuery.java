/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */
package uk.gov.dbt.ndtp.ia.node.management.service.discovery;

import java.util.Map;

/**
 * The two statements of one discovery search - the page and the total - sharing one
 * {@code WHERE} clause and one set of bound parameters.
 *
 * @param pageSql selects one row per product of the requested page
 * @param countSql counts every product matching the same conditions
 * @param parameters the bound values of both statements
 */
public record ProductSearchQuery(String pageSql, String countSql, Map<String, Object> parameters) {

    /** Column holding the product id; always selected. */
    public static final String ID = "id";

    /** Column holding the owning organisation's id; always selected, never returned. */
    public static final String ORGANISATION_ID = "organisation_id";

    /** Column holding the producer's id; always selected, never returned. */
    public static final String PRODUCER_ID = "producer_id";

    /** Column telling whether unmask rule {@code index} applies to the row. */
    public static String unmaskColumn(int index) {
        return "unmask_" + index;
    }
}
