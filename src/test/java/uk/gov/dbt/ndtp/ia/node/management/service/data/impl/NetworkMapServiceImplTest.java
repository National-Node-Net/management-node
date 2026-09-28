/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.data.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.annotation.Transactional;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.network.NetworkMapDTO;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.configuration.NetworkMapRepository;

/**
 * Assembling the network map.
 *
 * The service composes rather than converts, so most of what is worth asserting is about the
 * shape of the whole: that every list reaches the caller, that the totals describe what was
 * actually read rather than being counted twice, and that the five reads happen inside one
 * read-only transaction.
 */
@ExtendWith(MockitoExtension.class)
class NetworkMapServiceImplTest {

    @Mock
    private NetworkMapRepository repository;

    @InjectMocks
    private NetworkMapServiceImpl service;

    @Test
    void networkMap_returnsEveryListAndCountsThemIntoTotals() {
        when(repository.findOrganisations())
                .thenReturn(List.of(
                        NetworkMapDTO.Organisation.builder().key("ENV").build(),
                        NetworkMapDTO.Organisation.builder().key("BCC").build()));
        when(repository.findProducers())
                .thenReturn(List.of(NetworkMapDTO.Producer.builder().id(1L).build()));
        when(repository.findConsumers())
                .thenReturn(List.of(
                        NetworkMapDTO.Consumer.builder().id(2L).build(),
                        NetworkMapDTO.Consumer.builder().id(3L).build(),
                        NetworkMapDTO.Consumer.builder().id(4L).build()));
        when(repository.findProducts())
                .thenReturn(List.of(NetworkMapDTO.Product.builder().id(5L).build()));
        when(repository.findSubscriptions()).thenReturn(List.of());

        final NetworkMapDTO map = service.networkMap();

        assertThat(map.organisations()).hasSize(2);
        assertThat(map.producers()).hasSize(1);
        assertThat(map.consumers()).hasSize(3);
        assertThat(map.products()).hasSize(1);
        assertThat(map.subscriptions()).isEmpty();

        // The totals must describe the document, not be recomputed from a second read - a
        // count that disagrees with the list beside it is worse than no count.
        assertThat(map.totals().organisations()).isEqualTo(2);
        assertThat(map.totals().producers()).isEqualTo(1);
        assertThat(map.totals().consumers()).isEqualTo(3);
        assertThat(map.totals().products()).isEqualTo(1);
        assertThat(map.totals().subscriptions()).isZero();
    }

    @Test
    void networkMap_readsEachKindExactlyOnce() {
        when(repository.findOrganisations()).thenReturn(List.of());
        when(repository.findProducers()).thenReturn(List.of());
        when(repository.findConsumers()).thenReturn(List.of());
        when(repository.findProducts()).thenReturn(List.of());
        when(repository.findSubscriptions()).thenReturn(List.of());

        service.networkMap();

        // Five statements, not more: the point of this endpoint is replacing N+1 round trips,
        // and a stray per-entity read reintroduced later would undo it invisibly.
        verify(repository).findOrganisations();
        verify(repository).findProducers();
        verify(repository).findConsumers();
        verify(repository).findProducts();
        verify(repository).findSubscriptions();
        verifyNoMoreInteractions(repository);
    }

    @Test
    void networkMap_emptyDatabase_isEmptyListsRatherThanNulls() {
        when(repository.findOrganisations()).thenReturn(List.of());
        when(repository.findProducers()).thenReturn(List.of());
        when(repository.findConsumers()).thenReturn(List.of());
        when(repository.findProducts()).thenReturn(List.of());
        when(repository.findSubscriptions()).thenReturn(List.of());

        final NetworkMapDTO map = service.networkMap();

        assertThat(map.organisations()).isNotNull().isEmpty();
        assertThat(map.producers()).isNotNull().isEmpty();
        assertThat(map.consumers()).isNotNull().isEmpty();
        assertThat(map.products()).isNotNull().isEmpty();
        assertThat(map.subscriptions()).isNotNull().isEmpty();
    }

    @Test
    void networkMap_readsTheFiveStatementsInOneReadOnlyTransaction() throws Exception {
        final Method method = NetworkMapServiceImpl.class.getMethod("networkMap");
        final Transactional transactional = method.getAnnotation(Transactional.class);

        // Without one transaction the map is five reads at five moments, and a subscription
        // written between the third and the fifth would reference a consumer the map does not
        // contain - an edge with a missing endpoint. Asserted because nothing in the returned
        // document would reveal its absence.
        assertThat(transactional)
                .as("the five reads must be one consistent snapshot")
                .isNotNull();
        assertThat(transactional.readOnly()).isTrue();
    }
}
