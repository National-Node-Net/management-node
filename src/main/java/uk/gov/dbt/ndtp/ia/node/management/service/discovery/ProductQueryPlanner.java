/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.discovery;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uk.gov.dbt.ndtp.ia.node.management.config.OpaProperties;
import uk.gov.dbt.ndtp.ia.node.management.exception.AccessRejectedException;
import uk.gov.dbt.ndtp.ia.node.management.model.policy.product.ProductPolicyContractDetails;
import uk.gov.dbt.ndtp.ia.node.management.persistency.repository.configuration.ProductDiscoveryRepository;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecision;

/**
 * Turns a policy decision into a query the database can run: it resolves what the caller may see,
 * refuses anything it cannot honour, and compiles the result.
 *
 * <p>Every endpoint that reads products goes through here - searching many
 * ({@code POST /api/v1/product/discover}) and reading one
 * ({@code GET /api/v1/product/{productId}}) - because the step that must never differ between them
 * is this one. Each refusal below is a place where getting it wrong would disclose products nobody
 * authorised, silently and in a response that looks entirely normal, so there is one implementation
 * rather than one per endpoint.
 *
 * <p>Three rules hold throughout, and none may be weakened:
 *
 * <ul>
 *   <li>a decision that did not arrive while policy is <em>on</em> is a refusal, not permission;
 *   <li>an obligation this service cannot fulfil is a refusal, not something to ignore;
 *   <li>a contract that will not compile is a refusal, not a contract to skip.
 * </ul>
 */
@Component
@Slf4j
public class ProductQueryPlanner {

    /** Logged, not returned: it says the policy is unusable, which is nothing a caller can act on. */
    public static final String REASON_ROW_FILTER_UNCOMPILABLE = "policy.row_filter_uncompilable";

    /** Logged, not returned, for the same reason. */
    public static final String REASON_OBLIGATION_UNSUPPORTED = "policy.obligation_unsupported";

    /**
     * Policy was meant to judge the request and no decision arrived. Logged at {@code ERROR}: it
     * means the enforcement path is broken, which is an operator's problem, not a caller's.
     */
    public static final String REASON_ENFORCEMENT_MISSING = "policy.enforcement_missing";

    static final String REFUSAL_MESSAGE = "Access denied by policy";

    private final ProductSearchQueryBuilder queryBuilder;
    private final ProductDiscoveryRepository repository;

    /**
     * Whether policy was meant to judge this request - the master switch, read once. It is what
     * tells an absent decision ("policy is off") from a missing one ("policy is on and broken").
     */
    private final boolean policyEnforcementEnabled;

    public ProductQueryPlanner(
            ProductSearchQueryBuilder queryBuilder,
            ProductDiscoveryRepository repository,
            OpaProperties opaProperties) {
        this.queryBuilder = queryBuilder;
        this.repository = repository;
        this.policyEnforcementEnabled = opaProperties.enabled();
    }

    /**
     * What this caller may see.
     *
     * <p>A decision brings its contract. No decision means one of two quite different things, and
     * conflating them would be the worst bug in this class:
     *
     * <ul>
     *   <li>policy enforcement is <b>switched off</b> - nothing judged the request because nothing
     *       was meant to. The master switch documents this as "every decision returns ALLOW", so the
     *       read runs open rather than refusing or silently returning nothing;
     *   <li>policy enforcement is <b>on</b> and a decision still did not arrive - the enforcement
     *       point did not run, or its decision could not be handed over. Policy was meant to judge
     *       this request and did not, so it is refused. Returning the open contract here would
     *       disclose every product to a caller nobody authorised, in a response indistinguishable
     *       from a working one.
     * </ul>
     *
     * @param what the endpoint asking, for the log line - e.g. {@code "Product discover"}
     */
    public ProductSearchContract contractFor(
            String what, Optional<? extends PolicyDecision<? extends ProductPolicyContractDetails>> decision) {
        if (decision.isPresent()) {
            ProductSearchContract contract = ProductSearchContract.enforcing(decision.get());
            refuseUnfulfillableObligations(what, contract, decision);
            return contract;
        }
        if (policyEnforcementEnabled) {
            String errorId = UUID.randomUUID().toString();
            log.error(
                    "{} refused, error_id={}, reason={}: policy enforcement is on "
                            + "(application.opa.enabled=true) but no decision reached the handler. The Policy "
                            + "Enforcement Point did not run for this request; the service will not read "
                            + "unrestricted in its place.",
                    what,
                    errorId,
                    REASON_ENFORCEMENT_MISSING);
            throw new AccessRejectedException(REFUSAL_MESSAGE, List.of(REASON_ENFORCEMENT_MISSING), errorId);
        }
        log.debug("{} running without a policy decision (policy enforcement is off)", what);
        return ProductSearchContract.open();
    }

    /**
     * A Policy Enforcement Point that cannot fulfil an obligation must not grant access, so an
     * obligation this service does not know refuses the request rather than being ignored.
     */
    private void refuseUnfulfillableObligations(
            String what,
            ProductSearchContract contract,
            Optional<? extends PolicyDecision<? extends ProductPolicyContractDetails>> decision) {
        List<String> unsupported = DiscoveryObligations.unsupported(contract.obligations());
        if (unsupported.isEmpty()) {
            return;
        }
        String errorId = UUID.randomUUID().toString();
        log.warn(
                "{} refused, error_id={}, reason={}, obligations={}, policy={}",
                what,
                errorId,
                REASON_OBLIGATION_UNSUPPORTED,
                unsupported,
                provenance(decision));
        throw new AccessRejectedException(REFUSAL_MESSAGE, List.of(REASON_OBLIGATION_UNSUPPORTED), errorId);
    }

    /** The sensitive names are only consulted when the contract withholds them, so only then loaded. */
    public Set<String> sensitiveAttributeNames(ProductSearchContract contract) {
        return contract.masksSensitiveAttributes() ? repository.findSensitiveAttributeNames() : Set.of();
    }

    /**
     * Compiles the contract and the criteria into one statement.
     *
     * <p>The projection says how much of a product the endpoint returns; it narrows the contract
     * and can never widen it.
     *
     * <p>Caller criteria are checked when they are created, so a predicate that will not compile
     * here is the contract's: a row filter or unmask condition naming something this service cannot
     * translate. That refuses the request - a policy that cannot be applied is never skipped.
     */
    public ProductSearchQuery compile(
            String what,
            ProductSearchContract contract,
            ProductSearchCriteria criteria,
            ProductProjection projection,
            Optional<? extends PolicyDecision<? extends ProductPolicyContractDetails>> decision) {
        try {
            ProductSearchQuery query = queryBuilder.build(contract, criteria, projection);
            log.debug("{} query: {} parameters={}", what, query.pageSql(), query.parameters());
            return query;
        } catch (FilterCompilationException e) {
            String errorId = UUID.randomUUID().toString();
            log.warn(
                    "{} refused, error_id={}, reason={}, policy={}, detail={}",
                    what,
                    errorId,
                    REASON_ROW_FILTER_UNCOMPILABLE,
                    provenance(decision),
                    e.getMessage());
            throw new AccessRejectedException(REFUSAL_MESSAGE, List.of(REASON_ROW_FILTER_UNCOMPILABLE), errorId);
        }
    }

    /** Which rule answered, for a log line; empty when no decision was taken. */
    public static Map<String, String> provenance(
            Optional<? extends PolicyDecision<? extends ProductPolicyContractDetails>> decision) {
        return decision.map(taken -> Map.of(
                        "id", String.valueOf(taken.policy().id()),
                        "version", String.valueOf(taken.policy().version())))
                .orElse(Map.of());
    }
}
