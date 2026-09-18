/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.data.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import uk.gov.dbt.ndtp.ia.node.management.converter.impl.OrganisationCertificateConverter;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.OrganisationCertificateDTO;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.certificate.CertificateType;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.certificate.OrganisationCertificate;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.organisation.Organisation;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.certificate.OrganisationCertificateRepository;

class OrganisationCertificateServiceImplTest {

    @Mock
    private OrganisationCertificateRepository repository;

    @Mock
    private OrganisationCertificateConverter converter;

    private OrganisationCertificateServiceImpl service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new OrganisationCertificateServiceImpl(repository, converter);
    }

    @Test
    void findByOrganisationId_whenFound_returnsDto() {
        OrganisationCertificate entity = new OrganisationCertificate();
        entity.setId(1L);
        OrganisationCertificateDTO dto =
                OrganisationCertificateDTO.builder().id(1L).build();

        when(repository.findByOrganisationId(10L)).thenReturn(Optional.of(entity));
        when(converter.toDto(entity)).thenReturn(dto);

        Optional<OrganisationCertificateDTO> result = service.findByOrganisationId(10L);

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(1L);
    }

    @Test
    void findByOrganisationId_whenNotFound_returnsEmpty() {
        when(repository.findByOrganisationId(99L)).thenReturn(Optional.empty());

        Optional<OrganisationCertificateDTO> result = service.findByOrganisationId(99L);

        assertThat(result).isEmpty();
    }

    @Test
    void findAllByOrganisationIds_delegatesToRepositoryAndConverter() {
        Organisation org = new Organisation();
        org.setId(1L);
        org.setCertificateAutomationEnabled(true);

        OrganisationCertificate entity = new OrganisationCertificate();
        entity.setId(1L);
        entity.setOrganisation(org);
        entity.setType(CertificateType.AUTOMATED);

        OrganisationCertificateDTO dto = OrganisationCertificateDTO.builder()
                .id(1L)
                .organisationId(1L)
                .type(CertificateType.AUTOMATED)
                .build();

        when(repository.findAllWithOrganisationByOrganisationIdIn(Set.of(1L, 2L)))
                .thenReturn(List.of(entity));
        when(converter.toDtoList(List.of(entity))).thenReturn(List.of(dto));

        List<OrganisationCertificateDTO> result = service.findAllByOrganisationIds(Set.of(1L, 2L));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(1L);
    }

    @Test
    void findAllByOrganisationIds_withNullInput_returnsEmptyList() {
        List<OrganisationCertificateDTO> result = service.findAllByOrganisationIds(null);

        assertThat(result).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    void findAllByOrganisationIds_withEmptyInput_returnsEmptyList() {
        List<OrganisationCertificateDTO> result = service.findAllByOrganisationIds(List.of());

        assertThat(result).isEmpty();
        verifyNoInteractions(repository);
    }

    @Test
    void findByClientId_whenFound_returnsDto() {
        OrganisationCertificate entity = new OrganisationCertificate();
        entity.setId(1L);
        OrganisationCertificateDTO dto =
                OrganisationCertificateDTO.builder().id(1L).build();

        when(repository.findByClientId("client-1")).thenReturn(Optional.of(entity));
        when(converter.toDto(entity)).thenReturn(dto);

        Optional<OrganisationCertificateDTO> result = service.findByClientId("client-1");

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(1L);
    }

    @Test
    void findByClientId_whenNotFound_returnsEmpty() {
        when(repository.findByClientId("unknown")).thenReturn(Optional.empty());

        Optional<OrganisationCertificateDTO> result = service.findByClientId("unknown");

        assertThat(result).isEmpty();
    }

    @Test
    void save_convertsAndPersists() {
        OrganisationCertificateDTO inputDto =
                OrganisationCertificateDTO.builder().id(1L).serialNumber("abc").build();
        OrganisationCertificate entity = new OrganisationCertificate();
        entity.setId(1L);
        entity.setSerialNumber("abc");
        OrganisationCertificateDTO outputDto =
                OrganisationCertificateDTO.builder().id(1L).serialNumber("abc").build();

        when(converter.toEntity(inputDto)).thenReturn(entity);
        when(repository.save(entity)).thenReturn(entity);
        when(converter.toDto(entity)).thenReturn(outputDto);

        OrganisationCertificateDTO result = service.save(inputDto);

        assertThat(result.getSerialNumber()).isEqualTo("abc");
        verify(repository).save(entity);
    }
}
