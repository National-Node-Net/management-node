/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.policy;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import jakarta.annotation.PostConstruct;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uk.gov.dbt.ndtp.ia.node.management.config.OpaProperties;

/**
 * Writes the decision document that is about to be sent to the PDP, so what a policy actually
 * sees can be read off the log rather than inferred. Off unless {@code application.opa.log-input}
 * is set: the input carries the token's claims verbatim, so this is a development aid, not
 * something to leave on where logs are retained or shipped.
 *
 * <p>Each entry is a summary of the fields a policy is most likely to key on, followed by the
 * serialised payload exactly as it goes on the wire - including {@code request.body}, which is
 * rendered as {@code <none>} when the caller supplied none, so "no body was passed" is
 * distinguishable from "a body was passed and serialised to nothing".
 */
@Component
@Slf4j
public class PolicyInputLogger {

    private static final String NONE = "<none>";

    private final boolean enabled;
    private final String decisionUri;
    private final ObjectWriter prettyWriter;
    private final ObjectWriter compactWriter;

    public PolicyInputLogger(OpaProperties opaProperties, ObjectMapper objectMapper) {
        this.enabled = opaProperties.logInput();
        this.decisionUri = opaProperties.url() + opaProperties.decisionPath();
        // A copy, not the shared mapper: the payload is logged with the record's own
        // null-omitting inclusion whatever the application mapper is configured to do, so the
        // log shows the same document the PDP receives.
        ObjectMapper loggingMapper = objectMapper.copy().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        this.prettyWriter = loggingMapper.writerWithDefaultPrettyPrinter();
        this.compactWriter = loggingMapper.writer();
    }

    @PostConstruct
    void warnWhenEnabled() {
        if (enabled) {
            log.warn("PDP decision input logging is ON (application.opa.log-input=true). Every decision "
                    + "document, including the caller's token claims, is written to the log. Enable this "
                    + "only for local diagnosis, never where logs are retained or shipped.");
        }
    }

    /** True when the caller should avoid building anything only needed for logging. */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Logs one outgoing decision request. Never throws: a logging failure must not turn into a
     * failed authorisation decision, so a payload that cannot be serialised is reported in place
     * of the payload and the decision carries on.
     *
     * @param input the decision input about to be sent
     */
    public void logInput(PolicyInput input) {
        if (!enabled || input == null) {
            return;
        }
        PolicySubject subject = input.subject();
        PolicyOrganisation organisation = subject == null ? null : subject.organisation();
        PolicyResource resource = input.resource();
        PolicyHttpRequest request = input.request();
        log.info(
                """
                PDP decision request -> {}
                  subject.kind         : {}
                  subject.user_id      : {}
                  subject.clientId     : {}
                  subject.organisation : {} attributes={}
                  action               : {}
                  resource             : {} id={} attributes={}
                  request              : {} {}
                  request.headers      : {}
                  request.query        : {}
                  request.body         : {}
                  payload:
                {}""",
                decisionUri,
                subject == null ? NONE : value(subject.kind()),
                subject == null ? NONE : value(subject.userId()),
                subject == null ? NONE : value(subject.clientId()),
                organisation == null ? NONE : value(organisation.key()),
                organisation == null ? Map.of() : attributes(organisation.attributes()),
                value(input.action()),
                resource == null ? NONE : value(resource.kind()),
                resource == null ? NONE : value(resource.id()),
                resource == null ? Map.of() : attributes(resource.attributes()),
                request == null ? NONE : value(request.method()),
                request == null ? NONE : value(request.path()),
                request == null ? Map.of() : attributes(request.headers()),
                request == null ? Map.of() : attributes(request.query()),
                body(request),
                payload(input));
    }

    private <V> Map<String, V> attributes(Map<String, V> attributes) {
        return attributes == null ? Map.of() : attributes;
    }

    /**
     * Distinguishes a body the caller never supplied from one that serialised to an empty
     * document - the two are indistinguishable in the payload, and only the second is a binding
     * problem. Rendered compact so the summary stays one line per fact.
     */
    private String body(PolicyHttpRequest request) {
        if (request == null || request.body() == null) {
            return NONE;
        }
        return serialise(compactWriter, request.body());
    }

    private String payload(PolicyInput input) {
        return serialise(prettyWriter, new PolicyDecisionRequest(input))
                .indent(2)
                .stripTrailing();
    }

    private String serialise(ObjectWriter objectWriter, Object value) {
        try {
            return objectWriter.writeValueAsString(value);
        } catch (Exception e) {
            return "<not serialisable: " + e.getMessage() + ">";
        }
    }

    private String value(String value) {
        return value == null ? NONE : value;
    }
}
