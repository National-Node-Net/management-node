/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.discovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.DiscoveredProductDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.product.ProductDiscoveryPolicyDecisionDetails;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.configuration.ProductDiscoveryRepository;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.configuration.ProductDiscoveryRepository.Grant;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.configuration.ProductDiscoveryRepository.SubscribingOrganisation;
import uk.gov.dbt.ndtp.ia.node.management.service.data.PolicyAttributeScopeCode;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecision;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyProvenance;

/**
 * Covers turning a page of rows into products: every block is loaded for the products that show it
 * and for no others, a masked block is never loaded at all, and the attribute queries carry the
 * exclusions rather than the result being stripped afterwards.
 */
@ExtendWith(MockitoExtension.class)
class DiscoveredProductAssemblerTest {

    private static final LocalDateTime EARLIEST = LocalDateTime.of(2026, 1, 5, 9, 0);
    private static final LocalDateTime LATER = LocalDateTime.of(2026, 3, 9, 14, 30);

    @Mock
    private ProductDiscoveryRepository repository;

    @InjectMocks
    private DiscoveredProductAssembler assembler;

    @Captor
    private ArgumentCaptor<Collection<Long>> idsCaptor;

    @Captor
    private ArgumentCaptor<Set<String>> maskedCaptor;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void assemble_openContract_populatesEveryBlockWithItsScopesAttributes() {
        when(repository.findAttributes(eq(PolicyAttributeScopeCode.PRODUCT), anySet(), anySet(), anyBoolean()))
                .thenReturn(Map.of(1L, Map.of("identifiability", "anonymised")));
        when(repository.findAttributes(eq(PolicyAttributeScopeCode.ORGANISATION), anySet(), anySet(), anyBoolean()))
                .thenReturn(Map.of(10L, Map.of("jurisdictions", List.of("England"))));
        when(repository.findAttributes(eq(PolicyAttributeScopeCode.PRODUCER), anySet(), anySet(), anyBoolean()))
                .thenReturn(Map.of(20L, Map.of("tier", 3)));
        when(repository.findAttributes(eq(PolicyAttributeScopeCode.CONSUMER), anySet(), anySet(), anyBoolean()))
                .thenReturn(Map.of(30L, Map.of("operating_remit", "national")));
        when(repository.findAttributes(eq(PolicyAttributeScopeCode.SUBSCRIPTION), anySet(), anySet(), anyBoolean()))
                .thenReturn(Map.of(40L, Map.of("delivery", "batch")));
        when(repository.findGrants(anySet())).thenReturn(List.of(grant(40L, 1L, 30L)));
        when(repository.findSubscribingOrganisations(anySet()))
                .thenReturn(List.of(new SubscribingOrganisation(1L, "BCC", "Borough Council", 2, EARLIEST)));

        List<DiscoveredProductDTO> products =
                assembler.assemble(List.of(row(1L)), ProductSearchContract.open(), ProductProjection.FULL);

        assertThat(products).hasSize(1);
        DiscoveredProductDTO product = products.get(0);
        assertThat(product.id()).isEqualTo(1L);
        assertThat(product.name()).isEqualTo("Flood risk");
        assertThat(product.description()).isEqualTo("Flood risk of every property");
        assertThat(product.topic()).isEqualTo("flood.risk");
        assertThat(product.type()).isEqualTo("topic");
        assertThat(product.source()).isEqualTo("https://example.test/flood");
        assertThat(product.attributes()).containsExactly(Map.entry("identifiability", "anonymised"));
        assertThat(product.organisation())
                .isEqualTo(new DiscoveredProductDTO.Organisation(
                        "ENV", "Environment Agency", Map.of("jurisdictions", List.of("England"))));
        assertThat(product.producer())
                .isEqualTo(new DiscoveredProductDTO.Producer(
                        "Flood producer", "Publishes flood data", true, Map.of("tier", 3)));
        assertThat(product.consumers())
                .containsExactly(new DiscoveredProductDTO.Consumer(
                        "Council consumer",
                        new DiscoveredProductDTO.Organisation("BCC", "Borough Council", null),
                        Map.of("operating_remit", "national"),
                        new DiscoveredProductDTO.Subscription(
                                EARLIEST, new BigDecimal("30"), "cron", "0 0 * * *", Map.of("delivery", "batch"))));
        assertThat(product.subscribedBy())
                .containsExactly(
                        new DiscoveredProductDTO.SubscribingOrganisation("BCC", "Borough Council", 2, EARLIEST));
    }

