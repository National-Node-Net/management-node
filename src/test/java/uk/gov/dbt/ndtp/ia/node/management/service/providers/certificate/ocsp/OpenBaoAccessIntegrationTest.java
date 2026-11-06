/*
 * SPDX-License-Identifier: Apache-2.0
 * © Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally
 * attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.
 */

package uk.gov.dbt.ndtp.ia.node.management.service.providers.certificate.ocsp;

import java.util.List;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Management Node Vault access against OpenBao. Skipped when Docker is not available. */
@Testcontainers(disabledWithoutDocker = true)
class OpenBaoAccessIntegrationTest extends AbstractVaultAccessIntegrationTest {

    OpenBaoAccessIntegrationTest() {
        super("openbao/openbao:2.7.2", "BAO", true, List.of());
    }
}
