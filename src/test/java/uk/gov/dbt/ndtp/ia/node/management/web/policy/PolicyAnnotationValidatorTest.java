/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.web.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import uk.gov.dbt.ndtp.ia.node.management.controller.v1.ConfigurationController;
import uk.gov.dbt.ndtp.ia.node.management.controller.v1.ProductController;
import uk.gov.dbt.ndtp.ia.node.management.service.data.ProductService;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.configuration.ConfigurationProvider;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecision;
import uk.gov.dbt.ndtp.ia.node.management.service.providers.policy.PolicyDecisionDetails;

class PolicyAnnotationValidatorTest {

    static class SubscriptionDetails extends PolicyDecisionDetails {}

    abstract static class AbstractDetails extends PolicyDecisionDetails {}

    static class NoDefaultConstructorDetails extends PolicyDecisionDetails {
        NoDefaultConstructorDetails(String required) {}
    }

    static class Handlers {

        @Policy(resource = "product", action = "subscribe", details = SubscriptionDetails.class)
        public void subscribe(Optional<PolicyDecision<SubscriptionDetails>> decision) {}

        @Policy(resource = "product", action = "subscribe", details = SubscriptionDetails.class)
        public void subscribeGeneric(Optional<PolicyDecision<PolicyDecisionDetails>> decision) {}

        @Policy(resource = "product", action = "view")
        public void typedParameterUntypedPolicy(Optional<PolicyDecision<SubscriptionDetails>> decision) {}

        @Policy(resource = "configuration", action = "producer")
        public void producer() {}

        public void unannotated(Optional<String> unrelated) {}

        public void decisionWithoutPolicy(Optional<PolicyDecision<PolicyDecisionDetails>> decision) {}

        @Policy(resource = "Product", action = "view")
        public void upperCaseResource() {}

        @Policy(resource = "product", action = "bulk-view")
        public void hyphenatedAction() {}

        @Policy(resource = "product", action = "")
        public void emptyAction() {}

        @Policy(resource = "fallback", action = "view")
        public void reservedResource() {}

        @Policy(resource = "product", action = "fallback")
        public void reservedAction() {}

        @Policy(resource = "product", action = "view", details = AbstractDetails.class)
        public void abstractDetails() {}

        @Policy(resource = "product", action = "view", details = NoDefaultConstructorDetails.class)
        public void noDefaultConstructorDetails() {}
    }

    private final PolicyAnnotationValidator validator = new PolicyAnnotationValidator(emptyProvider());

    private Logger logger;
    private ListAppender<ILoggingEvent> logAppender;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(PolicyAnnotationValidator.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        logger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logAppender);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<RequestMappingHandlerMapping> emptyProvider() {
        ObjectProvider<RequestMappingHandlerMapping> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(invocation -> Stream.empty());
        return provider;
    }

    private static HandlerMethod handler(String name) {
        return new HandlerMethod(
                new Handlers(),
                Arrays.stream(Handlers.class.getMethods())
                        .filter(method -> method.getName().equals(name))
                        .findFirst()
                        .orElseThrow());
    }

    private static Map<RequestMappingInfo, HandlerMethod> handlers(String... names) {
        Map<RequestMappingInfo, HandlerMethod> handlers = new LinkedHashMap<>();
        for (String name : names) {
            handlers.put(
                    RequestMappingInfo.paths("/api/v1/" + name)
                            .methods(RequestMethod.POST)
                            .build(),
                    handler(name));
        }
        return handlers;
    }

    @Test
    void validDeclarations_pass() {
        assertThatCode(() -> validator.validate(handlers("subscribe", "producer", "unannotated")))
                .doesNotThrowAnyException();
    }