    @Test
    void assemble_maskedBlocks_loadNothingAndAreAbsent() throws JsonProcessingException {
        ProductSearchContract contract = enforcing(
                """
                {"visible_fields": ["name"], "masked_filtered_attributes": ["consumer.*"]}""");

        List<DiscoveredProductDTO> products = assembler.assemble(List.of(row(1L)), contract, ProductProjection.FULL);

        // A masked block is not queried at all - not queried with nothing, simply not queried.
        verify(repository, never()).findAttributes(any(), any(), any(), anyBoolean());
        verify(repository, never()).findGrants(any());
        verify(repository, never()).findSubscribingOrganisations(any());

        DiscoveredProductDTO product = products.get(0);
        assertThat(product.name()).isEqualTo("Flood risk");
        assertThat(product.attributes()).isNull();
        assertThat(product.organisation()).isNull();
        assertThat(product.producer()).isNull();
        assertThat(product.consumers()).isNull();
        assertThat(product.subscribedBy()).isNull();
    }

    @Test
    void assemble_unmaskedRowOnly_loadsBlocksForThatProductAlone() throws JsonProcessingException {
        ProductSearchContract contract = enforcing(
                """
                {"visible_fields": ["name"],
                 "unmask_when": [{"names": ["subscribedBy", "policyAttributes"],
                                  "when": {"type": "literal", "value": true}}]}""");
        Map<String, Object> unmasked = row(1L);
        unmasked.put(ProductSearchQuery.unmaskColumn(0), true);
        Map<String, Object> masked = row(2L);
        masked.put(ProductSearchQuery.unmaskColumn(0), false);
        when(repository.findSubscribingOrganisations(anySet()))
                .thenReturn(List.of(new SubscribingOrganisation(1L, "BCC", "Borough Council", 1, EARLIEST)));

        List<DiscoveredProductDTO> products =
                assembler.assemble(List.of(unmasked, masked), contract, ProductProjection.FULL);

        verify(repository)
                .findAttributes(eq(PolicyAttributeScopeCode.PRODUCT), idsCaptor.capture(), anySet(), anyBoolean());
        verify(repository).findSubscribingOrganisations(idsCaptor.capture());
        assertThat(idsCaptor.getAllValues()).allSatisfy(ids -> assertThat(ids).containsExactly(1L));
        assertThat(products.get(0).subscribedBy()).hasSize(1);
        assertThat(products.get(0).attributes()).isEmpty();
        assertThat(products.get(1).subscribedBy()).isNull();
        assertThat(products.get(1).attributes()).isNull();
    }

    @Test
    void assemble_attributeQueries_carryTheMaskedNamesAndTheSensitiveFlag() throws JsonProcessingException {
        ProductSearchContract contract = enforcing(
                """
                {"visible_fields": ["policyAttributes", "organisation"],
                 "masked_filtered_attributes": ["population_risk_tags", "organisation.authorised_classifications"]}""");

        assembler.assemble(List.of(row(1L)), contract, ProductProjection.FULL);

        verify(repository)
                .findAttributes(
                        eq(PolicyAttributeScopeCode.PRODUCT), idsCaptor.capture(), maskedCaptor.capture(), eq(true));
        assertThat(idsCaptor.getValue()).containsExactly(1L);
        assertThat(maskedCaptor.getValue()).containsExactly("population_risk_tags");

        verify(repository)
                .findAttributes(
                        eq(PolicyAttributeScopeCode.ORGANISATION),
                        idsCaptor.capture(),
                        maskedCaptor.capture(),
                        eq(true));
        assertThat(idsCaptor.getValue()).containsExactly(10L);
        assertThat(maskedCaptor.getValue()).containsExactly("authorised_classifications");
    }

