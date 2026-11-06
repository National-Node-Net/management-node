/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp;

import static org.junit.jupiter.api.Assertions.*;
import static uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp.OcspTestPki.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.cert.CertificateFactory;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.AuthorityInformationAccess;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.Extensions;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cloud.vault.config.VaultAutoConfiguration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.vault.authentication.AppRoleAuthentication;
import org.springframework.vault.authentication.AppRoleAuthenticationOptions;
import org.springframework.vault.authentication.AppRoleAuthenticationOptions.RoleId;
import org.springframework.vault.authentication.AppRoleAuthenticationOptions.SecretId;
import org.springframework.vault.authentication.LifecycleAwareSessionManager;
import org.springframework.vault.authentication.event.AfterLoginEvent;
import org.springframework.vault.authentication.event.AfterLoginTokenRenewedEvent;
import org.springframework.vault.client.ClientHttpRequestFactoryFactory;
import org.springframework.vault.client.VaultClients;
import org.springframework.vault.client.VaultEndpoint;
import org.springframework.vault.core.VaultKeyValueOperationsSupport.KeyValueBackend;
import org.springframework.vault.core.VaultTemplate;
import org.springframework.vault.support.ClientOptions;
import org.springframework.vault.support.SslConfiguration;
import org.springframework.web.client.RestOperations;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.VaultPkiService;
import uk.gov.dbt.ndtp.ia.node.management.utils.cryptography.PemUtil;

