/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.asn1.ASN1GeneralizedTime;
import org.bouncycastle.asn1.ocsp.OCSPObjectIdentifiers;
import org.bouncycastle.asn1.ocsp.RevokedInfo;
import org.bouncycastle.asn1.x509.CRLReason;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.Extensions;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.ocsp.BasicOCSPResp;
import org.bouncycastle.cert.ocsp.BasicOCSPRespBuilder;
import org.bouncycastle.cert.ocsp.CertificateID;
import org.bouncycastle.cert.ocsp.CertificateStatus;
import org.bouncycastle.cert.ocsp.OCSPException;
import org.bouncycastle.cert.ocsp.OCSPReq;
import org.bouncycastle.cert.ocsp.OCSPResp;
import org.bouncycastle.cert.ocsp.OCSPRespBuilder;
import org.bouncycastle.cert.ocsp.Req;
import org.bouncycastle.cert.ocsp.RevokedStatus;
import org.bouncycastle.cert.ocsp.UnknownStatus;
import org.bouncycastle.operator.DigestCalculatorProvider;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.bouncycastle.util.encoders.Hex;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import uk.gov.dbt.ndtp.ia.node.management.config.OcspProperties;
import uk.gov.dbt.ndtp.ia.node.management.exception.CertificateStatusUnavailableException;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.CertificateStatusDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.OcspResponseDTO;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.certificate.RevocationReason;

/**
 * Implementation of {@link OcspResponderService}. Parses a DER-encoded OCSP request, looks up the status of every
 * requested certificate with the {@link CertificateStatusProvider} and returns a DER-encoded response signed by the
 * delegated OCSP signer from {@link OcspSignerService}.
 */
@Service
@Slf4j
public class OcspResponderServiceImpl implements OcspResponderService {

    private static final String NONE = "-";

    private final CertificateStatusProvider statusProvider;
    private final OcspSignerService signerService;
    private final OcspProperties properties;
    private final MeterRegistry meterRegistry;
    private final Clock clock;
    private final DigestCalculatorProvider digestCalculatorProvider;

    @Autowired
    public OcspResponderServiceImpl(
            CertificateStatusProvider statusProvider,
            OcspSignerService signerService,
            OcspProperties properties,
            MeterRegistry meterRegistry) {
        this(statusProvider, signerService, properties, meterRegistry, Clock.systemUTC());
    }

    OcspResponderServiceImpl(
            CertificateStatusProvider statusProvider,
            OcspSignerService signerService,
            OcspProperties properties,
            MeterRegistry meterRegistry,
            Clock clock) {
        this.statusProvider = statusProvider;
        this.signerService = signerService;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.clock = clock;
        try {
            this.digestCalculatorProvider = new JcaDigestCalculatorProviderBuilder().build();
        } catch (OperatorCreationException e) {
            throw new IllegalStateException("Unable to create digest calculator provider", e);
        }
    }

    @Override
    public OcspResponseDTO respond(byte[] request, String requesterIp) {
        long start = System.nanoTime();
        List<EntryLog> entries = new ArrayList<>();
        OcspResponseDTO result;
        try {
            result = process(request, entries);
        } catch (Exception e) {
            log.error("Unexpected error while building OCSP response", e);
            result = unsigned(OCSPRespBuilder.INTERNAL_ERROR);
        }
        long latencyNanos = System.nanoTime() - start;
        record(result, entries, latencyNanos, requesterIp);
        return result;
    }

    private OcspResponseDTO process(byte[] request, List<EntryLog> entries) throws Exception {
        Optional<OCSPReq> ocspReq = parseRequest(request);
        if (ocspReq.isEmpty()) {
            return unsigned(OCSPRespBuilder.MALFORMED_REQUEST);
        }

        Optional<OcspSigner> currentSigner = signerService.currentSigner();
        if (currentSigner.isEmpty()) {
            log.warn("No valid OCSP signer available; answering tryLater");
            return unsigned(OCSPRespBuilder.TRY_LATER);
        }

        return buildResponse(ocspReq.get(), currentSigner.get(), entries);
    }

    /**
     * Parses the request body and checks it against the size and entry limits.
     *
     * @return the parsed request, or empty if it must be answered with {@code malformedRequest}
     */
    private Optional<OCSPReq> parseRequest(byte[] request) {
        if (request == null || request.length == 0 || request.length > properties.maxRequestBytes()) {
            log.debug("Rejecting OCSP request with size {}", request == null ? 0 : request.length);
            return Optional.empty();
        }

        OCSPReq ocspReq;
        Req[] requestList;
        try {
            ocspReq = new OCSPReq(request);
            requestList = ocspReq.getRequestList();
        } catch (IOException | RuntimeException e) {
            log.debug("Rejecting OCSP request that could not be parsed: {}", e.getMessage());
            return Optional.empty();
        }
        if (requestList.length == 0 || requestList.length > properties.maxRequestEntries()) {
            log.debug("Rejecting OCSP request with {} entries", requestList.length);
            return Optional.empty();
        }
        return Optional.of(ocspReq);
    }

