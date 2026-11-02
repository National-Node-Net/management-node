/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.controller.v1;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.OcspResponseDTO;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp.OcspResponderService;

/**
 * Security tests for OcspController.
 * Verifies that the {@code management-node-access} role is required, using a minimal Spring context with
 * method security enabled.
 */
@SpringJUnitConfig(OcspControllerSecurityTest.Config.class)
class OcspControllerSecurityTest {

    @Configuration
    @EnableMethodSecurity()
    static class Config {
        @Bean
        OcspController ocspController(OcspResponderService responderService) {
            return new OcspController(responderService);
        }
    }

    @MockitoBean
    private OcspResponderService responderService;

    @Autowired
    private OcspController controller;

    private static final byte[] BODY = {0x30, 0x00};

    @Test
    @WithMockUser(authorities = "ROLE_management-node:management-node-access")
    void ocsp_withCorrectRole_shouldSucceed() {
        when(responderService.respond(any(), any())).thenReturn(new OcspResponseDTO(new byte[] {0x30}, 1, null));

        assertDoesNotThrow(() -> controller.ocsp(BODY, new MockHttpServletRequest()));
    }

    @Test
    @WithMockUser(
            authorities = {"ROLE_management-node:access_public_certificates", "ROLE_management-node:sign_certificate"})
    void ocsp_withWrongRoles_shouldThrowAccessDenied() {
        assertThrows(AuthorizationDeniedException.class, () -> controller.ocsp(BODY, new MockHttpServletRequest()));
    }

    @Test
    @WithMockUser(authorities = "ROLE_management-node-access")
    void ocsp_withRealmRoleOfSameName_shouldThrowAccessDenied() {
        assertThrows(AuthorizationDeniedException.class, () -> controller.ocsp(BODY, new MockHttpServletRequest()));
    }

    @Test
    @WithMockUser
    void ocsp_withNoRoles_shouldThrowAccessDenied() {
        assertThrows(AuthorizationDeniedException.class, () -> controller.ocsp(BODY, new MockHttpServletRequest()));
    }
}
