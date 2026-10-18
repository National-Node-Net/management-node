/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.controller.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.network.NetworkMapDTO;
import uk.gov.dbt.ndtp.ia.node.management.service.data.NetworkMapService;
import uk.gov.dbt.ndtp.ia.node.management.web.policy.Policy;

/**
 * The network endpoint.
 *
 * Two of these tests are about what the endpoint does; the last two are about what it
 * deliberately does <em>not</em> do, and those are the ones worth keeping. "No policy" is a
 * decision that is invisible in the output - a passing response looks identical whether or not a
 * rule was consulted - so the absence is asserted directly rather than assumed, and so is the
 * role check that is the only thing standing in its place.
 */
@ExtendWith(MockitoExtension.class)
class NetworkControllerTest {

    @Mock
    private NetworkMapService networkMapService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new NetworkController(networkMapService))
                .build();
    }

    private static NetworkMapDTO sampleMap() {
        return NetworkMapDTO.builder()
                .organisations(List.of(NetworkMapDTO.Organisation.builder()
                        .id(1L)
                        .key("ENV")
                        .name("Environment Agency")
                        .build()))
                .producers(List.of(NetworkMapDTO.Producer.builder()
                        .id(10L)
                        .name("ENV-PRODUCER-1")
                        .active(true)
                        .organisationKey("ENV")
                        .build()))
                .consumers(List.of(NetworkMapDTO.Consumer.builder()
                        .id(20L)
                        .name("BCC-CONSUMER-1")
                        .organisationKey("BCC")
                        .build()))
                .products(List.of(NetworkMapDTO.Product.builder()
                        .id(30L)
                        .name("FloodRiskMapZones")
                        .type("topic")
                        .producerId(10L)
                        .organisationKey("ENV")
                        .build()))
                .subscriptions(List.of(NetworkMapDTO.Subscription.builder()
                        .id(40L)
                        .productId(30L)
                        .consumerId(20L)
                        .grantedAt(LocalDateTime.of(2026, 1, 1, 9, 0))
                        .validity(BigDecimal.valueOf(30))
                        .scheduleType("cron")
                        .build()))
                .totals(NetworkMapDTO.Totals.builder()
                        .organisations(1)
                        .producers(1)
                        .consumers(1)
                        .products(1)
                        .subscriptions(1)
                        .build())
                .build();
    }

    @Test
    void getNetworkMap_returnsEveryListAndTheKeysThatJoinThem() throws Exception {
        when(networkMapService.networkMap()).thenReturn(sampleMap());

        mockMvc.perform(get("/api/v1/network/map"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organisations[0].key").value("ENV"))
                .andExpect(jsonPath("$.producers[0].organisationKey").value("ENV"))
                .andExpect(jsonPath("$.consumers[0].organisationKey").value("BCC"))
                // The three keys a caller draws edges from. If any of these stop being emitted
                // the response is still valid JSON and the graph silently loses its edges, so
                // they are asserted by name.
                .andExpect(jsonPath("$.products[0].producerId").value(10))
                .andExpect(jsonPath("$.subscriptions[0].productId").value(30))
                .andExpect(jsonPath("$.subscriptions[0].consumerId").value(20))
                .andExpect(jsonPath("$.totals.products").value(1));

        verify(networkMapService).networkMap();
    }

    @Test
    void getNetworkMap_emptyNetwork_isEmptyListsRatherThanNulls() throws Exception {
        // An empty network is a valid answer, and a caller that has to null-check five lists
        // before iterating them is one the DTO has failed.
        when(networkMapService.networkMap()).thenReturn(NetworkMapDTO.builder().build());

        mockMvc.perform(get("/api/v1/network/map"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organisations").isArray())
                .andExpect(jsonPath("$.organisations").isEmpty())
                .andExpect(jsonPath("$.subscriptions").isArray())
                .andExpect(jsonPath("$.subscriptions").isEmpty());
    }

    @Test
    void getNetworkMap_carriesNoPolicyAnnotation() throws Exception {
        final Method method = NetworkController.class.getMethod("getNetworkMap");

        // The whole point of this endpoint. Adding @Policy later is a real change - it would
        // make the PDP answer for this route and could refuse it - so it should fail a test
        // rather than arrive unnoticed.
        assertThat(method.getAnnotation(Policy.class))
                .as("the network map is deliberately not policy-enforced")
                .isNull();
    }

    @Test
    void getNetworkMap_isStillGuardedByARoleCheck() throws Exception {
        final Method method = NetworkController.class.getMethod("getNetworkMap");
        final PreAuthorize preAuthorize = method.getAnnotation(PreAuthorize.class);

        // Not policy-enforced is not the same as unguarded. The role is the only thing standing
        // between this topology and an unauthenticated caller, so its absence must break the
        // build rather than open the endpoint.
        assertThat(preAuthorize).as("the network map must still require a role").isNotNull();
        assertThat(preAuthorize.value()).isEqualTo("hasAuthority('ROLE_management-node:product_discovery')");
    }
}
