/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.configuration;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.configuration.ConsumerConfigDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.configuration.ConsumerDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.configuration.ProducerConfigDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.configuration.ProducerDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.configuration.ProductConsumerDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.configuration.ProductDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.organisation.OrganisationDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.policy.PolicyAttributeDTO;
import uk.gov.dbt.ndtp.ia.node.management.service.data.ConsumerService;
import uk.gov.dbt.ndtp.ia.node.management.service.data.OrganisationService;
import uk.gov.dbt.ndtp.ia.node.management.service.data.PolicyAttributeScopeCode;
import uk.gov.dbt.ndtp.ia.node.management.service.data.PolicyAttributeService;
import uk.gov.dbt.ndtp.ia.node.management.service.data.ProducerService;
import uk.gov.dbt.ndtp.ia.node.management.service.data.ProductConsumerService;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.CertificateValidationProvider;

/**
 * Implementation of {@link ConfigurationProvider} that retrieves configuration from database services.
 */
@Service
@Slf4j
public class ConfigurationProviderImpl implements ConfigurationProvider {

    private final ConsumerService consumerService;

    private final ProductConsumerService productConsumerService;

    private final ProducerService producerService;

    private final CertificateValidationProvider certificateValidationProvider;

    private final PolicyAttributeService policyAttributeService;

    private final OrganisationService organisationService;

    /**
     * Constructs a new ConfigurationProviderImpl with required services.
     *
     * @param consumerService the consumer service
     * @param consumerAllowedDataProviders the product consumer service
     * @param producerService the producer service
     * @param certificateValidationProvider the certificate validation provider
     * @param policyAttributeService resolves policy attributes for the producer config response
     * @param organisationService resolves the organisation carried by each producer and consumer
     */
    public ConfigurationProviderImpl(
            ConsumerService consumerService,
            ProductConsumerService consumerAllowedDataProviders,
            ProducerService producerService,
            CertificateValidationProvider certificateValidationProvider,
            PolicyAttributeService policyAttributeService,
            OrganisationService organisationService) {

        this.consumerService = consumerService;
        this.productConsumerService = consumerAllowedDataProviders;
        this.producerService = producerService;
        this.certificateValidationProvider = certificateValidationProvider;
        this.policyAttributeService = policyAttributeService;
        this.organisationService = organisationService;
    }

    /**
     * Checks if a granted timestamp is still valid based on the validity period.
     *
     * @param grantedTs the timestamp when access was granted
     * @param validity the validity period in days
     * @return true if valid, false otherwise
     */
    private static boolean isValidGrantedTs(Timestamp grantedTs, BigDecimal validity) {
        return grantedTs != null
                && grantedTs
                        .toInstant()
                        .plus(Duration.ofDays(validity.longValue()))
                        .isAfter(Instant.now());
    }

    @Override
    public ConsumerConfigDTO getConsumerConfigByClientId(String clientId, Optional<Long> consumerId) {
        List<ConsumerDTO> consumers = consumerService.findByIdpClientId(clientId);
        if (consumerId.isPresent()) {
            consumers = consumers.stream()
                    .filter(consumer -> consumer.getId().equals(consumerId.get()))
                    .toList();
        }
        List<Long> consumerIds = consumers.stream().map(ConsumerDTO::getId).toList();

        List<ProductConsumerDTO> validProductConsumers = getValidProductConsumers(consumers);
        List<Long> validProductIds = validProductConsumers.stream()
                .map(ProductConsumerDTO::getProductId)
                .toList();

        List<ProducerDTO> producers = producerService.getProducersByConsumerIds(consumerIds).stream()
                .filter(ProducerDTO::getActive)
                .toList();

        producers = filterProducersByActiveCertificate(producers);

        // Filter products of each producer to only those in validProductIds
        if (!validProductIds.isEmpty()) {
            Set<Long> validIdsSet = new HashSet<>(validProductIds);
            producers.forEach(
                    p -> p.getProducts().removeIf(prod -> prod.getId() == null || !validIdsSet.contains(prod.getId())));
        } else {
            // If no valid products, clear products for all producers
            producers.forEach(p -> p.getProducts().clear());
        }

        // finding the products and adding the configurations
        producers.forEach(producer -> producer.getProducts().forEach(product -> {
            List<ProductConsumerDTO> configs = validProductConsumers.stream()
                    .filter(pc -> pc.getProductId().equals(product.getId()))
                    .toList();
            product.setConfigurations(configs);
        }));
        if (consumers.isEmpty()) {
            return ConsumerConfigDTO.builder()
                    .clientId(clientId)
                    .producers(List.of())
                    .build();
        }
        // Identity only: a consumer must not be handed the producer's organisation's policy
        // attributes, which describe what the publishing side is entitled to hold.
        populateOrganisations(producers, false);

        ConsumerDTO firstConsumer = consumers.getFirst();
        return ConsumerConfigDTO.builder()
                .scheduleExpression(firstConsumer.getScheduleExpression())
                .scheduleType(firstConsumer.getScheduleType())
                .clientId(clientId)
                .name(firstConsumer.getName())
                .producers(producers)
                .build();
    }

