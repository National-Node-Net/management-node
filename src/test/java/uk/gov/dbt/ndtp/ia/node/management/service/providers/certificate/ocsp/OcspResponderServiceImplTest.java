/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp.OcspTestPki.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.ocsp.OCSPObjectIdentifiers;
import org.bouncycastle.asn1.ocsp.ResponderID;
import org.bouncycastle.asn1.x509.AlgorithmIdentifier;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.ocsp.BasicOCSPResp;
import org.bouncycastle.cert.ocsp.CertificateID;
import org.bouncycastle.cert.ocsp.OCSPResp;
import org.bouncycastle.cert.ocsp.OCSPRespBuilder;
import org.bouncycastle.cert.ocsp.RevokedStatus;
import org.bouncycastle.cert.ocsp.SingleResp;
import org.bouncycastle.cert.ocsp.UnknownStatus;
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import uk.gov.dbt.ndtp.ia.node.management.config.OcspProperties;
import uk.gov.dbt.ndtp.ia.node.management.exception.CertificateStatusUnavailableException;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.CertificateStatusDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.OcspResponseDTO;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OcspResponderServiceImplTest {

    private static final BigInteger SERIAL = BigInteger.valueOf(0x1234);
    private static final String REQUESTER_IP = "10.0.0.1";

    @Mock
    private CertificateStatusProvider statusProvider;

    @Mock
    private OcspSignerService signerService;

    private SimpleMeterRegistry meterRegistry;
    private OcspResponderServiceImpl service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        service = newService(properties(Duration.ofMinutes(5), 10, 16384));
        when(signerService.currentSigner()).thenReturn(Optional.of(rsaSigner()));
    }

    private OcspResponderServiceImpl newService(OcspProperties properties) {
        return new OcspResponderServiceImpl(
                statusProvider, signerService, properties, meterRegistry, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static OcspProperties properties(Duration validity, int maxEntries, int maxBytes) {
        return new OcspProperties(
                validity,
                maxEntries,
                maxBytes,
                "vault",
                new OcspProperties.Signer("ocsp-signer.management-node", Duration.ofHours(24), Duration.ofMinutes(5)));
    }

    private static BasicOCSPResp basic(OcspResponseDTO result) throws Exception {
        OCSPResp resp = new OCSPResp(result.getEncoded());
        assertEquals(OCSPRespBuilder.SUCCESSFUL, resp.getStatus());
        return (BasicOCSPResp) resp.getResponseObject();
    }

    private static void assertUnsigned(OcspResponseDTO result, int expectedStatus) throws Exception {
        OCSPResp resp = new OCSPResp(result.getEncoded());
        assertEquals(expectedStatus, resp.getStatus());
        assertEquals(expectedStatus, result.getResponseStatus());
        assertNull(resp.getResponseObject());
        assertNull(result.getNextUpdate());
    }

    @Test
    void respond_goodCertificate_shouldReturnSignedGoodStatus() throws Exception {
        when(statusProvider.getStatus(SERIAL, CA)).thenReturn(CertificateStatusDTO.good());

        OcspResponseDTO result = service.respond(request(null, certId(CA, SERIAL)), REQUESTER_IP);

        assertTrue(result.isSuccessful());
        SingleResp[] responses = basic(result).getResponses();
        assertEquals(1, responses.length);
        assertEquals(SERIAL, responses[0].getCertID().getSerialNumber());
        assertNull(responses[0].getCertStatus(), "null certStatus means good");
    }

    @Test
    void respond_revokedCertificate_shouldReturnRevocationTimeWithoutReason() throws Exception {
        Instant revokedAt = Instant.parse("2026-10-22T08:30:00Z");
        when(statusProvider.getStatus(SERIAL, CA)).thenReturn(CertificateStatusDTO.revoked(revokedAt));

        OcspResponseDTO result = service.respond(request(null, certId(CA, SERIAL)), REQUESTER_IP);

        RevokedStatus status = assertInstanceOf(
                RevokedStatus.class, basic(result).getResponses()[0].getCertStatus());
        assertEquals(revokedAt, status.getRevocationTime().toInstant());
        assertFalse(status.hasRevocationReason());
    }

    @Test
    void respond_unknownCertificate_shouldReturnUnknownStatus() throws Exception {
        when(statusProvider.getStatus(SERIAL, CA)).thenReturn(CertificateStatusDTO.unknown());

        OcspResponseDTO result = service.respond(request(null, certId(CA, SERIAL)), REQUESTER_IP);

        assertInstanceOf(UnknownStatus.class, basic(result).getResponses()[0].getCertStatus());
    }

    @Test
    void respond_certificateFromOtherIssuer_shouldReturnUnknownWithoutLookup() throws Exception {
        OcspResponseDTO result = service.respond(request(null, certId(OTHER_CA, SERIAL)), REQUESTER_IP);

        assertInstanceOf(UnknownStatus.class, basic(result).getResponses()[0].getCertStatus());
        verifyNoInteractions(statusProvider);
    }

    @Test
    void respond_sha256CertificateId_shouldMatchIssuer() throws Exception {
        when(statusProvider.getStatus(SERIAL, CA)).thenReturn(CertificateStatusDTO.good());
        CertificateID id = certId(CA, SERIAL, new AlgorithmIdentifier(NISTObjectIdentifiers.id_sha256));

        OcspResponseDTO result = service.respond(request(null, id), REQUESTER_IP);

        assertNull(basic(result).getResponses()[0].getCertStatus());
    }

    @Test
    void respond_shouldBeSignedByIncludedOcspSignerIssuedByCa() throws Exception {
        when(statusProvider.getStatus(SERIAL, CA)).thenReturn(CertificateStatusDTO.good());

        BasicOCSPResp basic = basic(service.respond(request(null, certId(CA, SERIAL)), REQUESTER_IP));

        X509CertificateHolder[] certs = basic.getCerts();
        assertEquals(1, certs.length);
        X509CertificateHolder signer = certs[0];
        assertTrue(basic.isSignatureValid(new JcaContentVerifierProviderBuilder().build(signer)));
        assertTrue(signer.isSignatureValid(new JcaContentVerifierProviderBuilder().build(CA)));
        assertTrue(toX509(signer).getExtendedKeyUsage().contains(KeyPurposeId.id_kp_OCSPSigning.getId()));
        assertEquals("1.2.840.113549.1.1.11", basic.getSignatureAlgOID().getId()); // sha256WithRSAEncryption
    }

    @Test
    void respond_shouldIdentifyResponderByKeyHash() throws Exception {
        when(statusProvider.getStatus(SERIAL, CA)).thenReturn(CertificateStatusDTO.good());

        BasicOCSPResp basic = basic(service.respond(request(null, certId(CA, SERIAL)), REQUESTER_IP));

        ResponderID responderId = basic.getResponderId().toASN1Primitive();
        assertNull(responderId.getName());
        assertNotNull(responderId.getKeyHash());
    }

    @Test
    void respond_withEcSigner_shouldSignWithEcdsa() throws Exception {
        when(signerService.currentSigner()).thenReturn(Optional.of(ecSigner()));
        when(statusProvider.getStatus(SERIAL, CA)).thenReturn(CertificateStatusDTO.good());

        BasicOCSPResp basic = basic(service.respond(request(null, certId(CA, SERIAL)), REQUESTER_IP));

        assertEquals("1.2.840.10045.4.3.2", basic.getSignatureAlgOID().getId()); // ecdsa-with-SHA256
        assertTrue(basic.isSignatureValid(new JcaContentVerifierProviderBuilder().build(basic.getCerts()[0])));
    }

    @Test
    void respond_withNonce_shouldEchoNonce() throws Exception {
        when(statusProvider.getStatus(SERIAL, CA)).thenReturn(CertificateStatusDTO.good());
        byte[] nonce = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16};

        BasicOCSPResp basic = basic(service.respond(request(nonce, certId(CA, SERIAL)), REQUESTER_IP));

        assertEquals(nonceExtension(nonce), basic.getExtension(OCSPObjectIdentifiers.id_pkix_ocsp_nonce));
    }

    @Test
    void respond_withoutNonce_shouldNotAddNonce() throws Exception {
        when(statusProvider.getStatus(SERIAL, CA)).thenReturn(CertificateStatusDTO.good());

        BasicOCSPResp basic = basic(service.respond(request(null, certId(CA, SERIAL)), REQUESTER_IP));

        assertNull(basic.getExtension(OCSPObjectIdentifiers.id_pkix_ocsp_nonce));
    }

    @Test
    void respond_shouldUseCurrentTimeAndDefaultValidityOfFiveMinutes() throws Exception {
        when(statusProvider.getStatus(SERIAL, CA)).thenReturn(CertificateStatusDTO.good());

        OcspResponseDTO result = service.respond(request(null, certId(CA, SERIAL)), REQUESTER_IP);

        BasicOCSPResp basic = basic(result);
        SingleResp single = basic.getResponses()[0];
        assertEquals(NOW, single.getThisUpdate().toInstant());
        assertEquals(NOW.plus(Duration.ofMinutes(5)), single.getNextUpdate().toInstant());
        assertEquals(NOW, basic.getProducedAt().toInstant());
        assertEquals(NOW.plus(Duration.ofMinutes(5)), result.getNextUpdate());
    }

    @Test
    void respond_withConfiguredValidity_shouldUseIt() throws Exception {
        service = newService(properties(Duration.ofMinutes(10), 10, 16384));
        when(statusProvider.getStatus(SERIAL, CA)).thenReturn(CertificateStatusDTO.good());

        SingleResp single = basic(service.respond(request(null, certId(CA, SERIAL)), REQUESTER_IP))
                .getResponses()[0];

        assertEquals(
                Duration.ofMinutes(10),
                Duration.between(
                        single.getThisUpdate().toInstant(),
                        single.getNextUpdate().toInstant()));
    }

    @Test
    void respond_garbageBody_shouldReturnUnsignedMalformedRequest() throws Exception {
        assertUnsigned(service.respond(new byte[] {0x30, 0x03, 0x01, 0x02}, REQUESTER_IP), 1);
        assertUnsigned(service.respond("not an ocsp request".getBytes(), REQUESTER_IP), 1);
        verifyNoInteractions(statusProvider);
    }

    @Test
    void respond_emptyOrMissingBody_shouldReturnMalformedRequest() throws Exception {
        assertUnsigned(service.respond(new byte[0], REQUESTER_IP), OCSPRespBuilder.MALFORMED_REQUEST);
        assertUnsigned(service.respond(null, REQUESTER_IP), OCSPRespBuilder.MALFORMED_REQUEST);
    }

    @Test
    void respond_oversizedBody_shouldReturnMalformedRequest() throws Exception {
        service = newService(properties(Duration.ofMinutes(5), 10, 16));

        assertUnsigned(
                service.respond(request(null, certId(CA, SERIAL)), REQUESTER_IP), OCSPRespBuilder.MALFORMED_REQUEST);
    }

    @Test
    void respond_vaultUnavailable_shouldReturnTryLater() throws Exception {
        when(statusProvider.getStatus(SERIAL, CA))
                .thenThrow(new CertificateStatusUnavailableException("down", new RuntimeException()));

        assertUnsigned(service.respond(request(null, certId(CA, SERIAL)), REQUESTER_IP), OCSPRespBuilder.TRY_LATER);
    }

    @Test
    void respond_vaultUnavailableForOneOfSeveralEntries_shouldNeverReturnGood() throws Exception {
        BigInteger other = BigInteger.valueOf(0x5678);
        when(statusProvider.getStatus(SERIAL, CA)).thenReturn(CertificateStatusDTO.good());
        when(statusProvider.getStatus(other, CA))
                .thenThrow(new CertificateStatusUnavailableException("down", new RuntimeException()));

        OcspResponseDTO result = service.respond(request(null, certId(CA, SERIAL), certId(CA, other)), REQUESTER_IP);

        assertUnsigned(result, OCSPRespBuilder.TRY_LATER);
    }

    @Test
    void respond_noValidSigner_shouldReturnTryLater() throws Exception {
        when(signerService.currentSigner()).thenReturn(Optional.empty());

        assertUnsigned(service.respond(request(null, certId(CA, SERIAL)), REQUESTER_IP), OCSPRespBuilder.TRY_LATER);
        verifyNoInteractions(statusProvider);
    }

    @Test
    void respond_unexpectedError_shouldReturnInternalError() throws Exception {
        when(statusProvider.getStatus(any(), any())).thenThrow(new IllegalStateException("boom"));

        assertUnsigned(
                service.respond(request(null, certId(CA, SERIAL)), REQUESTER_IP), OCSPRespBuilder.INTERNAL_ERROR);
    }

    @Test
    void respond_multipleCertificates_shouldAnswerEveryEntry() throws Exception {
        BigInteger revoked = BigInteger.valueOf(2);
        BigInteger unknown = BigInteger.valueOf(3);
        when(statusProvider.getStatus(SERIAL, CA)).thenReturn(CertificateStatusDTO.good());
        when(statusProvider.getStatus(revoked, CA)).thenReturn(CertificateStatusDTO.revoked(NOW));
        when(statusProvider.getStatus(unknown, CA)).thenReturn(CertificateStatusDTO.unknown());

        SingleResp[] responses = basic(service.respond(
                        request(null, certId(CA, SERIAL), certId(CA, revoked), certId(CA, unknown)), REQUESTER_IP))
                .getResponses();

        assertEquals(3, responses.length);
        assertEquals(SERIAL, responses[0].getCertID().getSerialNumber());
        assertNull(responses[0].getCertStatus());
        assertEquals(revoked, responses[1].getCertID().getSerialNumber());
        assertInstanceOf(RevokedStatus.class, responses[1].getCertStatus());
        assertEquals(unknown, responses[2].getCertID().getSerialNumber());
        assertInstanceOf(UnknownStatus.class, responses[2].getCertStatus());
    }

    @Test
    void respond_moreEntriesThanLimit_shouldReturnMalformedRequest() throws Exception {
        service = newService(properties(Duration.ofMinutes(5), 2, 16384));

        OcspResponseDTO result = service.respond(
                request(null, certId(CA, BigInteger.ONE), certId(CA, BigInteger.TWO), certId(CA, BigInteger.TEN)),
                REQUESTER_IP);

        assertUnsigned(result, OCSPRespBuilder.MALFORMED_REQUEST);
        verifyNoInteractions(statusProvider);
    }

    @Test
    void respond_shouldRecordMetrics() {
        when(statusProvider.getStatus(SERIAL, CA)).thenReturn(CertificateStatusDTO.good());

        service.respond(request(null, certId(CA, SERIAL)), REQUESTER_IP);
        service.respond(new byte[] {1}, REQUESTER_IP);

        assertEquals(
                1,
                meterRegistry
                        .get("ocsp.requests")
                        .tag("response_status", "successful")
                        .timer()
                        .count());
        assertEquals(
                1,
                meterRegistry
                        .get("ocsp.requests")
                        .tag("response_status", "malformedRequest")
                        .timer()
                        .count());
        assertEquals(
                1.0,
                meterRegistry
                        .get("ocsp.cert.status")
                        .tag("cert_status", "good")
                        .counter()
                        .count());
    }

    @Test
    void respond_shouldPassMatchedIssuerToProvider() {
        when(statusProvider.getStatus(any(), any())).thenReturn(CertificateStatusDTO.good());

        service.respond(request(null, certId(CA, SERIAL)), REQUESTER_IP);

        verify(statusProvider).getStatus(eq(SERIAL), eq(CA));
    }

    @ParameterizedTest
    @CsvSource({
        "0, successful",
        "1, malformedRequest",
        "2, internalError",
        "3, tryLater",
        "5, sigRequired",
        "6, unauthorized",
        "4, unknown(4)"
    })
    void responseStatusName_shouldMapRfcNames(int status, String name) {
        assertEquals(name, OcspResponderServiceImpl.responseStatusName(status));
    }
}
