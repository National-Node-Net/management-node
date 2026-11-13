/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp.OcspTestPki.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import org.bouncycastle.asn1.ocsp.OCSPObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.ocsp.BasicOCSPResp;
import org.bouncycastle.cert.ocsp.CertificateID;
import org.bouncycastle.cert.ocsp.OCSPResp;
import org.bouncycastle.cert.ocsp.OCSPRespBuilder;
import org.bouncycastle.cert.ocsp.RevokedStatus;
import org.bouncycastle.cert.ocsp.SingleResp;
import org.bouncycastle.cert.ocsp.UnknownStatus;
import org.bouncycastle.operator.jcajce.JcaContentVerifierProviderBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.vault.authentication.TokenAuthentication;
import org.springframework.vault.client.VaultEndpoint;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.vault.support.VaultResponse;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.vault.VaultContainer;
import uk.gov.dbt.ndtp.ia.node.management.config.OcspProperties;
import uk.gov.dbt.ndtp.ia.node.management.exception.PkiCertificateExpiredException;
import uk.gov.dbt.ndtp.ia.node.management.exception.PkiCertificateNotFoundException;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.OcspResponseDTO;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.certificate.RevocationReason;
import uk.gov.dbt.ndtp.ia.node.management.service.data.OrganisationCertificateService;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.RevocationTestProperties;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.VaultPkiService;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.status.CertificateStatusServiceImpl;
import uk.gov.dbt.ndtp.ia.node.management.utils.cryptography.CertificateSerials;
import uk.gov.dbt.ndtp.ia.node.management.utils.cryptography.PemUtil;

