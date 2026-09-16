/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
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
 * Writes the decision the PDP returned for one evaluation, so what a policy actually decided can
 * be read off the log rather than inferred. Off unless {@code application.opa.log-output} is set.
 * Unlike {@link PolicyInputLogger}, the decision document does not carry the caller's token
 * claims - but product discovery still asks for one decision per candidate product, so this
 * remains a diagnostic aid rather than something to leave on.
 *
 * <p>Each entry is a summary of the fields a decision is most likely to be read for, followed by
 * the serialised response exactly as it came off the wire.
 */
@Component
@Slf4j
public class PolicyOutputLogger {

    private static final String NONE = "<none>";

    private final boolean enabled;
    private final ObjectWriter prettyWriter;
    private final ObjectWriter compactWriter;

    public PolicyOutputLogger(OpaProperties opaProperties, ObjectMapper objectMapper) {
        this.enabled = opaProperties.logOutput();
        // A copy, not the shared mapper: the payload is logged with the record's own
        // null-omitting inclusion whatever the application mapper is configured to do, so the
        // log shows the same document the PDP returned.
        ObjectMapper loggingMapper = objectMapper.copy().setSerializationInclusion(JsonInclude.Include.NON_NULL);
        this.prettyWriter = loggingMapper.writerWithDefaultPrettyPrinter();
        this.compactWriter = loggingMapper.writer();
    }

    @PostConstruct
    void warnWhenEnabled() {
        if (enabled) {
            log.warn("PDP decision output logging is ON (application.opa.log-output=true). Every decision "
                    + "the PDP returns is written to the log. Product discovery asks for one decision per "
                    + "candidate product, so expect one log entry per candidate.");
        }
    }

    /** True when the caller should avoid building anything only needed for logging. */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Logs one decision returned by the PDP. Never throws: a logging failure must not turn into
     * a failed authorisation decision, so a payload that cannot be serialised is reported in
     * place of the payload and the decision carries on.
     *
     * @param input the decision input the response answers
     * @param output the decision the PDP returned
     */
    public void logOutput(PolicyInput input, PolicyDecision<?> output) {
        if (!enabled || input == null || output == null) {
            return;
        }
        PolicyResource resource = input.resource();
        log.info(
                """
                PDP decision response <- {}
                  action               : {}
                  resource             : {} id={}
                  allowed_attributes   : {}
                  denied_attributes    : {}
                  masked_attributes    : {}
                  reasons              : {}
                  policy               : id={} version={} resolution={}
                  details              : {}
                  payload:
                {}""",
                output.verdict(),
                value(input.action()),
                resource == null ? NONE : value(resource.kind()),
                resource == null ? NONE : value(resource.id()),
                output.allowedFilteredAttributes(),
                output.deniedFilteredAttributes(),
                output.maskedFilteredAttributes(),
                output.reasons(),
                output.policy().id(),
                output.policy().version(),
                output.policy().resolution(),
                compact(output.details()),
                payload(output));
    }

    private String payload(PolicyDecision<?> output) {
        return serialise(Map.of("result", output)).indent(2).stripTrailing();
    }

    private String compact(Object value) {
        try {
            return compactWriter.writeValueAsString(value);
        } catch (Exception e) {
            return "<not serialisable: " + e.getMessage() + ">";
        }
    }

    private String serialise(Object value) {
        try {
            return prettyWriter.writeValueAsString(value);
        } catch (Exception e) {
            return "<not serialisable: " + e.getMessage() + ">";
        }
    }

    private String value(String value) {
        return value == null ? NONE : value;
    }
}
