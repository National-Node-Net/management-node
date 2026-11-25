# Certificate Revocation Demo (Postman)

**Repository:** `management-node`  
**Description:** `A local, Postman-driven demo of certificate signing, OCSP status, revocation and the revocation filter`  
**SPDX-License-Identifier:** `Apache-2.0 AND OGL-UK-3.0`

---

## What the demo shows

Four steps, each one a folder in the Postman collection:

1. **Sign:** a node generates its own key and CSR, and Management Node signs the CSR through Vault PKI.
2. **Status:** another federation participant asks the OCSP responder about the certificate and gets `good`. The node fetches its producer configuration with the new certificate.
3. **Revoke:** an administrator revokes the certificate with a reason and a comment. Both are stored in the database.
4. **Status after revocation:** OCSP answers `revoked (keyCompromise)` straight away, and the node's next configuration request is refused with `401 CERTIFICATE_REVOKED`.

Everything runs on your machine. The demo uses a throwaway Vault, so the shared `docker/vault` Vault is not touched.

| File | What it is |
|---|---|
| `revocation-demo.postman_collection.json` | the Postman collection |
| `setup-vault.sh` | starts the throwaway Vault with a ready PKI and builds the application's truststore |
| `start-app.sh` | starts Management Node against that Vault |
| `demo-env.sh` | helper functions for the terminal (see [Helper reference](#helper-reference)) |
| `revocation-check.sql` | two queries: the ENV organisation's certificate row and its history |
| `work/` | everything the demo generates (keys, certificates, OCSP request, log); gitignored |

Postman cannot generate a key or build a binary OCSP request, so the terminal does exactly two things during the demo: `node_csr` before signing and `node_files` after it.

---

## Who is who

| Actor | mTLS certificate | Token | Used for |
|---|---|---|---|
| Operator / ENV federator | `client.crt` on `localhost:8090` | `FEDERATOR_ENV` | signing the CSR, revoking |
| Another federation participant | `client.crt` on `localhost:8090` | `management-node` (has `check_certificate_status`) | asking OCSP for the status |
| The node with the new certificate | `node.crt` (from Vault) on `127.0.0.1:8090` | `FEDERATOR_ENV` | fetching the producer configuration: 200 before revocation, 401 after |

The same server answers on two addresses, `localhost` and `127.0.0.1`. This is a Postman trick: a client certificate is attached to a host, so each address presents a different certificate.

---

## Prerequisites (once per machine)

### Tools

| Tool | Notes |
|---|---|
| Git Bash | the scripts are bash; on Windows they were written for Git Bash |
| Docker | runs Keycloak, its Postgres and the throwaway Vault |
| JDK 21 | for `./mvnw` and `keytool`; set `JAVA_HOME` if `keytool` is not on `PATH` |
| OpenSSL | ships with Git Bash |
| Postman desktop | the web version cannot use client certificates or local files |
| SQL client (optional) | DBeaver, pgAdmin or DataGrip; the terminal works too |

### Local setup from the main README

The demo builds on the standard local setup, so follow these sections of the [main README](../../README.md) first:

1. **Certificate Setup:** `client.crt`, `client.key`, `keystore.jks` and `truststore.jks` in the repository root (password `changeit`).
2. **Setting up Keycloak with Docker Compose:** `docker/keycloak/.env` with `KEYCLOAK_ADMIN`, `KEYCLOAK_ADMIN_PASSWORD`, `POSTGRES_USER` and `POSTGRES_PASSWORD`. Keycloak on `https://localhost:8443` with mTLS, and its Postgres on `localhost:5433`.
3. **Keycloak Realm Setup:** realm `mng-node`. Its `management-node` service account already has `check_certificate_status`.

You do **not** need the Vault setup from the main README. `setup-vault.sh` replaces it.

### Keycloak client `FEDERATOR_ENV`

The demo acts as the sample organisation `ENV` (from `src/main/resources/db/samples`). It needs a Keycloak client `FEDERATOR_ENV` whose tokens carry the `organisation=ENV` claim, with these `management-node` client roles on its service account:

| Role | For |
|---|---|
| `sign_certificate` | 1.2 Sign CSR |
| `access_producer_configurations` | 2.4 and 4.4 Producer config |
| `revoke_certificate` | 3.2 Revoke certificate |

The federator clients created by `docker/keycloak/tofu` already have the claim mapper. Assigning `revoke_certificate` is described in [CERTIFICATE_REVOCATION.md](../../docs/CERTIFICATE_REVOCATION.md#roles). Without the tofu clients, create `FEDERATOR_ENV` by hand as in the main README ("Testing further with a federator client") and add the roles above.

---

## Preparation (about 15 minutes before the demo)

### 1. Environment (Git Bash, repository root)

Keycloak and its Postgres must be running (`docker ps` shows `keycloak` and `keycloak-postgres-1`).

```bash
./mvnw -q package -DskipTests
bash scripts/revocation-demo/setup-vault.sh
```

`setup-vault.sh`:

1. **Starts a fresh Vault in Docker** in dev mode: container `vault-demo`, port 8210, root token `demo-root-token`. Dev mode keeps everything in memory, so every run starts clean.
2. **Creates the PKI with the same layout as the environments.** It mounts `pki-int`, creates the CA `Demo Federation CA` and the role `default-role` with `max_ttl=72h`. Management Node signs CSRs with that role.
3. **Adds the new CA to a copy of the application's truststore.** Without it, Tomcat would refuse the node's certificate at the mTLS handshake, before the revocation filter is reached.
4. **Clears any revocation left on the ENV certificate row** by a previous run, so the demo can be repeated.

> If asked: *"On the environments, DevOps set up Vault and the PKI. Here I start a local copy with the same layout: mount `pki-int`, role `default-role`."*

In a second window, keep the application running:

```bash
bash scripts/revocation-demo/start-app.sh
```

It is ready when the log shows `Started ManagementNodeApplication` and `Certificate revocation enforcement is ACTIVE`.

`start-app.sh` runs the built JAR with these overrides of `application.yml`:

| Override | Why |
|---|---|
| `vault.uri=http://localhost:8210`, `authentication=token` | the test Vault and its root token; the environments log in with AppRole and policy `mgmt-node-pki` |
| `trust-store=work/truststore-demo.jks` | the truststore from `setup-vault.sh`, which trusts the test CA |
| `opaquetoken.client-secret` | `management-node`'s secret for token introspection, read live from Keycloak |
| `application.opa.enabled=false` | no OPA server in this demo |

> Show the log line `Certificate revocation enforcement is ACTIVE (mTLS required)`: *"The application says at startup whether it checks revocation and why. `/actuator/info` reports the same."*

Always run `setup-vault.sh` **before** `start-app.sh`. Every new Vault has a new CA, and at startup the application issues itself a certificate from it to sign OCSP responses.

### 2. Postman settings (Settings, the gear icon)

| Where | What to set |
|---|---|
| General → SSL certificate verification | **OFF** (the servers use local development certificates) |
| General → Working directory | `<repo>\scripts\revocation-demo\work` (Postman reads `ocsp-req.der` from here) |
| Certificates → Add certificate | Host `localhost`, port `8090`: CRT `<repo>\client.crt`, KEY `<repo>\client.key` |
| Certificates → Add certificate | Host `localhost`, port `8443`: the same `client.crt` / `client.key` (Keycloak requires mTLS too) |
| Certificates → Add certificate | Host `127.0.0.1`, port `8090`: CRT `<repo>\scripts\revocation-demo\work\node.crt` (**.crt, not .csr**), KEY `<repo>\scripts\revocation-demo\work\node.key` |

The `node.*` files do not exist yet. They are created during the demo, and Postman reads them only when a request is sent.

### 3. Postman collection

1. Import `scripts/revocation-demo/revocation-demo.postman_collection.json`.
2. Collection → Variables: fill `kcAdminUser` and `kcAdminPassword` (current value) from `docker/keycloak/.env` (`KEYCLOAK_ADMIN`, `KEYCLOAK_ADMIN_PASSWORD`).
3. Run folder **0. Setup (once, before the demo)**. All 5 requests should return 200. The folder stores the client secrets of `FEDERATOR_ENV` and `management-node` in collection variables.

Re-importing the collection clears its variables, so after every import repeat steps 2 and 3.

### 4. Database

Connect with a SQL client:

| Field | Value |
|---|---|
| Host / port | `localhost` / `5433` |
| Database | `keycloak_db` |
| Schema | `mn` |
| User / password | `POSTGRES_USER` / `POSTGRES_PASSWORD` from `docker/keycloak/.env` |

Open `scripts/revocation-demo/revocation-check.sql`. Without a SQL client, the terminal does the same:

```bash
( set -a; . docker/keycloak/.env; set +a; docker exec -i keycloak-postgres-1 psql -U "$POSTGRES_USER" -d keycloak_db < scripts/revocation-demo/revocation-check.sql )
```

### 5. Terminal for the demo (Git Bash, repository root)

```bash
source scripts/revocation-demo/demo-env.sh
```

During the demo you only run two commands from it: `node_csr` and `node_files`.

---

## Running the demo

Keycloak tokens live 5 minutes, so every folder starts with a token request. Always send the requests in order, and send each token request right before the request that uses it.

The whole terminal part of the demo:

| When | In the terminal |
|---|---|
| once, at the start | `source scripts/revocation-demo/demo-env.sh` |
| before 1.2 (step 1a) | `node_csr` |
| after 1.2, before folder 2 (step 1e) | `node_files` |

### Step 1. Generate and sign

| # | Where | What | Result | What to say |
|---|---|---|---|---|
| 1a | terminal, then Postman | `node_csr`, then in **1.2 Sign CSR** → Body: `Ctrl+A`, `Ctrl+V` | `node.key`, `node.csr`, `sign.json` in `work/`; the body of 1.2 shows `{"csr": "-----BEGIN CERTIFICATE REQUEST-----..."}` | The node generates its own private key and CSR. The key never leaves the node: Management Node only gets what you see in the body. |
| 1b | Postman | **1.1 Token FEDERATOR_ENV** | 200, Test Results list the roles | The node logs in to Keycloak (client credentials). Note the `sign_certificate` role. |
| 1c | Postman | **1.2 Sign CSR**, right after 1.1 | 200, Test Results: `serial = …` | Management Node signs the CSR through Vault PKI (`pki-int/sign/default-role`) and records the certificate in the database. |
| 1d | database | queries 1 and 2 | new `serial_number`, `revoked_*` columns empty; `RENEWED` in the history | The issued certificate is recorded against organisation ENV. |
| 1e | terminal | `node_files` | `node.crt`, `ocsp-req.der`, subject and issuer `CN=Demo Federation CA` | We fetch the issued certificate and prepare an OCSP request for its serial number. |

**1a. What `node_csr` does:**

```bash
openssl req -new -newkey rsa:2048 -nodes -keyout node.key -out node.csr -subj "/CN=env-node.federator.local"
```

- It creates an **RSA 2048 key pair** and a **CSR** with the node's name `CN=env-node.federator.local`.
- `node.key` stays on disk. `-nodes` means the key has no passphrase, which is fine only for a demo.
- `sign.json` is the CSR wrapped in JSON (`{"csr": "-----BEGIN CERTIFICATE REQUEST-----\n..."}`). `node_csr` copies it to the clipboard (`clip.exe` on Windows, `pbcopy` on macOS). Paste it into **1.2 Sign CSR** → Body (raw, JSON) over the placeholder text. Every `node_csr` creates a new key, so paste the body again after each run.

> *"The node generates its private key itself and never sends it. Management Node only gets the CSR: the public key and the node's name, signed with the private key, which proves the node holds it."*

**1c. What Management Node does on 1.2 Sign CSR:**

1. It checks the token and the `sign_certificate` role.
2. It sends the CSR to Vault (`pki-int/sign/default-role`) and gets the certificate back.
3. It stores the certificate's serial number in `organisation_certificate` for organisation ENV.
4. It adds an event to `certificate_events`.

Both writes show up in step 1d.

**1e. What `node_files` does:**

1. **It reads the serial number** Management Node stored for ENV from the database.
2. **It fetches that certificate from Vault** (`pki-int/cert/<serial>`) and saves it as `node.crt`. This is a demo shortcut. In real life the node takes the certificate from the response to 1.2; here it saves copying the PEM out of Postman by hand.
3. **It builds the OCSP request** (`openssl ocsp ... -reqout ocsp-req.der`). This is a small binary (DER) file with three fields: a hash of the issuer's name, a hash of the issuer's key, and the serial number. `ca.pem` is needed to compute the hashes. The certificate itself is not in the request.
4. **It prints the subject, issuer and serial.** Point out **issuer = `CN=Demo Federation CA`**: the certificate comes from our PKI.

> *"An OCSP request does not contain the certificate, only the serial number and the issuer's identity. Any federation participant can ask about someone else's certificate knowing only its serial number."*

**Every 1.2 issues a new certificate.** If you send 1.2 again, run `node_files` again too, or OCSP and 2.4/4.4 will be about the previous certificate.

### Step 2. Status

| # | Where | What | Result | What to say |
|---|---|---|---|---|
| 2a | Postman | **2.1 Token management-node** | 200 | The status is checked by another federation participant, which has the `check_certificate_status` role. |
| 2b | Postman | **2.2 OCSP status** | 200, Test Results: `certStatus = good`; Body → Visualize: a table with the status | Standard OCSP (RFC 6960). The response is signed and valid for 5 minutes. |
| 2c | Postman | **2.3 Token FEDERATOR_ENV**, then **2.4 Producer config as the node** | 200 and the configuration | The node with its new certificate fetches its configuration as usual. |

**2b.** The body is `ocsp-req.der` from step 1e, built without a nonce (`-no_nonce`; a nonce is optional in RFC 6960). This lets 4.2 send exactly the same request, so the answers before and after revocation can be compared.

The response is binary DER, so **Body → Pretty/Raw** shows garbage. That is not an error or encryption, it is the OCSP format. The collection decodes the response in a test script. The result is in **Test Results** and in **Body → Visualize**: a coloured status, the serial, `thisUpdate` and `nextUpdate`. A test also checks that the response is about the serial from 1.2.

> *"OCSP is binary, not JSON. Postman shows the bytes, so a script decodes them. `openssl ocsp` gives the same result."*

**2c.** On `127.0.0.1:8090` Postman presents `node.crt`. `/api/v1/configuration/producer` is one of the paths the revocation filter checks, so the filter looks up this certificate's status in Vault, which is then cached. The certificate is valid, so the request goes on to the token check.

### Step 3. Revocation

| # | Where | What | Result | What to say |
|---|---|---|---|---|
| 3a | Postman | **3.1 Token FEDERATOR_ENV**, then **3.2 Revoke certificate** | 200, `status = REVOKED, reason = KEY_COMPROMISE` | The administrator revokes the certificate with a reason and a comment. Management Node revokes it in Vault and evicts it from the status cache. |
| 3b | database | queries 1 and 2 | `revoked_at`, `revoked_by`, **`revocation_reason = KEY_COMPROMISE`**, `revocation_message = Demo: private key exposed`; `REVOKED` in the history | The reason and the comment are stored. `revoked_by` is the token's `sub`, so it records who revoked the certificate. |

The body of 3.2. The serial is filled in from step 1c:

```json
{
  "serialNumber": "{{serial}}",
  "reason": "KEY_COMPROMISE",
  "comment": "Demo: private key exposed"
}
```

Possible reasons are `UNSPECIFIED`, `KEY_COMPROMISE`, `AFFILIATION_CHANGED`, `SUPERSEDED` and `CESSATION_OF_OPERATION`. The comment can be at most 500 characters.

> At 3b: *"Vault is the source of truth for revocation: it knows the certificate is revoked. The database keeps the context: who, when, why and the comment."*

### Step 4. Status after revocation

| # | Where | What | Result | What to say |
|---|---|---|---|---|
| 4a | Postman | **4.1 Token management-node**, then **4.2 OCSP status** | `certStatus = revoked (keyCompromise)` with the revocation time | OCSP answers `revoked` with the reason straight away. It reads Vault without a cache. |
| 4b | Postman | **4.3 Token FEDERATOR_ENV**, then **4.4 Producer config as the node** | **401**, `code = CERTIFICATE_REVOKED` | The revoked node can no longer fetch its configuration. The filter refuses it before the token is even checked. |
| 4c | Postman (optional) | **4.5 Revoke again** | 409 | The same certificate cannot be revoked twice. |

> To finish: *"Revocation takes effect at once. OCSP reads Vault without a cache. The filter does cache statuses, but the revoke API evicts the entry, so 4.4 gets a 401 immediately, not when the cache expires."*

---

## Helper reference

All helpers are in `demo-env.sh`, loaded with `source scripts/revocation-demo/demo-env.sh` from the repository root.

### Run by hand during the demo

| Function | Step | What it does |
|---|---|---|
| `node_csr` | 1a, before 1.2 | Creates `node.key` and `node.csr` (`CN=env-node.federator.local`) and writes `sign.json`, the body of 1.2. Copies `sign.json` to the clipboard. |
| `node_files` | 1e, after 1.2 | Reads the ENV certificate's serial from the database and fetches that certificate from Vault as `node.crt`. Builds the OCSP request `ocsp-req.der` and prints the subject, issuer and serial. |

### Used by the scripts and the functions above

| Function | Used by | What it does |
|---|---|---|
| `vault ARGS...` | `setup-vault.sh`, `node_files` | Runs the Vault CLI inside the `vault-demo` container with the root token. |
| `db "SQL"` | `setup-vault.sh` | Runs SQL in the local Postgres (`keycloak-postgres-1`, database `keycloak_db`) and prints the table. Credentials come from `docker/keycloak/.env`. |
| `db_value "SQL"` | `node_files` | The same, but prints only the bare value. |
| `json_pem FILE` | `node_csr` | Turns a PEM file into one JSON string (line breaks as `\n`). |
| `kc_secret CLIENT_ID` | `start-app.sh` | Reads a client's secret through the Keycloak admin API. |
| `mtls PORT METHOD PATH CERT [CTYPE BODY]` | `kc_secret`, `kc_token`, `api`, `ocsp_check` | Sends one HTTPS request with a client certificate through `openssl s_client`. Curl in Git Bash cannot do mTLS here. |
| `body`, `json FIELD` | `kc_secret`, `kc_token` | Keep only the body of an HTTP answer; read a string field from JSON without `jq`. |

### For checks from the terminal (not needed with Postman)

| Function | What it does |
|---|---|
| `kc_token CLIENT_ID SECRET` | Gets a client-credentials token from Keycloak. |
| `api METHOD PATH [JSON_FILE] [CERT]` | Calls Management Node as ENV with a `FEDERATOR_ENV` token and prints the status and body. Pass `"$WORK/node"` as `CERT` to call as the node. |
| `ocsp_check CERT_FILE` | Asks OCSP about a certificate as `management-node` and prints the decoded answer. |
| `jwt_claims TOKEN` | Decodes a JWT's payload, for example to show its roles. |

### Variables

| Variable | Value |
|---|---|
| `ROOT` | the repository root |
| `WORK` | `scripts/revocation-demo/work`, every generated file |
| `VAULT_CONTAINER`, `VAULT_PORT` | `vault-demo`, `8210` |
| `VAULT_TOKEN` | `demo-root-token`, the dev-mode container's root token |
| `MN_PORT`, `MN_ACTUATOR` | `8090`, `8081` |
| `KC_PORT` | `8443` |
| `OPERATOR_CERT` | `<repo>/client`, meaning `client.crt` and `client.key` |
| `ORG_KEY` | `ENV` |
| `KEYTOOL` | `keytool` from `JAVA_HOME`, `PATH` or an installed JDK 21 |

`VAULT_CONTAINER`, `VAULT_PORT`, `MN_PORT` and `MN_ACTUATOR` can be set before `source` if a port is taken. The Postman collection always uses 8090 and 8443.

---

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| Postman: `Error: socket hang up` / `ECONNRESET` | no client certificate for that host and port (Settings → Certificates) |
| 2.2 / 4.2: `ENOENT` or "file not found" | `node_files` was not run after 1.2 (step 1e), so `ocsp-req.der` and `node.crt` are missing. Run it; also check the Postman working directory. |
| 2.4 / 4.4: `PEM routines ... NO_START_LINE` | the CRT for `127.0.0.1:8090` points at the wrong file (for example `node.csr`); it must be `node.crt` |
| 1.1: test `envSecret is set` fails | folder 0 was not run after importing the collection |
| 1.2: 400 | the body still holds the placeholder: run `node_csr` and paste the clipboard into the body |
| 401 without a `code` field | the token expired (5 minutes) or is empty: send that folder's "Token …" request right before the request |
| 1.2: 403 "a new bootstrap is required" | the ENV certificate is still revoked from a previous run: stop the application, run `setup-vault.sh` and `start-app.sh` again |
| 4.2 shows `good` after revocation; Test Results: "ocsp-req.der is for serial …" | 1.2 was sent again after `node_files`, so a newer certificate was revoked. Run `node_files` and send 4.1–4.4 again. |
| 3.2: 400 "must be hex bytes separated by colons" | the `serial` variable is empty (for example after re-importing the collection): send 1.1 and 1.2, then run `node_files` |
| OCSP: `responseStatus = unauthorized` / `malformedRequest` | `ocsp-req.der` belongs to an earlier Vault: after `setup-vault.sh`, run `node_csr`, 1.2 and `node_files` again |
| 2.4: 403 "serial mismatch" | `node.crt` comes from another run: run `node_files` after the last 1.2 |
| `node_files`: "not found in Vault" | 1.2 went to an application not started by `start-app.sh`, or the Vault was recreated after 1.2 |

---

## After the demo

Stop the application (`Ctrl+C`), then remove the Vault and the generated files:

```bash
docker rm -f vault-demo
rm -rf scripts/revocation-demo/work
```

The demo overwrites the ENV organisation's certificate row in your local database and adds rows to `certificate_events`. This is local sample data. Running `setup-vault.sh` again clears the revocation, so the demo can be repeated.