    /**
     * Retrieves valid product consumers for a list of consumers.
     *
     * @param consumers the list of consumers
     * @return a list of valid product consumer DTOs
     */
    private List<ProductConsumerDTO> getValidProductConsumers(List<ConsumerDTO> consumers) {
        List<ProductConsumerDTO> validProductIds = new ArrayList<>();

        consumers.forEach(consumer -> {
            List<ProductConsumerDTO> list = productConsumerService.findByConsumerId(consumer.getId()).stream()
                    .filter(this::isValidProvider)
                    .toList();

            validProductIds.addAll(list);
        });
        return validProductIds;
    }

    @Override
    public ProducerConfigDTO getProducerConfigByClientId(String clientId, Optional<Long> producerId) {
        List<ProducerDTO> producers = producerService.getProducersByClientId(clientId).stream()
                .filter(ProducerDTO::getActive)
                .toList();
        if (producerId.isPresent()) {
            producers = producers.stream()
                    .filter(producer -> producerId.get().equals(producer.getId()))
                    .toList();
        }
        List<Long> dataProviderIds = collectDataProviderIds(producers);

        // Get allowed consumers (not directly used but might be needed for side effects)
        consumerService.getConsumersOfProviders(dataProviderIds);

        populateConsumersForProducers(producers);
        populatePolicyAttributes(producers);
        populateOrganisations(producers, true);

        return ProducerConfigDTO.builder()
                .clientId(clientId)
                .organisation(configOrganisation(producers))
                .producers(producers)
                .build();
    }

    /**
     * Collects data provider IDs from a list of producers.
     *
     * @param producers the list of producers
     * @return a list of data provider IDs
     */
    private List<Long> collectDataProviderIds(List<ProducerDTO> producers) {
        List<Long> dataProviderIds = new ArrayList<>();

        for (ProducerDTO producer : producers) {
            List<Long> ids =
                    producer.getProducts().stream().map(ProductDTO::getId).toList();
            dataProviderIds.addAll(ids);
        }

        return dataProviderIds;
    }

    /**
     * Resolves consumers for each product and populates them onto the product DTOs,
     * filtering out consumers whose organisations have inactive certificates. Also attaches
     * each product's live subscriptions ({@code product_consumer} rows) as {@code
     * configurations} - previously never populated on this path - since {@link
     * #populatePolicyAttributes} needs a {@link ProductConsumerDTO} instance per subscription to
     * attach {@code SUBSCRIPTION}-scope policy attributes to.
     *
     * @param producers the list of producers whose products need consumer resolution
     */
    private void populateConsumersForProducers(List<ProducerDTO> producers) {
        // Resolve all valid consumers per product
        Map<ProductDTO, List<ConsumerDTO>> consumersByProduct = new LinkedHashMap<>();
        for (ProducerDTO producer : producers) {
            for (ProductDTO product : producer.getProducts()) {
                List<ProductConsumerDTO> validConfigurations =
                        productConsumerService.findByDataProviderId(product.getId()).stream()
                                .filter(this::isValidProvider)
                                .toList();
                product.setConfigurations(validConfigurations);

                List<ConsumerDTO> resolved = validConfigurations.stream()
                        .map(cp -> consumerService.findById(cp.getConsumerId()))
                        .filter(Optional::isPresent)
                        .map(Optional::get)
                        .toList();
                consumersByProduct.put(product, resolved);
            }
        }

        Set<Long> allConsumerOrgIds = consumersByProduct.values().stream()
                .flatMap(List::stream)
                .map(ConsumerDTO::getOrgId)
                .collect(Collectors.toSet());
        Set<Long> activeOrgIds = certificateValidationProvider.findActiveOrganisationIds(allConsumerOrgIds);

        // Populate each product's consumer list, skipping inactive orgs
        for (var entry : consumersByProduct.entrySet()) {
            ProductDTO product = entry.getKey();
            if (product.getConsumers() == null) {
                product.setConsumers(new ArrayList<>());
            }
            for (ConsumerDTO consumer : entry.getValue()) {
                if (activeOrgIds.contains(consumer.getOrgId())) {
                    product.getConsumers().add(consumer);
                }
            }
        }
    }

