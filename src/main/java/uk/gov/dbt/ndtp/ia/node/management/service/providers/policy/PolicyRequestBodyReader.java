/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import uk.gov.dbt.ndtp.ia.node.management.web.policy.CachedBodyHttpServletRequest;

/**
 * Reads the request body a caller has buffered, as the structured value a policy can address -
 * so a rule reaches {@code input.request.body.filters.region} rather than having to parse a
 * string. Used by the Policy Enforcement Point, which has no bound body of its own; a controller
 * that has already bound one passes that instead and never comes here.
 *
 * <p>Returns null - meaning "no body reached the PDP" - rather than throwing, for every case
 * where there is nothing dependable to send: an unbuffered request, an empty body, a content type
 * that is not JSON, or JSON that will not parse. A malformed body is the handler's to reject with
 * a 400; failing the authorisation decision first would turn a bad request into a misleading 403.
 */
@Component
@Slf4j
public class PolicyRequestBodyReader {

    private final ObjectMapper objectMapper;

    public PolicyRequestBodyReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @param request the request being authorised
     * @return the parsed body, or null when none is available
     */
    public Object read(HttpServletRequest request) {
        if (!(request instanceof CachedBodyHttpServletRequest cached)) {
            return null;
        }
        byte[] body = cached.body();
        if (body.length == 0) {
            return null;
        }
        if (!isJson(request.getContentType())) {
            log.debug(
                    "Body of {} {} has content type {}, which is not JSON - the PDP will see no body",
                    request.getMethod(),
                    request.getRequestURI(),
                    request.getContentType());
            return null;
        }
        try {
            return objectMapper.readValue(body, Object.class);
        } catch (Exception e) {
            log.warn(
                    "Body of {} {} is not parsable JSON, so the PDP will see no body: {}",
                    request.getMethod(),
                    request.getRequestURI(),
                    e.getMessage());
            return null;
        }
    }

    /** Accepts {@code application/json} and any {@code +json} suffix, whatever the charset. */
    private boolean isJson(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return false;
        }
        try {
            MediaType mediaType = MediaType.parseMediaType(contentType);
            return MediaType.APPLICATION_JSON.equalsTypeAndSubtype(mediaType)
                    || mediaType.getSubtype().endsWith("+json");
        } catch (Exception e) {
            return false;
        }
    }
}