    private OcspResponseDTO buildResponse(OCSPReq ocspReq, OcspSigner signer, List<EntryLog> entries) throws Exception {
        Instant thisUpdate = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Instant nextUpdate = thisUpdate.plus(properties.validity());
        BasicOCSPRespBuilder builder = new BasicOCSPRespBuilder(
                signer.certificate().getSubjectPublicKeyInfo(), digestCalculatorProvider.get(CertificateID.HASH_SHA1));

        for (Req req : ocspReq.getRequestList()) {
            CertificateID certId = req.getCertID();
            String serial = certId.getSerialNumber().toString(16);
            String issuerKeyHash =
                    Hex.toHexString(certId.toASN1Primitive().getIssuerKeyHash().getOctets());

            CertificateStatusDTO status;
            try {
                status = lookUpStatus(certId, signer.issuer());
            } catch (CertificateStatusUnavailableException e) {
                entries.add(new EntryLog(serial, issuerKeyHash, "unavailable"));
                return unsigned(OCSPRespBuilder.TRY_LATER);
            }
            entries.add(new EntryLog(serial, issuerKeyHash, statusName(status)));
            builder.addResponse(certId, toOcspStatus(status), Date.from(thisUpdate), Date.from(nextUpdate), null);
        }

        Extension nonce = ocspReq.getExtension(OCSPObjectIdentifiers.id_pkix_ocsp_nonce);
        if (nonce != null) {
            builder.setResponseExtensions(new Extensions(nonce));
        }

        BasicOCSPResp basicResponse = builder.build(
                signer.contentSigner(), new X509CertificateHolder[] {signer.certificate()}, Date.from(thisUpdate));
        OCSPResp response = new OCSPRespBuilder().build(OCSPRespBuilder.SUCCESSFUL, basicResponse);
        return OcspResponseDTO.builder()
                .encoded(response.getEncoded())
                .responseStatus(OCSPRespBuilder.SUCCESSFUL)
                .nextUpdate(nextUpdate)
                .build();
    }

    private CertificateStatusDTO lookUpStatus(CertificateID certId, X509CertificateHolder issuer) {
        if (!matchesIssuer(certId, issuer)) {
            return CertificateStatusDTO.unknown();
        }
        return statusProvider.getStatus(certId.getSerialNumber(), issuer);
    }

    /**
     * Compares the request's issuer name hash and issuer key hash with the CA, using the hash algorithm of the
     * request. Unsupported hash algorithms never match.
     */
    private boolean matchesIssuer(CertificateID certId, X509CertificateHolder issuer) {
        try {
            return certId.matchesIssuer(issuer, digestCalculatorProvider);
        } catch (OCSPException e) {
            log.debug("Unable to match OCSP request issuer: {}", e.getMessage());
            return false;
        }
    }

    private static CertificateStatus toOcspStatus(CertificateStatusDTO status) {
        return switch (status.getStatus()) {
            case GOOD -> CertificateStatus.GOOD;
            case REVOKED ->
                new RevokedStatus(new RevokedInfo(
                        new ASN1GeneralizedTime(Date.from(status.getRevokedAt())),
                        crlReason(status.getRevocationReason())));
            case UNKNOWN -> new UnknownStatus();
        };
    }

    /**
     * The {@code revocationReason} of a revoked response. {@code UNSPECIFIED} is left out, as RFC 5280 asks:
     * an absent reason already means unspecified.
     */
    private static CRLReason crlReason(RevocationReason reason) {
        if (reason == null || reason == RevocationReason.UNSPECIFIED) {
            return null;
        }
        return CRLReason.lookup(reason.crlReasonCode());
    }

    private static String statusName(CertificateStatusDTO status) {
        return status.getStatus().name().toLowerCase(Locale.ROOT);
    }

    private static OcspResponseDTO unsigned(int responseStatus) {
        try {
            return OcspResponseDTO.builder()
                    .encoded(new OCSPRespBuilder().build(responseStatus, null).getEncoded())
                    .responseStatus(responseStatus)
                    .build();
        } catch (OCSPException | IOException e) {
            throw new IllegalStateException("Unable to encode OCSP response status " + responseStatus, e);
        }
    }

    private void record(OcspResponseDTO result, List<EntryLog> entries, long latencyNanos, String requesterIp) {
        String responseStatus = responseStatusName(result.getResponseStatus());
        Timer.builder("ocsp.requests")
                .description("OCSP responses by response status")
                .tag("response_status", responseStatus)
                .register(meterRegistry)
                .record(Duration.ofNanos(latencyNanos));

        long latencyMs = Duration.ofNanos(latencyNanos).toMillis();
        if (entries.isEmpty()) {
            log.info(
                    "ocsp_response serial={} issuerKeyHash={} certStatus={} responseStatus={} latencyMs={} requesterIp={}",
                    NONE,
                    NONE,
                    NONE,
                    responseStatus,
                    latencyMs,
                    requesterIp);
            return;
        }
        for (EntryLog entry : entries) {
            if (result.isSuccessful()) {
                meterRegistry
                        .counter("ocsp.cert.status", "cert_status", entry.certStatus())
                        .increment();
            }
            log.info(
                    "ocsp_response serial={} issuerKeyHash={} certStatus={} responseStatus={} latencyMs={} requesterIp={}",
                    entry.serial(),
                    entry.issuerKeyHash(),
                    entry.certStatus(),
                    responseStatus,
                    latencyMs,
                    requesterIp);
        }
    }

    static String responseStatusName(int responseStatus) {
        return switch (responseStatus) {
            case OCSPRespBuilder.SUCCESSFUL -> "successful";
            case OCSPRespBuilder.MALFORMED_REQUEST -> "malformedRequest";
            case OCSPRespBuilder.INTERNAL_ERROR -> "internalError";
            case OCSPRespBuilder.TRY_LATER -> "tryLater";
            case OCSPRespBuilder.SIG_REQUIRED -> "sigRequired";
            case OCSPRespBuilder.UNAUTHORIZED -> "unauthorized";
            default -> "unknown(" + responseStatus + ")";
        };
    }

    private record EntryLog(String serial, String issuerKeyHash, String certStatus) {}
}
