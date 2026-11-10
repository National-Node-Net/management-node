# Vault Access

**Repository:** `management-node`  
**Description:** `What the Management Node needs in Vault, how it logs in, and what it is allowed to do there`  
**SPDX-License-Identifier:** `Apache-2.0 AND OGL-UK-3.0`

---

## Overview

The Management Node logs in to Vault with its own identity, limited to the paths it needs. On shared environments, the platform team configures Vault with its own tooling; [Changes on a shared environment](#changes-on-a-shared-environment) lists what to change. For a local Vault, follow [Vault Setup](../README.md#vault-setup-optional-required-for-certificate-manager) in the README.

The names below are the defaults: PKI mount `pki-int` (`application.vault.pki-mount`) and role `default-role` (`application.vault.default-role`). If an environment uses other names, change them in the policy too.

---

## Changes on a shared environment

1. **Policy.** Create `mgmt-node-pki` from [docker/vault/policies/mgmt-node-pki.hcl](../docker/vault/policies/mgmt-node-pki.hcl):

   ```sh
   vault policy write mgmt-node-pki docker/vault/policies/mgmt-node-pki.hcl
   ```

2. **Login.** Give the Management Node a login that issues tokens with this policy only. See [Authentication](#authentication) for AppRole, the default, and Kubernetes auth.
3. **Auto-tidy** on the PKI mount:

   ```sh
   vault write pki-int/config/auto-tidy \
     enabled=true \
     interval_duration=12h \
     safety_buffer=72h \
     tidy_cert_store=true \
     tidy_revoked_certs=true \
     tidy_revoked_cert_issuer_associations=true
   ```

4. **Check what already exists:**
   - `default-role` accepts any key type (`key_type=any`), because the OCSP signer uses an EC key;
   - `default-role` accepts the common name `ocsp-signer.management-node` (for example with `allow_any_name=true`);
   - `default-role` allows the bootstrap OID in `allowed_other_sans` (`1.3.6.1.4.1.32473.1.1;utf8:*` by default, `BOOTSTRAP_OID`);
   - `pki-int/config/urls` has no `ocsp_servers` (see [OCSP limitations](OCSP.md#limitations));
   - the PKI mount's CA has the CRL signing key usage. Without it Vault does not build a CRL. OCSP does not depend on the CRL.
5. **Deployment.** Set the variables from [Authentication](#authentication), and `VAULT_ADDR`.
6. **Network.** Federators must have no route to Vault, because some endpoints answer without a token (see [Public endpoints](#public-endpoints)).

Leave the CA, the mounts and existing roles as they are.

Not part of this change: a dedicated `ocsp-signer` role, CRL settings, and an audit identity for the Management Node.

---

## Authentication

The login method is set with `VAULT_AUTHENTICATION`.

### AppRole (default)

| Setting | Value |
|---|---|
| Role | `management-node`, on the `approle` mount |
| `token_policies` | `mgmt-node-pki` |
| `token_no_default_policy` | `true` |
| `token_type` | `service` |
| `token_ttl` | `15m` |
| `token_max_ttl` | `4h` |
| `secret_id_num_uses` | `0`, because the service logs in again whenever a token reaches its maximum TTL |

```sh
vault auth enable approle   # if not enabled yet
vault write auth/approle/role/management-node \
  token_policies=mgmt-node-pki \
  token_no_default_policy=true \
  token_type=service \
  token_ttl=15m \
  token_max_ttl=4h \
  secret_id_num_uses=0
vault read -field=role_id auth/approle/role/management-node/role-id
vault write -f -field=secret_id auth/approle/role/management-node/secret-id
```

The deployment sets `VAULT_ROLE_ID` and `VAULT_SECRET_ID`, the secret ID from a Kubernetes Secret. The application does not start without them.

Spring Cloud Vault renews the token before it expires. When the token reaches its maximum TTL, the service logs in again without a restart.

After 5 failed logins Vault locks the AppRole, and every login for it returns 403, even with the right secret ID. The lock lasts 15 minutes by default. An administrator can list locks at `sys/locked-users` and lift one with `sys/locked-users/:mount_accessor/unlock/:role_id`.

### Kubernetes auth

With Kubernetes auth there is no secret ID to store: the pod logs in with its service account token. Vault must be able to reach the cluster's API, and its Kubernetes auth role must bind the Management Node's service account and namespace and attach only `mgmt-node-pki`, with the same TTLs as above.

The deployment sets:

| Variable | Value |
|---|---|
| `VAULT_AUTHENTICATION` | `kubernetes` |
| `VAULT_KUBERNETES_ROLE` | the Vault role name, `management-node` by default |
| `VAULT_KUBERNETES_PATH` | the auth mount, `kubernetes` by default |

This mode has not been tested with the Management Node.

### Token

`VAULT_AUTHENTICATION=token` with `spring.cloud.vault.token` is for local development only. A root token bypasses every policy, so permission problems do not show up in that mode.

### Shutdown

The policy does not grant `auth/token/revoke-self`. When the service stops, Spring Vault logs a `Cannot revoke VaultToken` warning, and the token stays valid until its TTL ends, at most 15 minutes later.

---

## Permissions

### `mgmt-node-pki`

| Path | Capability | Used for |
|---|---|---|
| `pki-int/cert/*` | `read` | certificate status (`cert/:serial`), and the CA (`cert/ca`, `cert/ca_chain`) |
| `pki-int/certs/revoked` | `list` | serial numbers of revoked certificates |
| `pki-int/revoke` | `update` | revoking a certificate |
| `pki-int/sign/default-role` | `update`, without `not_after` | signing federator CSRs and bootstrap certificates, and issuing the Management Node's own OCSP signer |
| `auth/token/renew-self` | `update` | renewing its token |
| `auth/token/lookup-self` | `read` | reading its token's TTL |

The last two come from the default policy, which is not attached, so they are listed here.

`not_after` is refused because Vault caps `ttl` at the role's `max_ttl` but does not check `not_after` against it. A caller with `not_after` could get any date the CA allows.

`certs/revoked` returns serial numbers only. The revocation time comes from `cert/:serial`.

The OCSP signer key is generated by the Management Node and kept in memory, so the policy grants no access to secrets. See [OCSP signer](OCSP.md#ocsp-signer).

### Everything else

Any other path returns 403, including `root/*`, `issue/*`, `sign-intermediate`, `sign-verbatim`, `config/*`, `sys/*` and other mounts.

### Public endpoints

No policy can change this exception. These endpoints do not check the token and answer every caller:

| Where | Endpoints |
|---|---|
| Vault | `sys/health`, `sys/seal-status`, `sys/leader`, `sys/init`, `sys/internal/specs/openapi`, `sys/internal/ui/mounts`, `sys/generate-root/attempt`, `sys/rekey/init`, `sys/replication/status`, `sys/internal/ui/feature-flags` |
| PKI mount | `ca`, `ca/pem`, `ca_chain`, `crl`, `crl/pem`, `crl/delta`, `crl/delta/pem`, `cert/*`, `LIST issuers`, `issuer/:id/pem`, `issuer/:id/der`, `issuer/:id/json`, `issuer/:id/crl`, `issuer/:id/crl/*`, `ocsp` |

That is why nodes calling Vault directly has to be prevented on the network.

---

## Behaviour

- **Auto-tidy** removes certificates, and their revocation records, 72 hours after they expire. An OCSP request for such a serial returns `unknown`. The certificate has expired, so clients reject it regardless.
- **Revocation** is visible in `cert/:serial` immediately, so OCSP reports it on the next request. Revoking the same serial again returns the first revocation time, revoking an unknown serial returns 400, and revoking an expired certificate returns 200 with a warning and records nothing.
- **Vault unavailable:** OCSP answers `tryLater`, never `good`. `/actuator/health` returns 503, so it should not be used as a liveness probe.
- **Login failing:** `/actuator/health` stays `UP` when the AppRole login fails. These failures show as `tryLater` and in the `ocsp.vault.errors` metric.
