/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(OpaProperties.class)
@Slf4j
public class OpaClientConfig {

    @Bean
    public RestClient opaRestClient(OpaProperties properties) {
        log.info(
                "OPA policy enforcement is {} - url={} decisionPath={}",
                properties.enabled() ? "ENABLED" : "DISABLED",
                properties.url(),
                properties.decisionPath());

        // Only worth warning about a URL that will actually be called: with policy enforcement
        // switched off this client is never used, and the warning would sit confusingly beside
        // the "OPA is switched off" one from PolicyDecisionClient.
        if (properties.enabled() && !properties.url().startsWith("https://")) {
            log.warn(
                    "OPA URL {} is not using TLS. This service enforces mTLS for all "
                            + "service-to-service traffic - set OPA_URL to an https:// endpoint "
                            + "in any non-local environment.",
                    properties.url());
        }

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) properties.connectTimeout().toMillis());
        requestFactory.setReadTimeout((int) properties.readTimeout().toMillis());

        return RestClient.builder()
                .baseUrl(properties.url())
                .requestFactory(requestFactory)
                .build();
    }
}
