/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.controller.v1;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.CreateCsrRequestDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.CreateCsrResponseDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.CreateKeyResponseDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.IntermediateCertResponseDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.SignCertRequestDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.SignCertResponseDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.jwt.EnhancedPrincipal;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.CertificateSigningProvider;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.VaultPkiService;

/**
 * Security tests for CertificateController.
 * Verifies that @PreAuthorize annotations correctly enforce role-based access.
 *
 * <p>Uses a minimal Spring context that only enables method security and creates
 * the controller bean, so @PreAuthorize checks are active without needing the
 * full application context.
 *
 * @see <a href="https://docs.spring.io/spring-security/reference/servlet/test/method.html">
 *     Spring Security - Testing Method Security</a>
 */
@SpringJUnitConfig(CertificateControllerSecurityTest.Config.class)
class CertificateControllerSecurityTest {

    @Configuration
    @EnableMethodSecurity(prePostEnabled = true)
    static class Config {
        @Bean
        CertificateController certificateController(
                VaultPkiService pkiService, CertificateSigningProvider signingProvider) {
            return new CertificateController(pkiService, signingProvider);
        }
    }

    @MockitoBean
    private VaultPkiService pkiService;

    @MockitoBean
    private CertificateSigningProvider signingProvider;

    @Autowired
    private CertificateController controller;

    private static final EnhancedPrincipal TEST_PRINCIPAL =
            new EnhancedPrincipal("sub", "client-1", "test-organisation");
    private static final SignCertRequestDTO SIGN_REQUEST =
            SignCertRequestDTO.builder().csr("CSR").build();
    private static final CreateCsrRequestDTO CSR_REQUEST = new CreateCsrRequestDTO();

    @Test
    @WithMockUser(authorities = "ROLE_management-node:create_keys")
    void createKeyPair_withCorrectRole_shouldSucceed() {
        when(pkiService.createKeyPair(anyString(), any()))
                .thenReturn(CreateKeyResponseDTO.builder()
                        .algorithm("RSA")
                        .publicKeyPem("PUB")
                        .privateKeyPem("PRIV")
                        .build());

        assertDoesNotThrow(() -> controller.createKeyPair());
    }

    @Test
    @WithMockUser(
            authorities = {"ROLE_management-node:sign_certificate", "ROLE_management-node:access_public_certificates"})
    void createKeyPair_withWrongRoles_shouldThrowAccessDenied() {
        assertThrows(AuthorizationDeniedException.class, () -> controller.createKeyPair());
    }

    @Test
    @WithMockUser
    void createKeyPair_withNoRoles_shouldThrowAccessDenied() {
        assertThrows(AuthorizationDeniedException.class, () -> controller.createKeyPair());
    }

    @Test
    @WithMockUser(authorities = "ROLE_management-node:create_keys")
    void createCsr_withCorrectRole_shouldSucceed() {
        when(pkiService.createCsr(any(CreateCsrRequestDTO.class)))
                .thenReturn(new CreateCsrResponseDTO("id", "CSR_PEM"));

        assertDoesNotThrow(() -> controller.createCsr(CSR_REQUEST));
    }

    @Test
    @WithMockUser(authorities = "ROLE_management-node:access_public_certificates")
    void createCsr_withWrongRole_shouldThrowAccessDenied() {
        assertThrows(AuthorizationDeniedException.class, () -> controller.createCsr(CSR_REQUEST));
    }

    @Test
    @WithMockUser
    void createCsr_withNoRoles_shouldThrowAccessDenied() {
        assertThrows(AuthorizationDeniedException.class, () -> controller.createCsr(CSR_REQUEST));
    }

    @Test
    @WithMockUser(authorities = "ROLE_management-node:sign_certificate")
    void signCsr_withCorrectRole_shouldSucceed() {
        when(signingProvider.signAndRecord(anyString(), anyString()))
                .thenReturn(SignCertResponseDTO.builder()
                        .certificate("CERT")
                        .serialNumber("123")
                        .build());

        assertDoesNotThrow(() -> controller.signCsr(SIGN_REQUEST, TEST_PRINCIPAL));
    }

    @Test
    @WithMockUser(authorities = {"ROLE_management-node:create_keys", "ROLE_management-node:access_public_certificates"})
    void signCsr_withWrongRoles_shouldThrowAccessDenied() {
        assertThrows(AuthorizationDeniedException.class, () -> controller.signCsr(SIGN_REQUEST, TEST_PRINCIPAL));
    }

    @Test
    @WithMockUser
    void signCsr_withNoRoles_shouldThrowAccessDenied() {
        assertThrows(AuthorizationDeniedException.class, () -> controller.signCsr(SIGN_REQUEST, TEST_PRINCIPAL));
    }

    @Test
    @WithMockUser(authorities = "ROLE_management-node:access_public_certificates")
    void getIntermediateCertificate_withCorrectRole_shouldSucceed() {
        when(pkiService.getIntermediateCertificate())
                .thenReturn(IntermediateCertResponseDTO.builder()
                        .certificate("CERT")
                        .build());

        assertDoesNotThrow(() -> controller.getIntermediateCertificate());
    }

    @Test
    @WithMockUser(authorities = "ROLE_management-node:create_keys")
    void getIntermediateCertificate_withWrongRole_shouldThrowAccessDenied() {
        assertThrows(AuthorizationDeniedException.class, () -> controller.getIntermediateCertificate());
    }

    @Test
    @WithMockUser
    void getIntermediateCertificate_withNoRoles_shouldThrowAccessDenied() {
        assertThrows(AuthorizationDeniedException.class, () -> controller.getIntermediateCertificate());
    }
}
