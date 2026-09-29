/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class SslPropertyInitializerTest {

    @AfterEach
    void tearDown() {
        System.clearProperty("javax.net.ssl.keyStore");
        System.clearProperty("javax.net.ssl.keyStorePassword");
        System.clearProperty("javax.net.ssl.keyStoreType");
        System.clearProperty("javax.net.ssl.trustStore");
        System.clearProperty("javax.net.ssl.trustStorePassword");
        System.clearProperty("javax.net.ssl.trustStoreType");
    }

    @Test
    void init_shouldSetSystemProperties() {
        // Arrange
        SslPropertyInitializer initializer = new SslPropertyInitializer();
        ReflectionTestUtils.setField(initializer, "keyStore", "test-keystore");
        ReflectionTestUtils.setField(initializer, "keyStorePassword", "test-keystore-password");
        ReflectionTestUtils.setField(initializer, "keyStoreType", "PKCS12");
        ReflectionTestUtils.setField(initializer, "trustStore", "test-truststore");
        ReflectionTestUtils.setField(initializer, "trustStorePassword", "test-truststore-password");
        ReflectionTestUtils.setField(initializer, "trustStoreType", "JKS");

        // Act
        initializer.init();

        // Assert
        assertEquals("test-keystore", System.getProperty("javax.net.ssl.keyStore"));
        assertEquals("test-keystore-password", System.getProperty("javax.net.ssl.keyStorePassword"));
        assertEquals("PKCS12", System.getProperty("javax.net.ssl.keyStoreType"));
        assertEquals("test-truststore", System.getProperty("javax.net.ssl.trustStore"));
        assertEquals("test-truststore-password", System.getProperty("javax.net.ssl.trustStorePassword"));
        assertEquals("JKS", System.getProperty("javax.net.ssl.trustStoreType"));
    }
}