    @Test
    void validDeclarations_areLoggedAsAnInventory() {
        validator.validate(handlers("subscribe", "producer", "unannotated"));

        assertThat(logAppender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage())
                    .contains("2 policy-enforced endpoint(s)")
                    .contains("[POST] [/api/v1/subscribe] -> product.subscribe")
                    .contains("[POST] [/api/v1/producer] -> configuration.producer")
                    .doesNotContain("unannotated");
        });
    }

    @Test
    void noAnnotatedHandlers_saysSo() {
        validator.validate(handlers("unannotated"));

        assertThat(logAppender.list)
                .anySatisfy(event -> assertThat(event.getFormattedMessage()).contains("No policy-enforced endpoints"));
    }

    @Test
    void identifierNotLowerCase_failsNamingTheMethod() {
        assertThatThrownBy(() -> validator.validate(handlers("upperCaseResource")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("upperCaseResource")
                .hasMessageContaining("resource 'Product'");
    }

    @Test
    void identifierWithAHyphen_fails() {
        assertThatThrownBy(() -> validator.validate(handlers("hyphenatedAction")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hyphenatedAction")
                .hasMessageContaining("action 'bulk-view'");
    }

    @Test
    void emptyIdentifier_fails() {
        assertThatThrownBy(() -> validator.validate(handlers("emptyAction")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("emptyAction");
    }

    @Test
    void reservedIdentifier_failsForResourceAndAction() {
        assertThatThrownBy(() -> validator.validate(handlers("reservedResource", "reservedAction")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("reservedResource")
                .hasMessageContaining("reservedAction")
                .hasMessageContaining("reserved");
    }

    @Test
    void abstractDetails_fail() {
        assertThatThrownBy(() -> validator.validate(handlers("abstractDetails")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("abstractDetails")
                .hasMessageContaining("abstract");
    }

    @Test
    void detailsWithoutANoArgumentConstructor_fail() {
        assertThatThrownBy(() -> validator.validate(handlers("noDefaultConstructorDetails")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("noDefaultConstructorDetails")
                .hasMessageContaining("no-argument constructor");
    }

    /** A parameter declaring a supertype of the policy's details type can hold the decision. */
    @Test
    void decisionParameterDeclaringASupertypeOfThePolicyDetails_passes() {
        assertThatCode(() -> validator.validate(handlers("subscribeGeneric"))).doesNotThrowAnyException();
    }

    @Test
    void decisionParameterTheDetailsCannotBeHandedTo_fails() {
        assertThatThrownBy(() -> validator.validate(handlers("typedParameterUntypedPolicy")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("typedParameterUntypedPolicy")
                .hasMessageContaining(SubscriptionDetails.class.getName())
                .hasMessageContaining("could never be handed to it");
    }

    @Test
    void decisionParameterWithoutPolicy_fails() {
        assertThatThrownBy(() -> validator.validate(handlers("decisionWithoutPolicy")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("decisionWithoutPolicy")
                .hasMessageContaining("Optional<PolicyDecision>");
    }

    /** Every policy-enforced API receives its decision typed with the details its @Policy declares. */
    @Test
    void shippedControllers_declareDecisionParametersMatchingTheirPolicy() {
        Map<RequestMappingInfo, HandlerMethod> handlers = new LinkedHashMap<>();
        Stream.of(
                        new ProductController(mock(ProductService.class)),
                        new ConfigurationController(mock(ConfigurationProvider.class)))
                .forEach(controller -> Arrays.stream(controller.getClass().getDeclaredMethods())
                        .filter(method -> method.isAnnotationPresent(Policy.class))
                        .forEach(method -> {
                            HandlerMethod handler = new HandlerMethod(controller, method);
                            assertThat(Arrays.stream(handler.getMethodParameters())
                                            .filter(PolicyDecisionArgumentResolver::isDecisionParameter)
                                            .map(PolicyDecisionArgumentResolver::declaredDetailsType))
                                    .as("decision parameter of %s", method)
                                    .containsExactly(
                                            method.getAnnotation(Policy.class).details());
                            handlers.put(
                                    RequestMappingInfo.paths("/" + method.getName())
                                            .build(),
                                    handler);
                        }));

        assertThat(handlers).hasSize(5);
        assertThatCode(() -> validator.validate(handlers)).doesNotThrowAnyException();
    }

    @Test
    void afterSingletonsInstantiated_validatesEveryMappingsHandlers() {
        RequestMappingHandlerMapping mapping = mock(RequestMappingHandlerMapping.class);
        when(mapping.getHandlerMethods()).thenReturn(handlers("decisionWithoutPolicy"));
        @SuppressWarnings("unchecked")
        ObjectProvider<RequestMappingHandlerMapping> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenAnswer(invocation -> Stream.of(mapping));

        assertThatThrownBy(() -> new PolicyAnnotationValidator(provider).afterSingletonsInstantiated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("decisionWithoutPolicy");
    }
}
