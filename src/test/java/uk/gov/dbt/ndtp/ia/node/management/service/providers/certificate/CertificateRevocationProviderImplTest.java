/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import uk.gov.dbt.ndtp.ia.node.management.exception.AccessRejectedException;
import uk.gov.dbt.ndtp.ia.node.management.exception.CertificateAlreadyRevokedException;
import uk.gov.dbt.ndtp.ia.node.management.exception.CertificateRevocationRejectedException;
import uk.gov.dbt.ndtp.ia.node.management.exception.CertificateRevocationRejectedException.Reason;
import uk.gov.dbt.ndtp.ia.node.management.exception.PkiCertificateExpiredException;
import uk.gov.dbt.ndtp.ia.node.management.exception.PkiPermissionDeniedException;
import uk.gov.dbt.ndtp.ia.node.management.exception.PkiUnavailableException;
import uk.gov.dbt.ndtp.ia.node.management.model.UnknownIdentifiers;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.OrganisationCertificateDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.RevokeCertificateRequestDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.RevokeCertificateResponseDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.jwt.EnhancedPrincipal;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.certificate.CertificateType;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.certificate.RevocationReason;
import uk.gov.dbt.ndtp.ia.node.management.service.data.OrganisationCertificateService;
import uk.gov.dbt.ndtp.ia.node.management.service.data.OrganisationService;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.status.CertificateIssuer;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.status.CertificateStatusService;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.status.FederationCaService;

/** The acceptance criteria of the revocation API, at the level where they are decided. */
@ExtendWith(MockitoExtension.class)
class CertificateRevocationProviderImplTest {

    private static final String VAULT_SERIAL = "39:dd:2e";
    private static final String STORED_SERIAL = "39dd2e";
    private static final BigInteger SERIAL = new BigInteger(STORED_SERIAL, 16);
    private static final long ORG_A = 1L;
    private static final long ORG_B = 2L;
    private static final Instant REVOKED_AT = Instant.parse("2026-10-12T13:15:02Z");
    private static final EnhancedPrincipal ADMIN_OF_A = new EnhancedPrincipal("admin-a", "FEDERATOR_ENV", "ENV");
    private static final CertificateIssuer FEDERATION_CA = new CertificateIssuer("abd01de5", null);

    @Mock
    private PkiBackend pkiBackend;

    @Mock
    private OrganisationCertificateService certificateService;

    @Mock
    private OrganisationService organisationService;

    @Mock
    private RevocationRateLimiter rateLimiter;

    @Mock
    private CertificateStatusService statusService;

    @Mock
    private FederationCaService federationCa;

