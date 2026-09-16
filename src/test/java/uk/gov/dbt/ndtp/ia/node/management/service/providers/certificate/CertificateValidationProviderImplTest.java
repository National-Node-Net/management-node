/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.OrganisationCertificateDTO;
import uk.gov.dbt.ndtp.ia.node.management.service.data.OrganisationCertificateService;

class CertificateValidationProviderImplTest {

    @Mock
    private OrganisationCertificateService certificateService;

    private CertificateValidationProviderImpl provider;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        provider = new CertificateValidationProviderImpl(certificateService);
    }

    private OrganisationCertificateDTO cert(Long orgId, Timestamp expiresAt, Timestamp revokedAt) {
        return OrganisationCertificateDTO.builder()
                .id(orgId)
                .organisationId(orgId)
                .expiresAt(expiresAt)
                .revokedAt(revokedAt)
                .build();
    }

    @Test
    void findByClientId_delegatesToService() {
        OrganisationCertificateDTO dto =
                OrganisationCertificateDTO.builder().id(1L).build();
        when(certificateService.findByClientId("client-1")).thenReturn(Optional.of(dto));

        assertThat(provider.findByClientId("client-1")).isPresent();
    }

    @Test
    void findByClientId_unknownClient_returnsEmpty() {
        when(certificateService.findByClientId("unknown")).thenReturn(Optional.empty());

        assertThat(provider.findByClientId("unknown")).isEmpty();
    }

    @Test
    void isActive_nullExpiryNullRevocation_returnsTrue() {
        assertThat(provider.isActive(cert(1L, null, null))).isTrue();
    }

    @Test
    void isActive_futureExpiry_returnsTrue() {
        Timestamp future = Timestamp.from(Instant.now().plus(30, ChronoUnit.DAYS));

        assertThat(provider.isActive(cert(1L, future, null))).isTrue();
    }

    @Test
    void isActive_pastExpiry_returnsFalse() {
        Timestamp past = Timestamp.from(Instant.now().minus(1, ChronoUnit.DAYS));

        assertThat(provider.isActive(cert(1L, past, null))).isFalse();
    }

    @Test
    void isActive_pastRevocation_returnsFalse() {
        Timestamp revokedAt = Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS));

        assertThat(provider.isActive(cert(1L, null, revokedAt))).isFalse();
    }

    @Test
    void isActive_futureRevocation_returnsTrue() {
        Timestamp futureRevocation = Timestamp.from(Instant.now().plus(7, ChronoUnit.DAYS));

        assertThat(provider.isActive(cert(1L, null, futureRevocation))).isTrue();
    }

    @Test
    void findActiveOrganisationIds_nullInput_returnsEmptySet() {
        assertThat(provider.findActiveOrganisationIds(null)).isEmpty();
    }

    @Test
    void findActiveOrganisationIds_emptyInput_returnsEmptySet() {
        assertThat(provider.findActiveOrganisationIds(Set.of())).isEmpty();
    }

    @Test
    void findActiveOrganisationIds_returnsOnlyActiveOrgs() {
        Timestamp future = Timestamp.from(Instant.now().plus(30, ChronoUnit.DAYS));
        Timestamp past = Timestamp.from(Instant.now().minus(1, ChronoUnit.DAYS));
        Timestamp revokedAt = Timestamp.from(Instant.now().minus(1, ChronoUnit.HOURS));

        OrganisationCertificateDTO active = cert(1L, future, null);
        OrganisationCertificateDTO expired = cert(2L, past, null);
        OrganisationCertificateDTO revoked = cert(3L, future, revokedAt);

        when(certificateService.findAllByOrganisationIds(Set.of(1L, 2L, 3L)))
                .thenReturn(List.of(active, expired, revoked));

        Set<Long> result = provider.findActiveOrganisationIds(Set.of(1L, 2L, 3L));

        assertThat(result).containsExactly(1L);
    }

    @Test
    void findActiveOrganisationIds_orgsWithoutCertRecords_excluded() {
        Timestamp future = Timestamp.from(Instant.now().plus(30, ChronoUnit.DAYS));
        OrganisationCertificateDTO active = cert(1L, future, null);

        when(certificateService.findAllByOrganisationIds(Set.of(1L, 2L))).thenReturn(List.of(active));

        Set<Long> result = provider.findActiveOrganisationIds(Set.of(1L, 2L));

        assertThat(result).containsExactly(1L);
    }
}
