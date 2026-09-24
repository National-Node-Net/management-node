/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */
package uk.gov.dbt.ndtp.ia.node.management.service.discovery;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.product.DiscoveredProductDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.product.ProductPolicyContractDetails.UnmaskRule;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.configuration.ProductDiscoveryRepository;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.configuration.ProductDiscoveryRepository.Grant;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.configuration.ProductDiscoveryRepository.SubscribingOrganisation;
import uk.gov.dbt.ndtp.ia.node.management.service.data.PolicyAttributeScopeCode;

/**
 * Turns the rows of a search page into {@link DiscoveredProductDTO}s, loading the related blocks -
 * attributes, consumers, subscribing organisations - for the whole page at once.
 *
 * <p>Nothing is loaded and then removed. A block is fetched only for the products on which the
 * contract shows it, and the attribute queries leave masked and sensitive attributes out
 * themselves, so there is no stripping step that a later change could forget.
 */
@Component
public class DiscoveredProductAssembler {

    private final ProductDiscoveryRepository repository;

    public DiscoveredProductAssembler(ProductDiscoveryRepository repository) {
        this.repository = repository;
    }

    /**
     * @param rows the page, as returned by {@link ProductDiscoveryRepository#findPage}
     * @param contract what the caller may see
     * @param projection how much of a product this endpoint returns; a block outside it is not
     *     queried at all
     */
    public List<DiscoveredProductDTO> assemble(
            List<Map<String, Object>> rows, ProductSearchContract contract, ProductProjection projection) {
        List<PageRow> page =
                rows.stream().map(row -> new PageRow(row, contract, projection)).toList();

        Map<Long, Map<String, Object>> productAttributes = attributes(
                PolicyAttributeScopeCode.PRODUCT,
                page,
                ProductBlock.POLICY_ATTRIBUTES,
                PageRow::productId,
                contract,
                projection);
        Map<Long, Map<String, Object>> organisationAttributes = attributes(
                PolicyAttributeScopeCode.ORGANISATION,
                page,
                ProductBlock.ORGANISATION,
                PageRow::organisationId,
                contract,
                projection);
        Map<Long, Map<String, Object>> producerAttributes = attributes(
                PolicyAttributeScopeCode.PRODUCER,
                page,
                ProductBlock.PRODUCER,
                PageRow::producerId,
                contract,
                projection);

        List<Grant> grants = grants(idsShowing(page, ProductBlock.CONSUMERS, PageRow::productId));
        Map<Long, Map<String, Object>> consumerAttributes =
                attributes(PolicyAttributeScopeCode.CONSUMER, ids(grants, Grant::consumerId), contract, projection);
        Map<Long, Map<String, Object>> subscriptionAttributes =
                attributes(PolicyAttributeScopeCode.SUBSCRIPTION, ids(grants, Grant::grantId), contract, projection);
        List<SubscribingOrganisation> subscribing =
                subscribingOrganisations(idsShowing(page, ProductBlock.SUBSCRIBED_BY, PageRow::productId));

        return page.stream()
                .map(row -> DiscoveredProductDTO.builder()
                        .id(row.productId())
                        .name(row.text(ProductField.NAME))
                        .description(row.text(ProductField.DESCRIPTION))
                        .topic(row.text(ProductField.TOPIC))
                        .type(row.text(ProductField.TYPE))
                        .source(row.text(ProductField.SOURCE))
                        .attributes(
                                row.shows(ProductBlock.POLICY_ATTRIBUTES)
                                        ? productAttributes.getOrDefault(row.productId(), Map.of())
                                        : null)
                        .organisation(
                                row.shows(ProductBlock.ORGANISATION)
                                        ? new DiscoveredProductDTO.Organisation(
                                                row.text(ProductField.ORGANISATION_KEY),
                                                row.text(ProductField.ORGANISATION_NAME),
                                                attributesOf(
                                                        organisationAttributes,
                                                        row.organisationId(),
                                                        PolicyAttributeScopeCode.ORGANISATION,
                                                        projection))
                                        : null)
                        .producer(
                                row.shows(ProductBlock.PRODUCER)
                                        ? new DiscoveredProductDTO.Producer(
                                                row.text(ProductField.PRODUCER_NAME),
                                                row.text(ProductField.PRODUCER_DESCRIPTION),
                                                (Boolean) row.value(ProductField.PRODUCER_ACTIVE),
                                                producerAttributes.getOrDefault(row.producerId(), Map.of()))
                                        : null)
                        .consumers(
                                row.shows(ProductBlock.CONSUMERS)
                                        ? consumers(row.productId(), grants, consumerAttributes, subscriptionAttributes)
                                        : null)
                        .subscribedBy(
                                row.shows(ProductBlock.SUBSCRIBED_BY)
                                        ? subscribedBy(row.productId(), subscribing)
                                        : null)
                        .build())
                .toList();
    }