/**
 * Checks the acceptance criteria of the Management Node's Vault access against a real server.
 * The policies are the files OpenTofu applies. The roles and mount settings repeat docker/vault/tofu
 * (roles.tf, auth.tf, mounts.tf, pki_config.tf), so keep them in step.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class AbstractVaultAccessIntegrationTest {

    private static final String ROOT_TOKEN = "vault-access-it-root-token";
    private static final String PKI_MOUNT = "pki-int";
    private static final String KV_MOUNT = "mn-kv";
    private static final String KV_PATH = "management-node/ocsp-signer";
    private static final String POLICY_DIR = "docker/vault/tofu/policies/";
    private static final String APPLICATION_YML = "src/main/resources/application.yml";
    private static final String AUDIT_FILE = "/tmp/audit.log";
    private static final String AUDIT_SERVER_CONFIG =
            """
            audit "file" "file" {
              options {
                file_path = "/tmp/audit.log"
              }
            }
            """;

    private static final String SIGNER_CN = "ocsp-signer.management-node";
    private static final int SIGNER_MAX_TTL_SECONDS = 604770;
    private static final Duration SIGNER_MAX_LIFETIME = Duration.ofDays(7);
    private static final String OCSP_SIGNING_OID = "1.3.6.1.5.5.7.3.9";
    private static final int KEY_CERT_SIGN = 5; // position in X509Certificate.getKeyUsage()
    private static final String CA_ISSUERS_URL = "http://vault.test/v1/pki-int/ca";
    private static final int TOKEN_TTL_SECONDS = 900;

    private static final String MANAGEMENT_NODE_ROLE = "management-node";

    private static final String SHORT_LIVED_ROLE = "short-lived";

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();
    private final GenericContainer<?> server;
    private final boolean auditInServerConfig;
    private final List<String> enginePublicPaths;

    private VaultEndpoint endpoint;
    private ClientHttpRequestFactory requestFactory;
    private RestOperations rest;
    private String managementNodeToken;
    private JsonNode caRequest;

    /**
     * @param auditInServerConfig the server refuses audit devices through the API, as current OpenBao does, so the
     *     device is declared in its configuration file (the case {@code enable_audit_device = false} is for)
     * @param enginePublicPaths endpoints that only this engine answers without a token
     */
    protected AbstractVaultAccessIntegrationTest(
            String image, String envPrefix, boolean auditInServerConfig, List<String> enginePublicPaths) {
        this.auditInServerConfig = auditInServerConfig;
        this.enginePublicPaths = enginePublicPaths;
        server = new GenericContainer<>(DockerImageName.parse(image))
                .withExposedPorts(8200)
                .withEnv(envPrefix + "_DEV_ROOT_TOKEN_ID", ROOT_TOKEN)
                .withEnv(envPrefix + "_DEV_LISTEN_ADDRESS", "0.0.0.0:8200")
                .withCreateContainerCmdModifier(
                        cmd -> cmd.getHostConfig().withCapAdd(com.github.dockerjava.api.model.Capability.IPC_LOCK))
                .waitingFor(Wait.forHttp("/v1/sys/health").forPort(8200).forStatusCode(200));
        // Without high availability sys/step-down answers 204 to anyone and does nothing, so 403 would prove nothing.
        List<String> command = new ArrayList<>(List.of("server", "-dev", "-dev-ha"));
        if (auditInServerConfig) {
            server.withCopyToContainer(Transferable.of(AUDIT_SERVER_CONFIG), "/tmp/audit.hcl");
            command.add("-config=/tmp/audit.hcl");
        }
        server.withCommand(command.toArray(String[]::new));
    }

    @BeforeAll
    void setUp() throws Exception {
        server.start();
        endpoint = VaultEndpoint.create(server.getHost(), server.getFirstMappedPort());
        endpoint.setScheme("http");
        requestFactory = ClientHttpRequestFactoryFactory.create(new ClientOptions(), SslConfiguration.unconfigured());
        rest = VaultClients.createRestTemplate(endpoint, requestFactory);

        if (!auditInServerConfig) {
            admin("POST", "sys/audit/file", Map.of("type", "file", "options", Map.of("file_path", AUDIT_FILE)));
        }
        admin("POST", "sys/mounts/" + KV_MOUNT, Map.of("type", "kv", "options", Map.of("version", "2")));
        admin(
                "POST",
                KV_MOUNT + "/data/" + KV_PATH,
                Map.of("data", Map.of("private_key", "test", "certificate", "test")));
        admin("POST", "sys/mounts/pki-other", Map.of("type", "pki"));
        admin(
                "POST",
                "sys/mounts/" + PKI_MOUNT,
                Map.of(
                        "type",
                        "pki",
                        "config",
                        Map.of(
                                "max_lease_ttl",
                                "87600h",
                                "audit_non_hmac_request_keys",
                                List.of("serial_number", "common_name"),
                                "audit_non_hmac_response_keys",
                                List.of("serial_number", "revocation_time"))));
        caRequest = admin(
                "POST",
                PKI_MOUNT + "/intermediate/generate/internal",
                Map.of("common_name", "federation-test-intermediate", "key_type", "rsa", "key_bits", 2048));
        admin(
                "POST",
                PKI_MOUNT + "/intermediate/set-signed",
                Map.of(
                        "certificate",
                        signedChainFromOfflineRoot(
                                caRequest.path("data").path("csr").asText())));
        admin(
                "POST",
                PKI_MOUNT + "/config/urls",
                Map.of(
                        "issuing_certificates", List.of(CA_ISSUERS_URL),
                        "crl_distribution_points", List.of("http://vault.test/v1/pki-int/crl")));
        admin("POST", PKI_MOUNT + "/config/crl", Map.of("expiry", "72h", "auto_rebuild", true, "ocsp_disable", true));
        admin(
                "POST",
                PKI_MOUNT + "/roles/default-role",
                Map.of("allow_any_name", true, "key_type", "any", "key_bits", 0, "max_ttl", 31536000));
        admin("POST", PKI_MOUNT + "/roles/ocsp-signer", ocspSignerRole());

        writePolicy("mgmt-node-pki");

        admin("POST", "sys/auth/approle", Map.of("type", "approle"));
        createAppRole(MANAGEMENT_NODE_ROLE, List.of("mgmt-node-pki"), TOKEN_TTL_SECONDS, 14400);
        createAppRole(SHORT_LIVED_ROLE, List.of("mgmt-node-pki"), 6, 14);

        managementNodeToken =
                appRoleAuthentication(MANAGEMENT_NODE_ROLE).login().getToken();
    }

    @AfterAll
    void tearDown() {
        server.stop();
    }

    /** Stands in for the manual signing step: the intermediate signed by an offline root, then the root. */
    private static String signedChainFromOfflineRoot(String csrPem) throws Exception {
        KeyPair rootKeys = rsaKeyPair();
        X500Name rootName = new X500Name("CN=Offline Test Root");
        PKCS10CertificationRequest csr;
        try (PEMParser parser = new PEMParser(new StringReader(csrPem))) {
            csr = (PKCS10CertificationRequest) parser.readObject();
        }
        X509CertificateHolder root = signCa(
                rootName,
                rootKeys,
                rootName,
                SubjectPublicKeyInfo.getInstance(rootKeys.getPublic().getEncoded()));
        X509CertificateHolder intermediate =
                signCa(rootName, rootKeys, csr.getSubject(), csr.getSubjectPublicKeyInfo());
        return toPem(intermediate) + toPem(root);
    }

    private static X509CertificateHolder signCa(
            X500Name issuer, KeyPair issuerKeys, X500Name subject, SubjectPublicKeyInfo publicKey) throws Exception {
        Instant now = Instant.now();
        return new X509v3CertificateBuilder(
                        issuer,
                        BigInteger.valueOf(System.nanoTime()),
                        Date.from(now.minus(Duration.ofMinutes(1))),
                        Date.from(now.plus(Duration.ofDays(365))),
                        subject,
                        publicKey)
                .addExtension(Extension.basicConstraints, true, new BasicConstraints(true))
                .addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign))
                .addExtension(
                        Extension.subjectKeyIdentifier,
                        false,
                        new JcaX509ExtensionUtils().createSubjectKeyIdentifier(publicKey))
                .build(new JcaContentSignerBuilder("SHA256withRSA").build(issuerKeys.getPrivate()));
    }

    private static Map<String, Object> ocspSignerRole() {
        Map<String, Object> role = new HashMap<>();
        role.put("allow_any_name", false);
        role.put("allowed_domains", List.of(SIGNER_CN));
        role.put("allow_bare_domains", true);
        role.put("allow_subdomains", false);
        role.put("allow_glob_domains", false);
        role.put("allow_localhost", false);
        role.put("allow_ip_sans", false);
        role.put("use_csr_sans", false);
        role.put("require_cn", true);
        role.put("server_flag", false);
        role.put("client_flag", false);
        role.put("code_signing_flag", false);
        role.put("email_protection_flag", false);
        role.put("basic_constraints_valid_for_non_ca", true);
        role.put("key_type", "any");
        role.put("key_bits", 0);
        role.put("key_usage", List.of("DigitalSignature"));
        role.put("ext_key_usage", List.of("OCSPSigning"));
        role.put("max_ttl", SIGNER_MAX_TTL_SECONDS);
        role.put("not_before_duration", "30s");
        return role;
    }

    private void writePolicy(String name) throws Exception {
        String policy = Files.readString(Path.of(POLICY_DIR + name + ".hcl.tftpl"))
                .replace("${pki_mount}", PKI_MOUNT)
                .replace("${kv_mount}", KV_MOUNT)
                .replace("${ocsp_signer_kv_path}", KV_PATH);
        admin("POST", "sys/policies/acl/" + name, Map.of("policy", policy));
    }

    private void createAppRole(String name, List<String> policies, int ttl, int maxTtl) throws Exception {
        admin(
                "POST",
                "auth/approle/role/" + name,
                Map.of(
                        "token_policies",
                        policies,
                        "token_no_default_policy",
                        true,
                        "token_type",
                        "service",
                        "token_ttl",
                        ttl,
                        "token_max_ttl",
                        maxTtl,
                        "secret_id_num_uses",
                        0));
    }

    private String roleId(String role) throws Exception {
        return admin("GET", "auth/approle/role/" + role + "/role-id", null)
                .path("data")
                .path("role_id")
                .asText();
    }

    private String secretId(String role) throws Exception {
        return admin("POST", "auth/approle/role/" + role + "/secret-id", Map.of())
                .path("data")
                .path("secret_id")
                .asText();
    }

    private AppRoleAuthentication appRoleAuthentication(String role) throws Exception {
        return new AppRoleAuthentication(
                AppRoleAuthenticationOptions.builder()
                        .roleId(RoleId.provided(roleId(role)))
                        .secretId(SecretId.provided(secretId(role)))
                        .build(),
                rest);
    }

    /** The production application.yml on top of Spring Cloud Vault's own auto-configuration. */
    private ApplicationContextRunner applicationConfiguration() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(VaultAutoConfiguration.class))
                .withInitializer(context -> {
                    try {
                        new YamlPropertySourceLoader()
                                .load("application", new FileSystemResource(APPLICATION_YML))
                                .forEach(context.getEnvironment().getPropertySources()::addLast);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                })
                .withPropertyValues(
                        "VAULT_ADDR=http://" + server.getHost() + ":" + server.getFirstMappedPort(),
                        "VAULT_AUTHENTICATION=approle");
    }

    private record ApiResponse(int status, JsonNode body) {}

    private ApiResponse call(String method, String path, String token, Object body) throws Exception {
        return send(method, path, token, null, body == null ? null : mapper.writeValueAsString(body));
    }

    private ApiResponse send(String method, String path, String token, String contentType, String body)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                        URI.create("http://" + server.getHost() + ":" + server.getFirstMappedPort() + "/v1/" + path))
                .method(
                        method,
                        body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("X-Vault-Token", token);
        }
        if (contentType != null) {
            request.header("Content-Type", contentType);
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        String text = response.body();
        JsonNode json = text == null || text.isBlank() ? mapper.createObjectNode() : parseOrEmpty(text);
        return new ApiResponse(response.statusCode(), json);
    }

    private JsonNode parseOrEmpty(String text) {
        try {
            return mapper.readTree(text);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    private JsonNode admin(String method, String path, Object body) throws Exception {
        ApiResponse response = call(method, path, ROOT_TOKEN, body);
        assertTrue(
                response.status() / 100 == 2,
                () -> method + " " + path + " failed with " + response.status() + ": " + response.body());
        return response.body();
    }

    private ApiResponse signOcsp(String token, String csr, Map<String, Object> extra) throws Exception {
        return sign("ocsp-signer", token, csr, extra);
    }

    private ApiResponse sign(String role, String token, String csr, Map<String, Object> extra) throws Exception {
        Map<String, Object> body = new HashMap<>(extra);
        body.put("csr", csr);
        return call("POST", PKI_MOUNT + "/sign/" + role, token, body);
    }

    private static X509Certificate certificate(ApiResponse response) {
        assertEquals(200, response.status(), () -> response.body().toString());
        return PemUtil.parseCertificate(
                response.body().path("data").path("certificate").asText());
    }

    private static Duration validity(X509Certificate certificate) {
        return Duration.between(
                certificate.getNotBefore().toInstant(),
                certificate.getNotAfter().toInstant());
    }

    private String issueLeaf(String ttl) throws Exception {
        return admin(
                        "POST",
                        PKI_MOUNT + "/sign/default-role",
                        Map.of("csr", csrPem("CN=node.federation.test", LEAF_KEYS), "ttl", ttl))
                .path("data")
                .path("serial_number")
                .asText();
    }

    private List<String> revokedSerials() throws Exception {
        ApiResponse response = call("LIST", PKI_MOUNT + "/certs/revoked", managementNodeToken, null);
        List<String> serials = new ArrayList<>();
        response.body().path("data").path("keys").forEach(key -> serials.add(key.asText()));
        return serials;
    }

    private boolean isOnCrl(String serial) throws Exception {
        String pem = call("GET", PKI_MOUNT + "/cert/crl", null, null)
                .body()
                .path("data")
                .path("certificate")
                .asText();
        if (pem.isBlank()) {
            return false; // OpenBao has no CRL until the first rebuild
        }
        X509CRL crl = (X509CRL) CertificateFactory.getInstance("X.509")
                .generateCRL(new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
        return crl.getRevokedCertificate(new BigInteger(serial.replace(":", ""), 16)) != null;
    }

    private ApiResponse login(Map<String, Object> credentials) throws Exception {
        return call("POST", "auth/approle/login", null, credentials);
    }

    private static String thirtyDaysFromNow() {
        return Instant.now()
                .plus(Duration.ofDays(30))
                .truncatedTo(ChronoUnit.SECONDS)
                .toString();
    }

    @Test
    void login_returnsShortLivedTokenCarryingOnlyMgmtNodePki() throws Exception {
        JsonNode token = call("GET", "auth/token/lookup-self", managementNodeToken, null)
                .body()
                .path("data");

        assertEquals(List.of("mgmt-node-pki"), mapper.convertValue(token.path("policies"), List.class));
        assertTrue(token.path("ttl").asInt() <= TOKEN_TTL_SECONDS);
        assertTrue(token.path("renewable").asBoolean());
        assertEquals(
                200,
                call("POST", "auth/token/renew-self", managementNodeToken, Map.of())
                        .status());
    }

    @Test
    void login_isRefusedWithBadCredentials() throws Exception {
        String roleId = roleId(MANAGEMENT_NODE_ROLE);
        String secretId = secretId(MANAGEMENT_NODE_ROLE);
        String unknown = "00000000-0000-0000-0000-000000000000";

        assertEquals(400, login(Map.of("role_id", roleId, "secret_id", unknown)).status());
        assertEquals(
                400, login(Map.of("role_id", unknown, "secret_id", secretId)).status());
        assertEquals(400, login(Map.of("role_id", roleId)).status());
        assertEquals(
                200, login(Map.of("role_id", roleId, "secret_id", secretId)).status());

        admin(
                "POST",
                "auth/approle/role/" + MANAGEMENT_NODE_ROLE + "/secret-id/destroy",
                Map.of("secret_id", secretId));

        assertEquals(
                400, login(Map.of("role_id", roleId, "secret_id", secretId)).status());
    }

    @Test
    void revokedToken_stopsWorkingAndTheServiceCanLogInAgain() throws Exception {
        Map<String, Object> credentials =
                Map.of("role_id", roleId(MANAGEMENT_NODE_ROLE), "secret_id", secretId(MANAGEMENT_NODE_ROLE));
        String token =
                login(credentials).body().path("auth").path("client_token").asText();
        assertEquals(200, call("GET", "auth/token/lookup-self", token, null).status());

        admin("POST", "auth/token/revoke", Map.of("token", token));

        assertEquals(403, call("GET", "auth/token/lookup-self", token, null).status());
        assertEquals(200, login(credentials).status());
    }

    /** A corrected secret ID keeps failing until the lock ends or an administrator lifts it. */
    @Test
    void appRole_isLockedAfterRepeatedBadLogins() throws Exception {
        String role = "lockout-test";
        createAppRole(role, List.of("mgmt-node-pki"), TOKEN_TTL_SECONDS, 14400);
        String roleId = roleId(role);
        Map<String, Object> rightCredentials = Map.of("role_id", roleId, "secret_id", secretId(role));
        Map<String, Object> wrongCredentials =
                Map.of("role_id", roleId, "secret_id", "00000000-0000-0000-0000-000000000000");

        for (int attempt = 0; attempt < 5; attempt++) {
            assertEquals(400, login(wrongCredentials).status());
        }
        assertEquals(403, login(rightCredentials).status());

        String accessor = admin("GET", "sys/auth", null)
                .path("data")
                .path("approle/")
                .path("accessor")
                .asText();
        admin("POST", "sys/locked-users/" + accessor + "/unlock/" + roleId, Map.of());

        assertEquals(200, login(rightCredentials).status());
    }

    @Test
    void sessionManager_renewsTokenAndLogsInAgainAfterMaximumTtl() throws Exception {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.initialize();
        LifecycleAwareSessionManager sessionManager = new LifecycleAwareSessionManager(
                appRoleAuthentication(SHORT_LIVED_ROLE),
                scheduler,
                rest,
                new LifecycleAwareSessionManager.FixedTimeoutRefreshTrigger(2, TimeUnit.SECONDS));
        AtomicInteger renewals = new AtomicInteger();
        AtomicInteger logins = new AtomicInteger();
        sessionManager.addAuthenticationListener(event -> {
            if (event instanceof AfterLoginTokenRenewedEvent) {
                renewals.incrementAndGet();
            } else if (event instanceof AfterLoginEvent) {
                logins.incrementAndGet();
            }
        });
        VaultTemplate vault = new VaultTemplate(endpoint, requestFactory, sessionManager);
        try {
            for (int second = 0; second < 24; second++) {
                assertNotNull(vault.read("auth/token/lookup-self"), "request failed after " + second + "s");
                Thread.sleep(1000);
            }
        } finally {
            sessionManager.destroy();
            scheduler.shutdown();
        }

        assertTrue(renewals.get() >= 1, "token should have been renewed");
        assertTrue(logins.get() >= 2, "service should have logged in again after the maximum TTL");
    }

    @Test
    void statusLookupAndRevocation_returnCertificateAndRevocationTime() throws Exception {
        String serial = issueLeaf("1h");

        ApiResponse before = call("GET", PKI_MOUNT + "/cert/" + serial, managementNodeToken, null);
        assertEquals(200, before.status());
        assertEquals(0, before.body().path("data").path("revocation_time").asLong());

        ApiResponse revoked = call("POST", PKI_MOUNT + "/revoke", managementNodeToken, Map.of("serial_number", serial));
        assertEquals(200, revoked.status(), () -> revoked.body().toString());
        long revocationTime =
                revoked.body().path("data").path("revocation_time").asLong();
        assertTrue(revocationTime > 0);

        JsonNode after = call("GET", PKI_MOUNT + "/cert/" + serial, managementNodeToken, null)
                .body()
                .path("data");
        assertEquals(revocationTime, after.path("revocation_time").asLong());
        assertFalse(after.path("certificate").asText().isBlank());
        assertTrue(revokedSerials().contains(serial));
    }

    @Test
    void revoke_keepsTheFirstTimeAndRefusesUnknownSerials() throws Exception {
        Map<String, Object> request = Map.of("serial_number", issueLeaf("1h"));

        long first = call("POST", PKI_MOUNT + "/revoke", managementNodeToken, request)
                .body()
                .path("data")
                .path("revocation_time")
                .asLong();
        ApiResponse second = call("POST", PKI_MOUNT + "/revoke", managementNodeToken, request);

        assertEquals(200, second.status());
        assertEquals(first, second.body().path("data").path("revocation_time").asLong());
        assertEquals(
                400,
                call("POST", PKI_MOUNT + "/revoke", managementNodeToken, Map.of("serial_number", "11:22:33:44"))
                        .status());
        assertEquals(
                404,
                call("GET", PKI_MOUNT + "/cert/11-22-33-44", managementNodeToken, null)
                        .status());
    }

    /** The call succeeds with a warning and records nothing, so a revocation API has to pass that warning on. */
    @Test
    void revoke_doesNotRecordAnExpiredCertificate() throws Exception {
        String serial = issueLeaf("2s");
        Thread.sleep(4000);

        ApiResponse response =
                call("POST", PKI_MOUNT + "/revoke", managementNodeToken, Map.of("serial_number", serial));

        assertEquals(200, response.status());
        assertTrue(response.body().path("warnings").toString().contains("expired"), () -> response.body()
                .toString());
        assertEquals(
                0,
                call("GET", PKI_MOUNT + "/cert/" + serial, managementNodeToken, null)
                        .body()
                        .path("data")
                        .path("revocation_time")
                        .asLong());
    }

    /** With auto_rebuild a revocation waits for the next rebuild. cert/:serial, which OCSP reads, is immediate. */
    @Test
    void crl_showsARevocationAfterTheNextRebuild() throws Exception {
        String serial = issueLeaf("1h");
        assertEquals(
                200,
                call("POST", PKI_MOUNT + "/revoke", managementNodeToken, Map.of("serial_number", serial))
                        .status());

        assertTrue(call("GET", PKI_MOUNT + "/cert/" + serial, managementNodeToken, null)
                        .body()
                        .path("data")
                        .path("revocation_time")
                        .asLong()
                > 0);
        assertFalse(isOnCrl(serial));

        admin("GET", PKI_MOUNT + "/crl/rotate", null);

        assertTrue(isOnCrl(serial));
    }

    /** The server takes the chain and only fails on the first signature, which is what make verify catches. */
    @Test
    void chainFromAnotherCsr_isAcceptedButTheMountCannotSign() throws Exception {
        String mount = "pki-wrong-chain";
        admin("POST", "sys/mounts/" + mount, Map.of("type", "pki"));
        admin(
                "POST",
                mount + "/intermediate/generate/internal",
                Map.of("common_name", "wrong-chain-test", "key_type", "rsa", "key_bits", 2048));
        admin(
                "POST",
                mount + "/intermediate/set-signed",
                Map.of("certificate", signedChainFromOfflineRoot(csrPem("CN=wrong-chain-test", LEAF_KEYS))));
        admin("POST", mount + "/roles/any", Map.of("allow_any_name", true));

        ApiResponse signed = call(
                "POST",
                mount + "/sign/any",
                ROOT_TOKEN,
                Map.of("csr", csrPem("CN=node.federation.test", LEAF_KEYS), "ttl", "1h"));
        admin("DELETE", "sys/mounts/" + mount, null);

        assertEquals(500, signed.status(), () -> signed.body().toString());
    }

    @Test
    void ocspSigner_isIssuedWithOcspSigningEkuAndWithinMaximumLifetime() throws Exception {
        X509Certificate rsa = certificate(signOcsp(
                managementNodeToken,
                csrPem("CN=" + SIGNER_CN, LEAF_KEYS),
                Map.of("ttl", SIGNER_MAX_TTL_SECONDS + "s")));

        assertEquals(List.of(OCSP_SIGNING_OID), rsa.getExtendedKeyUsage());
        assertEquals(-1, rsa.getBasicConstraints());
        assertTrue(validity(rsa).compareTo(SIGNER_MAX_LIFETIME) <= 0, () -> "validity was " + validity(rsa));

        KeyPair ecKeys = ecKeyPair();
        assertEquals(
                List.of(OCSP_SIGNING_OID),
                certificate(signOcsp(managementNodeToken, csrPem("CN=" + SIGNER_CN, ecKeys), Map.of()))
                        .getExtendedKeyUsage());
    }

    @Test
    void ocspSignerRole_rejectsOtherNames() throws Exception {
        assertEquals(
                400,
                signOcsp(managementNodeToken, csrPem("CN=other.example", LEAF_KEYS), Map.of())
                        .status());
        assertEquals(
                400,
                signOcsp(managementNodeToken, csrPem("CN=sub." + SIGNER_CN, LEAF_KEYS), Map.of())
                        .status());
        assertEquals(
                400,
                signOcsp(managementNodeToken, csrPem("CN=" + SIGNER_CN, LEAF_KEYS), Map.of("alt_names", "evil.example"))
                        .status());
    }

    /** Neither engine can reject this without Sentinel: the TTL is capped and a warning returned. */
    @Test
    void ocspSignerRole_capsTtlAboveTheMaximum() throws Exception {
        ApiResponse response =
                signOcsp(managementNodeToken, csrPem("CN=" + SIGNER_CN, LEAF_KEYS), Map.of("ttl", "720h"));

        X509Certificate certificate = certificate(response);
        assertTrue(
                validity(certificate).compareTo(SIGNER_MAX_LIFETIME) <= 0,
                () -> "validity was " + validity(certificate));
        assertTrue(response.body().path("warnings").toString().contains("maxTTL"));
    }

    /**
     * Neither engine checks not_after against max_ttl, as the last assertion shows, so the policy refuses the
     * parameter on both signing paths.
     */
    @Test
    void notAfter_cannotGetAroundTheMaximumLifetime() throws Exception {
        String farAway = thirtyDaysFromNow();
        String signerCsr = csrPem("CN=" + SIGNER_CN, LEAF_KEYS);
        Map<String, Object> notAfter = Map.of("not_after", farAway);
        Map<String, Object> notAfterAndTtl = Map.of("not_after", farAway, "ttl", "1h");

        for (String role : List.of("ocsp-signer", "default-role")) {
            assertEquals(
                    403, sign(role, managementNodeToken, signerCsr, notAfter).status(), role);
            assertEquals(
                    403,
                    sign(role, managementNodeToken, signerCsr, notAfterAndTtl).status(),
                    role);
            assertEquals(
                    200,
                    sign(role, managementNodeToken, signerCsr, Map.of("ttl", "1h"))
                            .status(),
                    role);
        }

        X509Certificate unrestricted = certificate(signOcsp(ROOT_TOKEN, signerCsr, notAfter));
        assertTrue(validity(unrestricted).compareTo(SIGNER_MAX_LIFETIME) > 0);
    }

    /** No spelling or transport of not_after gets through. On a write the server does not read the query string. */
    @Test
    void notAfter_isRefusedHoweverItIsSent() throws Exception {
        String farAway = thirtyDaysFromNow();
        String csr = csrPem("CN=" + SIGNER_CN, LEAF_KEYS);
        String json = mapper.writeValueAsString(Map.of("csr", csr, "not_after", farAway));
        String form = "csr=" + URLEncoder.encode(csr, StandardCharsets.UTF_8) + "&not_after="
                + URLEncoder.encode(farAway, StandardCharsets.UTF_8);

        for (String role : List.of("ocsp-signer", "default-role")) {
            String path = PKI_MOUNT + "/sign/" + role;
            assertEquals(
                    403,
                    sign(role, managementNodeToken, csr, Map.of("NOT_AFTER", farAway))
                            .status(),
                    role);
            assertEquals(
                    403,
                    sign(role, managementNodeToken, csr, Map.of("Not_After", farAway))
                            .status(),
                    role);
            assertEquals(403, send("PUT", path, managementNodeToken, null, json).status(), role);
            assertEquals(
                    403,
                    send("PATCH", path, managementNodeToken, "application/merge-patch+json", json)
                            .status(),
                    role);
            assertEquals(
                    403,
                    send("POST", path, managementNodeToken, "application/x-www-form-urlencoded", form)
                            .status(),
                    role);

            X509Certificate fromQuery = certificate(
                    call("POST", path + "?not_after=" + farAway, managementNodeToken, Map.of("csr", csr, "ttl", "1h")));
            assertTrue(validity(fromQuery).compareTo(Duration.ofHours(2)) < 0, role);
        }
        assertEquals(
                403,
                call(
                                "POST",
                                PKI_MOUNT + "/issuer/default/sign/default-role",
                                managementNodeToken,
                                Map.of("csr", csr, "not_after", farAway))
                        .status());
    }

    /** Any of these reaching a signing path under another name would also escape the rule that refuses not_after. */
    @Test
    void signingPaths_cannotBeReachedThroughAnotherSpelling() throws Exception {
        Map<String, Object> request =
                Map.of("csr", csrPem("CN=" + SIGNER_CN, LEAF_KEYS), "not_after", thirtyDaysFromNow());
        List<String> signed = new ArrayList<>();

        for (String role : List.of("ocsp-signer", "default-role")) {
            for (String path : List.of(
                    PKI_MOUNT + "/sign/" + role + "/",
                    PKI_MOUNT + "//sign/" + role,
                    PKI_MOUNT + "/sign//" + role,
                    PKI_MOUNT + "/sign/./" + role,
                    PKI_MOUNT + "/sign/x/../" + role,
                    PKI_MOUNT + "/sign/" + role + "%2F",
                    PKI_MOUNT + "/sign%2F" + role,
                    PKI_MOUNT + "/sign/" + role.toUpperCase(Locale.ROOT))) {
                if (call("POST", path, managementNodeToken, request).status() / 100 == 2) {
                    signed.add(path);
                }
            }
        }

        assertEquals(List.of(), signed);
    }

    /** sign/:role cannot produce a CA, so the CSR is not rejected: its CA flag and SANs are dropped. */
    @Test
    void ocspSignerRole_neverIssuesCaCertificate() throws Exception {
        X509Certificate certificate = certificate(signOcsp(managementNodeToken, caCsrPem(LEAF_KEYS), Map.of()));

        assertEquals(-1, certificate.getBasicConstraints());
        BasicConstraints constraints =
                BasicConstraints.fromExtensions(new X509CertificateHolder(certificate.getEncoded()).getExtensions());
        assertNotNull(constraints, "CA:FALSE should be explicit");
        assertFalse(constraints.isCA());
        assertFalse(certificate.getKeyUsage()[KEY_CERT_SIGN]);
        assertFalse(certificate.getSubjectAlternativeNames().toString().contains("evil.example"));
    }

    /** default-role signs any name, so a CSR must not be able to make the result a CA or an OCSP signer. */
    @Test
    void defaultRole_ignoresCaAndOcspSigningAskedForInTheCsr() throws Exception {
        X509Certificate certificate =
                certificate(sign("default-role", managementNodeToken, caCsrPem(LEAF_KEYS), Map.of("ttl", "1h")));

        assertEquals(-1, certificate.getBasicConstraints());
        assertFalse(certificate.getKeyUsage()[KEY_CERT_SIGN]);
        assertFalse(certificate.getExtendedKeyUsage().contains(OCSP_SIGNING_OID));
    }

    private static String caCsrPem(KeyPair keys) throws Exception {
        Extensions extensions = new Extensions(new Extension[] {
            new Extension(Extension.basicConstraints, true, new BasicConstraints(true).getEncoded()),
            new Extension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign).getEncoded()),
            new Extension(
                    Extension.extendedKeyUsage,
                    false,
                    new ExtendedKeyUsage(KeyPurposeId.id_kp_OCSPSigning).getEncoded()),
            new Extension(
                    Extension.subjectAlternativeName,
                    false,
                    new GeneralNames(new GeneralName(GeneralName.dNSName, "evil.example")).getEncoded())
        });
        var csr = new JcaPKCS10CertificationRequestBuilder(new X500Name("CN=" + SIGNER_CN), keys.getPublic())
                .addAttribute(PKCSObjectIdentifiers.pkcs_9_at_extensionRequest, extensions)
                .build(new JcaContentSignerBuilder("SHA256withRSA").build(keys.getPrivate()));
        return PemUtil.toPem("CERTIFICATE REQUEST", csr.getEncoded());
    }

    private void assertAllDenied(String[][] requests) throws Exception {
        List<String> allowed = new ArrayList<>();
        for (String[] request : requests) {
            int status =
                    call(request[0], request[1], managementNodeToken, Map.of()).status();
            if (status != 403) {
                allowed.add(Arrays.toString(request) + " -> " + status);
            }
        }
        assertEquals(List.of(), allowed);
    }

    @Test
    void everythingElse_isDenied() throws Exception {
        assertAllDenied(new String[][] {
            {"POST", PKI_MOUNT + "/issue/default-role"},
            {"POST", PKI_MOUNT + "/issue/ocsp-signer"},
            {"POST", PKI_MOUNT + "/sign/other-role"},
            {"POST", PKI_MOUNT + "/revoke-with-key"},
            {"POST", PKI_MOUNT + "/tidy"},
            {"GET", PKI_MOUNT + "/config/urls"},
            {"POST", PKI_MOUNT + "/config/urls"},
            {"GET", PKI_MOUNT + "/roles/ocsp-signer"},
            {"GET", "sys/mounts"},
            {"GET", "sys/policies/acl/mgmt-node-pki"},
            {"GET", "sys/audit"},
            {"GET", "sys/auth"},
            {"POST", "sys/capabilities-self"},
            {"GET", "secret/data/anything"},
            {"POST", "cubbyhole/anything"},
            {"POST", "pki-other/sign/anything"},
        });
    }

    @Test
    void administration_isDenied() throws Exception {
        assertAllDenied(new String[][] {
            {"GET", PKI_MOUNT + "/crl/rotate"},
            {"GET", PKI_MOUNT + "/crl/rotate-delta"},
            {"POST", PKI_MOUNT + "/tidy-cancel"},
            {"GET", PKI_MOUNT + "/tidy-status"},
            {"GET", PKI_MOUNT + "/config/auto-tidy"},
            {"POST", PKI_MOUNT + "/config/auto-tidy"},
            {"GET", PKI_MOUNT + "/config/crl"},
            {"POST", PKI_MOUNT + "/config/crl"},
            {"GET", PKI_MOUNT + "/config/issuers"},
            {"POST", PKI_MOUNT + "/config/issuers"},
            {"GET", PKI_MOUNT + "/config/keys"},
            {"LIST", PKI_MOUNT + "/keys"},
            {"GET", PKI_MOUNT + "/key/default"},
            {"POST", PKI_MOUNT + "/issuer/default"},
            {"DELETE", PKI_MOUNT + "/issuer/default"},
            {"POST", PKI_MOUNT + "/issuer/default/revoke"},
            {"LIST", PKI_MOUNT + "/roles"},
            {"POST", PKI_MOUNT + "/roles/ocsp-signer"},
            {"DELETE", PKI_MOUNT + "/roles/ocsp-signer"},
            {"LIST", PKI_MOUNT + "/certs"},
            {"POST", PKI_MOUNT + "/revoke/anything"},
            {"DELETE", KV_MOUNT + "/data/" + KV_PATH},
            {"POST", KV_MOUNT + "/delete/" + KV_PATH},
            {"POST", KV_MOUNT + "/destroy/" + KV_PATH},
            {"POST", KV_MOUNT + "/undelete/" + KV_PATH},
            {"LIST", KV_MOUNT + "/metadata/management-node"},
            {"POST", "auth/token/create"},
            {"POST", "auth/token/revoke-self"},
            {"POST", "auth/token/lookup"},
            {"LIST", "auth/token/accessors"},
            {"LIST", "auth/approle/role"},
            {"GET", "auth/approle/role/" + MANAGEMENT_NODE_ROLE + "/role-id"},
            {"POST", "auth/approle/role/" + MANAGEMENT_NODE_ROLE + "/secret-id"},
            {"POST", "auth/approle/role/" + MANAGEMENT_NODE_ROLE},
            {"LIST", "identity/entity/id"},
            {"POST", "sys/seal"},
            {"POST", "sys/step-down"},
            {"LIST", "sys/leases/lookup"},
        });
    }

    @Test
    void protectedPaths_refuseCallsWithoutToken() throws Exception {
        String csr = csrPem("CN=" + SIGNER_CN, LEAF_KEYS);

        assertEquals(403, sign("ocsp-signer", null, csr, Map.of()).status());
        assertEquals(403, sign("default-role", null, csr, Map.of()).status());
        assertEquals(
                403,
                call("POST", PKI_MOUNT + "/revoke", null, Map.of("serial_number", "01"))
                        .status());
        assertEquals(403, call("GET", KV_MOUNT + "/data/" + KV_PATH, null, null).status());
    }

    @Test
    void caIssuingAndKeyExportPaths_areDenied() throws Exception {
        assertAllDenied(new String[][] {
            {"POST", PKI_MOUNT + "/root/generate/internal"},
            {"POST", PKI_MOUNT + "/root/generate/exported"},
            {"POST", PKI_MOUNT + "/root/sign-intermediate"},
            {"POST", PKI_MOUNT + "/root/sign-self-issued"},
            {"POST", PKI_MOUNT + "/root/rotate/internal"},
            {"POST", PKI_MOUNT + "/sign-intermediate"},
            {"POST", PKI_MOUNT + "/sign-verbatim"},
            {"POST", PKI_MOUNT + "/sign-verbatim/default-role"},
            {"POST", PKI_MOUNT + "/intermediate/generate/internal"},
            {"POST", PKI_MOUNT + "/intermediate/generate/exported"},
            {"POST", PKI_MOUNT + "/intermediate/set-signed"},
            {"POST", PKI_MOUNT + "/issuers/generate/root/internal"},
            {"POST", PKI_MOUNT + "/issuers/generate/intermediate/internal"},
            {"POST", PKI_MOUNT + "/issuer/default/sign-intermediate"},
            {"POST", PKI_MOUNT + "/issuer/default/sign-verbatim"},
            {"POST", PKI_MOUNT + "/issuer/default/sign/default-role"},
            {"POST", PKI_MOUNT + "/issuer/default/issue/default-role"},
            {"POST", PKI_MOUNT + "/keys/generate/exported"},
            {"POST", PKI_MOUNT + "/config/ca"},
        });
    }

    @Test
    void caPrivateKey_staysInsideTheServer() throws Exception {
        assertFalse(caRequest.toString().contains("PRIVATE KEY"));
        assertFalse(admin("GET", PKI_MOUNT + "/key/default", null).toString().contains("PRIVATE KEY"));

        String chain = call("GET", PKI_MOUNT + "/cert/ca_chain", managementNodeToken, null)
                .body()
                .path("data")
                .path("ca_chain")
                .asText();
        assertEquals(2, chain.split("BEGIN CERTIFICATE", -1).length - 1, chain);
    }

    /** These endpoints skip authentication by design, so no policy can turn them into a 403. */
    @Test
    void publicEndpoints_answerWithoutToken() throws Exception {
        String serial = issueLeaf("1h");
        List<String> paths = new ArrayList<>(List.of(
                "sys/health",
                "sys/seal-status",
                "sys/leader",
                "sys/init",
                "sys/internal/specs/openapi",
                "sys/internal/ui/mounts"));
        paths.addAll(enginePublicPaths);
        List.of(
                        "ca",
                        "ca/pem",
                        "ca_chain",
                        "crl",
                        "crl/pem",
                        "crl/delta",
                        "crl/delta/pem",
                        "cert/ca",
                        "cert/ca_chain",
                        "cert/crl",
                        "cert/delta-crl",
                        "cert/" + serial,
                        "issuer/default/pem",
                        "issuer/default/der",
                        "issuer/default/json",
                        "issuer/default/crl",
                        "issuer/default/crl/pem",
                        "issuer/default/crl/der",
                        "issuer/default/crl/delta",
                        "issuer/default/crl/delta/pem",
                        "issuer/default/crl/delta/der")
                .forEach(path -> paths.add(PKI_MOUNT + "/" + path));

        for (String path : paths) {
            int status = call("GET", path, null, null).status();
            assertTrue(status == 200 || status == 204, () -> path + " -> " + status); // 204: no CRL built yet
        }
        assertEquals(200, call("LIST", PKI_MOUNT + "/issuers", null, null).status());
    }

    /** Public, but it lists only the mounts the caller can use, which is none without a token. */
    @Test
    void mountListing_showsOnlyWhatTheTokenCanUse() throws Exception {
        JsonNode anonymous =
                call("GET", "sys/internal/ui/mounts", null, null).body().path("data");
        JsonNode visible = call("GET", "sys/internal/ui/mounts", managementNodeToken, null)
                .body()
                .path("data");

        assertTrue(anonymous.path("secret").isEmpty());
        assertTrue(anonymous.path("auth").isEmpty());
        assertEquals(
                Set.of(PKI_MOUNT + "/", KV_MOUNT + "/"),
                mapper.convertValue(visible.path("secret"), Map.class).keySet());
        assertEquals(
                Set.of("token/"),
                mapper.convertValue(visible.path("auth"), Map.class).keySet());
    }

    /** Also reachable without a token, but the built-in responder is off, so it refuses to answer. */
    @Test
    void builtInOcspResponder_answersUnauthorized() throws Exception {
        assertEquals(401, call("GET", PKI_MOUNT + "/ocsp/anything", null, null).status());
        assertEquals(401, call("POST", PKI_MOUNT + "/ocsp", null, Map.of()).status());
    }

    @Test
    void signerSecret_isTheOnlySecretTheManagementNodeCanRead() throws Exception {
        assertEquals(
                200,
                call("GET", KV_MOUNT + "/data/" + KV_PATH, managementNodeToken, null)
                        .status());
        assertEquals(
                403,
                call("POST", KV_MOUNT + "/data/" + KV_PATH, managementNodeToken, Map.of("data", Map.of()))
                        .status());
        assertEquals(
                403,
                call("GET", KV_MOUNT + "/data/other", managementNodeToken, null).status());
        assertEquals(
                403,
                call("GET", KV_MOUNT + "/metadata/" + KV_PATH, managementNodeToken, null)
                        .status());
    }

    /** The OCSP endpoint needs a JWT, which TLS libraries following AIA cannot send: see docs/OCSP.md. */
    @Test
    void issuedCertificate_hasNoOcspUrlInAia() throws Exception {
        JsonNode signed = admin(
                        "POST",
                        PKI_MOUNT + "/sign/default-role",
                        Map.of("csr", csrPem("CN=node.federation.test", LEAF_KEYS), "ttl", "1h"))
                .path("data");
        X509Certificate certificate =
                PemUtil.parseCertificate(signed.path("certificate").asText());

        AuthorityInformationAccess aia = AuthorityInformationAccess.fromExtensions(
                new X509CertificateHolder(certificate.getEncoded()).getExtensions());
        List<String> urls = Arrays.stream(aia.getAccessDescriptions())
                .map(description -> description.getAccessLocation().getName().toString())
                .toList();

        assertEquals(List.of(CA_ISSUERS_URL), urls);
    }

    /** Runs tidy with the auto-tidy flags from pki_config.tf; only the safety buffer is shortened. */
    @Test
    void tidy_removesExpiredCertificatesAndRevocationRecords() throws Exception {
        String expired = issueLeaf("8s");
        String expiredAndRevoked = issueLeaf("8s"); // revoke ignores a certificate that has already expired
        admin("POST", PKI_MOUNT + "/revoke", Map.of("serial_number", expiredAndRevoked));
        assertTrue(revokedSerials().contains(expiredAndRevoked));
        Thread.sleep(10000);

        admin(
                "POST",
                PKI_MOUNT + "/tidy",
                Map.of(
                        "safety_buffer",
                        "1s",
                        "tidy_cert_store",
                        true,
                        "tidy_revoked_certs",
                        true,
                        "tidy_revoked_cert_issuer_associations",
                        true));
        for (int attempt = 0; attempt < 50; attempt++) {
            String state = admin("GET", PKI_MOUNT + "/tidy-status", null)
                    .path("data")
                    .path("state")
                    .asText();
            if ("Finished".equals(state)) {
                break;
            }
            Thread.sleep(200);
        }

        assertEquals(
                404,
                call("GET", PKI_MOUNT + "/cert/" + expired, managementNodeToken, null)
                        .status());
        assertEquals(
                404,
                call("GET", PKI_MOUNT + "/cert/" + expiredAndRevoked, managementNodeToken, null)
                        .status());
        assertFalse(revokedSerials().contains(expiredAndRevoked));
    }

    @Test
    void applicationConfiguration_logsInAndKeepsExistingFeaturesWorking() throws Exception {
        String roleId = roleId(MANAGEMENT_NODE_ROLE);
        String secretId = secretId(MANAGEMENT_NODE_ROLE);

        applicationConfiguration()
                .withPropertyValues("VAULT_ROLE_ID=" + roleId, "VAULT_SECRET_ID=" + secretId)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    VaultTemplate vault = context.getBean(VaultTemplate.class);
                    VaultPkiService pki = new VaultPkiService(vault, PKI_MOUNT, "default-role", "1h");

                    assertEquals(
                            List.of("mgmt-node-pki"),
                            vault.read("auth/token/lookup-self").getData().get("policies"));
                    assertNotNull(pki.getIntermediateCertificate().getCertificate());
                    assertNotNull(pki.signCsr(
                                    csrPem("CN=node.federation.test", LEAF_KEYS), Optional.empty(), Optional.empty())
                            .getCertificate());
                    assertNotNull(
                            vault.opsForKeyValue(KV_MOUNT, KeyValueBackend.KV_2).get(KV_PATH));
                });
    }

    @Test
    void applicationConfiguration_doesNotStartWithoutCredentials() {
        applicationConfiguration()
                .withPropertyValues("VAULT_ROLE_ID=", "VAULT_SECRET_ID=")
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void auditLog_namesTheManagementNode() throws Exception {
        String serial = issueLeaf("1h");
        assertEquals(
                200,
                call("POST", PKI_MOUNT + "/revoke", managementNodeToken, Map.of("serial_number", serial))
                        .status());
        assertEquals(
                200,
                signOcsp(managementNodeToken, csrPem("CN=" + SIGNER_CN, LEAF_KEYS), Map.of())
                        .status());

        List<String> log =
                server.execInContainer("cat", AUDIT_FILE).getStdout().lines().toList();

        assertTrue(
                log.stream()
                        .anyMatch(line -> line.contains("\"type\":\"response\"")
                                && line.contains("\"path\":\"" + PKI_MOUNT + "/revoke\"")
                                && line.contains("\"role_name\":\"management-node\"")
                                && line.contains(serial)),
                "revoke should be logged with the Management Node's identity and the serial in clear");
        assertTrue(
                log.stream()
                        .anyMatch(line -> line.contains("\"path\":\"" + PKI_MOUNT + "/sign/ocsp-signer\"")
                                && line.contains("\"role_name\":\"management-node\"")),
                "sign/ocsp-signer should be logged with the Management Node's identity");
    }
}
