/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.controller.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import uk.gov.dbt.ndtp.ia.node.management.exception.AccessRejectedException;
import uk.gov.dbt.ndtp.ia.node.management.exception.CertificateAlreadyRevokedException;
import uk.gov.dbt.ndtp.ia.node.management.exception.CertificateRevocationRejectedException;
import uk.gov.dbt.ndtp.ia.node.management.exception.CertificateRevocationRejectedException.Reason;
import uk.gov.dbt.ndtp.ia.node.management.exception.PkiCertificateNotFoundException;
import uk.gov.dbt.ndtp.ia.node.management.exception.PkiPermissionDeniedException;
import uk.gov.dbt.ndtp.ia.node.management.exception.PkiUnavailableException;
import uk.gov.dbt.ndtp.ia.node.management.exception.handlers.GlobalExceptionHandler;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.RevokeCertificateResponseDTO;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.certificate.RevocationReason;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.CertificateRevocationProvider;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.CertificateSigningProvider;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.VaultPkiService;

/**
 * {@code POST /api/v1/certificate/revoke} through Spring MVC: body validation, the JSON of the response, and the
 * status and body of every error. The role check is covered by {@code CertificateControllerSecurityTest}.
 */
class CertificateRevocationWebTest {

    private static final String PATH = "/api/v1/certificate/revoke";
    private static final String SERIAL = "39:dd:2e:90:b7:23:1f:8d:d3:7d:31:c5:1b:da:84:d0:5b:65:31:58";
    private static final String VALID_BODY = "{\"serialNumber\":\"" + SERIAL
            + "\",\"reason\":\"KEY_COMPROMISE\",\"comment\":\"Private key exposed on build server\"}";

    private CertificateRevocationProvider revocationProvider;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        revocationProvider = mock(CertificateRevocationProvider.class);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        CertificateController controller = new CertificateController(
                mock(VaultPkiService.class), mock(CertificateSigningProvider.class), revocationProvider);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    private ResultActions revoke(String body) throws Exception {
        return mockMvc.perform(
                post(PATH).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void providerThrows(RuntimeException e) {
        when(revocationProvider.revoke(any(), any())).thenThrow(e);
    }

    @Test
    void validRequest_shouldReturnTheRevocation() throws Exception {
        when(revocationProvider.revoke(any(), any()))
                .thenReturn(RevokeCertificateResponseDTO.builder()
                        .serialNumber(SERIAL)
                        .status(RevokeCertificateResponseDTO.STATUS_REVOKED)
                        .reason(RevocationReason.KEY_COMPROMISE)
                        .revokedAt(Instant.parse("2026-10-12T13:15:02Z"))
                        .revokedBy("a1b2c3d4-keycloak-user-id")
                        .build());

        revoke(VALID_BODY)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serialNumber").value(SERIAL))
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.reason").value("KEY_COMPROMISE"))
                .andExpect(jsonPath("$.revokedAt").value("2026-10-12T13:15:02Z"))
                .andExpect(jsonPath("$.revokedBy").value("a1b2c3d4-keycloak-user-id"));
    }

    @Test
    void hyphenSeparatedSerial_shouldBeAccepted() throws Exception {
        when(revocationProvider.revoke(any(), any()))
                .thenReturn(RevokeCertificateResponseDTO.builder().build());

        revoke("{\"serialNumber\":\"39-DD-2E\",\"reason\":\"SUPERSEDED\"}").andExpect(status().isOk());
    }

    @Test
    void missingSerial_shouldBeABadRequestNamingTheField() throws Exception {
        assertBadRequestNaming("{\"reason\":\"KEY_COMPROMISE\"}", "serialNumber");
    }

    @Test
    void serialWithoutSeparators_shouldBeABadRequestNamingTheField() throws Exception {
        assertBadRequestNaming("{\"serialNumber\":\"39dd2e\",\"reason\":\"KEY_COMPROMISE\"}", "serialNumber");
    }

    @Test
    void serialThatIsNotHex_shouldBeABadRequestNamingTheField() throws Exception {
        assertBadRequestNaming("{\"serialNumber\":\"zz:01\",\"reason\":\"KEY_COMPROMISE\"}", "serialNumber");
    }

    @Test
    void missingReason_shouldBeABadRequestNamingTheField() throws Exception {
        assertBadRequestNaming("{\"serialNumber\":\"39:dd\"}", "reason");
    }

    @Test
    void unknownReason_shouldBeABadRequestNamingTheField() throws Exception {
        assertBadRequestNaming("{\"serialNumber\":\"39:dd\",\"reason\":\"LOST\"}", "reason");
    }

    @Test
    void commentOfFiveHundredCharacters_shouldBeAccepted() throws Exception {
        when(revocationProvider.revoke(any(), any()))
                .thenReturn(RevokeCertificateResponseDTO.builder().build());

        revoke("{\"serialNumber\":\"39:dd\",\"reason\":\"KEY_COMPROMISE\",\"comment\":\"" + "x".repeat(500) + "\"}")
                .andExpect(status().isOk());
    }

    @Test
    void commentAboveFiveHundredCharacters_shouldBeABadRequestNamingTheField() throws Exception {
        assertBadRequestNaming(
                "{\"serialNumber\":\"39:dd\",\"reason\":\"KEY_COMPROMISE\",\"comment\":\"" + "x".repeat(501) + "\"}",
                "comment");
    }

    private void assertBadRequestNaming(String body, String field) throws Exception {
        String message = revoke(body)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errorId").isNotEmpty())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(message).contains(field);
        verifyNoInteractions(revocationProvider);
    }

    @Test
    void certificateOfAnotherOrganisation_shouldBeForbidden() throws Exception {
        providerThrows(new AccessRejectedException(
                "The certificate does not belong to the caller's organisation", "error-id"));

        revoke(VALID_BODY)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("The certificate does not belong to the caller's organisation"));
    }

    @Test
    void unknownSerial_shouldBeNotFound() throws Exception {
        providerThrows(new CertificateRevocationRejectedException(Reason.CERTIFICATE_NOT_FOUND, "No such serial"));

        revoke(VALID_BODY)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("No such serial"));
    }

