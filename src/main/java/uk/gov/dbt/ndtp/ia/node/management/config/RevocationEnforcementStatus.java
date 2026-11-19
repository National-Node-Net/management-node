/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;

/**
 * The revocation enforcement mode this instance started with, shown on {@code /actuator/info} so operators can
 * confirm it per environment:
 *
 * <pre>
 * "revocationEnforcement": {"active": false, "mode": "MTLS_DISABLED", "reason": "mTLS disabled (...)"}
 * </pre>
 */
public record RevocationEnforcementStatus(RevocationEnforcementMode mode) implements InfoContributor {

    static final String INFO_KEY = "revocationEnforcement";

    public boolean active() {
        return mode.isActive();
    }

    @Override
    public void contribute(Info.Builder builder) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("active", mode.isActive());
        details.put("mode", mode.name());
        details.put("reason", mode.reason());
        builder.withDetail(INFO_KEY, details);
    }
}
