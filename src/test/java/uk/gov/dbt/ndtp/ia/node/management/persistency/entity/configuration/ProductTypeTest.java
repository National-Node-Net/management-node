/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.persistency.entity.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProductTypeTest {

    @Test
    void testProductTypeSettersAndGetters() {
        ProductType productType = new ProductType();
        productType.setId(1L);
        productType.setName("Test Product Type");
        productType.setDescription("Test Description");

        assertThat(productType.getId()).isEqualTo(1L);
        assertThat(productType.getName()).isEqualTo("Test Product Type");
        assertThat(productType.getDescription()).isEqualTo("Test Description");
    }
}
