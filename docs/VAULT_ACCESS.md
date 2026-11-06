# Vault Access

**Repository:** `management-node`  
**Description:** `How the Management Node authenticates to Vault or OpenBao, and what it is allowed to do there`  
**SPDX-License-Identifier:** `Apache-2.0 AND OGL-UK-3.0`

---

## Overview

The Management Node has its own identity in Vault, limited to the paths it needs. All of this is delivered as code in [docker/vault/tofu](../docker/vault/tofu/README.md), so that every environment, on Vault or OpenBao, gets the same configuration.

---

## Authentication

The Management Node logs in with AppRole, using `VAULT_ROLE_ID` and `VAULT_SECRET_ID`.

```yaml
spring:
  cloud:
    vault:
      uri: ${VAULT_ADDR:http://localhost:8200}
      authentication: ${VAULT_AUTHENTICATION:approle}
      app-role:
        role-id: ${VAULT_ROLE_ID:}
        secret-id: ${VAULT_SECRET_ID:}
```

| Setting | Value |
|---|---|
| Token TTL | 15 minutes (`approle_token_ttl_seconds`) |
| Token maximum TTL | 4 hours (`approle_token_max_ttl_seconds`) |
| Default policy | not attached |

Spring Cloud Vault renews the token before it expires. When the token reaches its maximum TTL, the service logs in again without a restart.

The application does not start when the credentials are missing.

The policy does not grant `auth/token/revoke-self`. When the service stops, Spring Vault therefore logs a `Cannot revoke VaultToken` warning with a stack trace, and the token stays valid until its TTL ends, at most 15 minutes later.

After 5 failed logins the server locks the AppRole, and every login for it returns 403, even with the right secret ID. The lock lasts 15 minutes by default, so a corrected secret ID can keep failing for that long. An administrator can list locks at `sys/locked-users` and lift one with `sys/locked-users/:mount_accessor/unlock/:role_id`.

For local development only, `VAULT_AUTHENTICATION=token` with `spring.cloud.vault.token` still works. A root token bypasses every policy, so permission problems do not show up in that mode.

---

## Permissions

### `mgmt-node-pki`

| Path | Capability | Used for |
|---|---|---|
| `<pki>/cert/*` | `read` | certificate status (`cert/:serial`), and the CA (`cert/ca`, `cert/ca_chain`) |
| `<pki>/certs/revoked` | `list` | serial numbers of revoked certificates |
| `<pki>/revoke` | `update` | revoking a certificate; no Management Node API uses this yet |
| `<pki>/sign/ocsp-signer` | `update`, without `not_after` | issuing the OCSP signer; the Management Node does not call this yet |
| `<pki>/sign/default-role` | `update`, without `not_after` | signing federator CSRs and bootstrap certificates, including re-issuing a certificate after revocation |
| `<kv>/data/management-node/ocsp-signer` | `read` | loading the OCSP signer |
| `auth/token/renew-self` | `update` | renewing its token |
| `auth/token/lookup-self` | `read` | reading its token's TTL |

The last two come from the default policy, which is not attached, so they are listed here.

`certs/revoked` returns serial numbers only. The revocation time comes from `cert/:serial`.

`cert/*` is public (see below), so this rule is redundant. It stays so that the policy lists everything the Management Node uses.

Automated renewal of the OCSP signer will also need `create` and `update` on the secret's path.

### Everything else

Any other path returns 403, including `root/*`, `issue/*`, `sign-intermediate`, `sign-verbatim`, `config/*`, `sys/*` and other mounts.

### Public endpoints

There is one exception, which no policy can change. These endpoints do not check the token and answer every caller:

| Where | Endpoints |
|---|---|
| Vault and OpenBao | `sys/health`, `sys/seal-status`, `sys/leader`, `sys/init`, `sys/internal/specs/openapi`, `sys/internal/ui/mounts` |
| Vault only | `sys/generate-root/attempt`, `sys/rekey/init`, `sys/replication/status`, `sys/internal/ui/feature-flags` |
| PKI mount | `ca`, `ca/pem`, `ca_chain`, `crl`, `crl/pem`, `crl/delta`, `crl/delta/pem`, `cert/*`, `LIST issuers`, `issuer/:id/pem`, `issuer/:id/der`, `issuer/:id/json`, `issuer/:id/crl`, `issuer/:id/crl/*`, `ocsp` |

