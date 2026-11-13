/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.vault.VaultException;
import org.springframework.vault.authentication.VaultLoginException;
import org.springframework.vault.core.RestOperationsCallback;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.vault.support.VaultResponse;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestOperations;
import uk.gov.dbt.ndtp.ia.node.management.exception.PkiCertificateExpiredException;
import uk.gov.dbt.ndtp.ia.node.management.exception.PkiCertificateNotFoundException;
import uk.gov.dbt.ndtp.ia.node.management.exception.PkiPermissionDeniedException;
import uk.gov.dbt.ndtp.ia.node.management.exception.PkiRejectedException;
import uk.gov.dbt.ndtp.ia.node.management.exception.PkiUnavailableException;

/**
 * {@link VaultPkiService#revokeCertificate}: how each Vault answer is turned into a result or a typed exception,
 * and which failures are retried. The behaviour of a real Vault is covered by {@code OcspVaultIntegrationTest}.
 */
@ExtendWith(MockitoExtension.class)
class VaultPkiServiceRevocationTest {

    private static final BigInteger SERIAL = new BigInteger("39dd2e", 16);
    private static final Map<String, Object> REQUEST = Map.of("serial_number", "39:dd:2e");
    private static final String PATH = "pki-int/revoke";
    private static final long REVOCATION_TIME = 1_760_274_902L;

    @Mock
    private VaultTemplate vaultTemplate;

    @Mock
    private RestOperations restOperations;

    private VaultPkiService service;

    @BeforeEach
    void setUp() {
        service = new VaultPkiService(vaultTemplate, "pki-int", "default-role", "24h", RevocationTestProperties.fast());
    }

    /** Runs the callback passed to doWithSession against the mocked RestOperations, as VaultTemplate does. */
    private void sessionCallsRestOperations() {
        when(vaultTemplate.doWithSession(any()))
                .thenAnswer(invocation ->
                        invocation.<RestOperationsCallback<?>>getArgument(0).doWithRestOperations(restOperations));
    }

    private static VaultResponse response(Object revocationTime, List<String> warnings) {
        Map<String, Object> data = new HashMap<>();
        data.put("revocation_time", revocationTime);
        VaultResponse response = new VaultResponse();
        response.setData(data);
        response.setWarnings(warnings);
        return response;
    }

