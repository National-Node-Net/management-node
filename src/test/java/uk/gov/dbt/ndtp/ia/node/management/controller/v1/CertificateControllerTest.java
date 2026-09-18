/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.controller.v1;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.CertificateInfoDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.CreateCsrRequestDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.CreateCsrResponseDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.CreateKeyResponseDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.IntermediateCertResponseDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.SignCertResponseDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.jwt.EnhancedPrincipal;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.CertificateSigningProvider;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.VaultPkiService;

@ExtendWith(MockitoExtension.class)
class CertificateControllerTest {

    private MockMvc mockMvc;

    @Mock
    private VaultPkiService pkiService;

    @Mock
    private CertificateSigningProvider signingProvider;

    @InjectMocks
    private CertificateController certificateController;

    private static final EnhancedPrincipal TEST_PRINCIPAL =
            new EnhancedPrincipal("subject", "client-1", "test-organisation");

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(certificateController)
                .setCustomArgumentResolvers(new HandlerMethodArgumentResolver() {
                    @Override
                    public boolean supportsParameter(MethodParameter parameter) {
                        return parameter.hasParameterAnnotation(AuthenticationPrincipal.class);
                    }

                    @Override
                    public Object resolveArgument(
                            MethodParameter parameter,
                            ModelAndViewContainer mavContainer,
                            NativeWebRequest webRequest,
                            WebDataBinderFactory binderFactory) {
                        return TEST_PRINCIPAL;
                    }
                })
                .build();
    }

    @Test
    void createKeyPair_shouldReturnKeyPair() throws Exception {
        CreateKeyResponseDTO response = CreateKeyResponseDTO.builder()
                .algorithm("RSA")
                .publicKeyPem("PUB")
                .privateKeyPem("PRIV")
                .build();
        when(pkiService.createKeyPair(anyString(), any())).thenReturn(response);

        mockMvc.perform(get("/api/v1/certificate/keyPair").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.algorithm").value("RSA"));
    }

    @Test
    void createCsr_shouldReturnCsr() throws Exception {
        CreateCsrResponseDTO response = new CreateCsrResponseDTO("id", "CSR_PEM");
        when(pkiService.createCsr(any(CreateCsrRequestDTO.class))).thenReturn(response);

        String jsonRequest = "{\"commonName\":\"test\"}";

        mockMvc.perform(post("/api/v1/certificate/csr/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(jsonRequest))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.csrPem").value("CSR_PEM"));
    }

    @Test
    void signCsr_shouldDelegateToSigningProvider() throws Exception {
        SignCertResponseDTO response = SignCertResponseDTO.builder()
                .certificate("CERT")
                .serialNumber("123")
                .build();
        when(signingProvider.signAndRecord("CSR", "client-1")).thenReturn(response);

        mockMvc.perform(post("/api/v1/certificate/csr/sign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"csr\":\"CSR\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.certificate").value("CERT"));
    }

    @Test
    void getIntermediateCertificate_shouldReturnCertificateWithInfo() throws Exception {
        String mockCert = "-----BEGIN CERTIFICATE-----\nABC\n-----END CERTIFICATE-----";
        CertificateInfoDTO info = CertificateInfoDTO.builder()
                .subject("CN=Test")
                .issuer("CN=Root")
                .serialNumber("12345")
                .build();
        IntermediateCertResponseDTO responseDTO = IntermediateCertResponseDTO.builder()
                .certificate(mockCert)
                .caChain(null)
                .info(info)
                .build();
        when(pkiService.getIntermediateCertificate()).thenReturn(responseDTO);

        mockMvc.perform(get("/api/v1/certificate/intermediate").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.certificate").value(mockCert))
                .andExpect(jsonPath("$.info.subject").value("CN=Test"))
                .andExpect(jsonPath("$.info.issuer").value("CN=Root"))
                .andExpect(jsonPath("$.info.serialNumber").value("12345"));
    }

    @Test
    void issueBootstrapCertificate_shouldReturnZipDownload() throws Exception {
        byte[] zipBytes = new byte[] {0x50, 0x4B, 0x03, 0x04};
        when(signingProvider.issueBootstrapPackage(any(), anyString(), anyString()))
                .thenReturn(zipBytes);

        mockMvc.perform(post("/api/v1/certificate/bootstrap")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"organisationId\":10,\"csr\":\"CSR_PEM\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/zip"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"bootstrap_bundle.zip\""))
                .andExpect(content().bytes(zipBytes));
    }
}
