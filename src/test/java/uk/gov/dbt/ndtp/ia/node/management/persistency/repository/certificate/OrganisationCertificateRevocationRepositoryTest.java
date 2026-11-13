/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.persistency.repository.certificate;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.certificate.CertificateType;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.certificate.OrganisationCertificate;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.certificate.RevocationReason;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.organisation.Organisation;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.AbstractPostgresRepositoryTest;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.organisation.OrganisationRepository;

/**
 * The revocation columns and queries against real Postgres with the Flyway migrations applied, so the
 * migration's column types (revoked_at with time zone, revocation_message as text) are what is tested.
 */
class OrganisationCertificateRevocationRepositoryTest extends AbstractPostgresRepositoryTest {

    private static final Instant REVOKED_AT = Instant.parse("2026-10-12T13:15:02Z");

    @Autowired
    private OrganisationCertificateRepository certificateRepository;

    @Autowired
    private OrganisationRepository organisationRepository;

    private OrganisationCertificate persist(String key, String serial) {
        Organisation organisation = new Organisation();
        organisation.setName("Organisation " + key);
        organisation.setOrganisationKey(key);
        organisationRepository.saveAndFlush(organisation);

        OrganisationCertificate certificate = new OrganisationCertificate();
        certificate.setOrganisation(organisation);
        certificate.setSerialNumber(serial);
        certificate.setType(CertificateType.AUTOMATED);
        return certificateRepository.saveAndFlush(certificate);
    }

    private int markRevoked(OrganisationCertificate certificate, String serial, String by) {
        return certificateRepository.markRevoked(
                certificate.getId(),
                serial,
                Timestamp.from(REVOKED_AT),
                by,
                RevocationReason.KEY_COMPROMISE,
                "x".repeat(1000));
    }

    @Test
    void findBySerialNumber_shouldReturnTheRecordWithItsOrganisation() {
        OrganisationCertificate saved = persist("REV_FIND", "39dd2e01");

        assertThat(certificateRepository.findBySerialNumber("39dd2e01")).get().satisfies(found -> {
            assertThat(found.getId()).isEqualTo(saved.getId());
            assertThat(found.getOrganisation().getOrganisationKey()).isEqualTo("REV_FIND");
        });
        assertThat(certificateRepository.findBySerialNumber("39dd2e02")).isEmpty();
    }

    @Test
    void markRevoked_activeRecord_shouldStoreTheRevocation() {
        OrganisationCertificate saved = persist("REV_MARK", "39dd2e03");

        assertThat(markRevoked(saved, "39dd2e03", "admin-a")).isEqualTo(1);

        OrganisationCertificate revoked =
                certificateRepository.findById(saved.getId()).orElseThrow();
        assertThat(revoked.getRevokedAt().toInstant()).isEqualTo(REVOKED_AT);
        assertThat(revoked.getRevokedBy()).isEqualTo("admin-a");
        assertThat(revoked.getRevocationReason()).isEqualTo(RevocationReason.KEY_COMPROMISE);
        assertThat(revoked.getRevocationMessage()).hasSize(1000);
    }

    @Test
    void markRevoked_alreadyRevoked_shouldLeaveTheFirstRevocation() {
        OrganisationCertificate saved = persist("REV_TWICE", "39dd2e04");
        markRevoked(saved, "39dd2e04", "first-admin");

        assertThat(markRevoked(saved, "39dd2e04", "second-admin")).isZero();

        assertThat(certificateRepository.findById(saved.getId()).orElseThrow().getRevokedBy())
                .isEqualTo("first-admin");
    }

    @Test
    void markRevoked_recordNowHoldingAnotherSerial_shouldChangeNothing() {
        OrganisationCertificate saved = persist("REV_REISSUED", "39dd2e05");

        assertThat(markRevoked(saved, "39dd2e99", "admin-a")).isZero();

        assertThat(certificateRepository.findById(saved.getId()).orElseThrow().getRevokedAt())
                .isNull();
    }

    @Test
    void findRevocationReasonBySerialNumber_shouldOnlyAnswerForRevokedRecords() {
        OrganisationCertificate saved = persist("REV_REASON", "39dd2e06");
        assertThat(certificateRepository.findRevocationReasonBySerialNumber("39dd2e06"))
                .isEmpty();

        markRevoked(saved, "39dd2e06", "admin-a");

        assertThat(certificateRepository.findRevocationReasonBySerialNumber("39dd2e06"))
                .contains(RevocationReason.KEY_COMPROMISE);
        assertThat(certificateRepository.findRevocationReasonBySerialNumber("39dd2e07"))
                .isEmpty();
    }
}
