/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.data.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.dbt.ndtp.ia.node.management.converter.impl.OrganisationConverter;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.OrganisationDTO;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.Organisation;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.OrganisationRepository;

@ExtendWith(MockitoExtension.class)
class OrganisationServiceImplTest {

    @Mock
    private OrganisationRepository organisationRepository;

    private OrganisationServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new OrganisationServiceImpl(organisationRepository, new OrganisationConverter());
    }

    private static Organisation organisation(Long id, String name, String key) {
        Organisation organisation = new Organisation();
        organisation.setId(id);
        organisation.setName(name);
        organisation.setOrganisationKey(key);
        return organisation;
    }

    @Test
    void findById_mapsNameAndKey() {
        when(organisationRepository.findById(1L))
                .thenReturn(Optional.of(organisation(1L, "Environment Agency (ENV)", "ENV")));

        Optional<OrganisationDTO> result = service.findById(1L);

        assertThat(result).isPresent();
        assertThat(result.get().getName()).isEqualTo("Environment Agency (ENV)");
        assertThat(result.get().getKey()).isEqualTo("ENV");
        assertThat(result.get().getPolicyAttributes()).isEmpty();
    }

    @Test
    void findById_returnsEmptyWhenNotFound() {
        when(organisationRepository.findById(99L)).thenReturn(Optional.empty());

        assertThat(service.findById(99L)).isEmpty();
    }

    @Test
    void findById_returnsEmptyForNullIdWithoutQuerying() {
        assertThat(service.findById(null)).isEmpty();

        verifyNoInteractions(organisationRepository);
    }

    @Test
    void findByKey_mapsNameAndKey() {
        when(organisationRepository.findByOrganisationKey("BCC"))
                .thenReturn(Optional.of(organisation(2L, "Bristol City Council (BCC)", "BCC")));

        Optional<OrganisationDTO> result = service.findByKey("BCC");

        assertThat(result).isPresent();
        assertThat(result.get().getKey()).isEqualTo("BCC");
        assertThat(result.get().getName()).isEqualTo("Bristol City Council (BCC)");
    }

    @Test
    void findByKey_returnsEmptyWhenNotFound() {
        when(organisationRepository.findByOrganisationKey("NOPE")).thenReturn(Optional.empty());

        assertThat(service.findByKey("NOPE")).isEmpty();
    }

    @Test
    void findByKey_returnsEmptyForNullOrBlankWithoutQuerying() {
        assertThat(service.findByKey(null)).isEmpty();
        assertThat(service.findByKey("")).isEmpty();
        assertThat(service.findByKey("   ")).isEmpty();

        verify(organisationRepository, never()).findByOrganisationKey(any());
    }

    @Test
    void findByIds_returnsOneEntryPerFoundOrganisationKeyedById() {
        when(organisationRepository.findAllById(List.of(1L, 3L)))
                .thenReturn(List.of(
                        organisation(1L, "Environment Agency (ENV)", "ENV"),
                        organisation(3L, "Homes England (HEG)", "HEG")));

        var result = service.findByIds(List.of(1L, 3L));

        assertThat(result).hasSize(2);
        assertThat(result.get(1L).getKey()).isEqualTo("ENV");
        assertThat(result.get(3L).getKey()).isEqualTo("HEG");
    }

    @Test
    void findByIds_omitsIdsWithNoMatchingRow() {
        when(organisationRepository.findAllById(List.of(1L, 404L)))
                .thenReturn(List.of(organisation(1L, "Environment Agency (ENV)", "ENV")));

        var result = service.findByIds(List.of(1L, 404L));

        assertThat(result).containsOnlyKeys(1L);
    }

    @Test
    void findByIds_returnsEmptyMapForNullOrEmptyWithoutQuerying() {
        assertThat(service.findByIds(null)).isEmpty();
        assertThat(service.findByIds(List.of())).isEmpty();

        verifyNoInteractions(organisationRepository);
    }

    @Test
    void findIdByKey_returnsTheRowIdForAKnownKey() {
        when(organisationRepository.findByOrganisationKey("ENV"))
                .thenReturn(Optional.of(organisation(42L, "Environment Agency (ENV)", "ENV")));

        assertThat(service.findIdByKey("ENV")).contains(42L);
    }

    @Test
    void findIdByKey_returnsEmptyForAnUnknownKey() {
        when(organisationRepository.findByOrganisationKey("NOPE")).thenReturn(Optional.empty());

        assertThat(service.findIdByKey("NOPE")).isEmpty();
    }

    @Test
    void findIdByKey_doesNotQueryForANullOrBlankKey() {
        assertThat(service.findIdByKey(null)).isEmpty();
        assertThat(service.findIdByKey("  ")).isEmpty();

        verify(organisationRepository, never()).findByOrganisationKey(any());
    }
}
