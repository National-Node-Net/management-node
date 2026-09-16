/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.persistency.entity.certificate;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CertificateEventTypeTest {

    @Test
    void testCertificateEventTypeValues() {
        CertificateEventType[] values = CertificateEventType.values();

        assertThat(values)
                .containsExactly(
                        CertificateEventType.ISSUED,
                        CertificateEventType.RENEWED,
                        CertificateEventType.EXPIRED,
                        CertificateEventType.REVOKED);
    }

    @Test
    void testCertificateEventTypeValueOf() {
        assertThat(CertificateEventType.valueOf("ISSUED")).isEqualTo(CertificateEventType.ISSUED);
        assertThat(CertificateEventType.valueOf("RENEWED")).isEqualTo(CertificateEventType.RENEWED);
        assertThat(CertificateEventType.valueOf("EXPIRED")).isEqualTo(CertificateEventType.EXPIRED);
        assertThat(CertificateEventType.valueOf("REVOKED")).isEqualTo(CertificateEventType.REVOKED);
    }
}