    /**
     * Attaches live policy attributes to every producer, allowed consumer, consumer organisation,
     * and subscription in the (already assembled) producer config DTO graph - DPAV-3162.
     *
     * @param producers the fully assembled producer DTO graph ({@link #populateConsumersForProducers}
     *     must have already run, so each product's {@code consumers}/{@code configurations} are populated)
     */
    private void populatePolicyAttributes(List<ProducerDTO> producers) {
        for (ProducerDTO producer : producers) {
            producer.getPolicyAttributes()
                    .addAll(policyAttributeService.findAttributes(producer.getId(), PolicyAttributeScopeCode.PRODUCER));

            for (ProductDTO product : producer.getProducts()) {
                product.getPolicyAttributes()
                        .addAll(policyAttributeService.findAttributes(
                                product.getId(), PolicyAttributeScopeCode.PRODUCT));

                for (ConsumerDTO consumer : product.getConsumers()) {
                    consumer.getPolicyAttributes()
                            .addAll(policyAttributeService.findAttributes(
                                    consumer.getId(), PolicyAttributeScopeCode.CONSUMER));
                }
                for (ProductConsumerDTO configuration : product.getConfigurations()) {
                    configuration
                            .getPolicyAttributes()
                            .addAll(policyAttributeService.findAttributes(
                                    configuration.getId(), PolicyAttributeScopeCode.SUBSCRIPTION));
                }
            }
        }
    }

