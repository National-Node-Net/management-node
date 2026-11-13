/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate;

import java.math.BigInteger;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import uk.gov.dbt.ndtp.ia.node.management.exception.AccessRejectedException;
import uk.gov.dbt.ndtp.ia.node.management.exception.CertificateAlreadyRevokedException;
import uk.gov.dbt.ndtp.ia.node.management.exception.CertificateRevocationRejectedException;
import uk.gov.dbt.ndtp.ia.node.management.exception.CertificateRevocationRejectedException.Reason;
import uk.gov.dbt.ndtp.ia.node.management.exception.PkiCertificateExpiredException;
import uk.gov.dbt.ndtp.ia.node.management.model.UnknownIdentifiers;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.OrganisationCertificateDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.RevokeCertificateRequestDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.RevokeCertificateResponseDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.jwt.EnhancedPrincipal;
import uk.gov.dbt.ndtp.ia.node.management.service.data.OrganisationCertificateService;
import uk.gov.dbt.ndtp.ia.node.management.service.data.OrganisationService;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.status.CertificateStatusService;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.status.FederationCaService;
import uk.gov.dbt.ndtp.ia.node.management.utils.cryptography.CertificateSerials;

/**
 * Implementation of {@link CertificateRevocationProvider}.
 *
 * <p>Runs the checks in the order the API promises - rate limit, certificate exists, it belongs to the caller's
 * organisation, it is not revoked yet - so that a refused request never reaches Vault. Then revokes in Vault,
 * drops the certificate's cached status and records the revocation in the database.
 *
 * <p>Not transactional: the Vault call must not hold a database transaction open, and the database update is a
 * transaction of its own ({@link OrganisationCertificateService#recordRevocation}). If that update fails after
 * Vault revoked the certificate, the failure is logged for reconciliation and the request fails; the record still
 * says the certificate is active, so repeating the request revokes it in Vault again, which Vault answers with the
 * original time, and records it.
 */
@Service
@Slf4j
public class CertificateRevocationProviderImpl implements CertificateRevocationProvider {

    private final PkiBackend pkiBackend;
    private final OrganisationCertificateService certificateService;
    private final OrganisationService organisationService;
    private final RevocationRateLimiter rateLimiter;
    private final CertificateStatusService statusService;
    private final FederationCaService federationCa;

    public CertificateRevocationProviderImpl(
            PkiBackend pkiBackend,
            OrganisationCertificateService certificateService,
            OrganisationService organisationService,
            RevocationRateLimiter rateLimiter,
            CertificateStatusService statusService,
            FederationCaService federationCa) {
        this.pkiBackend = pkiBackend;
        this.certificateService = certificateService;
        this.organisationService = organisationService;
        this.rateLimiter = rateLimiter;
        this.statusService = statusService;
        this.federationCa = federationCa;
    }

    @Override
    public RevokeCertificateResponseDTO revoke(RevokeCertificateRequestDTO request, EnhancedPrincipal caller) {
        rateLimiter.acquire(callerKey(caller));

        BigInteger serial = CertificateSerials.parse(request.getSerialNumber());
        String vaultSerial = CertificateSerials.toVault(serial);
        String storedSerial = CertificateSerials.toStored(serial);

        OrganisationCertificateDTO certificate = certificateService
                .findBySerialNumber(storedSerial)
                .orElseThrow(() -> {
                    log.info("Revocation refused: no organisation certificate has serial {}", vaultSerial);
                    return new CertificateRevocationRejectedException(
                            Reason.CERTIFICATE_NOT_FOUND, "No organisation certificate has this serial number");
                });
        requireCallersOrganisation(certificate, caller, vaultSerial);
        if (certificate.getRevokedAt() != null) {
            log.info("Revocation refused: serial {} is already revoked", vaultSerial);
            throw alreadyRevoked(certificate);
        }

        Instant revokedAt;
        try {
            revokedAt = pkiBackend.revokeCertificate(serial);
        } catch (PkiCertificateExpiredException e) {
            throw new CertificateRevocationRejectedException(
                    Reason.CERTIFICATE_EXPIRED,
                    "Certificate has expired, so it cannot be revoked; nothing was changed");
        }
        // Right after Vault, so the certificate is refused from now on even if recording it fails.
        evictCachedStatus(serial, vaultSerial);

        String comment = blankToNull(request.getComment());
        record(certificate, vaultSerial, storedSerial, revokedAt, caller.subject(), request, comment);
        // Again once the reason is recorded: a status cached in between would be revoked, but without the reason.
        evictCachedStatus(serial, vaultSerial);

        log.info(
                "Certificate {} of organisation {} revoked at {} by {}, reason {}",
                vaultSerial,
                certificate.getOrganisationId(),
                revokedAt,
                caller.subject(),
                request.getReason());
        return RevokeCertificateResponseDTO.builder()
                .serialNumber(vaultSerial)
                .status(RevokeCertificateResponseDTO.STATUS_REVOKED)
                .reason(request.getReason())
                .revokedAt(revokedAt)
                .revokedBy(caller.subject())
                .build();
    }

