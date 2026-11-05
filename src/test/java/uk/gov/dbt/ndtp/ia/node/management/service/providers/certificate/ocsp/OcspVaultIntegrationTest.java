/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp;

import static org.junit.jupiter.api.Assertions.*;
import static uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp.OcspTestPki.*;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import org.bouncycastle.asn1.ocsp.OCSPObjectIdentifiers;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.vault.authentication.TokenAuthentication;
import org.springframework.vault.client.VaultEndpoint;
import org.springframework.vault.core.VaultKeyValueOperationsSupport.KeyValueBackend;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.vault.support.VaultResponse;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.vault.VaultContainer;
import uk.gov.dbt.ndtp.ia.node.management.config.OcspProperties;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.OcspResponseDTO;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.VaultPkiService;
import uk.gov.dbt.ndtp.ia.node.management.utils.cryptography.PemUtil;

/**
 * End-to-end OCSP tests against a real Vault PKI engine running in a container.
 * Skipped when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class OcspVaultIntegrationTest {

    private static final String TOKEN = "ocsp-it-root-token";
    private static final String PKI_MOUNT = "pki-int";
    private static final String KV_MOUNT = "secret";
    private static final String KV_PATH = "management-node/ocsp-signer";

    @Container
    private static final VaultContainer<?> VAULT = new VaultContainer<>(DockerImageName.parse("hashicorp/vault:1.16"))
            .withVaultToken(TOKEN)
            .withInitCommand(
                    "secrets enable -path=pki-int pki",
                    "secrets tune -max-lease-ttl=87600h pki-int",
                    "write -field=serial_number pki-int/root/generate/internal common_name=federation-test-ca"
                            + " ttl=87600h",
                    "write pki-int/roles/default-role allow_any_name=true max_ttl=72h",
                    "write pki-int/roles/ocsp-signer allow_any_name=true server_flag=false client_flag=false"
                            + " ext_key_usage=OCSPSigning key_usage=DigitalSignature max_ttl=24h");

    private static VaultTemplate vault;
    private static VaultPkiService pkiService;
    private static OcspProperties properties;
    private static OcspSignerService signerService;
    private static OcspResponderService responder;
    private static X509CertificateHolder ca;

    @BeforeAll
    static void setUp() throws Exception {
        VaultEndpoint endpoint = VaultEndpoint.create(VAULT.getHost(), VAULT.getFirstMappedPort());
        endpoint.setScheme("http");
        vault = new VaultTemplate(endpoint, new TokenAuthentication(TOKEN));
        pkiService = new VaultPkiService(vault, PKI_MOUNT, "default-role", "24h");
        ca = new X509CertificateHolder(
                PemUtil.parseCertificate(pkiService.getIntermediateCertificate().getCertificate())
                        .getEncoded());

        // Delegated OCSP signer: key generated locally, certificate issued by Vault, both stored in KV v2
        KeyPair signerKeys = rsaKeyPair();
        String signerCert = pkiService
                .signCsr(
                        csrPem("CN=ocsp-signer.federation.test", signerKeys),
                        Optional.of("ocsp-signer"),
                        Optional.of("1h"))
                .getCertificate();
        vault.opsForKeyValue(KV_MOUNT, KeyValueBackend.KV_2)
                .put(KV_PATH, Map.of("private_key", privateKeyPem(signerKeys), "certificate", signerCert));

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        properties = new OcspProperties(
                Duration.ofMinutes(5),
                10,
                16384,
                "vault",
                new OcspProperties.Signer(KV_MOUNT, KV_PATH, Duration.ofMinutes(5)));
        signerService = new OcspSignerServiceImpl(vault, pkiService, properties, meterRegistry);
        signerService.refresh();
        assertTrue(signerService.currentSigner().isPresent(), "OCSP signer should load from Vault");

        responder = new OcspResponderServiceImpl(
                new VaultCertificateStatusProvider(vault, PKI_MOUNT, meterRegistry),
                signerService,
                properties,
                meterRegistry);
    }

    private static BigInteger issueLeaf(String commonName) {
        String serial = pkiService
                .signCsr(csrPem("CN=" + commonName, LEAF_KEYS), Optional.empty(), Optional.of("1h"))
                .getSerialNumber();
        return new BigInteger(serial.replace(":", ""), 16);
    }

    private static Instant revoke(BigInteger serial) {
        VaultResponse response = vault.write(
                PKI_MOUNT + "/revoke", Map.of("serial_number", VaultCertificateStatusProvider.toVaultSerial(serial)));
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
        assertTrue(toX509(signer).getExtendedKeyUsage().contains(KeyPurposeId.id_kp_OCSPSigning.getId()));
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
                new VaultCertificateStatusProvider(unreachable, PKI_MOUNT, new SimpleMeterRegistry()),
                signerService,
                properties,
                new SimpleMeterRegistry());

        OcspResponseDTO result = unavailableResponder.respond(
                request(null, certId(ca, issueLeaf("unreachable.federation.test"))), "127.0.0.1");

        assertEquals(OCSPRespBuilder.TRY_LATER, new OCSPResp(result.getEncoded()).getStatus());
    }
}