    private static HttpClientErrorException clientError(HttpStatus status, String body) {
        return HttpClientErrorException.create(
                status,
                status.getReasonPhrase(),
                HttpHeaders.EMPTY,
                body.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
    }

    private static HttpServerErrorException serverError(HttpStatus status) {
        return HttpServerErrorException.create(
                status,
                status.getReasonPhrase(),
                HttpHeaders.EMPTY,
                "{\"errors\":[\"Vault is sealed\"]}".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
    }

    private void verifyAttempts(int attempts) {
        verify(restOperations, times(attempts)).postForObject(eq(PATH), eq(REQUEST), eq(VaultResponse.class));
    }

    @Test
    void revoke_shouldSendTheVaultSerialAndReturnVaultsRevocationTime() {
        sessionCallsRestOperations();
        when(restOperations.postForObject(PATH, REQUEST, VaultResponse.class))
                .thenReturn(response(REVOCATION_TIME, null));

        assertEquals(Instant.ofEpochSecond(REVOCATION_TIME), service.revokeCertificate(SERIAL));
        verifyAttempts(1);
    }

    @Test
    void revoke_serialWithLeadingZeroNibble_shouldKeepItInTheVaultSerial() {
        sessionCallsRestOperations();
        when(restOperations.postForObject(PATH, Map.of("serial_number", "0a:01"), VaultResponse.class))
                .thenReturn(response(REVOCATION_TIME, null));

        assertEquals(Instant.ofEpochSecond(REVOCATION_TIME), service.revokeCertificate(new BigInteger("a01", 16)));
    }

    @Test
    void revoke_expiredCertificate_shouldThrowExpiredWithoutRetrying() {
        sessionCallsRestOperations();
        when(restOperations.postForObject(PATH, REQUEST, VaultResponse.class))
                .thenReturn(response(0, List.of("certificate with serial 39:dd:2e has expired; not revoking")));

        assertThrows(PkiCertificateExpiredException.class, () -> service.revokeCertificate(SERIAL));
        verifyAttempts(1);
    }

    @Test
    void revoke_emptyResponse_shouldThrowExpired() {
        sessionCallsRestOperations();
        when(restOperations.postForObject(PATH, REQUEST, VaultResponse.class)).thenReturn(null);

        assertThrows(PkiCertificateExpiredException.class, () -> service.revokeCertificate(SERIAL));
    }

    @Test
    void revoke_unknownSerial_shouldThrowNotFoundWithoutRetrying() {
        sessionCallsRestOperations();
        when(restOperations.postForObject(PATH, REQUEST, VaultResponse.class))
                .thenThrow(clientError(
                        HttpStatus.BAD_REQUEST, "{\"errors\":[\"certificate with serial 39:dd:2e not found.\"]}"));

        assertThrows(PkiCertificateNotFoundException.class, () -> service.revokeCertificate(SERIAL));
        verifyAttempts(1);
    }

    @Test
    void revoke_permissionDenied_shouldThrowPermissionDeniedWithoutRetrying() {
        sessionCallsRestOperations();
        when(restOperations.postForObject(PATH, REQUEST, VaultResponse.class))
                .thenThrow(clientError(
                        HttpStatus.FORBIDDEN, "{\"errors\":[\"1 error occurred:\\n\\t* permission denied\\n\\n\"]}"));

        assertThrows(PkiPermissionDeniedException.class, () -> service.revokeCertificate(SERIAL));
        verifyAttempts(1);
    }

    @Test
    void revoke_otherClientError_shouldThrowRejectedWithoutRetrying() {
        sessionCallsRestOperations();
        when(restOperations.postForObject(PATH, REQUEST, VaultResponse.class))
                .thenThrow(clientError(HttpStatus.BAD_REQUEST, "{\"errors\":[\"invalid serial number\"]}"));

        PkiRejectedException e = assertThrows(PkiRejectedException.class, () -> service.revokeCertificate(SERIAL));
        assertEquals(PkiRejectedException.class, e.getClass());
        verifyAttempts(1);
    }

    @Test
    void revoke_serverErrorThenSuccess_shouldRetryAndReturnTheRevocationTime() {
        sessionCallsRestOperations();
        when(restOperations.postForObject(PATH, REQUEST, VaultResponse.class))
                .thenThrow(serverError(HttpStatus.SERVICE_UNAVAILABLE))
                .thenReturn(response(REVOCATION_TIME, null));

        assertEquals(Instant.ofEpochSecond(REVOCATION_TIME), service.revokeCertificate(SERIAL));
        verifyAttempts(2);
    }

    @Test
    void revoke_tooManyRequests_shouldBeRetried() {
        sessionCallsRestOperations();
        when(restOperations.postForObject(PATH, REQUEST, VaultResponse.class))
                .thenThrow(clientError(HttpStatus.TOO_MANY_REQUESTS, "{\"errors\":[\"rate limited\"]}"))
                .thenReturn(response(REVOCATION_TIME, null));

        assertEquals(Instant.ofEpochSecond(REVOCATION_TIME), service.revokeCertificate(SERIAL));
        verifyAttempts(2);
    }

    @Test
    void revoke_vaultUnreachable_shouldRetryUpToTheLimitThenThrowUnavailable() {
        sessionCallsRestOperations();
        when(restOperations.postForObject(PATH, REQUEST, VaultResponse.class))
                .thenThrow(new ResourceAccessException("Connection refused"));

        PkiUnavailableException e =
                assertThrows(PkiUnavailableException.class, () -> service.revokeCertificate(SERIAL));
        assertInstanceOf(ResourceAccessException.class, e.getCause());
        verifyAttempts(RevocationTestProperties.MAX_ATTEMPTS);
    }

    @Test
    void revoke_vaultException_shouldBeTreatedAsUnavailable() {
        when(vaultTemplate.doWithSession(any())).thenThrow(new VaultException("Cannot renew token"));

        assertThrows(PkiUnavailableException.class, () -> service.revokeCertificate(SERIAL));
        verify(vaultTemplate, times(RevocationTestProperties.MAX_ATTEMPTS)).doWithSession(any());
    }

    @Test
    void revoke_loginRefused_shouldThrowPermissionDeniedWithoutRetrying() {
        when(vaultTemplate.doWithSession(any()))
                .thenThrow(VaultLoginException.create(
                        "AppRole",
                        clientError(HttpStatus.BAD_REQUEST, "{\"errors\":[\"invalid role or secret ID\"]}")));

        assertThrows(PkiPermissionDeniedException.class, () -> service.revokeCertificate(SERIAL));
        verify(vaultTemplate, times(1)).doWithSession(any());
        verify(restOperations, never()).postForObject(anyString(), anyMap(), eq(VaultResponse.class));
    }

    @Test
    void revoke_loginFailingBecauseVaultIsUnreachable_shouldBeRetriedAsUnavailable() {
        when(vaultTemplate.doWithSession(any()))
                .thenThrow(VaultLoginException.create("AppRole", new ResourceAccessException("Connection refused")));

        assertThrows(PkiUnavailableException.class, () -> service.revokeCertificate(SERIAL));
        verify(vaultTemplate, times(RevocationTestProperties.MAX_ATTEMPTS)).doWithSession(any());
    }
}