    private static List<DiscoveredProductDTO.Consumer> consumers(
            long productId,
            List<Grant> grants,
            Map<Long, Map<String, Object>> consumerAttributes,
            Map<Long, Map<String, Object>> subscriptionAttributes) {
        return grants.stream()
                .filter(grant -> grant.productId() == productId)
                .map(grant -> new DiscoveredProductDTO.Consumer(
                        grant.consumerName(),
                        new DiscoveredProductDTO.Organisation(grant.organisationKey(), grant.organisationName(), null),
                        consumerAttributes.getOrDefault(grant.consumerId(), Map.of()),
                        new DiscoveredProductDTO.Subscription(
                                grant.grantedAt(),
                                grant.validity(),
                                grant.scheduleType(),
                                grant.scheduleExpression(),
                                subscriptionAttributes.getOrDefault(grant.grantId(), Map.of()))))
                .toList();
    }

    private static List<DiscoveredProductDTO.SubscribingOrganisation> subscribedBy(
            long productId, List<SubscribingOrganisation> subscribing) {
        return subscribing.stream()
                .filter(organisation -> organisation.productId() == productId)
                .map(organisation -> new DiscoveredProductDTO.SubscribingOrganisation(
                        organisation.organisationKey(),
                        organisation.organisationName(),
                        organisation.consumers(),
                        organisation.since()))
                .toList();
    }

    /** The attributes of one scope, for the rows of the page that show {@code block}. */
    private Map<Long, Map<String, Object>> attributes(
            PolicyAttributeScopeCode scope,
            List<PageRow> page,
            ProductBlock block,
            Function<PageRow, Long> entityId,
            ProductSearchContract contract,
            ProductProjection projection) {
        return attributes(scope, idsShowing(page, block, entityId), contract, projection);
    }

    private Map<Long, Map<String, Object>> attributes(
            PolicyAttributeScopeCode scope,
            Set<Long> entityIds,
            ProductSearchContract contract,
            ProductProjection projection) {
        Set<String> masked = contract.maskedAttributeNames(scope);
        if (!projection.includesAttributesOf(scope)
                || entityIds.isEmpty()
                || masked.contains(ProductSearchContract.ALL_ATTRIBUTES)) {
            return Map.of();
        }
        return repository.findAttributes(scope, entityIds, masked, contract.masksSensitiveAttributes());
    }

    /**
     * The grants on the products that show their consumers - and nothing asked of the repository
     * when none does.
     *
     * <p>Asking for the attributes of no entity, or the grants on no product, would be answered
     * with nothing anyway. Not asking is what makes "a block outside the projection, or one policy
     * masks, is never queried" true here rather than true by the repository's good manners.
     */
    private List<Grant> grants(Set<Long> productIds) {
        return productIds.isEmpty() ? List.of() : repository.findGrants(productIds);
    }

    /** The organisations subscribing to the products that show them; see {@link #grants}. */
    private List<SubscribingOrganisation> subscribingOrganisations(Set<Long> productIds) {
        return productIds.isEmpty() ? List.of() : repository.findSubscribingOrganisations(productIds);
    }

    /**
     * One entity's attributes, or <b>null</b> when this endpoint does not carry that scope's
     * attributes at all - so they are absent from the JSON rather than present and empty. An empty
     * map would say "this organisation has no attributes", which is a different claim.
     */
    private static Map<String, Object> attributesOf(
            Map<Long, Map<String, Object>> loaded,
            Long entityId,
            PolicyAttributeScopeCode scope,
            ProductProjection projection) {
        return projection.includesAttributesOf(scope) ? loaded.getOrDefault(entityId, Map.of()) : null;
    }

    private static Set<Long> idsShowing(List<PageRow> page, ProductBlock block, Function<PageRow, Long> entityId) {
        return page.stream()
                .filter(row -> row.shows(block))
                .map(entityId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static Set<Long> ids(List<Grant> grants, Function<Grant, Long> id) {
        return grants.stream().map(id).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** One row of the page query, read through the column aliases of {@link ProductSearchQuery}. */
    private static final class PageRow {

        private final Map<String, Object> columns;
        private final ProductSearchContract contract;
        private final Set<String> unmasked = new HashSet<>();

        private final ProductProjection projection;

        PageRow(Map<String, Object> columns, ProductSearchContract contract, ProductProjection projection) {
            this.columns = columns;
            this.contract = contract;
            this.projection = projection;
            List<UnmaskRule> rules = contract.unmaskRules();
            for (int i = 0; i < rules.size(); i++) {
                if (Boolean.TRUE.equals(columns.get(ProductSearchQuery.unmaskColumn(i)))) {
                    unmasked.addAll(rules.get(i).names());
                }
            }
        }

        /**
         * Whether this product shows a block. The endpoint has to return it at all, and policy has
         * to permit it - either always, or because an unmask rule applies to this product.
         */
        boolean shows(ProductBlock block) {
            return projection.includes(block)
                    && (contract.isVisible(block.apiName()) || unmasked.contains(block.apiName()));
        }

        Long productId() {
            return id(ProductSearchQuery.ID);
        }

        Long organisationId() {
            return id(ProductSearchQuery.ORGANISATION_ID);
        }

        Long producerId() {
            return id(ProductSearchQuery.PRODUCER_ID);
        }

        /** The value of a field; null when the field was not selected, i.e. is withheld. */
        Object value(ProductField field) {
            return columns.get(field.alias());
        }

        String text(ProductField field) {
            Object value = value(field);
            return value == null ? null : value.toString();
        }

        private Long id(String column) {
            return ((Number) columns.get(column)).longValue();
        }
    }
}