/**
 * End-to-end OCSP tests against a real Vault PKI engine running in a container.
 * Skipped when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class OcspVaultIntegrationTest {

    private static final String TOKEN = "ocsp-it-root-token";
    private static final String PKI_MOUNT = "pki-int";
    private static final String SIGNER_COMMON_NAME = "ocsp-signer.federation.test";

    @Container
    private static final VaultContainer<?> VAULT = new VaultContainer<>(DockerImageName.parse("hashicorp/vault:1.16"))
            .withVaultToken(TOKEN)
            .withInitCommand(
                    "secrets enable -path=pki-int pki",
                    "secrets tune -max-lease-ttl=87600h pki-int",
                    "write -field=serial_number pki-int/root/generate/internal common_name=federation-test-ca"
                            + " ttl=87600h",
                    "write pki-int/roles/default-role allow_any_name=true key_type=any max_ttl=72h");

    private static VaultTemplate vault;
    private static VaultPkiService pkiService;
    private static OrganisationCertificateService certificateService;
    private static OcspProperties properties;
    private static OcspSignerService signerService;
    private static OcspResponderService responder;
    private static X509CertificateHolder ca;

    @BeforeAll
    static void setUp() throws Exception {
        VaultEndpoint endpoint = VaultEndpoint.create(VAULT.getHost(), VAULT.getFirstMappedPort());
        endpoint.setScheme("http");
        vault = new VaultTemplate(endpoint, new TokenAuthentication(TOKEN));
        pkiService = new VaultPkiService(vault, PKI_MOUNT, "default-role", "24h", RevocationTestProperties.fast());
        // The revocation reason comes from the database; no reason is recorded unless a test says so.
        certificateService = mock(OrganisationCertificateService.class);
        ca = new X509CertificateHolder(
                PemUtil.parseCertificate(pkiService.getIntermediateCertificate().getCertificate())
                        .getEncoded());

        // OCSP signer: key generated in memory, certificate issued by Vault with the default role
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        properties = new OcspProperties(
                Duration.ofMinutes(5),
                10,
                16384,
                "vault",
                new OcspProperties.Signer(SIGNER_COMMON_NAME, Duration.ofHours(1), Duration.ofMinutes(5)));
        signerService = new OcspSignerServiceImpl(pkiService, properties, meterRegistry);
        signerService.refresh();
        assertTrue(signerService.currentSigner().isPresent(), "OCSP signer should be issued by Vault");

        responder = new OcspResponderServiceImpl(
                new CertificateStatusServiceImpl(
                        new VaultCertificateStatusProvider(vault, certificateService, PKI_MOUNT, meterRegistry),
                        meterRegistry),
                signerService,
                properties,
                meterRegistry);
    }

    private static BigInteger issueLeaf(String commonName) {
        return issueLeaf(commonName, "1h");
    }

    private static BigInteger issueLeaf(String commonName, String ttl) {
        String serial = pkiService
                .signCsr(csrPem("CN=" + commonName, LEAF_KEYS), Optional.empty(), Optional.of(ttl))
                .getSerialNumber();
        return CertificateSerials.parse(serial);
    }

    /** Revokes directly in Vault, as an administrator would, bypassing the Management Node. */
    private static Instant revoke(BigInteger serial) {
        VaultResponse response =
                vault.write(PKI_MOUNT + "/revoke", Map.of("serial_number", CertificateSerials.toVault(serial)));
        return Instant.ofEpochSecond(((Number) response.getData().get("revocation_time")).longValue());
    }

    private static BasicOCSPResp query(byte[] nonce, BigInteger... serials) throws Exception {
        CertificateID[] ids = Arrays.stream(serials).map(s -> certId(ca, s)).toArray(CertificateID[]::new);
        OcspResponseDTO result = responder.respond(request(nonce, ids), "127.0.0.1");
        OCSPResp resp = new OCSPResp(result.getEncoded());
        assertEquals(OCSPRespBuilder.SUCCESSFUL, resp.getStatus());
        return (BasicOCSPResp) resp.getResponseObject();
    }

    @Test
    void goodCertificate_shouldReturnGood() throws Exception {
        BigInteger serial = issueLeaf("good.federation.test");

        SingleResp single = query(null, serial).getResponses()[0];

        assertEquals(serial, single.getCertID().getSerialNumber());
        assertNull(single.getCertStatus(), "null certStatus means good");
    }

    @Test
    void revokedCertificate_shouldBeRevokedImmediatelyWithVaultRevocationTime() throws Exception {
        BigInteger serial = issueLeaf("revoked.federation.test");
        assertNull(query(null, serial).getResponses()[0].getCertStatus());

        Instant revokedAt = revoke(serial);

        RevokedStatus status = assertInstanceOf(
                RevokedStatus.class, query(null, serial).getResponses()[0].getCertStatus());
        assertEquals(revokedAt, status.getRevocationTime().toInstant());
    }

    @Test
    void serialNotIssuedByCa_shouldReturnUnknown() throws Exception {
        BigInteger serial = new BigInteger(128, new SecureRandom());

        assertInstanceOf(
                UnknownStatus.class, query(null, serial).getResponses()[0].getCertStatus());
    }

    @Test
    void response_shouldBeVerifiableWithCaViaIncludedSigner() throws Exception {
        BasicOCSPResp basic = query(null, issueLeaf("verify.federation.test"));

        X509CertificateHolder signer = basic.getCerts()[0];
        assertTrue(basic.isSignatureValid(new JcaContentVerifierProviderBuilder().build(signer)));
        assertTrue(signer.isSignatureValid(new JcaContentVerifierProviderBuilder().build(ca)));
        assertEquals(new X500Name("CN=" + SIGNER_COMMON_NAME), signer.getSubject());
    }

    @Test
    void response_shouldEchoNonceAndBeValidForFiveMinutes() throws Exception {
        byte[] nonce = new byte[16];
        new SecureRandom().nextBytes(nonce);
        Instant before = Instant.now().minusSeconds(1);

        BasicOCSPResp basic = query(nonce, issueLeaf("nonce.federation.test"));

        assertEquals(nonceExtension(nonce), basic.getExtension(OCSPObjectIdentifiers.id_pkix_ocsp_nonce));
        SingleResp single = basic.getResponses()[0];
        Instant thisUpdate = single.getThisUpdate().toInstant();
        assertFalse(thisUpdate.isBefore(before.truncatedTo(ChronoUnit.SECONDS)));
        assertFalse(thisUpdate.isAfter(Instant.now()));
        assertEquals(
                Duration.ofMinutes(5),
                Duration.between(thisUpdate, single.getNextUpdate().toInstant()));
    }

    @Test
    void multipleCertificates_shouldAnswerEveryEntry() throws Exception {
        BigInteger good = issueLeaf("multi-good.federation.test");
        BigInteger revoked = issueLeaf("multi-revoked.federation.test");
        revoke(revoked);
        BigInteger unknown = new BigInteger(128, new SecureRandom());

        SingleResp[] responses = query(null, good, revoked, unknown).getResponses();

        assertEquals(3, responses.length);
        assertNull(responses[0].getCertStatus());
        assertInstanceOf(RevokedStatus.class, responses[1].getCertStatus());
        assertInstanceOf(UnknownStatus.class, responses[2].getCertStatus());
    }

    @Test
    void vaultUnreachable_shouldReturnTryLater() throws Exception {
        VaultEndpoint closed = VaultEndpoint.create("localhost", 1);
        closed.setScheme("http");
        VaultTemplate unreachable = new VaultTemplate(closed, new TokenAuthentication(TOKEN));
        OcspResponderService unavailableResponder = new OcspResponderServiceImpl(
                new CertificateStatusServiceImpl(
                        new VaultCertificateStatusProvider(
                                unreachable, certificateService, PKI_MOUNT, new SimpleMeterRegistry()),
                        new SimpleMeterRegistry()),
                signerService,
                properties,
                new SimpleMeterRegistry());

        OcspResponseDTO result = unavailableResponder.respond(
                request(null, certId(ca, issueLeaf("unreachable.federation.test"))), "127.0.0.1");

        assertEquals(OCSPRespBuilder.TRY_LATER, new OCSPResp(result.getEncoded()).getStatus());
    }

    // ---------------------------------------------------------------------------------------
    // Revocation through VaultPkiService, as the revocation API does it
    // ---------------------------------------------------------------------------------------

    /** Acceptance criterion "OCSP reflects revocation": same revocation time and reason as the API reported. */
    @Test
    void revokeCertificate_shouldBeReportedByOcspWithTheSameTimeAndTheRecordedReason() throws Exception {
        BigInteger serial = issueLeaf("api-revoked.federation.test");
        when(certificateService.findRevocationReason(CertificateSerials.toStored(serial)))
                .thenReturn(Optional.of(RevocationReason.KEY_COMPROMISE));

        Instant revokedAt = pkiService.revokeCertificate(serial);

        RevokedStatus status = assertInstanceOf(
                RevokedStatus.class, query(null, serial).getResponses()[0].getCertStatus());
        assertEquals(revokedAt, status.getRevocationTime().toInstant());
        assertTrue(status.hasRevocationReason());
        assertEquals(RevocationReason.KEY_COMPROMISE.crlReasonCode(), status.getRevocationReason());
    }

    /** A retry after a failure must not move the revocation time. */
    @Test
    void revokeCertificate_repeated_shouldReturnTheOriginalTime() throws Exception {
        BigInteger serial = issueLeaf("api-repeated.federation.test");

        Instant first = pkiService.revokeCertificate(serial);
        Thread.sleep(1100);
        Instant second = pkiService.revokeCertificate(serial);

        assertEquals(first, second);
    }

    @Test
    void revokeCertificate_unknownSerial_shouldThrowNotFound() {
        BigInteger serial = new BigInteger(128, new SecureRandom());

        assertThrows(PkiCertificateNotFoundException.class, () -> pkiService.revokeCertificate(serial));
    }

    @Test
    void revokeCertificate_expiredCertificate_shouldThrowExpiredAndRecordNothing() throws Exception {
        BigInteger serial = issueLeaf("api-expired.federation.test", "2s");
        Thread.sleep(4000);

        assertThrows(PkiCertificateExpiredException.class, () -> pkiService.revokeCertificate(serial));
        VaultResponse cert = vault.read(PKI_MOUNT + "/cert/" + CertificateSerials.toVault(serial));
        assertEquals(0L, ((Number) cert.getData().get("revocation_time")).longValue());
    }
}
