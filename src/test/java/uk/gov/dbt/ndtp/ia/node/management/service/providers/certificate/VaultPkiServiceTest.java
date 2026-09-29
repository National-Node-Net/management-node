/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.math.BigInteger;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.security.auth.x500.X500Principal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.vault.support.VaultResponse;
import uk.gov.dbt.ndtp.ia.node.management.exception.PkiException;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.CreateCsrRequestDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.CreateCsrResponseDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.CreateKeyResponseDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.IntermediateCertResponseDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.SignCertResponseDTO;
import uk.gov.dbt.ndtp.ia.node.management.utils.cryptography.PemUtil;

@ExtendWith(MockitoExtension.class)
class VaultPkiServiceTest {

    @Mock
    private VaultTemplate vaultTemplate;

    private VaultPkiService vaultPkiService;

    private static final String PKI_MOUNT = "pki-int";

    private static final String DEFAULT_ROLE = "default-role";

    private static final String DEFAULT_TTL = "24h";

    @BeforeEach
    void setUp() {
        vaultPkiService = new VaultPkiService(vaultTemplate, PKI_MOUNT, DEFAULT_ROLE, DEFAULT_TTL);
    }

    @Test
    void createKeyPair_shouldReturnValidKeyPair() {
        CreateKeyResponseDTO response = vaultPkiService.createKeyPair("RSA", 2048);

        assertNotNull(response);
        assertEquals("RSA", response.getAlgorithm());
        assertTrue(response.getPublicKeyPem().contains("BEGIN PUBLIC KEY"));
        assertTrue(response.getPrivateKeyPem().contains("BEGIN PRIVATE KEY"));
    }

    @Test
    void createKeyPair_withNullAlgorithm_shouldUseDefault() {
        CreateKeyResponseDTO response = vaultPkiService.createKeyPair(null, null);

        assertNotNull(response);
        assertEquals("RSA", response.getAlgorithm());
    }

    @Test
    void createKeyPair_withInvalidAlgorithm_shouldThrowPkiException() {
        assertThrows(PkiException.class, () -> vaultPkiService.createKeyPair("INVALID", 1024));
    }

    @Test
    void createCsr_shouldReturnValidCsr() {
        CreateKeyResponseDTO keyPair = vaultPkiService.createKeyPair("RSA", 2048);
        CreateCsrRequestDTO request = CreateCsrRequestDTO.builder()
                .commonName("test.example.com")
                .organization("Test Org")
                .organizationalUnit("Test Unit")
                .country("GB")
                .publicKeyPem(keyPair.getPublicKeyPem())
                .privateKeyPem(keyPair.getPrivateKeyPem())
                .dnsSans(List.of("alt.example.com"))
                .build();

        CreateCsrResponseDTO response = vaultPkiService.createCsr(request);

        assertNotNull(response);
        assertNotNull(response.getCsrId());
        assertTrue(response.getCsrPem().contains("BEGIN CERTIFICATE REQUEST"));
    }

    @Test
    void createCsr_withInvalidKeys_shouldThrowPkiException() {
        CreateCsrRequestDTO request = CreateCsrRequestDTO.builder()
                .commonName("test.example.com")
                .publicKeyPem("INVALID")
                .privateKeyPem("INVALID")
                .build();

        assertThrows(PkiException.class, () -> vaultPkiService.createCsr(request));
    }

    @Test
    @SuppressWarnings("unchecked")
    void signCsr_shouldReturnSignedCertificate() {
        String csrPem = "-----BEGIN CERTIFICATE REQUEST-----\n...\n-----END CERTIFICATE REQUEST-----";
        String role = "test-role";
        String ttl = "24h";

        VaultResponse vaultResponse = mock(VaultResponse.class);
        Map<String, Object> data = Map.of(
                "certificate", "SIGNED_CERT",
                "ca_chain", List.of("CA1", "CA2"),
                "issuing_ca", "ISSUING_CA",
                "serial_number", "SERIAL_123",
                "expiration", 123456789);
        when(vaultResponse.getData()).thenReturn(data);
        when(vaultTemplate.write(eq(PKI_MOUNT + "/sign/" + role), anyMap())).thenReturn(vaultResponse);

        SignCertResponseDTO response = vaultPkiService.signCsr(csrPem, Optional.of(role), Optional.of(ttl));

        assertNotNull(response);
        assertEquals("SIGNED_CERT", response.getCertificate());
        assertEquals("SERIAL_123", response.getSerialNumber());

        ArgumentCaptor<Map<String, Object>> bodyCaptor = ArgumentCaptor.forClass(Map.class);
        verify(vaultTemplate).write(eq(PKI_MOUNT + "/sign/" + role), bodyCaptor.capture());
        Map<String, Object> body = bodyCaptor.getValue();
        assertEquals(csrPem, body.get("csr"));
        assertEquals(ttl, body.get("ttl"));
        assertEquals("pem", body.get("format"));
    }

    @Test
    void signCsr_withNullRole_shouldUseDefaultRole() {
        String csrPem = "-----BEGIN CERTIFICATE REQUEST-----\n...\n-----END CERTIFICATE REQUEST-----";
        String ttl = "24h";

        VaultResponse vaultResponse = mock(VaultResponse.class);
        Map<String, Object> data = Map.of(
                "certificate", "SIGNED_CERT",
                "ca_chain", List.of("CA1", "CA2"),
                "issuing_ca", "ISSUING_CA",
                "serial_number", "SERIAL_123",
                "expiration", 123456789);
        when(vaultResponse.getData()).thenReturn(data);
        when(vaultTemplate.write(eq(PKI_MOUNT + "/sign/" + DEFAULT_ROLE), anyMap()))
                .thenReturn(vaultResponse);

        SignCertResponseDTO response = vaultPkiService.signCsr(csrPem, Optional.empty(), Optional.of(ttl));

        assertNotNull(response);
        verify(vaultTemplate).write(eq(PKI_MOUNT + "/sign/" + DEFAULT_ROLE), anyMap());
    }

