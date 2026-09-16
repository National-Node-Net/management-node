/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import uk.gov.dbt.ndtp.ia.node.management.config.OpaProperties;
import uk.gov.dbt.ndtp.ia.node.management.model.UnknownIdentifiers;
import uk.gov.dbt.ndtp.ia.node.management.model.jwt.EnhancedPrincipal;
import uk.gov.dbt.ndtp.ia.node.management.service.data.OrganisationService;
import uk.gov.dbt.ndtp.ia.node.management.service.data.PolicyAttributeScopeCode;
import uk.gov.dbt.ndtp.ia.node.management.service.data.PolicyAttributeService;

/** Covers how each PDP input fact is sourced: token claims, certificate, database and request. */
@ExtendWith(MockitoExtension.class)
class PolicyInputFactoryTest {

    @Mock
    private OrganisationService organisationService;

    @Mock
    private PolicyAttributeService policyAttributeService;

    private PolicyInputFactory factory;

    @BeforeEach
    void setUp() {
        OpaProperties properties = new OpaProperties(
                true,
                "http://opa",
                "/v1/data/management_node/decision",
                Duration.ofSeconds(2),
                Duration.ofSeconds(3),
                List.of("/api/v1/configuration/**"),
                List.of("content-type", "x-correlation-id", "authorization"),
                false,
                false);
        factory = new PolicyInputFactory(organisationService, policyAttributeService, properties);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void action_and_resourceKind_areDerivedFromThePath() {
        authenticate("service-account-catalogue-ui", "catalogue-ui", Map.of());

        PolicyInput input = factory.create(request("/api/v1/product/discover"), null);

        assertThat(input.action()).isEqualTo("discover");
        assertThat(input.resource().kind()).isEqualTo("product");
        assertThat(input.resource().id()).isNull();
    }

    @Test
    void serviceAccountToken_isReportedAsServiceWithTheClientId() {
        authenticate("service-account-catalogue-ui", "catalogue-ui", Map.of());

        PolicySubject subject =
                factory.create(request("/api/v1/product/discover"), null).subject();

        assertThat(subject.kind()).isEqualTo(PolicySubject.KIND_SERVICE);
        assertThat(subject.userId()).isEqualTo("catalogue-ui");
    }

    @Test
    void humanToken_isReportedAsUserWithTheEmail() {
        authenticate("j.okafor", "catalogue-ui", Map.of("email", "j.okafor@nhsengland.nhs.uk"));

        PolicySubject subject =
                factory.create(request("/api/v1/product/discover"), null).subject();

        assertThat(subject.kind()).isEqualTo(PolicySubject.KIND_USER);
        assertThat(subject.userId()).isEqualTo("j.okafor@nhsengland.nhs.uk");
    }

    @Test
    void organisation_comesFromThePrincipalWithItsDatabaseAttributes() {
        authenticate("service-account-catalogue-ui", "catalogue-ui", Map.of());
        when(organisationService.findIdByKey("FEDERATOR_ENV")).thenReturn(Optional.of(42L));
        when(policyAttributeService.findAttributeMap(42L, PolicyAttributeScopeCode.ORGANISATION))
                .thenReturn(Map.of("nationality", "GB"));

        PolicyOrganisation organisation = factory.create(request("/api/v1/product/discover"), null)
                .subject()
                .organisation();

        assertThat(organisation.key()).isEqualTo("FEDERATOR_ENV");
        assertThat(organisation.attributes()).containsEntry("nationality", "GB");
    }

    @Test
    void organisation_keyStillReachesThePdpWhenNoOrganisationRowMatches() {
        authenticate("service-account-catalogue-ui", "catalogue-ui", Map.of());
        when(organisationService.findIdByKey("FEDERATOR_ENV")).thenReturn(Optional.empty());

        PolicyOrganisation organisation = factory.create(request("/api/v1/product/discover"), null)
                .subject()
                .organisation();

        // The policy decides what an unknown organisation means; the factory does not fail.
        assertThat(organisation.key()).isEqualTo("FEDERATOR_ENV");
        assertThat(organisation.attributes()).isEmpty();
    }

    @Test
    void organisation_isEmptyWhenTheTokenNamesNoOrganisation() {
        authenticateWithOrganisation("service-account-catalogue-ui", "catalogue-ui", UnknownIdentifiers.UNKNOWN_ORG);

        PolicyOrganisation organisation = factory.create(request("/api/v1/product/discover"), null)
                .subject()
                .organisation();

        // UNKNOWN_ORG is the principal's sentinel for a missing claim, not a real key.
        assertThat(organisation.key()).isNull();
        assertThat(organisation.attributes()).isEmpty();
    }

    @Test
    void clientId_comesFromThePrincipalAlongsideTheOrganisation() {
        authenticate("j.okafor", "catalogue-ui", Map.of("email", "j.okafor@nhsengland.nhs.uk"));

        PolicySubject subject =
                factory.create(request("/api/v1/product/discover"), null).subject();

        // A human caller: user_id identifies the person, clientId the application they used.
        assertThat(subject.userId()).isEqualTo("j.okafor@nhsengland.nhs.uk");
        assertThat(subject.clientId()).isEqualTo("catalogue-ui");
    }

    @Test
    void headers_forwardOnlyAllowListedOnesAndNeverTheCredential() {
        authenticate("service-account-catalogue-ui", "catalogue-ui", Map.of());
        MockHttpServletRequest request = request("/api/v1/product/discover");
        request.addHeader("Content-Type", "application/json");
        request.addHeader("X-Correlation-Id", "abc-123");
        request.addHeader("Authorization", "Bearer super-secret");
        request.addHeader("X-Not-Listed", "nope");

        Map<String, String> headers = factory.create(request, null).request().headers();

        assertThat(headers)
                .containsEntry("content-type", "application/json")
                .containsEntry("x-correlation-id", "abc-123");
        // Authorization is configured above, yet must still be refused.
        assertThat(headers).doesNotContainKey("authorization").doesNotContainKey("x-not-listed");
        assertThat(headers.toString()).doesNotContain("super-secret");
    }

    @Test
    void query_keepsEveryValueOfARepeatedParameter() {
        authenticate("service-account-catalogue-ui", "catalogue-ui", Map.of());
        MockHttpServletRequest request = request("/api/v1/product/discover");
        request.setQueryString("topic=a&topic=b");

        assertThat(factory.create(request, null).request().query()).containsEntry("topic", List.of("a", "b"));
    }

    @Test
    void query_readsThePagingParametersOffTheQueryString() {
        authenticate("service-account-catalogue-ui", "catalogue-ui", Map.of());
        MockHttpServletRequest request = request("/api/v1/product/discover");
        request.setQueryString("page=1&last_page=222");

        Map<String, List<String>> query =
                factory.create(request, null).request().query();

        assertThat(query).containsEntry("page", List.of("1")).containsEntry("last_page", List.of("222"));
    }

    @Test
    void query_percentDecodesValues() {
        authenticate("service-account-catalogue-ui", "catalogue-ui", Map.of());
        MockHttpServletRequest request = request("/api/v1/product/discover");
        request.setQueryString("q=two%20words&flag");

        Map<String, List<String>> query =
                factory.create(request, null).request().query();

        assertThat(query).containsEntry("q", List.of("two words")).containsEntry("flag", List.of(""));
    }

    @Test
    void query_isEmptyWhenThereIsNoQueryString() {
        authenticate("service-account-catalogue-ui", "catalogue-ui", Map.of());

        assertThat(factory.create(request("/api/v1/product/discover"), null)
                        .request()
                        .query())
                .isEmpty();
    }

    @Test
    void body_suppliedByTheCallerIsUsedAsIs() {
        authenticate("service-account-catalogue-ui", "catalogue-ui", Map.of());
        Object criteria = Map.of("topic", "planning");

        assertThat(factory.create(request("/api/v1/product/discover"), criteria)
                        .request()
                        .body())
                .isEqualTo(criteria);
    }

    @Test
    void timestampClaims_areEmittedAsEpochSecondsNotSerialisedInstants() {
        Instant expiry = Instant.ofEpochSecond(1789392949L);
        authenticate("service-account-catalogue-ui", "catalogue-ui", Map.of("exp", expiry));

        Map<String, Object> token = factory.create(request("/api/v1/product/discover"), null)
                .subject()
                .token();

        assertThat(token).containsEntry("exp", 1789392949L);
    }

    @Test
    void body_isCarriedThroughWhenTheCallerSuppliesOne() {
        authenticate("service-account-catalogue-ui", "catalogue-ui", Map.of());
        Object body = Map.of("topic", "planning");

        assertThat(factory.create(request("/api/v1/product/discover"), body)
                        .request()
                        .body())
                .isEqualTo(body);
    }

    private MockHttpServletRequest request(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        request.setRequestURI(path);
        return request;
    }

    private void authenticate(String preferredUsername, String clientId, Map<String, Object> extraClaims) {
        authenticateWithOrganisation(preferredUsername, clientId, "FEDERATOR_ENV", extraClaims);
    }

    private void authenticateWithOrganisation(String preferredUsername, String clientId, String organisation) {
        authenticateWithOrganisation(preferredUsername, clientId, organisation, Map.of());
    }

    private void authenticateWithOrganisation(
            String preferredUsername, String clientId, String organisation, Map<String, Object> extraClaims) {
        Jwt.Builder jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claim("preferred_username", preferredUsername)
                .claim("client_id", clientId);
        extraClaims.forEach(jwt::claim);
        EnhancedPrincipal principal = new EnhancedPrincipal("subject", clientId, organisation);
        SecurityContextHolder.getContext().setAuthentication(new TestToken(jwt.build(), principal));
    }

    /** A {@link JwtAuthenticationToken} whose principal is the node's {@link EnhancedPrincipal}. */
    private static final class TestToken extends JwtAuthenticationToken {
        private final EnhancedPrincipal principal;

        private TestToken(Jwt jwt, EnhancedPrincipal principal) {
            super(jwt, List.of());
            this.principal = principal;
        }

        @Override
        public Object getPrincipal() {
            return principal;
        }
    }
}
