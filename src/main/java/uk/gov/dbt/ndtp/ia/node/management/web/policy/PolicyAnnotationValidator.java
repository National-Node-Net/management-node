/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.web.policy;

import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecisionDetails;

/**
 * Fails startup on a {@link Policy} declaration the PDP could never answer correctly, so a mistake
 * surfaces when the service is deployed rather than as a 403 on the first request:
 *
 * <ul>
 *   <li>{@code resource} and {@code action} must match {@value #IDENTIFIER}, since they name a Rego
 *       package, and neither may be {@value #RESERVED}, which names the fallback rules
 *   <li>{@code details} must be a concrete {@link PolicyDecisionDetails} type with a no-argument
 *       constructor, since the decision's details are instantiated as it
 *   <li>a handler asking for {@code Optional<PolicyDecision<D>>} must carry {@link Policy}: without
 *       it no decision is ever published, and the parameter would always be empty
 *   <li>that {@code D} must be assignable from the {@code @Policy} details type, since otherwise the
 *       decision taken could never be handed to the parameter
 * </ul>
 *
 * <p>Also logs every policy-enforced endpoint, so what is and is not enforced can be read from the
 * startup log. Runs whether or not policy enforcement is switched on: an invalid declaration is a
 * defect even while the PDP is off.
 *
 * <p>Runs once every singleton exists - by then each handler mapping has detected its handler
 * methods - and reaches the mappings through an {@link ObjectProvider}, so this bean never forces
 * the MVC infrastructure into being early.
 */
@Component
@Slf4j
public class PolicyAnnotationValidator implements SmartInitializingSingleton {

    static final String IDENTIFIER = "[a-z][a-z0-9_]*";
    static final String RESERVED = "fallback";

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile(IDENTIFIER);

    private final ObjectProvider<RequestMappingHandlerMapping> handlerMappings;

    public PolicyAnnotationValidator(ObjectProvider<RequestMappingHandlerMapping> handlerMappings) {
        this.handlerMappings = handlerMappings;
    }

    @Override
    public void afterSingletonsInstantiated() {
        Map<RequestMappingInfo, HandlerMethod> handlers = new LinkedHashMap<>();
        handlerMappings.orderedStream().forEach(mapping -> handlers.putAll(mapping.getHandlerMethods()));
        validate(handlers);
    }

    /**
     * Checks every handler and logs the policy-enforced ones.
     *
     * @throws IllegalStateException naming each offending handler method, when any is invalid
     */
    void validate(Map<RequestMappingInfo, HandlerMethod> handlers) {
        List<String> violations = new ArrayList<>();
        List<String> inventory = new ArrayList<>();
        handlers.forEach((info, handler) -> {
            Policy policy = handler.getMethodAnnotation(Policy.class);
            if (policy == null) {
                if (asksForDecision(handler)) {
                    violations.add(handler
                            + " declares an Optional<PolicyDecision> parameter but is not annotated"
                            + " with @Policy, so no decision would ever be published for it");
                }
                return;
            }
            checkIdentifier(handler, "resource", policy.resource(), violations);
            checkIdentifier(handler, "action", policy.action(), violations);
            checkDetails(handler, policy.details(), violations);
            checkDecisionParameters(handler, policy.details(), violations);
            inventory.add(info.getMethodsCondition().getMethods() + " " + info.getPatternValues() + " -> "
                    + policy.resource() + "." + policy.action());
        });
        if (!violations.isEmpty()) {
            throw new IllegalStateException("Invalid @Policy declarations:\n  " + String.join("\n  ", violations));
        }
        if (inventory.isEmpty()) {
            log.info("No policy-enforced endpoints: no handler method is annotated with @Policy");
        } else {
            log.info(
                    "{} policy-enforced endpoint(s):\n  {}",
                    inventory.size(),
                    String.join("\n  ", inventory.stream().sorted().toList()));
        }
    }

    private static void checkIdentifier(HandlerMethod handler, String name, String value, List<String> violations) {
        if (value == null || !IDENTIFIER_PATTERN.matcher(value).matches()) {
            violations.add(handler + " has @Policy " + name + " '" + value + "', which does not match " + IDENTIFIER);
        } else if (RESERVED.equals(value)) {
            violations.add(handler + " has @Policy " + name + " '" + RESERVED + "', which is reserved for the"
                    + " fallback rules");
        }
    }

    private static void checkDetails(HandlerMethod handler, Class<?> details, List<String> violations) {
        if (details.isInterface() || Modifier.isAbstract(details.getModifiers())) {
            violations.add(handler + " has @Policy details " + details.getName()
                    + ", an interface or abstract type the decision's details cannot be read into");
            return;
        }
        try {
            details.getDeclaredConstructor();
        } catch (NoSuchMethodException e) {
            violations.add(handler + " has @Policy details " + details.getName()
                    + ", which has no no-argument constructor to create empty details with");
        }
    }

    private static void checkDecisionParameters(HandlerMethod handler, Class<?> details, List<String> violations) {
        Arrays.stream(handler.getMethodParameters())
                .filter(PolicyDecisionArgumentResolver::isDecisionParameter)
                .map(PolicyDecisionArgumentResolver::declaredDetailsType)
                .filter(declared -> !declared.isAssignableFrom(details))
                .forEach(declared -> violations.add(handler + " declares Optional<PolicyDecision<" + declared.getName()
                        + ">> but its @Policy details are " + details.getName()
                        + ", so the decision could never be handed to it"));
    }

    private static boolean asksForDecision(HandlerMethod handler) {
        return Arrays.stream(handler.getMethodParameters())
                .anyMatch(PolicyDecisionArgumentResolver::isDecisionParameter);
    }
}
