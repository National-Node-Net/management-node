/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.persistency.entity.certificate;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.organisation.Organisation;

class OrganisationCertificateTest {

    @Test
    void testOrganisationCertificateSettersAndGetters() {
        Organisation organisation = new Organisation();
        organisation.setId(1L);

        Timestamp now = Timestamp.from(Instant.now());

        OrganisationCertificate cert = new OrganisationCertificate();
        cert.setId(10L);
        cert.setOrganisation(organisation);
        cert.setSubjectDn("CN=test,O=NDTP");
        cert.setSerialNumber("ABC123");
        cert.setIsRenewable(true);
        cert.setRenewalTtl(86400L);
        cert.setType(CertificateType.BOOTSTRAP);
        cert.setRequestedAt(now);
        cert.setIssuedAt(now);
        cert.setExpiresAt(now);
        cert.setRevokedAt(now);

        assertThat(cert.getId()).isEqualTo(10L);
        assertThat(cert.getOrganisation()).isEqualTo(organisation);
        assertThat(cert.getSubjectDn()).isEqualTo("CN=test,O=NDTP");
        assertThat(cert.getSerialNumber()).isEqualTo("ABC123");
        assertThat(cert.getIsRenewable()).isTrue();
        assertThat(cert.getRenewalTtl()).isEqualTo(86400L);
        assertThat(cert.getType()).isEqualTo(CertificateType.BOOTSTRAP);
        assertThat(cert.getRequestedAt()).isEqualTo(now);
        assertThat(cert.getIssuedAt()).isEqualTo(now);
        assertThat(cert.getExpiresAt()).isEqualTo(now);
        assertThat(cert.getRevokedAt()).isEqualTo(now);
    }

    @Test
    void testIsRenewableDefaultsToFalse() {
        OrganisationCertificate cert = new OrganisationCertificate();

        assertThat(cert.getIsRenewable()).isFalse();
    }
}
