/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.policy;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.configuration.Product;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.configuration.ProductRepository;
import uk.gov.dbt.ndtp.ia.node.management.service.data.PolicyAttributeScopeCode;
import uk.gov.dbt.ndtp.ia.node.management.service.data.PolicyAttributeService;

/**
 * Loads the product a request names, so a rule can decide on the product's own facts rather than
 * on the caller's alone.
 *
 * <p>It claims the {@code product} resource kind and acts only when the body carries a
 * {@code productId}. Product discovery posts search criteria and names no product, so a search
 * loads nothing; subscription posts the product it wants, so that product is read once and its
 * fields and live {@code PRODUCT}-scoped attributes travel with the decision.
 */
@Slf4j
@Component
public class ProductPolicyResourceLoader implements PolicyResourceLoader {

    static final String RESOURCE_KIND = "product";

    private final ProductRepository productRepository;
    private final PolicyAttributeService policyAttributeService;

    public ProductPolicyResourceLoader(
            ProductRepository productRepository, PolicyAttributeService policyAttributeService) {
        this.productRepository = productRepository;
        this.policyAttributeService = policyAttributeService;
    }

    @Override
    public boolean supports(String resourceKind) {
        return RESOURCE_KIND.equals(resourceKind);
    }

    @Override
    public Optional<PolicyResource> load(String resourceKind, String id) {
        return parse(id).flatMap(productId -> productRepository
                .findById(productId)
                .map(product -> PolicyResource.of(
                        resourceKind,
                        String.valueOf(productId),
                        fieldsOf(product),
                        policyAttributeService.findAttributeMap(productId, PolicyAttributeScopeCode.PRODUCT)))
                .or(() -> {
                    // Not an error here. The rule may refuse an unknown product, and the handler
                    // answers 404 either way; failing the decision would report it as a 403.
                    log.debug("Policy input: product {} does not exist, so none is sent to the PDP", productId);
                    return Optional.empty();
                }));
    }

    /**
     * The product's own columns, named as the API names them so a rule reads
     * {@code input.resource.fields.type} rather than a column name. Related rows are represented
     * by the keys a policy can compare: the owning organisation, not the whole object graph.
     */
    private Map<String, Object> fieldsOf(Product product) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("id", product.getId());
        fields.put("name", product.getName());
        fields.put("topic", product.getTopic());
        putIfPresent(fields, "description", product.getDescription());
        putIfPresent(fields, "source", product.getSource());
        if (product.getProductType() != null) {
            fields.put("type", product.getProductType().getName());
        }
        if (product.getProducer() != null) {
            fields.put("producer", product.getProducer().getName());
            if (product.getProducer().getOrg() != null) {
                fields.put("organisation", product.getProducer().getOrg().getOrganisationKey());
            }
        }
        return fields;
    }

    /** Absent rather than null: a rule testing a key should not have to tell the two apart. */
    private static void putIfPresent(Map<String, Object> fields, String name, Object value) {
        if (value != null) {
            fields.put(name, value);
        }
    }

    /** A non-numeric id is the handler's 400 to give, not a reason to fail the decision. */
    private static Optional<Long> parse(String id) {
        try {
            return Optional.of(Long.valueOf(id.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
