/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.status.CertificateStatusService;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.status.FederationCaService;
import uk.gov.dbt.ndtp.ia.node.management.web.certificate.CertificateRevocationFilter;

/**
 * Whether the revocation filter is registered, for the feature flag and the mTLS settings, and the startup log line
 * and {@code /actuator/info} entry that state the mode.
 */
@ExtendWith(OutputCaptureExtension.class)
class RevocationEnforcementConfigTest {

    private static final String ACTIVE_LOG = "Certificate revocation enforcement is ACTIVE";
    private static final String DISABLED_WARNING = "Certificate revocation enforcement is DISABLED";

    private final CertificateStatusService statusService = mock(CertificateStatusService.class);
    private final FederationCaService federationCa = mock(FederationCaService.class);

    /** Tomcat with mTLS required, as in application.yml; the tests below change it. */
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(RevocationConfig.class, RevocationEnforcementConfig.class)
            .withBean(CertificateStatusService.class, () -> statusService)
            .withBean(FederationCaService.class, () -> federationCa)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withPropertyValues("server.ssl.enabled=true", "server.ssl.client-auth=need");

    @Test
    void flagNotSet_withMtlsRequired_shouldEnableEnforcement(CapturedOutput output) {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(CertificateRevocationFilter.class);
            assertThat(context.getBean(RevocationEnforcementStatus.class).mode())
                    .isEqualTo(RevocationEnforcementMode.MTLS_REQUIRED);
            assertThat(output).contains(ACTIVE_LOG).contains("mTLS required").doesNotContain(DISABLED_WARNING);
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
    void mtlsOptional_shouldEnableEnforcement(CapturedOutput output) {
        contextRunner.withPropertyValues("server.ssl.client-auth=want").run(context -> {
            assertThat(context).hasSingleBean(CertificateRevocationFilter.class);
            assertThat(context.getBean(RevocationEnforcementStatus.class).mode())
                    .isEqualTo(RevocationEnforcementMode.MTLS_OPTIONAL);
            assertThat(output).contains(ACTIVE_LOG).contains("mTLS optional");
        });
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
            delimiter = '|',
            value = {
                "client-auth none     | server.ssl.client-auth=none           | MTLS_DISABLED    | mTLS disabled",
                "client-auth not set  | server.ssl.client-auth=               | MTLS_DISABLED    | mTLS disabled",
                "SSL disabled         | server.ssl.enabled=false              | SSL_DISABLED     | SSL disabled",
                "feature flag off     | application.revocation.enforcement.enabled=false | DISABLED_BY_FLAG | feature flag off"
            })
    void inactive_shouldLeaveTheFilterOutAndWarnOnceWithTheReason(
            String description,
            String property,
            RevocationEnforcementMode expectedMode,
            String expectedReason,
            CapturedOutput output) {
        contextRunner.withPropertyValues(property).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(CertificateRevocationFilter.class);
            assertThat(context).doesNotHaveBean(FilterRegistrationBean.class);
            assertThat(context.getBean(RevocationEnforcementStatus.class).mode())
                    .isEqualTo(expectedMode);
            assertThat(output.getOut().lines().filter(line -> line.contains(DISABLED_WARNING)))
                    .singleElement()
                    .satisfies(line -> assertThat(line).contains("WARN").contains(expectedReason));
            assertThat(output).doesNotContain(ACTIVE_LOG);
        });
    }

    @Test
    void inactive_shouldNotTouchTheStatusServiceOrTheFederationCa() {
        contextRunner.withPropertyValues("server.ssl.client-auth=none").run(context -> {
            assertThat(context).hasNotFailed();
            verifyNoInteractions(statusService, federationCa);
        });
    }

    @Test
    void actuatorInfo_shouldShowTheActiveMode() {
        contextRunner.run(context -> assertThat(infoOf(context.getBean(RevocationEnforcementStatus.class)))
                .containsEntry("active", true)
                .containsEntry("mode", "MTLS_REQUIRED"));
    }

    @Test
    void actuatorInfo_shouldShowWhyEnforcementIsOff() {
        contextRunner.withPropertyValues("server.ssl.client-auth=none").run(context -> {
            Map<String, Object> info = infoOf(context.getBean(RevocationEnforcementStatus.class));
            assertThat(info).containsEntry("active", false).containsEntry("mode", "MTLS_DISABLED");
            assertThat((String) info.get("reason")).startsWith("mTLS disabled");
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> infoOf(RevocationEnforcementStatus status) {
        Info.Builder builder = new Info.Builder();
        status.contribute(builder);
        return (Map<String, Object>) builder.build().get(RevocationEnforcementStatus.INFO_KEY);
    }
}
