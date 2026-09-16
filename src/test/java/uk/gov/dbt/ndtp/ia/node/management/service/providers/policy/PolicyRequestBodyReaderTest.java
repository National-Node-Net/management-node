/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.policy;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import uk.gov.dbt.ndtp.ia.node.management.web.policy.CachedBodyHttpServletRequest;

class PolicyRequestBodyReaderTest {

    private final PolicyRequestBodyReader reader = new PolicyRequestBodyReader(new ObjectMapper());

    private static HttpServletRequest cached(String contentType, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/configuration/producer");
        if (contentType != null) {
            request.setContentType(contentType);
        }
        return new CachedBodyHttpServletRequest(request, body.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void jsonBody_isReadAsStructuredValuesAPolicyCanAddress() {
        Object body = reader.read(
                cached(
                        "application/json",
                        """
                {"text": "this is sample text", "filters": {"key 1": "value", "key 2": [1234, 33, 222]}}"""));

        assertThat(body).isInstanceOf(Map.class);
        Map<?, ?> map = (Map<?, ?>) body;
        assertThat(map.get("text")).isEqualTo("this is sample text");
        assertThat(map.get("filters")).isEqualTo(Map.of("key 1", "value", "key 2", List.of(1234, 33, 222)));
    }

    @Test
    void jsonSuffixContentType_isAccepted() {
        assertThat(reader.read(cached("application/merge-patch+json", "{\"a\":1}")))
                .isEqualTo(Map.of("a", 1));
    }

    @Test
    void charsetOnTheContentType_doesNotPreventParsing() {
        assertThat(reader.read(cached("application/json;charset=UTF-8", "{\"a\":1}")))
                .isEqualTo(Map.of("a", 1));
    }

    @Test
    void unbufferedRequest_yieldsNoBody() {
        assertThat(reader.read(new MockHttpServletRequest("POST", "/api/v1/configuration/producer")))
                .isNull();
    }

    @Test
    void emptyBody_yieldsNoBody() {
        assertThat(reader.read(cached("application/json", ""))).isNull();
    }

    @Test
    void nonJsonContentType_yieldsNoBody() {
        assertThat(reader.read(cached("application/x-www-form-urlencoded", "a=1")))
                .isNull();
    }

    @Test
    void missingContentType_yieldsNoBody() {
        assertThat(reader.read(cached(null, "{\"a\":1}"))).isNull();
    }

    /** A malformed body is the handler's 400 to give; denying here would report it as a 403. */
    @Test
    void malformedJson_yieldsNoBodyRatherThanThrowing() {
        assertThat(reader.read(cached("application/json", "{\"a\":"))).isNull();
    }
}