    private CertificateRevocationProviderImpl provider;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        provider = new CertificateRevocationProviderImpl(
                pkiBackend, certificateService, organisationService, rateLimiter, statusService, federationCa);
        logs = new ListAppender<>();
        logs.start();
        logger().addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        logger().detachAppender(logs);
    }

    private static Logger logger() {
        return (Logger) LoggerFactory.getLogger(CertificateRevocationProviderImpl.class);
    }

    private static RevokeCertificateRequestDTO request(String serial) {
        return RevokeCertificateRequestDTO.builder()
                .serialNumber(serial)
                .reason(RevocationReason.KEY_COMPROMISE)
                .comment("Private key exposed on build server")
                .build();
    }

    private static OrganisationCertificateDTO certificateOf(long organisationId) {
        return OrganisationCertificateDTO.builder()
                .id(10L)
                .organisationId(organisationId)
                .serialNumber(STORED_SERIAL)
                .type(CertificateType.AUTOMATED)
                .build();
    }

    private void certificateExists(OrganisationCertificateDTO certificate) {
        when(certificateService.findBySerialNumber(STORED_SERIAL)).thenReturn(Optional.of(certificate));
    }

    private void callerActsFor(String key, long organisationId) {
        when(organisationService.findIdByKey(key)).thenReturn(Optional.of(organisationId));
    }

    @Test
    void revoke_activeCertificateOfOwnOrganisation_shouldRevokeInVaultThenRecordIt() {
        OrganisationCertificateDTO certificate = certificateOf(ORG_A);
        certificateExists(certificate);
        callerActsFor("ENV", ORG_A);
        when(pkiBackend.revokeCertificate(SERIAL)).thenReturn(REVOKED_AT);
        when(certificateService.recordRevocation(
                        certificate,
                        REVOKED_AT,
                        "admin-a",
                        RevocationReason.KEY_COMPROMISE,
                        "Private key exposed on build server"))
                .thenReturn(true);

        RevokeCertificateResponseDTO response = provider.revoke(request(VAULT_SERIAL), ADMIN_OF_A);

        assertEquals(VAULT_SERIAL, response.getSerialNumber());
        assertEquals(RevokeCertificateResponseDTO.STATUS_REVOKED, response.getStatus());
        assertEquals(RevocationReason.KEY_COMPROMISE, response.getReason());
        assertEquals(REVOKED_AT, response.getRevokedAt(), "revokedAt is Vault's time, not the local clock");
        assertEquals("admin-a", response.getRevokedBy());
        verify(rateLimiter).acquire("admin-a");
    }

    @Test
    void revoke_hyphenatedUppercaseSerial_shouldFindTheSameCertificate() {
        OrganisationCertificateDTO certificate = certificateOf(ORG_A);
        certificateExists(certificate);
        callerActsFor("ENV", ORG_A);
        when(pkiBackend.revokeCertificate(SERIAL)).thenReturn(REVOKED_AT);
        when(certificateService.recordRevocation(any(), any(), any(), any(), any()))
                .thenReturn(true);

        RevokeCertificateResponseDTO response = provider.revoke(request("39-DD-2E"), ADMIN_OF_A);

        assertEquals(VAULT_SERIAL, response.getSerialNumber());
    }

    @Test
    void revoke_blankComment_shouldBeStoredAsNull() {
        OrganisationCertificateDTO certificate = certificateOf(ORG_A);
        certificateExists(certificate);
        callerActsFor("ENV", ORG_A);
        when(pkiBackend.revokeCertificate(SERIAL)).thenReturn(REVOKED_AT);
        when(certificateService.recordRevocation(certificate, REVOKED_AT, "admin-a", RevocationReason.SUPERSEDED, null))
                .thenReturn(true);

        provider.revoke(
                RevokeCertificateRequestDTO.builder()
                        .serialNumber(VAULT_SERIAL)
                        .reason(RevocationReason.SUPERSEDED)
                        .comment("  ")
                        .build(),
                ADMIN_OF_A);

        verify(certificateService)
                .recordRevocation(certificate, REVOKED_AT, "admin-a", RevocationReason.SUPERSEDED, null);
    }

    @Test
    void revoke_unknownSerial_shouldBeNotFoundWithoutCallingVault() {
        when(certificateService.findBySerialNumber(STORED_SERIAL)).thenReturn(Optional.empty());

        CertificateRevocationRejectedException e = assertThrows(
                CertificateRevocationRejectedException.class, () -> provider.revoke(request(VAULT_SERIAL), ADMIN_OF_A));

        assertEquals(Reason.CERTIFICATE_NOT_FOUND, e.getReason());
        verifyNoInteractions(pkiBackend);
        verify(certificateService, never()).recordRevocation(any(), any(), any(), any(), any());
    }

    @Test
    void revoke_certificateOfAnotherOrganisation_shouldBeForbiddenWithoutCallingVault() {
        certificateExists(certificateOf(ORG_B));
        callerActsFor("ENV", ORG_A);

        assertThrows(AccessRejectedException.class, () -> provider.revoke(request(VAULT_SERIAL), ADMIN_OF_A));

        verifyNoInteractions(pkiBackend);
        verify(certificateService, never()).recordRevocation(any(), any(), any(), any(), any());
    }

    @Test
    void revoke_callerWithoutOrganisationClaim_shouldBeForbiddenWithoutCallingVault() {
        certificateExists(certificateOf(ORG_A));
        EnhancedPrincipal noOrganisation =
                new EnhancedPrincipal("admin-a", "FEDERATOR_ENV", UnknownIdentifiers.UNKNOWN_ORG);

        assertThrows(AccessRejectedException.class, () -> provider.revoke(request(VAULT_SERIAL), noOrganisation));

        verifyNoInteractions(pkiBackend, organisationService);
    }

    @Test
    void revoke_callerOfAnOrganisationNotInTheDatabase_shouldBeForbidden() {
        certificateExists(certificateOf(ORG_A));
        when(organisationService.findIdByKey("ENV")).thenReturn(Optional.empty());

        assertThrows(AccessRejectedException.class, () -> provider.revoke(request(VAULT_SERIAL), ADMIN_OF_A));

        verifyNoInteractions(pkiBackend);
    }

    @Test
    void revoke_alreadyRevoked_shouldConflictWithTheOriginalRevocationAndLeaveTheRecord() {
        OrganisationCertificateDTO certificate = certificateOf(ORG_A);
        certificate.setRevokedAt(Timestamp.from(REVOKED_AT));
        certificate.setRevokedBy("first-admin");
        certificateExists(certificate);
        callerActsFor("ENV", ORG_A);

        CertificateAlreadyRevokedException e = assertThrows(
                CertificateAlreadyRevokedException.class, () -> provider.revoke(request(VAULT_SERIAL), ADMIN_OF_A));

        assertEquals(REVOKED_AT, e.getRevokedAt());
        assertEquals("first-admin", e.getRevokedBy());
        verifyNoInteractions(pkiBackend);
        verify(certificateService, never()).recordRevocation(any(), any(), any(), any(), any());
    }

    @Test
    void revoke_vaultUnavailable_shouldPropagateAndLeaveTheRecord() {
        certificateExists(certificateOf(ORG_A));
        callerActsFor("ENV", ORG_A);
        when(pkiBackend.revokeCertificate(SERIAL)).thenThrow(new PkiUnavailableException("down", null));

        assertThrows(PkiUnavailableException.class, () -> provider.revoke(request(VAULT_SERIAL), ADMIN_OF_A));

        verify(certificateService, never()).recordRevocation(any(), any(), any(), any(), any());
    }

    @Test
    void revoke_vaultRefuses_shouldPropagateAndLeaveTheRecord() {
        certificateExists(certificateOf(ORG_A));
        callerActsFor("ENV", ORG_A);
        when(pkiBackend.revokeCertificate(SERIAL)).thenThrow(new PkiPermissionDeniedException("denied", null));

        assertThrows(PkiPermissionDeniedException.class, () -> provider.revoke(request(VAULT_SERIAL), ADMIN_OF_A));

        verify(certificateService, never()).recordRevocation(any(), any(), any(), any(), any());
    }

    @Test
    void revoke_expiredCertificate_shouldConflictAndLeaveTheRecord() {
        certificateExists(certificateOf(ORG_A));
        callerActsFor("ENV", ORG_A);
        when(pkiBackend.revokeCertificate(SERIAL)).thenThrow(new PkiCertificateExpiredException("expired"));

        CertificateRevocationRejectedException e = assertThrows(
                CertificateRevocationRejectedException.class, () -> provider.revoke(request(VAULT_SERIAL), ADMIN_OF_A));

        assertEquals(Reason.CERTIFICATE_EXPIRED, e.getReason());
        verify(certificateService, never()).recordRevocation(any(), any(), any(), any(), any());
    }

    /**
     * Acceptance criterion "Database failure after Vault success": the request fails, the failure is logged for
     * reconciliation, and the same request again succeeds and records the revocation.
     */
    @Test
    void revoke_databaseFailsAfterVault_shouldLogForReconciliationAndSucceedOnRetry() {
        OrganisationCertificateDTO certificate = certificateOf(ORG_A);
        certificateExists(certificate);
        callerActsFor("ENV", ORG_A);
        when(pkiBackend.revokeCertificate(SERIAL)).thenReturn(REVOKED_AT);
        when(certificateService.recordRevocation(any(), any(), any(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("database down"))
                .thenReturn(true);

        assertThrows(
                DataAccessResourceFailureException.class, () -> provider.revoke(request(VAULT_SERIAL), ADMIN_OF_A));
        assertThat(logs.list).anySatisfy(event -> {
            assertEquals(Level.ERROR, event.getLevel());
            assertThat(event.getFormattedMessage())
                    .contains("certificate_revocation_reconciliation_required")
                    .contains("serial=" + VAULT_SERIAL)
                    .contains("revocationTime=" + REVOKED_AT);
        });

        // The record still says active, so the retry reaches Vault again, which answers with the original time.
        RevokeCertificateResponseDTO retry = provider.revoke(request(VAULT_SERIAL), ADMIN_OF_A);

        assertEquals(REVOKED_AT, retry.getRevokedAt());
        verify(pkiBackend, times(2)).revokeCertificate(SERIAL);
        verify(certificateService, times(2))
                .recordRevocation(
                        certificate,
                        REVOKED_AT,
                        "admin-a",
                        RevocationReason.KEY_COMPROMISE,
                        "Private key exposed on build server");
    }

    @Test
    void revoke_revokedConcurrently_shouldConflictWithTheWinningRevocation() {
        certificateExists(certificateOf(ORG_A));
        callerActsFor("ENV", ORG_A);
        when(pkiBackend.revokeCertificate(SERIAL)).thenReturn(REVOKED_AT);
        when(certificateService.recordRevocation(any(), any(), any(), any(), any()))
                .thenReturn(false);
        OrganisationCertificateDTO revokedMeanwhile = certificateOf(ORG_A);
        revokedMeanwhile.setRevokedAt(Timestamp.from(REVOKED_AT));
        revokedMeanwhile.setRevokedBy("other-admin");
        when(certificateService.findBySerialNumber(STORED_SERIAL))
                .thenReturn(Optional.of(certificateOf(ORG_A)), Optional.of(revokedMeanwhile));

        CertificateAlreadyRevokedException e = assertThrows(
                CertificateAlreadyRevokedException.class, () -> provider.revoke(request(VAULT_SERIAL), ADMIN_OF_A));

        assertEquals("other-admin", e.getRevokedBy());
    }

    @Test
    void revoke_recordReissuedConcurrently_shouldFailAndLogForReconciliation() {
        certificateExists(certificateOf(ORG_A));
        callerActsFor("ENV", ORG_A);
        when(pkiBackend.revokeCertificate(SERIAL)).thenReturn(REVOKED_AT);
        when(certificateService.recordRevocation(any(), any(), any(), any(), any()))
                .thenReturn(false);
        when(certificateService.findBySerialNumber(STORED_SERIAL))
                .thenReturn(Optional.of(certificateOf(ORG_A)), Optional.empty());

        assertThrows(IllegalStateException.class, () -> provider.revoke(request(VAULT_SERIAL), ADMIN_OF_A));

        assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("certificate_revocation_reconciliation_required"));
    }

    @Test
    void revoke_rateLimited_shouldBeRefusedBeforeAnyLookup() {
        doThrow(new CertificateRevocationRejectedException(Reason.RATE_LIMITED, "slow down"))
                .when(rateLimiter)
                .acquire("admin-a");

        assertThrows(
                CertificateRevocationRejectedException.class, () -> provider.revoke(request(VAULT_SERIAL), ADMIN_OF_A));

        verifyNoInteractions(certificateService, organisationService, pkiBackend);
    }

    @Test
    void revoke_success_shouldEvictTheCachedStatusAfterVaultAndAgainAfterTheRecord() {
        certificateExists(certificateOf(ORG_A));
        callerActsFor("ENV", ORG_A);
        when(federationCa.current()).thenReturn(FEDERATION_CA);
        when(pkiBackend.revokeCertificate(SERIAL)).thenReturn(REVOKED_AT);
        when(certificateService.recordRevocation(any(), any(), any(), any(), any()))
                .thenReturn(true);

        provider.revoke(request(VAULT_SERIAL), ADMIN_OF_A);

        InOrder order = inOrder(pkiBackend, statusService, certificateService);
        order.verify(pkiBackend).revokeCertificate(SERIAL);
        order.verify(statusService).evict(FEDERATION_CA, SERIAL);
        order.verify(certificateService).recordRevocation(any(), any(), any(), any(), any());
        order.verify(statusService).evict(FEDERATION_CA, SERIAL);
    }

    @Test
    void revoke_databaseFailsAfterVault_shouldStillHaveEvictedTheCachedStatus() {
        certificateExists(certificateOf(ORG_A));
        callerActsFor("ENV", ORG_A);
        when(federationCa.current()).thenReturn(FEDERATION_CA);
        when(pkiBackend.revokeCertificate(SERIAL)).thenReturn(REVOKED_AT);
        when(certificateService.recordRevocation(any(), any(), any(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("database down"));

        assertThrows(
                DataAccessResourceFailureException.class, () -> provider.revoke(request(VAULT_SERIAL), ADMIN_OF_A));

        verify(statusService).evict(FEDERATION_CA, SERIAL);
    }

    @Test
    void revoke_refusedOrFailedBeforeVaultRevoked_shouldNotTouchTheCache() {
        certificateExists(certificateOf(ORG_A));
        callerActsFor("ENV", ORG_A);
        when(pkiBackend.revokeCertificate(SERIAL)).thenThrow(new PkiUnavailableException("down", null));

        assertThrows(PkiUnavailableException.class, () -> provider.revoke(request(VAULT_SERIAL), ADMIN_OF_A));

        verifyNoInteractions(statusService, federationCa);
    }

    @Test
    void revoke_evictionFails_shouldStillRecordAndReportTheRevocation() {
        certificateExists(certificateOf(ORG_A));
        callerActsFor("ENV", ORG_A);
        when(federationCa.current()).thenThrow(new IllegalStateException("cache is down"));
        when(pkiBackend.revokeCertificate(SERIAL)).thenReturn(REVOKED_AT);
        when(certificateService.recordRevocation(any(), any(), any(), any(), any()))
                .thenReturn(true);

        RevokeCertificateResponseDTO response = provider.revoke(request(VAULT_SERIAL), ADMIN_OF_A);

        assertEquals(RevokeCertificateResponseDTO.STATUS_REVOKED, response.getStatus());
        assertThat(logs.list)
                .anySatisfy(event -> assertThat(event.getFormattedMessage()).contains("could not be evicted"));
    }

    @Test
    void revoke_tokenWithoutSubject_shouldBeRateLimitedByClient() {
        when(certificateService.findBySerialNumber(STORED_SERIAL)).thenReturn(Optional.empty());

        assertThrows(
                CertificateRevocationRejectedException.class,
                () -> provider.revoke(request(VAULT_SERIAL), new EnhancedPrincipal(null, "FEDERATOR_ENV", "ENV")));

        verify(rateLimiter).acquire("client:FEDERATOR_ENV");
    }
}
