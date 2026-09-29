/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.configuration.Producer;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.configuration.Product;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.configuration.ProductType;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.organisation.Organisation;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.configuration.ProductRepository;
import uk.gov.dbt.ndtp.ia.node.management.service.data.PolicyAttributeScopeCode;
import uk.gov.dbt.ndtp.ia.node.management.service.data.PolicyAttributeService;

/**
 * The loader that makes a product's own facts available to a rule, and, just as importantly, does
 * nothing at all for the requests that name no product.
 */
@ExtendWith(MockitoExtension.class)
class ProductPolicyResourceLoaderTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private PolicyAttributeService policyAttributeService;

    @InjectMocks
    private ProductPolicyResourceLoader loader;

    private static Product product() {
        Organisation org = new Organisation();
        org.setOrganisationKey("ENV");
        Producer producer = new Producer();
        producer.setName("env-producer");
        producer.setOrg(org);
        ProductType type = new ProductType();
        type.setName("topic");

        Product product = new Product();
        product.setId(42L);
        product.setName("FloodRiskMapZones");
        product.setTopic("topic.FloodRiskMapZones");
        product.setSource("kafka://flood");
        product.setProducer(producer);
        product.setProductType(type);
        return product;
    }

    @Test
    void supportsOnlyTheProductKind() {
        assertThat(loader.supports("product")).isTrue();
        assertThat(loader.supports("configuration")).isFalse();
        assertThat(loader.supports("invoice")).isFalse();
    }

    @Test
    void anIdentifiedProduct_carriesItsFieldsAndAttributes() {
        when(productRepository.findById(42L)).thenReturn(Optional.of(product()));
        when(policyAttributeService.findAttributeMap(42L, PolicyAttributeScopeCode.PRODUCT))
                .thenReturn(Map.of("identifiability", "non_personal"));

        Optional<PolicyResource> resource = loader.load("product", "42");

        assertThat(resource).isPresent();
        assertThat(resource.get().id()).isEqualTo("42");
        assertThat(resource.get().attributes()).containsEntry("identifiability", "non_personal");
        assertThat(resource.get().fields())
                .containsEntry("name", "FloodRiskMapZones")
                .containsEntry("type", "topic")
                .containsEntry("producer", "env-producer")
                .containsEntry("organisation", "ENV");
    }

    /** A non-numeric id is the handler's 400; failing the decision would report it as a 403. */
    @Test
    void aNonNumericProductId_loadsNothingRatherThanFailing() {
        assertThat(loader.load("product", "abc")).isEmpty();
        verify(productRepository, never()).findById(any());
    }

    /** An id for a product that does not exist is empty, not an error: the rule may refuse it. */
    @Test
    void aProductThatDoesNotExist_loadsNothing() {
        when(productRepository.findById(7L)).thenReturn(Optional.empty());

        assertThat(loader.load("product", "7")).isEmpty();
        verifyNoInteractions(policyAttributeService);
    }

    /** Absent rather than null, so a rule need not tell the two apart. */
    @Test
    void fieldsThatAreNotSet_areAbsentRatherThanNull() {
        Product bare = new Product();
        bare.setId(9L);
        bare.setName("Bare");
        bare.setTopic("topic.bare");
        when(productRepository.findById(9L)).thenReturn(Optional.of(bare));
        when(policyAttributeService.findAttributeMap(any(), any())).thenReturn(Map.of());

        Map<String, Object> fields = loader.load("product", "9").orElseThrow().fields();

        assertThat(fields).containsKeys("id", "name", "topic");
        assertThat(fields).doesNotContainKeys("description", "source", "type", "producer", "organisation");
    }
}
