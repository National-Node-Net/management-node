/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.persistency.entity.certificate;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CertificateTypeTest {

    @Test
    void testCertificateTypeValues() {
        CertificateType[] values = CertificateType.values();

        assertThat(values)
                .containsExactly(CertificateType.BOOTSTRAP, CertificateType.MANUAL, CertificateType.AUTOMATED);
    }

    @Test
    void testCertificateTypeValueOf() {
        assertThat(CertificateType.valueOf("BOOTSTRAP")).isEqualTo(CertificateType.BOOTSTRAP);
        assertThat(CertificateType.valueOf("MANUAL")).isEqualTo(CertificateType.MANUAL);
        assertThat(CertificateType.valueOf("AUTOMATED")).isEqualTo(CertificateType.AUTOMATED);
    }
}
