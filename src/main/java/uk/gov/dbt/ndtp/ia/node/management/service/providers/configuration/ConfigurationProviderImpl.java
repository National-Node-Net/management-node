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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.*;
import uk.gov.dbt.ndtp.ia.node.management.service.data.ConsumerService;
import uk.gov.dbt.ndtp.ia.node.management.service.data.PolicyAttributeScope;
import uk.gov.dbt.ndtp.ia.node.management.service.data.PolicyAttributeService;
import uk.gov.dbt.ndtp.ia.node.management.service.data.ProducerService;
import uk.gov.dbt.ndtp.ia.node.management.service.data.ProductConsumerService;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.CertificateValidationProvider;

/**
 * Implementation of {@link ConfigurationProvider} that retrieves configuration from database services.
 */
@Service
public class ConfigurationProviderImpl implements ConfigurationProvider {

    private final ConsumerService consumerService;

    private final ProductConsumerService productConsumerService;

    private final ProducerService producerService;

    private final CertificateValidationProvider certificateValidationProvider;

    private final PolicyAttributeService policyAttributeService;

    /**
     * Constructs a new ConfigurationProviderImpl with required services.
     *
     * @param consumerService the consumer service
     * @param consumerAllowedDataProviders the product consumer service
     * @param producerService the producer service
     * @param certificateValidationProvider the certificate validation provider
     * @param policyAttributeService resolves policy attributes for the producer config response
     */
    public ConfigurationProviderImpl(
            ConsumerService consumerService,
            ProductConsumerService consumerAllowedDataProviders,
            ProducerService producerService,
            CertificateValidationProvider certificateValidationProvider,
            PolicyAttributeService policyAttributeService) {

        this.consumerService = consumerService;
        this.productConsumerService = consumerAllowedDataProviders;
        this.producerService = producerService;
        this.certificateValidationProvider = certificateValidationProvider;
        this.policyAttributeService = policyAttributeService;
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

        return ProducerConfigDTO.builder()
                .clientId(clientId)
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
                    .addAll(policyAttributeService.findAttributes(producer.getId(), PolicyAttributeScope.PRODUCER));

            for (ProductDTO product : producer.getProducts()) {
                for (ConsumerDTO consumer : product.getConsumers()) {
                    consumer.getPolicyAttributes()
                            .addAll(policyAttributeService.findAttributes(
                                    consumer.getId(), PolicyAttributeScope.CONSUMER));
                    consumer.getOrganisationPolicyAttributes()
                            .addAll(policyAttributeService.findAttributes(
                                    consumer.getOrgId(), PolicyAttributeScope.ORGANISATION));
                }
                for (ProductConsumerDTO configuration : product.getConfigurations()) {
                    configuration
                            .getPolicyAttributes()
                            .addAll(policyAttributeService.findAttributes(
                                    configuration.getId(), PolicyAttributeScope.SUBSCRIPTION));
                }
            }
        }
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