    @Test
    void assemble_subscribedBy_isPerOrganisationWithItsConsumerCountAndEarliestGrant() {
        when(repository.findSubscribingOrganisations(anySet()))
                .thenReturn(List.of(
                        new SubscribingOrganisation(1L, "BCC", "Borough Council", 2, EARLIEST),
                        new SubscribingOrganisation(1L, "HEG", "Heritage England", 1, LATER),
                        new SubscribingOrganisation(2L, "BCC", "Borough Council", 3, LATER)));
        when(repository.findGrants(anySet()))
                .thenReturn(List.of(grant(40L, 1L, 30L), grant(41L, 1L, 31L), grant(42L, 2L, 30L)));

        List<DiscoveredProductDTO> products =
                assembler.assemble(List.of(row(1L), row(2L)), ProductSearchContract.open(), ProductProjection.FULL);

        assertThat(products.get(0).subscribedBy())
                .containsExactly(
                        new DiscoveredProductDTO.SubscribingOrganisation("BCC", "Borough Council", 2, EARLIEST),
                        new DiscoveredProductDTO.SubscribingOrganisation("HEG", "Heritage England", 1, LATER));
        assertThat(products.get(1).subscribedBy())
                .containsExactly(new DiscoveredProductDTO.SubscribingOrganisation("BCC", "Borough Council", 3, LATER));
        // The grants of a product - three of them, each with a validity - leave subscribedBy untouched.
        assertThat(products.get(0).consumers()).hasSize(2);
        assertThat(DiscoveredProductDTO.SubscribingOrganisation.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("key", "name", "consumers", "since");
    }

    @Test
    void assemble_fieldColumnAbsentFromTheRow_isNullOnTheProduct() {
        Map<String, Object> row = row(1L);
        row.remove(ProductField.SOURCE.alias());
        row.remove(ProductField.PRODUCER_ACTIVE.alias());

        DiscoveredProductDTO product = assembler
                .assemble(List.of(row), ProductSearchContract.open(), ProductProjection.FULL)
                .get(0);

        assertThat(product.source()).isNull();
        assertThat(product.name()).isEqualTo("Flood risk");
        assertThat(product.producer().active()).isNull();
        assertThat(product.producer().name()).isEqualTo("Flood producer");
    }

    // -----------------------------------------------------------------------------------------
    // The projection: a search result carries the product itself and no blocks
    // -----------------------------------------------------------------------------------------

    /**
     * The efficiency and non-disclosure claim of {@link ProductProjection#SUMMARY}: a block outside
     * the projection is not queried, whatever the contract permits - so not even an unmask rule
     * that applies to this very row brings one back.
     */
    @Test
    void assemble_summaryProjection_loadsNoBlockEvenWhenTheContractShowsThem() throws JsonProcessingException {
        ProductSearchContract contract = enforcing(
                """
                {"visible_fields": ["name", "description", "type", "organisation", "producer",
                                    "consumers", "subscribedBy", "policyAttributes"],
                 "unmask_when": [{"names": ["consumers", "subscribedBy", "policyAttributes"],
                                  "when": {"type": "literal", "value": true}}]}""");
        Map<String, Object> row = row(1L);
        row.put(ProductSearchQuery.unmaskColumn(0), true);

        assembler.assemble(List.of(row), contract, ProductProjection.SUMMARY);

        // Nothing is asked for at all. The contract shows every block and an unmask rule fires on
        // this very row, so the only thing withholding them is the projection - and it withholds
        // them by never asking, which is what makes a search cost no block query.
        verifyNoInteractions(repository);
    }

    @Test
    void assemble_summaryProjection_returnsTheProductAndWhoseItIs() {
        List<DiscoveredProductDTO> products =
                assembler.assemble(List.of(summaryRow(1L)), ProductSearchContract.open(), ProductProjection.SUMMARY);

        DiscoveredProductDTO product = products.get(0);
        assertThat(product.id()).isEqualTo(1L);
        assertThat(product.name()).isEqualTo("Flood risk");
        assertThat(product.description()).isEqualTo("Flood risk of every property");
        assertThat(product.type()).isEqualTo("topic");

        // Whose product it is, from columns the search already joins for.
        assertThat(product.organisation()).isNotNull();
        assertThat(product.organisation().key()).isEqualTo("ENV");
        assertThat(product.organisation().name()).isEqualTo("Environment Agency");
        // Its attributes are a query of their own, so a search does not carry them. Null rather
        // than an empty map: "not carried here" is not the same claim as "this organisation has
        // none".
        assertThat(product.organisation().attributes()).isNull();

        // Absent from the JSON rather than empty: nothing else was asked for.
        assertThat(product.producer()).isNull();
        assertThat(product.consumers()).isNull();
        assertThat(product.subscribedBy()).isNull();
        assertThat(product.attributes()).isNull();
        // The columns SUMMARY does not select are not in the row, so they read as absent too.
        assertThat(product.topic()).isNull();
        assertThat(product.source()).isNull();
    }

    private ProductSearchContract enforcing(String detailsJson) throws JsonProcessingException {
        ProductDiscoveryPolicyDecisionDetails details =
                objectMapper.readValue(detailsJson, ProductDiscoveryPolicyDecisionDetails.class);
        PolicyDecision<ProductDiscoveryPolicyDecisionDetails> decision =
                new PolicyDecision<>(true, List.of(), PolicyProvenance.NONE, details);
        return ProductSearchContract.enforcing(decision);
    }

    /** One page row, keyed by the column aliases of {@link ProductSearchQuery} and {@link ProductField}. */
    private static Map<String, Object> row(long productId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(ProductSearchQuery.ID, productId);
        row.put(ProductSearchQuery.ORGANISATION_ID, 10L);
        row.put(ProductSearchQuery.PRODUCER_ID, 20L);
        row.put(ProductField.NAME.alias(), "Flood risk");
        row.put(ProductField.DESCRIPTION.alias(), "Flood risk of every property");
        row.put(ProductField.TOPIC.alias(), "flood.risk");
        row.put(ProductField.TYPE.alias(), "topic");
        row.put(ProductField.SOURCE.alias(), "https://example.test/flood");
        row.put(ProductField.ORGANISATION_KEY.alias(), "ENV");
        row.put(ProductField.ORGANISATION_NAME.alias(), "Environment Agency");
        row.put(ProductField.PRODUCER_NAME.alias(), "Flood producer");
        row.put(ProductField.PRODUCER_DESCRIPTION.alias(), "Publishes flood data");
        row.put(ProductField.PRODUCER_ACTIVE.alias(), Boolean.TRUE);
        return row;
    }

    /** One page row as {@link ProductProjection#SUMMARY} selects it: the ids and three columns. */
    private static Map<String, Object> summaryRow(long productId) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(ProductSearchQuery.ID, productId);
        row.put(ProductSearchQuery.ORGANISATION_ID, 10L);
        row.put(ProductSearchQuery.PRODUCER_ID, 20L);
        row.put(ProductField.NAME.alias(), "Flood risk");
        row.put(ProductField.DESCRIPTION.alias(), "Flood risk of every property");
        row.put(ProductField.TYPE.alias(), "topic");
        row.put(ProductField.ORGANISATION_KEY.alias(), "ENV");
        row.put(ProductField.ORGANISATION_NAME.alias(), "Environment Agency");
        return row;
    }

    private static Grant grant(long grantId, long productId, long consumerId) {
        return new Grant(
                grantId,
                productId,
                EARLIEST,
                new BigDecimal("30"),
                "cron",
                "0 0 * * *",
                consumerId,
                "Council consumer",
                "BCC",
                "Borough Council");
    }
}