Three of them need a note:

- `ocsp` is the built-in responder. It is disabled, so it answers 401 to everyone.
- `sys/internal/ui/mounts` lists only the mounts the caller can use. It is empty without a token.
- `sys/step-down` answers 204 to everyone on a server without high availability, and does nothing. With high availability, the Management Node gets 403.

`sys/metrics` also answers without a token in dev mode. On a server started from a configuration file it answers 403.

These endpoints are accepted exceptions. Policy therefore cannot enforce that nodes never call Vault directly, so that has to be enforced on the network: federators must have no route to Vault.

---

## The `ocsp-signer` role

| Request | Result |
|---|---|
| Common name `ocsp-signer.management-node` | issued, with the `id-kp-OCSPSigning` EKU only |
| Any other common name, or a subdomain | rejected (400) |
| `alt_names`, IP or URI SANs in the request | rejected (400) |
| SANs inside the CSR | ignored |
| TTL above the maximum | **issued with the maximum lifetime**, with a warning |
| `not_after`, with or without a TTL | rejected by the policy (403) |
| CA flag in the CSR | **issued as a non-CA certificate** (`CA:FALSE`) |

An issued certificate never exceeds the maximum lifetime, and the role never issues a CA certificate. Paths that issue CA certificates return 403 for the Management Node.

The role alone does not guarantee the lifetime. Vault and OpenBao cap `ttl` at `max_ttl`, but do not check `not_after` against it, so a caller with `not_after` could get any date the CA allows. That is why `mgmt-node-pki` refuses the parameter on both signing paths. An administrator token bypasses the policy, so the limit does not apply to it.

The maximum lifetime is 7 days. Vault backdates `notBefore` by 30 seconds, so `max_ttl` is 7 days minus 30 seconds, which keeps `notAfter - notBefore` within 7 days.

---

## PKI mount

| Setting | Value |
|---|---|
| CA | an intermediate whose private key is generated inside Vault and cannot be exported, signed by an offline root (see [Setting up the CA](../docker/vault/tofu/README.md#setting-up-the-ca)) |
| AIA OCSP URL | not set while the endpoint requires authentication (see [OCSP limitations](OCSP.md#limitations)) |
| CRL | expiry 72 hours, rebuilt 12 hours before it expires |
| Built-in OCSP responder | disabled; the Management Node is the only responder |
| Auto-tidy | every 12 hours, safety buffer 72 hours, covering the certificate store and revocation records |

Once tidy has removed a certificate, an OCSP request for its serial returns `unknown`. The certificate expired at least 72 hours earlier, so clients reject it regardless.

### Revocation

- A revocation is visible in `cert/:serial` immediately, so the Management Node's OCSP responder reports it straight away.
- It reaches the CRL only at the next rebuild, so a revoked certificate can stay off the CRL for about 60 hours. An administrator can rebuild it earlier with `pki-int/crl/rotate`.
- Revoking the same serial again returns the first revocation time.
- Revoking an unknown serial returns 400.
- Revoking an expired certificate returns 200 with a warning and records nothing: `revocation_time` stays 0. A revocation API has to pass that warning on.

---

## Audit

Every call appears in the audit log with the Management Node's identity:

- `auth.metadata.role_name` is `management-node`;
- `auth.entity_id` is the identity entity `management-node`.

`serial_number`, `common_name` and `revocation_time` are logged in clear text on the PKI mount, so that a revocation can be traced. Vault HMACs all other values.

---

## Tests

`VaultAccessIntegrationTest` and `OpenBaoAccessIntegrationTest` run the same checks against real servers, using the policy files that OpenTofu applies. They are skipped without Docker. The role and mount settings are repeated in `AbstractVaultAccessIntegrationTest`, so keep them in step with `docker/vault/tofu`.

```sh
./mvnw test -Dtest='VaultAccessIntegrationTest,OpenBaoAccessIntegrationTest'
```