    @Test
    void alreadyRevoked_shouldConflictWithTheOriginalRevocation() throws Exception {
        providerThrows(new CertificateAlreadyRevokedException(Instant.parse("2026-10-01T08:00:00Z"), "first-admin"));

        revoke(VALID_BODY)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").value("Certificate is already revoked"))
                .andExpect(jsonPath("$.errorId").isNotEmpty())
                .andExpect(jsonPath("$.revokedAt").value("2026-10-01T08:00:00Z"))
                .andExpect(jsonPath("$.revokedBy").value("first-admin"));
    }

    @Test
    void expiredCertificate_shouldConflict() throws Exception {
        providerThrows(new CertificateRevocationRejectedException(Reason.CERTIFICATE_EXPIRED, "expired"));

        revoke(VALID_BODY)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.revokedAt").doesNotExist());
    }

    @Test
    void rateLimited_shouldBeTooManyRequestsWithRetryAfterInWholeSeconds() throws Exception {
        providerThrows(new CertificateRevocationRejectedException(
                Reason.RATE_LIMITED, "Too many revocation requests", Duration.ofMillis(40_200)));

        revoke(VALID_BODY).andExpect(status().isTooManyRequests()).andExpect(header().string("Retry-After", "41"));
    }

    @Test
    void vaultRefusesTheSerial_shouldBeBadGatewayWithoutVaultDetails() throws Exception {
        providerThrows(new PkiCertificateNotFoundException("certificate with serial 39:dd not found", null));

        revoke(VALID_BODY)
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.message").value("The certificate authority rejected the request"));
    }

    @Test
    void vaultPermissionDenied_shouldBeBadGateway() throws Exception {
        providerThrows(new PkiPermissionDeniedException("permission denied", null));

        revoke(VALID_BODY).andExpect(status().isBadGateway());
    }

    @Test
    void vaultUnreachable_shouldBeServiceUnavailable() throws Exception {
        providerThrows(new PkiUnavailableException("Connection refused", null));

        revoke(VALID_BODY)
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message")
                        .value("The certificate authority is unavailable; nothing was changed, try again later"));
    }
}
