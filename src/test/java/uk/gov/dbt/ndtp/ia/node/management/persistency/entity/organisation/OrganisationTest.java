/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.persistency.entity.organisation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OrganisationTest {

    @Test
    void testOrganisationSettersAndGetters() {
        Organisation organisation = new Organisation();
        organisation.setId(1L);
        organisation.setName("Test Organisation");
        organisation.setCertificateAutomationEnabled(false);

        assertThat(organisation.getId()).isEqualTo(1L);
        assertThat(organisation.getName()).isEqualTo("Test Organisation");
        assertThat(organisation.getCertificateAutomationEnabled()).isFalse();
    }

    @Test
    void testCertificateAutomationEnabledDefaultsToTrue() {
        Organisation organisation = new Organisation();

        assertThat(organisation.getCertificateAutomationEnabled()).isTrue();
    }
}