    /** The caller's organisation, from the token's {@code organisation} claim, must own the certificate. */
    private void requireCallersOrganisation(
            OrganisationCertificateDTO certificate, EnhancedPrincipal caller, String vaultSerial) {
        String organisationKey = UnknownIdentifiers.knownOrganisationOrNull(caller.organisation());
        Optional<Long> callerOrganisationId =
                Optional.ofNullable(organisationKey).flatMap(organisationService::findIdByKey);
        if (callerOrganisationId.isEmpty()) {
            String errorId = UUID.randomUUID().toString();
            log.warn(
                    "Revocation refused: caller {} has no known organisation ({}), serial {}, error_id={}",
                    caller.subject(),
                    caller.organisation(),
                    vaultSerial,
                    errorId);
            throw new AccessRejectedException("The caller's organisation is not known", errorId);
        }
        if (!callerOrganisationId.get().equals(certificate.getOrganisationId())) {
            String errorId = UUID.randomUUID().toString();
            log.warn(
                    "Revocation refused: serial {} belongs to organisation {}, caller {} acts for organisation {},"
                            + " error_id={}",
                    vaultSerial,
                    certificate.getOrganisationId(),
                    caller.subject(),
                    callerOrganisationId.get(),
                    errorId);
            throw new AccessRejectedException("The certificate does not belong to the caller's organisation", errorId);
        }
    }

    private void record(
            OrganisationCertificateDTO certificate,
            String vaultSerial,
            String storedSerial,
            Instant revokedAt,
            String revokedBy,
            RevokeCertificateRequestDTO request,
            String comment) {
        boolean recorded;
        try {
            recorded = certificateService.recordRevocation(
                    certificate, revokedAt, revokedBy, request.getReason(), comment);
        } catch (RuntimeException e) {
            logReconciliationRequired(certificate, vaultSerial, revokedAt, revokedBy, "the database update failed");
            throw e;
        }
        if (recorded) {
            return;
        }
        // The record changed between reading and updating it: revoked by a concurrent request, or re-issued.
        OrganisationCertificateDTO current =
                certificateService.findBySerialNumber(storedSerial).orElse(null);
        if (current != null && current.getRevokedAt() != null) {
            log.info("Serial {} was revoked by a concurrent request", vaultSerial);
            throw alreadyRevoked(current);
        }
        logReconciliationRequired(certificate, vaultSerial, revokedAt, revokedBy, "the record changed meanwhile");
        throw new IllegalStateException("Certificate record changed while it was being revoked");
    }

    /**
     * Drops the certificate's cached status, so the revocation takes effect on the next request instead of when
     * the entry expires. A failure cannot undo the revocation in Vault, so it is logged and the request goes on:
     * the certificate is then refused once the cached status expires.
     */
    private void evictCachedStatus(BigInteger serial, String vaultSerial) {
        try {
            statusService.evict(federationCa.current(), serial);
        } catch (RuntimeException e) {
            log.error(
                    "Cached status of revoked certificate {} could not be evicted; it stays in use until it expires",
                    vaultSerial,
                    e);
        }
    }

    private static void logReconciliationRequired(
            OrganisationCertificateDTO certificate,
            String vaultSerial,
            Instant revokedAt,
            String revokedBy,
            String cause) {
        log.error(
                "certificate_revocation_reconciliation_required serial={} organisationCertificateId={}"
                        + " organisationId={} revocationTime={} revokedBy={} cause=\"{}\":"
                        + " revoked in Vault, not recorded in organisation_certificate; repeat the request to record it",
                vaultSerial,
                certificate.getId(),
                certificate.getOrganisationId(),
                revokedAt,
                revokedBy,
                cause);
    }

    private static CertificateAlreadyRevokedException alreadyRevoked(OrganisationCertificateDTO certificate) {
        return new CertificateAlreadyRevokedException(
                certificate.getRevokedAt().toInstant(), certificate.getRevokedBy());
    }

    /** Rate limits count per administrator; a token without a subject falls back to its client. */
    private static String callerKey(EnhancedPrincipal caller) {
        String subject = caller.subject();
        return subject == null || subject.isBlank() ? "client:" + caller.clientId() : subject;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
