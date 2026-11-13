/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.status.CertificateStatusService;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.status.FederationCaService;
import uk.gov.dbt.ndtp.ia.node.management.web.certificate.CertificateRevocationFilter;

/**
 * The feature flag of the revocation check on requests, {@code application.revocation.enforcement.enabled}, on by
 * default. Off, the {@link CertificateRevocationFilter} does not exist, so no request is checked and no status is
 * looked up for one; the OCSP and revocation APIs are not affected. The flag is read at startup only.
 */
@Configuration(proxyBeanMethods = false)
@Slf4j
public class RevocationEnforcementConfig {

    public static final String ENABLED_PROPERTY = "application.revocation.enforcement.enabled";

    private RevocationEnforcementConfig() {}

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = ENABLED_PROPERTY, havingValue = "true", matchIfMissing = true)
    static class Enabled {

        @Bean
        CertificateRevocationFilter certificateRevocationFilter(
                CertificateStatusService statusService,
                FederationCaService federationCa,
                RevocationProperties properties,
                ObjectMapper objectMapper,
                MeterRegistry meterRegistry) {
            RevocationProperties.Enforcement enforcement = properties.enforcement();
            if (!enforcement.exemptIssuers().isEmpty()) {
                log.warn(
                        "Certificate revocation is not checked for client certificates issued by: {}",
                        enforcement.exemptIssuers());
            }
            if (!enforcement.failClosed()) {
                log.warn(
                        "Certificate revocation enforcement fails open: a request is let through when the status of"
                                + " its certificate cannot be determined (application.revocation.enforcement.fail-closed=false)");
            }
            return new CertificateRevocationFilter(
                    statusService, federationCa, properties, objectMapper, meterRegistry, Clock.systemUTC());
        }

        /**
         * Spring Boot registers every {@code Filter} bean with the servlet container. This one belongs in Spring
         * Security's chain only ({@link SecurityConfig}), where its place relative to authentication is fixed.
         */
        @Bean
        FilterRegistrationBean<CertificateRevocationFilter> certificateRevocationFilterRegistration(
                CertificateRevocationFilter filter) {
            FilterRegistrationBean<CertificateRevocationFilter> registration = new FilterRegistrationBean<>(filter);
            registration.setEnabled(false);
            return registration;
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = ENABLED_PROPERTY, havingValue = "false")
    static class Disabled {

        @Bean
        DisabledNotice revocationEnforcementDisabledNotice() {
            log.warn(
                    "Certificate revocation enforcement is DISABLED ({}=false): requests are not checked, and a"
                            + " revoked client certificate is accepted by every API",
                    ENABLED_PROPERTY);
            return new DisabledNotice();
        }
    }

    /** Marks an application context that runs without the revocation check. */
    static final class DisabledNotice {}
}
