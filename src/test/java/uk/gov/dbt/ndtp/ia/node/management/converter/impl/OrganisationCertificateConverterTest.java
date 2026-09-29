/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.converter.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.OrganisationCertificateDTO;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.certificate.CertificateType;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.certificate.OrganisationCertificate;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.organisation.Organisation;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.organisation.OrganisationRepository;

class OrganisationCertificateConverterTest {

    @Mock
    private OrganisationRepository organisationRepository;

    private OrganisationCertificateConverter converter;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        converter = new OrganisationCertificateConverter(organisationRepository);
    }

    @Test
    void toDto_withNullEntity_returnsNull() {
        assertThat(converter.toDto(null)).isNull();
    }

    @Test
    void toDto_mapsAllFieldsCorrectly() {
        Organisation org = new Organisation();
        org.setId(10L);
        org.setName("Test Org");
        org.setCertificateAutomationEnabled(true);

        Timestamp now = Timestamp.from(Instant.now());

        OrganisationCertificate entity = new OrganisationCertificate();
        entity.setId(1L);
        entity.setOrganisation(org);
        entity.setSubjectDn("CN=test");
        entity.setSerialNumber("abc123");
        entity.setIsRenewable(true);
        entity.setRenewalTtl(3600L);
        entity.setType(CertificateType.AUTOMATED);
        entity.setRequestedAt(now);
        entity.setIssuedAt(now);
        entity.setExpiresAt(now);
        entity.setRevokedAt(null);

        OrganisationCertificateDTO dto = converter.toDto(entity);

        assertThat(dto.getId()).isEqualTo(1L);
        assertThat(dto.getOrganisationId()).isEqualTo(10L);
        assertThat(dto.getCertificateAutomationEnabled()).isTrue();
        assertThat(dto.getSubjectDn()).isEqualTo("CN=test");
        assertThat(dto.getSerialNumber()).isEqualTo("abc123");
        assertThat(dto.getIsRenewable()).isTrue();
        assertThat(dto.getRenewalTtl()).isEqualTo(3600L);
        assertThat(dto.getType()).isEqualTo(CertificateType.AUTOMATED);
        assertThat(dto.getRequestedAt()).isEqualTo(now);
        assertThat(dto.getIssuedAt()).isEqualTo(now);
        assertThat(dto.getExpiresAt()).isEqualTo(now);
        assertThat(dto.getRevokedAt()).isNull();
    }

    @Test
    void toDto_withNullOrganisation_setsNullOrgFields() {
        OrganisationCertificate entity = new OrganisationCertificate();
        entity.setId(1L);
        entity.setOrganisation(null);
        entity.setType(CertificateType.BOOTSTRAP);

        OrganisationCertificateDTO dto = converter.toDto(entity);

        assertThat(dto.getOrganisationId()).isNull();
        assertThat(dto.getCertificateAutomationEnabled()).isNull();
    }

    @Test
    void toEntity_withNullDto_returnsNull() {
        assertThat(converter.toEntity(null)).isNull();
    }

    @Test
    void toEntity_mapsAllFieldsCorrectly() {
        Organisation org = new Organisation();
        org.setId(10L);
        org.setName("Test Org");
        when(organisationRepository.findById(10L)).thenReturn(Optional.of(org));

        Timestamp now = Timestamp.from(Instant.now());

        OrganisationCertificateDTO dto = OrganisationCertificateDTO.builder()
                .id(1L)
                .organisationId(10L)
                .subjectDn("CN=test")
                .serialNumber("abc123")
                .isRenewable(true)
                .renewalTtl(3600L)
                .type(CertificateType.AUTOMATED)
                .requestedAt(now)
                .issuedAt(now)
                .expiresAt(now)
                .revokedAt(null)
                .build();

        OrganisationCertificate entity = converter.toEntity(dto);

        assertThat(entity.getId()).isEqualTo(1L);
        assertThat(entity.getOrganisation()).isEqualTo(org);
        assertThat(entity.getSubjectDn()).isEqualTo("CN=test");
        assertThat(entity.getSerialNumber()).isEqualTo("abc123");
        assertThat(entity.getIsRenewable()).isTrue();
        assertThat(entity.getRenewalTtl()).isEqualTo(3600L);
        assertThat(entity.getType()).isEqualTo(CertificateType.AUTOMATED);
        assertThat(entity.getRequestedAt()).isEqualTo(now);
        assertThat(entity.getIssuedAt()).isEqualTo(now);
        assertThat(entity.getExpiresAt()).isEqualTo(now);
        assertThat(entity.getRevokedAt()).isNull();
    }

    @Test
    void toEntity_withNullOrganisationId_doesNotLookUpOrg() {
        OrganisationCertificateDTO dto = OrganisationCertificateDTO.builder()
                .id(1L)
                .organisationId(null)
                .type(CertificateType.BOOTSTRAP)
                .build();

        OrganisationCertificate entity = converter.toEntity(dto);

        assertThat(entity.getOrganisation()).isNull();
    }

    @Test
    void toEntity_withUnknownOrganisationId_setsNullOrg() {
        when(organisationRepository.findById(999L)).thenReturn(Optional.empty());

        OrganisationCertificateDTO dto = OrganisationCertificateDTO.builder()
                .id(1L)
                .organisationId(999L)
                .type(CertificateType.AUTOMATED)
                .build();

        OrganisationCertificate entity = converter.toEntity(dto);

        assertThat(entity.getOrganisation()).isNull();
    }
}
