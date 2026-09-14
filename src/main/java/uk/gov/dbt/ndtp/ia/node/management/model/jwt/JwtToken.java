/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the Department for Business and Trade (UK) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.model.jwt;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents the structure of a JWT token.
 * This class is used to deserialize the JSON response from the token introspection endpoint.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JwtToken {
    private Long exp;
    private Long iat;
    private String jti;
    private String iss;

    @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    private List<String> aud;

    private String sub;
    private String typ;
    private String azp;
    private List<String> allowedOrigins;

    @JsonProperty("resource_access")
    private Map<String, ResourceAccess> resourceAccess;

    private String scope;
    private String organisation;
    private String clientId;
    private String username;
    private String tokenType;
    private Boolean active;

    /**
     * Represents the resource access structure in the JWT token.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ResourceAccess {
        private List<String> roles;
    }
}
