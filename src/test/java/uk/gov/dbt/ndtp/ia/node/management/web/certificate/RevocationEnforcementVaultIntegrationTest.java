/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.web.certificate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp.OcspTestPki.LEAF_KEYS;
import static uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp.OcspTestPki.OTHER_CA;
import static uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp.OcspTestPki.OTHER_CA_KEYS;
import static uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp.OcspTestPki.csrPem;
import static uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp.OcspTestPki.leafCertificate;
import static uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp.OcspTestPki.toX509;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigInteger;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.cache.CacheAutoConfiguration;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.vault.authentication.TokenAuthentication;
import org.springframework.vault.client.VaultEndpoint;
import org.springframework.vault.core.VaultTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.vault.VaultContainer;
import uk.gov.dbt.ndtp.ia.node.management.config.CertificateStatusCacheConfig;
import uk.gov.dbt.ndtp.ia.node.management.config.RevocationConfig;
import uk.gov.dbt.ndtp.ia.node.management.config.RevocationProperties;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.OrganisationCertificateDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.RevokeCertificateRequestDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.dto.certificate.SignCertResponseDTO;
import uk.gov.dbt.ndtp.ia.node.management.model.jwt.EnhancedPrincipal;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.certificate.CertificateType;
import uk.gov.dbt.ndtp.ia.node.management.persistency.entity.certificate.RevocationReason;
import uk.gov.dbt.ndtp.ia.node.management.service.data.OrganisationCertificateService;
import uk.gov.dbt.ndtp.ia.node.management.service.data.OrganisationService;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.CertificateRevocationProvider;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.CertificateRevocationProviderImpl;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.RevocationRateLimiter;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.RevocationTestProperties;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.VaultPkiService;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp.CertificateStatusProvider;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp.VaultCertificateStatusProvider;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.status.CertificateStatusService;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.status.CertificateStatusServiceImpl;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.status.FederationCaService;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.status.FederationCaServiceImpl;
import uk.gov.dbt.ndtp.ia.node.management.utils.cryptography.CertificateSerials;
import uk.gov.dbt.ndtp.ia.node.management.utils.cryptography.PemUtil;

