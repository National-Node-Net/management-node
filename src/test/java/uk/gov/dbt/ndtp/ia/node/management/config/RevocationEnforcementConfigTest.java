/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.status.CertificateStatusService;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.status.FederationCaService;
import uk.gov.dbt.ndtp.ia.node.management.web.certificate.CertificateRevocationFilter;

/** The feature flag {@code application.revocation.enforcement.enabled} in both states, and unset. */
@ExtendWith(OutputCaptureExtension.class)
class RevocationEnforcementConfigTest {

    private static final String DISABLED_WARNING = "Certificate revocation enforcement is DISABLED";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(RevocationConfig.class, RevocationEnforcementConfig.class)
            .withBean(CertificateStatusService.class, () -> mock(CertificateStatusService.class))
            .withBean(FederationCaService.class, () -> mock(FederationCaService.class))
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

    @Test
    void flagNotSet_shouldEnableEnforcement(CapturedOutput output) {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(CertificateRevocationFilter.class);
            assertThat(context).doesNotHaveBean(RevocationEnforcementConfig.DisabledNotice.class);
            assertThat(output).doesNotContain(DISABLED_WARNING);
        });
    }

    @Test
    void flagTrue_shouldRegisterTheFilterForTheSecurityChainOnly() {
        contextRunner
                .withPropertyValues("application.revocation.enforcement.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(CertificateRevocationFilter.class);
                    FilterRegistrationBean<?> registration = context.getBean(FilterRegistrationBean.class);
                    assertThat(registration.getFilter()).isSameAs(context.getBean(CertificateRevocationFilter.class));
                    assertThat(registration.isEnabled())
                            .as("not registered with the servlet container a second time")
                            .isFalse();
                });
    }

    @Test
    void flagFalse_shouldLeaveTheFilterOutAndWarnAtStartup(CapturedOutput output) {
        contextRunner
                .withPropertyValues("application.revocation.enforcement.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(CertificateRevocationFilter.class);
                    assertThat(context).doesNotHaveBean(FilterRegistrationBean.class);
                    assertThat(output).contains("WARN").contains(DISABLED_WARNING);
                });
    }

    @Test
    void exemptIssuers_shouldBeNamedInAWarningAtStartup(CapturedOutput output) {
        contextRunner
                .withPropertyValues("application.revocation.enforcement.exempt-issuers[0]=CN=Dev Root CA,O=Example")
                .run(context -> {
                    assertThat(context).hasSingleBean(CertificateRevocationFilter.class);
                    assertThat(output)
                            .contains("not checked for client certificates issued by")
                            .contains("Dev Root CA");
                });
    }

    @Test
    void failClosedOff_shouldWarnAtStartup(CapturedOutput output) {
        contextRunner
                .withPropertyValues("application.revocation.enforcement.fail-closed=false")
                .run(context -> assertThat(output).contains("fails open"));
    }
}
