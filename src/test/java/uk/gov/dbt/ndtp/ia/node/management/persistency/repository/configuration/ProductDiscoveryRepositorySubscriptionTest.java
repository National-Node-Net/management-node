package uk.gov.dbt.ndtp.ia.node.management.persistency.repository.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import uk.gov.dbt.ndtp.ia.node.management.service.discovery.DiscoverySchema;

@ExtendWith(MockitoExtension.class)
class ProductDiscoveryRepositorySubscriptionTest {

    @Mock
    private NamedParameterJdbcTemplate jdbc;

    @Captor
    private ArgumentCaptor<String> sql;

    @Captor
    private ArgumentCaptor<Map<String, Object>> parameters;

    private ProductDiscoveryRepository repository;

    @BeforeEach
    void setUp() {
        repository = new ProductDiscoveryRepository(jdbc, new DiscoverySchema("mn"));
    }

    @Test
    void findSubscribedProductIds_returnsTheProductsTheOrganisationHoldsAGrantOn() {
        when(jdbc.queryForList(anyString(), anyMap(), eq(Long.class))).thenReturn(List.of(1L, 3L));

        assertThat(repository.findSubscribedProductIds("BCC", List.of(1L, 2L, 3L)))
                .containsExactlyInAnyOrder(1L, 3L);
    }

    @Test
    void findSubscribedProductIds_matchesGrantsThroughAnyConsumerOfTheOrganisation() {
        repository.findSubscribedProductIds("BCC", List.of(1L, 2L));

        verify(jdbc).queryForList(sql.capture(), parameters.capture(), eq(Long.class));
        assertThat(sql.getValue())
                .startsWith("SELECT DISTINCT pc.product_id")
                .contains("FROM \"mn\".product_consumer pc")
                .contains("JOIN \"mn\".consumer c ON c.id = pc.consumer_id")
                .contains("JOIN \"mn\".organisation o ON o.id = c.org_id")
                .contains("pc.product_id IN (:ids)")
                .contains("o.organisation_key = :organisationKey")
                .doesNotContain("validity");
        assertThat(parameters.getValue())
                .containsEntry("organisationKey", "BCC")
                .containsEntry("ids", List.of(1L, 2L));
    }

    @Test
    void findSubscribedProductIds_withNoOrganisationOrNoProducts_queriesNothing() {
        assertThat(repository.findSubscribedProductIds(null, List.of(1L))).isEmpty();
        assertThat(repository.findSubscribedProductIds("BCC", List.of())).isEmpty();

        verify(jdbc, never()).queryForList(anyString(), any(Map.class), eq(Long.class));
    }
}
