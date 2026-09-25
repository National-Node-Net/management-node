/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class SslPropertyInitializer {

    @Value("${application.client.key-store}")
    private String keyStore;

    @Value("${application.client.key-store-password}")
    private String keyStorePassword;

    @Value("${application.client.keyStoreType:JKS}")
    private String keyStoreType;

    @Value("${server.ssl.trust-store}")
    private String trustStore;

    @Value("${server.ssl.trust-store-password}")
    private String trustStorePassword;

    @Value("${server.ssl.trust-store-type:JKS}")
    private String trustStoreType;

    @PostConstruct
    public void init() {
        System.setProperty("javax.net.ssl.keyStore", keyStore);
        System.setProperty("javax.net.ssl.keyStorePassword", keyStorePassword);
        System.setProperty("javax.net.ssl.keyStoreType", keyStoreType);

        System.setProperty("javax.net.ssl.trustStore", trustStore);
        System.setProperty("javax.net.ssl.trustStorePassword", trustStorePassword);
        System.setProperty("javax.net.ssl.trustStoreType", trustStoreType);
    }
}
