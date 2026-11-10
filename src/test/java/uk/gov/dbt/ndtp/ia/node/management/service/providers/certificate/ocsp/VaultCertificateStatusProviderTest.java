/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp.OcspTestPki.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigInteger;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.vault.VaultException;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.vault.support.VaultResponse;
import uk.gov.dbt.ndtp.ia.node.management.exception.CertificateStatusUnavailableException;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.CertificateStatusDTO;

@ExtendWith(MockitoExtension.class)
class VaultCertificateStatusProviderTest {

    private static final BigInteger SERIAL = new BigInteger("3a5f9c", 16);
    private static final String PATH = "pki-int/cert/3a:5f:9c";

    @Mock
    private VaultTemplate vaultTemplate;

    private SimpleMeterRegistry meterRegistry;
    private VaultCertificateStatusProvider provider;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        provider = new VaultCertificateStatusProvider(vaultTemplate, "pki-int", meterRegistry);
    }

    private static VaultResponse response(String certificatePem, Object revocationTime) {
        Map<String, Object> data = new HashMap<>();
        data.put("certificate", certificatePem);
        data.put("revocation_time", revocationTime);
        VaultResponse response = new VaultResponse();
        response.setData(data);
        return response;
    }

    private static String leafPem() {
        return toPem(leafCertificate(SERIAL, CA, CA_KEYS));
    }

    @Test
    void getStatus_notRevoked_shouldReturnGood() {
        when(vaultTemplate.read(PATH)).thenReturn(response(leafPem(), 0));

        assertEquals(CertificateStatusDTO.good(), provider.getStatus(SERIAL, CA));
    }

    @Test
    void getStatus_revoked_shouldReturnRevocationTime() {
        when(vaultTemplate.read(PATH)).thenReturn(response(leafPem(), 1_761_210_000L));

        assertEquals(
                CertificateStatusDTO.revoked(Instant.ofEpochSecond(1_761_210_000L)), provider.getStatus(SERIAL, CA));
    }

    @Test
    void getStatus_revocationTimeMissing_shouldReturnGood() {
        when(vaultTemplate.read(PATH)).thenReturn(response(leafPem(), null));

        assertEquals(CertificateStatusDTO.good(), provider.getStatus(SERIAL, CA));
    }

    @Test
    void getStatus_notFound_shouldReturnUnknown() {
        when(vaultTemplate.read(PATH)).thenReturn(null);

        assertEquals(CertificateStatusDTO.unknown(), provider.getStatus(SERIAL, CA));
    }

    @Test
    void getStatus_responseWithoutCertificate_shouldReturnUnknown() {
        when(vaultTemplate.read(PATH)).thenReturn(response("", 0));

        assertEquals(CertificateStatusDTO.unknown(), provider.getStatus(SERIAL, CA));
    }

    @Test
    void getStatus_certificateFromOtherIssuer_shouldReturnUnknown() {
        String otherPem = toPem(leafCertificate(SERIAL, OTHER_CA, OTHER_CA_KEYS));
        when(vaultTemplate.read(PATH)).thenReturn(response(otherPem, 1_761_210_000L));

        assertEquals(CertificateStatusDTO.unknown(), provider.getStatus(SERIAL, CA));
    }

    @Test
    void getStatus_certificateWithForgedIssuerName_shouldReturnUnknown() {
        // Same issuer DN as the CA, but signed by a different key
        String forgedPem = toPem(leafCertificate(SERIAL, CA, OTHER_CA_KEYS));
        when(vaultTemplate.read(PATH)).thenReturn(response(forgedPem, 0));

        assertEquals(CertificateStatusDTO.unknown(), provider.getStatus(SERIAL, CA));
    }

    @Test
    void getStatus_unparseableCertificate_shouldReturnUnknown() {
        when(vaultTemplate.read(PATH)).thenReturn(response("-----BEGIN CERTIFICATE-----\nAAAA\n", 0));

        assertEquals(CertificateStatusDTO.unknown(), provider.getStatus(SERIAL, CA));
    }

    @Test
    void getStatus_vaultError_shouldThrowUnavailableAndCountError() {
        when(vaultTemplate.read(anyString())).thenThrow(new VaultException("connection refused"));

        assertThrows(CertificateStatusUnavailableException.class, () -> provider.getStatus(SERIAL, CA));
        assertEquals(1.0, meterRegistry.get("ocsp.vault.errors").counter().count());
    }

    @Test
    void getStatus_shouldNotCacheResults() {
        when(vaultTemplate.read(PATH)).thenReturn(response(leafPem(), 0), response(leafPem(), 1_761_210_000L));

        assertEquals(CertificateStatusDTO.good(), provider.getStatus(SERIAL, CA));
        assertEquals(
                CertificateStatusDTO.Status.REVOKED,
                provider.getStatus(SERIAL, CA).getStatus());
        verify(vaultTemplate, times(2)).read(PATH);
    }

    @ParameterizedTest
    @CsvSource({
        "1, 01",
        "ff, ff",
        "0102, 01:02",
        "80, 80",
        "3a5f9c, 3a:5f:9c",
        "7fffffffffffffff01, 7f:ff:ff:ff:ff:ff:ff:ff:01"
    })
    void toVaultSerial_shouldFormatAsColonSeparatedHex(String hex, String expected) {
        assertEquals(expected, VaultCertificateStatusProvider.toVaultSerial(new BigInteger(hex, 16)));
    }
}