    @Test
    void signCsr_withNullTtl_shouldUseDefaultTtl() {
        String csrPem = "-----BEGIN CERTIFICATE REQUEST-----\n...\n-----END CERTIFICATE REQUEST-----";
        String role = "test-role";

        VaultResponse vaultResponse = mock(VaultResponse.class);
        Map<String, Object> data = Map.of(
                "certificate", "SIGNED_CERT",
                "ca_chain", List.of("CA1", "CA2"),
                "issuing_ca", "ISSUING_CA",
                "serial_number", "SERIAL_123",
                "expiration", 123456789);
        when(vaultResponse.getData()).thenReturn(data);
        when(vaultTemplate.write(eq(PKI_MOUNT + "/sign/" + role), anyMap())).thenReturn(vaultResponse);

        SignCertResponseDTO response = vaultPkiService.signCsr(csrPem, Optional.of(role), Optional.empty());

        assertNotNull(response);
        verify(vaultTemplate)
                .write(
                        eq(PKI_MOUNT + "/sign/" + role),
                        argThat((Map<String, Object> m) -> DEFAULT_TTL.equals(m.get("ttl"))));
    }

    @Test
    void signCsr_withEmptyCsr_shouldThrowPkiException() {
        Optional<String> role = Optional.of("role");
        Optional<String> ttl = Optional.of("24h");
        assertThrows(PkiException.class, () -> vaultPkiService.signCsr("", role, ttl));
    }

    @Test
    void signCsr_withEmptyRole_shouldThrowPkiException() {
        Optional<String> role = Optional.of("");
        Optional<String> ttl = Optional.of("24h");
        assertThrows(PkiException.class, () -> vaultPkiService.signCsr("csr", role, ttl));
    }

    @Test
    void signCsr_whenVaultReturnsNull_shouldThrowPkiException() {
        when(vaultTemplate.write(anyString(), anyMap())).thenReturn(null);
        Optional<String> role = Optional.of("role");
        Optional<String> ttl = Optional.of("24h");
        assertThrows(PkiException.class, () -> vaultPkiService.signCsr("csr", role, ttl));
    }

    @Test
    void getIntermediateCertificate_shouldReturnCertAndChain() {
        String certPem = "-----BEGIN CERTIFICATE-----\nFAKE_CERT\n-----END CERTIFICATE-----";

        VaultResponse certResp = mock(VaultResponse.class);
        when(certResp.getData()).thenReturn(Map.of("certificate", certPem));

        VaultResponse chainResp = mock(VaultResponse.class);
        when(chainResp.getData()).thenReturn(Map.of("ca_chain", List.of("CHAIN_1")));

        when(vaultTemplate.read(PKI_MOUNT + "/cert/ca")).thenReturn(certResp);
        when(vaultTemplate.read(PKI_MOUNT + "/cert/ca_chain")).thenReturn(chainResp);

        X509Certificate mockX509 = mock(X509Certificate.class);
        when(mockX509.getSubjectX500Principal()).thenReturn(new X500Principal("CN=test-cert"));
        when(mockX509.getIssuerX500Principal()).thenReturn(new X500Principal("CN=root-ca"));
        when(mockX509.getSerialNumber()).thenReturn(new BigInteger("12345"));
        when(mockX509.getNotBefore()).thenReturn(new Date());
        when(mockX509.getNotAfter()).thenReturn(new Date());
        when(mockX509.getSigAlgName()).thenReturn("SHA256withRSA");
        when(mockX509.getVersion()).thenReturn(3);

        try (MockedStatic<PemUtil> mockedPemUtil = mockStatic(PemUtil.class)) {
            mockedPemUtil.when(() -> PemUtil.parseCertificate(certPem)).thenReturn(mockX509);

            IntermediateCertResponseDTO response = vaultPkiService.getIntermediateCertificate();

            assertNotNull(response);
            assertEquals(certPem, response.getCertificate());
            assertNotNull(response.getInfo());
            assertEquals("CN=test-cert", response.getInfo().getSubject());
        }

        verify(vaultTemplate).read(PKI_MOUNT + "/cert/ca");
        verify(vaultTemplate).read(PKI_MOUNT + "/cert/ca_chain");
    }

    @Test
    void getIntermediateCertificate_whenParsingFails_shouldThrowPkiException() {
        VaultResponse certResp = mock(VaultResponse.class);
        when(certResp.getData()).thenReturn(Map.of("certificate", "INVALID_PEM"));

        VaultResponse chainResp = mock(VaultResponse.class);
        when(chainResp.getData()).thenReturn(Map.of("ca_chain", List.of("CHAIN_1")));

        when(vaultTemplate.read(PKI_MOUNT + "/cert/ca")).thenReturn(certResp);
        when(vaultTemplate.read(PKI_MOUNT + "/cert/ca_chain")).thenReturn(chainResp);

        assertThrows(PkiException.class, () -> vaultPkiService.getIntermediateCertificate());
    }

    @Test
    void getIntermediateCertificate_whenVaultReadFails_shouldThrowPkiException() {
        when(vaultTemplate.read(anyString())).thenReturn(null);
        assertThrows(PkiException.class, () -> vaultPkiService.getIntermediateCertificate());
    }
}