/**
 * The revocation check on requests against a real Vault PKI engine in a container, with the application's own
 * status cache (Caffeine) and the revocation API's provider. Skipped when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
class RevocationEnforcementVaultIntegrationTest {

    private static final String TOKEN = "enforcement-it-root-token";
    private static final String PKI_MOUNT = "pki-int";
    private static final String PATH = "/api/v1/configuration/producer";
    private static final long ORGANISATION = 1L;
    private static final EnhancedPrincipal ADMIN = new EnhancedPrincipal("admin-a", "FEDERATOR_ENV", "ENV");

    @Container
    private static final VaultContainer<?> VAULT = new VaultContainer<>(DockerImageName.parse("hashicorp/vault:1.16"))
            .withVaultToken(TOKEN)
            .withInitCommand(
                    "secrets enable -path=pki-int pki",
                    "secrets tune -max-lease-ttl=87600h pki-int",
                    "write -field=serial_number pki-int/root/generate/internal common_name=federation-test-ca"
                            + " ttl=87600h",
                    "write pki-int/roles/default-role allow_any_name=true key_type=any max_ttl=72h");

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** The status backend behind the cache; a test swaps it to make Vault unreachable for status lookups only. */
    private static final AtomicReference<CertificateStatusProvider> STATUS_BACKEND = new AtomicReference<>();

    private static AnnotationConfigApplicationContext context;
    private static VaultTemplate vault;
    private static VaultPkiService pkiService;
    private static OrganisationCertificateService certificateService;
    private static CertificateStatusService statusService;
    private static FederationCaService federationCa;
    private static CertificateRevocationProvider revocationProvider;
    private static CertificateRevocationFilter filter;

    @BeforeAll
    static void setUp() {
        VaultEndpoint endpoint = VaultEndpoint.create(VAULT.getHost(), VAULT.getFirstMappedPort());
        endpoint.setScheme("http");
        vault = new VaultTemplate(endpoint, new TokenAuthentication(TOKEN));
        RevocationProperties properties = RevocationTestProperties.fast();
        pkiService = new VaultPkiService(vault, PKI_MOUNT, "default-role", "24h", properties);
        certificateService = mock(OrganisationCertificateService.class);
        MeterRegistry meterRegistry = new SimpleMeterRegistry();

        // The status service as the application runs it: behind Spring's caching proxy, with a Caffeine cache.
        context = new AnnotationConfigApplicationContext();
        context.getEnvironment()
                .getPropertySources()
                .addFirst(new MapPropertySource(
                        "test",
                        Map.of(
                                "spring.cache.type",
                                "caffeine",
                                "spring.cache.cache-names",
                                CertificateStatusCacheConfig.CACHE_NAME)));
        context.registerBean(MeterRegistry.class, () -> meterRegistry);
        STATUS_BACKEND.set(new VaultCertificateStatusProvider(vault, certificateService, PKI_MOUNT, meterRegistry));
        context.registerBean(
                CertificateStatusProvider.class,
                () -> (serial, issuer) -> STATUS_BACKEND.get().getStatus(serial, issuer));
        context.register(
                RevocationConfig.class,
                CertificateStatusCacheConfig.class,
                CacheAutoConfiguration.class,
                CertificateStatusServiceImpl.class);
        context.refresh();
        statusService = context.getBean(CertificateStatusService.class);

        federationCa = new FederationCaServiceImpl(pkiService, properties);
        OrganisationService organisationService = mock(OrganisationService.class);
        when(organisationService.findIdByKey("ENV")).thenReturn(Optional.of(ORGANISATION));
        when(certificateService.recordRevocation(any(), any(), any(), any(), any()))
                .thenReturn(true);
        revocationProvider = new CertificateRevocationProviderImpl(
                pkiService,
                certificateService,
                organisationService,
                mock(RevocationRateLimiter.class),
                statusService,
                federationCa);
        filter = newFilter(statusService, federationCa);
    }

    @AfterAll
    static void tearDown() {
        if (context != null) {
            context.close();
        }
    }

    private static CertificateRevocationFilter newFilter(CertificateStatusService status, FederationCaService ca) {
        return new CertificateRevocationFilter(
                status,
                ca,
                RevocationTestProperties.fast(),
                OBJECT_MAPPER,
                new SimpleMeterRegistry(),
                Clock.systemUTC());
    }

    /** A certificate issued by Vault, held as its organisation's current certificate. */
    private static X509Certificate issue(String commonName) {
        SignCertResponseDTO signed =
                pkiService.signCsr(csrPem("CN=" + commonName, LEAF_KEYS), Optional.empty(), Optional.of("1h"));
        X509Certificate certificate = PemUtil.parseCertificate(signed.getCertificate());
        when(certificateService.findBySerialNumber(CertificateSerials.toStored(certificate.getSerialNumber())))
                .thenReturn(Optional.of(OrganisationCertificateDTO.builder()
                        .id(10L)
                        .organisationId(ORGANISATION)
                        .serialNumber(CertificateSerials.toStored(certificate.getSerialNumber()))
                        .type(CertificateType.AUTOMATED)
                        .build()));
        return certificate;
    }

    private static MockHttpServletResponse call(CertificateRevocationFilter filter, X509Certificate certificate)
            throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", PATH);
        request.setAttribute("jakarta.servlet.request.X509Certificate", new X509Certificate[] {certificate});
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        if (response.getStatus() == 200) {
            assertNotNull(chain.getRequest(), "a request that is not refused reaches the rest of the chain");
        } else {
            assertNull(chain.getRequest(), "a refused request does not reach the rest of the chain");
        }
        return response;
    }

    private static String code(MockHttpServletResponse response) throws Exception {
        JsonNode body = OBJECT_MAPPER.readTree(response.getContentAsString());
        return body.get("code").asText();
    }

    private static Cache.ValueWrapper cached(BigInteger serial) {
        return context.getBean(CacheManager.class)
                .getCache(CertificateStatusCacheConfig.CACHE_NAME)
                .get(federationCa.current().keyHash() + ":" + serial.toString(16));
    }

    private static void revokeDirectlyInVault(X509Certificate certificate) {
        vault.write(
                PKI_MOUNT + "/revoke",
                Map.of("serial_number", CertificateSerials.toVault(certificate.getSerialNumber())));
    }

    @Test
    void goodCertificate_shouldPassAndBeCached() throws Exception {
        X509Certificate certificate = issue("good.federation.test");

        assertEquals(200, call(filter, certificate).getStatus());
        assertNotNull(cached(certificate.getSerialNumber()), "the status is cached under <issuerKeyHash>:<serial>");
    }

    @Test
    void certificateRevokedThroughTheApi_shouldBeRefusedOnItsNextRequest() throws Exception {
        X509Certificate certificate = issue("revoked-by-api.federation.test");
        assertEquals(200, call(filter, certificate).getStatus(), "a GOOD status is cached before the revocation");

        revocationProvider.revoke(
                RevokeCertificateRequestDTO.builder()
                        .serialNumber(CertificateSerials.toVault(certificate.getSerialNumber()))
                        .reason(RevocationReason.KEY_COMPROMISE)
                        .build(),
                ADMIN);

        MockHttpServletResponse response = call(filter, certificate);
        assertEquals(401, response.getStatus(), "enforced at once, without waiting for the TTL");
        assertEquals("CERTIFICATE_REVOKED", code(response));
    }

    @Test
    void certificateRevokedDirectlyInVault_withoutACachedStatus_shouldBeRefused() throws Exception {
        X509Certificate certificate = issue("revoked-in-vault.federation.test");
        revokeDirectlyInVault(certificate);

        MockHttpServletResponse response = call(filter, certificate);

        assertEquals(401, response.getStatus());
        assertEquals("CERTIFICATE_REVOKED", code(response));
    }

    /** The documented exposure window: a revocation that bypasses the API is enforced when the entry expires. */
    @Test
    void certificateRevokedDirectlyInVault_withACachedStatus_shouldPassUntilTheEntryIsGone() throws Exception {
        X509Certificate certificate = issue("revoked-behind-cache.federation.test");
        assertEquals(200, call(filter, certificate).getStatus());
        revokeDirectlyInVault(certificate);

        assertEquals(200, call(filter, certificate).getStatus(), "the cached GOOD status is still in use");

        statusService.evict(federationCa.current(), certificate.getSerialNumber());
        assertEquals("CERTIFICATE_REVOKED", code(call(filter, certificate)));
    }

    @Test
    void certificateOfAnotherAuthority_shouldBeRefusedAsUnknown() throws Exception {
        X509Certificate foreign = toX509(leafCertificate(BigInteger.valueOf(0x4a1f), OTHER_CA, OTHER_CA_KEYS));

        MockHttpServletResponse response = call(filter, foreign);

        assertEquals(401, response.getStatus());
        assertEquals("CERTIFICATE_STATUS_UNKNOWN", code(response));
    }

    @Test
    void vaultUnreachable_withoutACachedStatus_shouldFailClosedAndCacheNothing() throws Exception {
        X509Certificate certificate = issue("vault-down.federation.test");
        // The federation CA is already known; only the status lookup finds Vault unreachable.
        federationCa.current();
        VaultEndpoint closed = VaultEndpoint.create("localhost", 1);
        closed.setScheme("http");
        CertificateStatusProvider reachable = STATUS_BACKEND.getAndSet(new VaultCertificateStatusProvider(
                new VaultTemplate(closed, new TokenAuthentication(TOKEN)),
                certificateService,
                PKI_MOUNT,
                new SimpleMeterRegistry()));
        try {
            MockHttpServletResponse response = call(filter, certificate);

            assertEquals(503, response.getStatus());
            assertEquals("CERTIFICATE_STATUS_UNAVAILABLE", code(response));
            assertNull(cached(certificate.getSerialNumber()), "a failed lookup leaves nothing in the cache");
        } finally {
            STATUS_BACKEND.set(reachable);
        }
        assertEquals(200, call(filter, certificate).getStatus(), "the next request asks Vault again");
    }

    @Test
    void vaultUnreachable_beforeTheFederationCaIsKnown_shouldFailClosed() throws Exception {
        VaultEndpoint closed = VaultEndpoint.create("localhost", 1);
        closed.setScheme("http");
        VaultPkiService unreachablePki = new VaultPkiService(
                new VaultTemplate(closed, new TokenAuthentication(TOKEN)),
                PKI_MOUNT,
                "default-role",
                "24h",
                RevocationTestProperties.fast());
        CertificateRevocationFilter failing =
                newFilter(statusService, new FederationCaServiceImpl(unreachablePki, RevocationTestProperties.fast()));
        X509Certificate certificate = issue("ca-unknown.federation.test");

        MockHttpServletResponse response = call(failing, certificate);

        assertEquals(503, response.getStatus());
        assertEquals("CERTIFICATE_STATUS_UNAVAILABLE", code(response));
    }
}