    /**
     * Attaches an {@link OrganisationDTO} - name, unique key, and {@code ORGANISATION}-scope policy
     * attributes - to every producer and to every consumer nested under their products.
     *
     * <p>Organisations are read through {@link OrganisationService} rather than off the entity: the
     * {@code producer.org}/{@code consumer.org} associations are lazy and this runs outside a
     * transaction. Each distinct organisation is fetched once and its attributes resolved once, then
     * a separate DTO instance is handed to each producer/consumer so nothing is shared by reference.
     *
     * @param producers the assembled producer graph
     * @param includePolicyAttributes whether to attach each organisation's {@code ORGANISATION}-scope
     *     policy attributes. False on the consumer config response, which names the producers'
     *     organisations but must not disclose what those organisations are entitled to hold; when
     *     false, no attribute lookup is performed at all.
     */
    private void populateOrganisations(List<ProducerDTO> producers, boolean includePolicyAttributes) {
        List<OrganisationHolder> holders = organisationHolders(producers);

        Set<Long> orgIds = holders.stream()
                .map(OrganisationHolder::orgId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (orgIds.isEmpty()) {
            return;
        }

        Map<Long, OrganisationDTO> organisationsById = organisationService.findByIds(orgIds);
        Map<Long, List<PolicyAttributeDTO>> attributesByOrgId =
                organisationPolicyAttributes(organisationsById.keySet(), includePolicyAttributes);

        holders.forEach(holder -> holder.assign(organisationFor(holder.orgId(), organisationsById, attributesByOrgId)));
    }

    /**
     * Every place in the assembled graph that carries an organisation: each producer, then the
     * consumers nested under its products, in that order.
     *
     * @param producers the assembled producer graph
     * @return one holder per producer and per nested consumer
     */
    private List<OrganisationHolder> organisationHolders(List<ProducerDTO> producers) {
        return producers.stream().flatMap(this::organisationHoldersOf).toList();
    }

    private Stream<OrganisationHolder> organisationHoldersOf(ProducerDTO producer) {
        Stream<OrganisationHolder> consumers = producer.getProducts().stream()
                // The consumer-config path never resolves consumers onto its products, so this is
                // null there rather than an empty list.
                .flatMap(product -> consumersOf(product).stream())
                .map(consumer -> new OrganisationHolder(consumer.getOrgId(), consumer::setOrganisation));

        return Stream.concat(
                Stream.of(new OrganisationHolder(producer.getOrgId(), producer::setOrganisation)), consumers);
    }

    /**
     * Resolves the {@code ORGANISATION}-scope policy attributes of each organisation, once per
     * organisation.
     *
     * @param orgIds the distinct organisations that were resolved
     * @param includePolicyAttributes when false no lookup is performed at all and the map is empty
     * @return attributes keyed by organisation id
     */
    private Map<Long, List<PolicyAttributeDTO>> organisationPolicyAttributes(
            Set<Long> orgIds, boolean includePolicyAttributes) {
        if (!includePolicyAttributes) {
            return Map.of();
        }

        Map<Long, List<PolicyAttributeDTO>> attributesByOrgId = new LinkedHashMap<>();
        for (Long orgId : orgIds) {
            attributesByOrgId.put(
                    orgId, policyAttributeService.findAttributes(orgId, PolicyAttributeScopeCode.ORGANISATION));
        }
        return attributesByOrgId;
    }

    /**
     * One slot in the response graph that an {@link OrganisationDTO} has to be attached to: the
     * organisation id to resolve (null when the owner has none) and where the resulting DTO goes.
     * Lets the graph be walked once to collect ids and once to assign, without repeating the nested
     * producer/product/consumer traversal.
     */
    private record OrganisationHolder(Long orgId, Consumer<OrganisationDTO> setter) {
        void assign(OrganisationDTO organisation) {
            setter.accept(organisation);
        }
    }

    /**
     * The organisation to report at the top of a producer config response: the one its producers
     * belong to. They are all the requesting client's producers, so in practice they share an
     * organisation; if they ever do not, the first is used and the disagreement logged rather than
     * silently picking one.
     *
     * @param producers the assembled producer graph, after {@link #populateOrganisations}
     * @return a copy of the organisation, or null when no producer resolved one
     */
    private OrganisationDTO configOrganisation(List<ProducerDTO> producers) {
        List<OrganisationDTO> resolved = producers.stream()
                .map(ProducerDTO::getOrganisation)
                .filter(Objects::nonNull)
                .toList();

        if (resolved.isEmpty()) {
            return null;
        }

        long distinctKeys =
                resolved.stream().map(OrganisationDTO::getKey).distinct().count();
        if (distinctKeys > 1) {
            log.warn(
                    "Producers for one client span {} organisations; reporting {} on the config response",
                    distinctKeys,
                    resolved.getFirst().getKey());
        }

        OrganisationDTO first = resolved.getFirst();
        OrganisationDTO organisation = OrganisationDTO.builder()
                .name(first.getName())
                .key(first.getKey())
                .build();
        organisation.getPolicyAttributes().addAll(first.getPolicyAttributes());
        return organisation;
    }

    private List<ConsumerDTO> consumersOf(ProductDTO product) {
        return product.getConsumers() == null ? List.of() : product.getConsumers();
    }

    /**
     * Builds a fresh {@link OrganisationDTO} for one owner, or null when the organisation could not
     * be resolved (an orphaned {@code org_id}, which the response should simply omit).
     */
    private OrganisationDTO organisationFor(
            Long orgId,
            Map<Long, OrganisationDTO> organisationsById,
            Map<Long, List<PolicyAttributeDTO>> attributesByOrgId) {
        OrganisationDTO resolved = orgId == null ? null : organisationsById.get(orgId);
        if (resolved == null) {
            return null;
        }

        OrganisationDTO organisation = OrganisationDTO.builder()
                .name(resolved.getName())
                .key(resolved.getKey())
                .build();
        organisation.getPolicyAttributes().addAll(attributesByOrgId.getOrDefault(orgId, List.of()));
        return organisation;
    }

    /**
     * Checks if a provider (product consumer) is valid based on its granted date and validity period.
     *
     * @param provider the product consumer DTO
     * @return true if valid, false otherwise
     */
    private boolean isValidProvider(ProductConsumerDTO provider) {

        if (provider.getValidity() == null || provider.getValidity().compareTo(BigDecimal.ZERO) == 0) return true;

        return isValidGrantedTs(provider.getGrantedTs(), provider.getValidity());
    }

    /**
     * Filters producers to only those whose organisations have active certificates.
     *
     * @param producers the list of producers to filter
     * @return producers with active organisation certificates
     */
    private List<ProducerDTO> filterProducersByActiveCertificate(List<ProducerDTO> producers) {
        Set<Long> producerOrgIds = producers.stream().map(ProducerDTO::getOrgId).collect(Collectors.toSet());

        if (producerOrgIds.isEmpty()) {
            return producers;
        }

        Set<Long> activeOrgIds = certificateValidationProvider.findActiveOrganisationIds(producerOrgIds);

        return producers.stream()
                .filter(p -> activeOrgIds.contains(p.getOrgId()))
                .toList();
    }
}
