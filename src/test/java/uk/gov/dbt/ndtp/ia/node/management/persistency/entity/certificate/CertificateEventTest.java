/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.persistency.entity.certificate;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CertificateEventTest {

    @Test
    void testCertificateEventSettersAndGetters() {
        OrganisationCertificate cert = new OrganisationCertificate();
        cert.setId(1L);

        Timestamp now = Timestamp.from(Instant.now());

        CertificateEvent event = new CertificateEvent();
        event.setId(5L);
        event.setOrganisationCertificate(cert);
        event.setType(CertificateType.AUTOMATED);
        event.setEventType(CertificateEventType.ISSUED);
        event.setEventTime(now);
        event.setPerformedBy("system");

        assertThat(event.getId()).isEqualTo(5L);
        assertThat(event.getOrganisationCertificate()).isEqualTo(cert);
        assertThat(event.getType()).isEqualTo(CertificateType.AUTOMATED);
        assertThat(event.getEventType()).isEqualTo(CertificateEventType.ISSUED);
        assertThat(event.getEventTime()).isEqualTo(now);
        assertThat(event.getPerformedBy()).isEqualTo("system");
    }
}
