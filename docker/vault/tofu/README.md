**Repository:** `management-node`  
**Description:** `OpenTofu configuration of the Vault or OpenBao instance used by the Management Node`  
**SPDX-License-Identifier:** `Apache-2.0 AND OGL-UK-3.0`

# Overview

This directory is the only place where the Vault side of the Management Node is configured. Applied to a clean instance, it creates:

- the PKI mount (`pki-int`) with its AIA URLs, CRL settings and auto-tidy;
- the intermediate CA's private key, inside Vault, and its CSR;
- the PKI role `default-role`, which also issues the Management Node's OCSP signer;
- the ACL policy `mgmt-node-pki`;
- the AppRole `management-node` and its identity entity;
- a file audit device.

What each permission is for is described in [Vault Access](../../../docs/VAULT_ACCESS.md).

The configuration runs as a Vault administrator. It has been checked on Vault 1.16, OpenBao 2.1.0 and OpenBao 2.7.2.

---

## Directory Layout

- backend.tf           - S3 backend and required providers
- providers.tf         - Vault provider
- variables.tf         - Input variables
- mounts.tf            - PKI mount
- ca.tf                - Intermediate CA: key and CSR, signed chain
- pki_config.tf        - AIA URLs, CRL settings, auto-tidy
- roles.tf             - PKI roles
- policies.tf          - ACL policies, from the templates in policies/
- auth.tf              - AppRole and identity entity
- audit.tf             - File audit device
- outputs.tf           - Credentials for the Management Node
- terraform.tfvars     - Default values for local use
- backends/            - Backend config files
- tfvars/              - Per-workspace tfvars files
- scripts/verify.sh    - Checks an applied configuration with the Management Node's credentials
- Makefile             - Helper targets

The Makefile targets work the same way as in [docker/keycloak/tofu](../../keycloak/tofu/README.md).

---

## Prerequisites

These steps are outside this code:

- Vault or OpenBao is initialised and unsealed.
- An administrator token is available. Pass it as `TF_VAR_vault_token`.
- For a local run, `backend.tf` uses the local backend, as described in the [Keycloak README](../../keycloak/tofu/README.md#using-a-local-backend-no-s3-on-your-machine).

### A Vault configured by hand

Start from an empty Vault. `tofu apply` fails on whatever already exists (the PKI mount, AppRole, the audit device, the identity entity), and a CA imported earlier has had its private key outside Vault, which this configuration exists to avoid.

---

## Usage

```sh
cd docker/vault/tofu
export TF_VAR_vault_token=<administrator token>
make init WORKSPACE=dev
make plan WORKSPACE=dev
make apply WORKSPACE=dev
```

The first apply leaves a mount that cannot issue certificates yet. Follow [Setting up the CA](#setting-up-the-ca) to finish it.

Environment-specific values go in `tfvars/<workspace>.tfvars`. Values in `terraform.tfvars` take precedence over `TF_VAR_*` variables, so override them with a tfvars file or `-var`.

| Variable | Set it to |
|---|---|
| `vault_address` | the Vault or OpenBao address |
| `issuing_certificates`, `crl_distribution_points` | URLs that the nodes can reach |
| `pki_intermediate_signed_chain` | the chain returned by the offline root (see [Setting up the CA](#setting-up-the-ca)) |
| `enable_audit_device` | `false` when the audit device is managed elsewhere (see [OpenBao](#openbao)) |

Durations are in seconds, because that is how Vault returns them. Otherwise every plan would show a change.

### Setting up the CA

The private key of the intermediate CA is generated inside Vault and cannot be exported. Only the CSR leaves Vault, so nothing secret reaches the state file. Local development works the same way, with `docker/rootCA.*` as the offline root.

Run these steps once per environment, after the first apply. The signing in step 2 is manual.

1. Save the CSR:

   ```sh
   tofu output -raw pki_intermediate_csr > intermediate.csr
   ```

2. Have the offline root sign it. The certificate must be a CA, with `keyCertSign`, `cRLSign` and a subject key identifier. Vault refuses to import it without the identifier, because it cannot build a CRL. Keep its lifetime shorter than the root's.

   Locally:

   ```sh
   cat > intermediate.ext <<EOF
   basicConstraints=critical,CA:TRUE,pathlen:0
   keyUsage=critical,keyCertSign,cRLSign
   subjectKeyIdentifier=hash
   authorityKeyIdentifier=keyid
   EOF
   openssl x509 -req -in intermediate.csr -CA ../../rootCA.crt -CAkey ../../rootCA.key -passin pass:changeit \
     -CAcreateserial -days 1825 -extfile intermediate.ext -out intermediate.crt
   ```

3. Put the chain in `pki_intermediate_signed_chain`, with the intermediate first and the root after it. The value is public. Keep it in `tfvars/<workspace>.tfvars`, because every later plan needs it too. Locally, an environment variable is enough:

   ```sh
   export TF_VAR_pki_intermediate_signed_chain="$(cat intermediate.crt ../../rootCA.crt)"
   ```

   A plan without the chain offers to destroy `vault_pki_secret_backend_intermediate_set_signed.ca[0]`. That only removes the entry from the state: the CA keeps working, and the next apply with the chain adds the entry back.

4. Apply again, then check the result as described in [Checking the result](#checking-the-result):

   ```sh
   make plan WORKSPACE=dev
   make apply WORKSPACE=dev
   ```

   The apply also succeeds with a chain signed for a different CSR, for example from an earlier attempt. The mount then has no default issuer and cannot sign, so `make verify` fails with `no default issuer currently configured`. Apply again with the right chain. The wrong issuers stay listed in the mount, unused.

### Credentials for the Management Node

The Management Node reads `VAULT_ROLE_ID` and `VAULT_SECRET_ID`.

- **Role ID:** `tofu output -raw management_node_role_id`
- **Secret ID, local:** set `create_dev_secret_id = true` (already set in `tfvars/dev.tfvars`), then `tofu output -raw management_node_secret_id`. The value is in the state file, so this is not for shared environments.
- **Secret ID, elsewhere:** the deployment pipeline generates it, so that it never reaches the state file:

  ```sh
  vault write -f -field=secret_id auth/approle/role/management-node/secret-id
  ```

### Checking the result

`make verify` logs in as the Management Node, signs and revokes a short-lived certificate, checks a sample of allowed and denied paths, including `not_after` on the signing path, and reads back the PKI configuration.

```sh
export VAULT_ADDR=<address>
export ADMIN_TOKEN=<administrator token>
export VAULT_ROLE_ID=$(tofu output -raw management_node_role_id)
export VAULT_SECRET_ID=$(tofu output -raw management_node_secret_id)
make verify
```

It needs the `vault` CLI (or `bao`, with `VAULT_CLI=bao`) and `openssl`. Set `AUDIT_FILE` to the audit log path to check the audit entry as well. The script has to be able to read that file, so run it on the Vault host.

---

## OpenBao

The same code applies to OpenBao, with one difference. Current OpenBao versions refuse to enable audit devices through the API (`cannot enable audit device via API`), so set `enable_audit_device = false` and declare the device in the server configuration:

```hcl
audit "file" "file" {
  options {
    file_path = "/openbao/logs/audit.log"
  }
}
```

---

## Notes

- The CA's private key exists only in Vault. Deleting the PKI mount, for example with `make destroy`, deletes the key. Changing `pki_intermediate_common_name` generates a new key, which the offline root has to sign.
- Auto-tidy, the PKI configuration and the roles are out of reach of the Management Node's token, so changing them always goes through this code.
- If every audit device fails to write, for example because the disk is full, Vault blocks all requests. Monitor the audit log's disk space.
