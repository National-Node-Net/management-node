# OCSP Local Testing Guide

**Repository:** `management-node`  
**Description:** `Step-by-step manual verification of the OCSP endpoint against a local Keycloak and Vault`  
**SPDX-License-Identifier:** `Apache-2.0 AND OGL-UK-3.0`  

---

## Overview

This guide checks `POST /api/v1/certificate/ocsp` end to end on a local machine. It covers eight scenarios, the metrics, a load test and the automated tests. Keycloak, Vault and certificate setup is described in the [README](../README.md) and is assumed to be working. The endpoint itself is specified in [OCSP Certificate Status API](OCSP.md).

---

## Prerequisites

Run every command from the repository root. On Windows, use Git Bash. Check the following before you start:

- Keycloak and Vault containers are running and Vault is unsealed (`vault operator unseal`).
- The `management-node` client in realm `mng-node` defines the client role `management-node-access`. If the realm was imported before this role was added to `docker/keycloak/management-node.json`, add the role manually in the admin console. Re-importing does not update a running Keycloak.
- A federator client exists, for example `FEDERATOR_ENV`. The role `management-node-access` is assigned to the service account of that client. Federator clients are the consumer and producer nodes that call the OCSP endpoint in a real deployment. You can create the client in either of two ways:
  - **OpenTofu** (`docker/keycloak/tofu`): `terraform.tfvars` already maps the role to `FEDERATOR_ENV`, `FEDERATOR_BCC` and `FEDERATOR_HEG`. These clients authenticate with their mTLS certificate (`client-x509`) and have no secret.
  - **Manually**: follow "Testing further with a federator client" under [Testing API Endpoints](../README.md#testing-api-endpoints) in the README, and in step 5 also assign `management-node-access`. Such a client authenticates with a client secret.
- Vault is set up as in [Vault Setup](../README.md#vault-setup-optional-required-for-certificate-manager). The role `default-role` accepts EC keys (`key_type=any`); the Management Node issues its OCSP signer through it.
- `client.crt` and `client.key` are in the repository root.

Session variables:

```bash
export MSYS_NO_PATHCONV=1                          # Git Bash only: stops "/CN=..." being rewritten as a Windows path
export VAULT_TOKEN=<vault administrator token>     # the commands below need more than the Management Node's token allows
export FEDERATOR_CLIENT_ID=FEDERATOR_ENV
export FEDERATOR_CLIENT_SECRET=<client secret>      # only for a client created manually with client authentication
export CURL=curl                                   # or the path to a curl build that uses OpenSSL
```

Notes on `curl`:

- **curl built with OpenSSL** (Linux, macOS, the official curl for Windows build) accepts `--cert client.crt --key client.key`, as used below.
- **curl built with Schannel** (the one bundled with Git for Windows) usually needs a PKCS#12 bundle instead. Create it with `openssl pkcs12 -export -out client-test.p12 -inkey client.key -in client.crt -passout pass:changeit`, then replace `--cert client.crt --key client.key` with `--cert-type P12 --cert client-test.p12:changeit --ssl-no-revoke`.
- **Use `-sS`, not `-s`.** With `-s` alone, curl fails silently, for example when a certificate file is missing, and no response file is written.

---

## Preparation

Do these steps once. They issue a test certificate, start the application and get a token. The OCSP signer needs no preparation: the application issues it through `default-role` when it starts.

1. Fetch the CA and issue a test certificate. The test certificate is the one that should come back as `good`:

   ```bash
   docker exec -e VAULT_TOKEN=$VAULT_TOKEN vault vault read -field=certificate pki-int/cert/ca > ca.pem
   docker exec -e VAULT_TOKEN=$VAULT_TOKEN vault vault read -field=ca_chain pki-int/cert/ca_chain > ca-chain.pem
   openssl req -new -newkey rsa:2048 -nodes -keyout ocsp-test.key -subj "/CN=ocsp-test.local" -out ocsp-test.csr
   docker exec -i -e VAULT_TOKEN=$VAULT_TOKEN vault vault write -field=certificate \
     pki-int/sign/default-role csr=- ttl=2h < ocsp-test.csr > ocsp-test.pem
   rm ocsp-test.key ocsp-test.csr
   ```

   `default-role` accepts any key type, so Vault can only sign with it. `issue/default-role` fails with `role key type "any" not allowed for issuing certificates`.

2. Start the application:

   ```bash
   ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
   ```

   The log should contain `Signing CSR with role: default-role` followed by `Issued OCSP signer CN=ocsp-signer.management-node ... valid until ...`. If it has `Failed to issue OCSP signer` instead, Vault was not reachable or `default-role` refused the CSR. The application tries again every 5 minutes.

3. Get a token as the federator client. Every caller uses the token endpoint of realm `mng-node`, because that realm is the issuer the Management Node trusts (`spring.security.oauth2.resourceserver.jwt.issuer-uri`). Only the client and the way it authenticates differ.

   For a client created with OpenTofu, which authenticates with its mTLS certificate:

   ```bash
   TOKEN=$("$CURL" -sSk --cert client.crt --key client.key \
     -d grant_type=client_credentials -d "client_id=$FEDERATOR_CLIENT_ID" \
     https://localhost:8443/realms/mng-node/protocol/openid-connect/token | jq -r .access_token)
   ```

   For a client created manually with a client secret:

   ```bash
   TOKEN=$("$CURL" -sSk --cert client.crt --key client.key \
     -d grant_type=client_credentials -d "client_id=$FEDERATOR_CLIENT_ID" \
     --data-urlencode "client_secret=$FEDERATOR_CLIENT_SECRET" \
     https://localhost:8443/realms/mng-node/protocol/openid-connect/token | jq -r .access_token)
   ```

   Check that the token belongs to the federator and carries the role:

   ```bash
   echo $TOKEN | cut -d. -f2 | base64 -d 2>/dev/null | grep -oE '"azp":"[^"]*"|management-node-access'
   ```

   The output should be `"azp":"FEDERATOR_ENV"` and `management-node-access`. Without `jq`, extract the token with `sed -E 's/.*"access_token":"([^"]+)".*/\1/'` instead. The token expires after the client's access token lifespan, so get a new one whenever you see `401`.

   For a quick check you can also request the token as the `management-node` client with its secret, as in the README's local setup. That client stands in for every caller locally and is not how nodes call the endpoint in a real deployment.

   The OCSP endpoint is under `/api/v1/certificate/**`, which `CertificateValidationInterceptor` skips. The federator therefore does not need an `organisation_certificate` row to check certificate status.

4. Define two helper functions:
   - `ocsp [request file] [response file]` sends a request. The defaults are `req.der` and `resp.der`.
   - `verify` saves the signer certificate from the response, checks that the `pki-int` CA issued it, then checks the signature, the nonce and the statuses.

   ```bash
   ocsp() { "$CURL" -sSk --cert client.crt --key client.key -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/ocsp-request" --data-binary @${1:-req.der} -o ${2:-resp.der} -D - https://localhost:8090/api/v1/certificate/ocsp; }
   verify() {
     openssl ocsp -respin resp.der -resp_text -noverify | sed -n '/BEGIN CERTIFICATE/,/END CERTIFICATE/p' > signer.pem
     openssl verify -CAfile ca-chain.pem signer.pem && openssl ocsp -reqin req.der -respin resp.der -issuer ca.pem -VAfile signer.pem -resp_text
   }
   ```

   The signer certificate has no `id-kp-OCSPSigning` EKU, so `-CAfile ca.pem` alone would fail with `missing ocspsigning usage`. `-VAfile` trusts the signer certificate explicitly instead.

---

## Test scenarios

Run the scenarios in order:
- F permanently revokes `ocsp-test.pem`, so it comes after the scenarios that expect `good`.
- H stops Vault, so it comes last.
- Each `openssl ocsp ... -reqout` call generates a new nonce, so build a fresh request in every scenario.

| # | What it checks | HTTP / Cache-Control | Expected OCSP result |
|---|---|---|---|
| A | Certificate issued by Vault | 200 / `max-age≈300` | `successful`, `good`, `Response verify OK` |
| B | Certificate not known to Vault | 200 / `max-age≈300` | `successful`, `unknown` |
| C | Several certificates in one request | 200 / `max-age≈300` | one signature; `good` + `unknown` in request order |
| D | More than 10 entries | 200 / `no-store` | `malformedRequest`, unsigned |
| E | Body that is not DER | 200 / `no-store` | `malformedRequest`, unsigned |
| F | Revocation in Vault | 200 / `max-age≈300` | `revoked` with Vault's time, no `Reason` |
| G | Missing token | 401 | empty body; the OCSP layer does not run |
| H | Vault unavailable | 200 / `no-store` | `tryLater`, never `good` |

### A. `good`

```bash
openssl ocsp -issuer ca.pem -cert ocsp-test.pem -nonce -reqout req.der
ocsp && verify
```

Check in the output:

- `signer.pem: OK` means the `pki-int` CA issued the signer certificate.
- `Response verify OK` with no `Nonce Verify error` means the signature is valid and the nonce was echoed.
- `Responder Id` equals the signer's `Subject Key Identifier`, so the responder ID is `byKey`.
- `Next Update` is `This Update` + 5 minutes, and `Cache-Control: max-age` counts down to it.
- `Signature Algorithm` is `ecdsa-with-SHA256`, because the signer key is EC P-256.
- The signer certificate `CN=ocsp-signer.management-node` is included in the response.

Pass only `-reqin` when verifying. If you add `-cert` or `-serial`, OpenSSL builds a new request with a different nonce and reports `Nonce Verify error`.

### B. `unknown`: certificate not known to Vault

`client.crt` has the same issuer, but `pki-int` does not hold it:

```bash
openssl ocsp -issuer ca.pem -cert client.crt -nonce -reqout req.der
ocsp && verify
```

Expected:
- `Cert Status: unknown` and `Response verify OK`.
- The application log has `certStatus=unknown responseStatus=successful` and no stack trace.

### C. Several certificates in one request

```bash
openssl ocsp -issuer ca.pem -cert ocsp-test.pem -cert client.crt -nonce -reqout req.der
ocsp && verify
```

Expected: two `Certificate ID` blocks in one signed response. `good` comes first, then `unknown`.

### D. Too many entries

The limit is `application.ocsp.max-request-entries` (default `10`):

```bash
openssl ocsp -issuer ca.pem $(for i in $(seq 1 11); do echo -serial 0x$i; done) -reqout big.der
ocsp big.der bad.der
openssl ocsp -respin bad.der -resp_text -noverify
```

Expected: `Responder Error: malformedrequest (1)` and `Cache-Control: no-store`.

### E. Garbage body

```bash
echo "garbage" > garbage.bin
ocsp garbage.bin bad.der
openssl ocsp -respin bad.der -resp_text -noverify
```

Expected: the same as D.

### F. `revoked`, visible immediately

```bash
docker exec -i -e VAULT_TOKEN=$VAULT_TOKEN vault vault write pki-int/revoke certificate=- < ocsp-test.pem
openssl ocsp -issuer ca.pem -cert ocsp-test.pem -nonce -reqout req.der
ocsp && verify
```

Expected:

- `Cert Status: revoked`, with a `Revocation Time` that matches `revocation_time` in Vault.
- No `Reason`, because Vault does not record a revocation reason.
- The status changes on the very next request, because status lookups are not cached.

### G. Missing token

```bash
"$CURL" -sSk --cert client.crt --key client.key -H "Content-Type: application/ocsp-request" \
  --data-binary @req.der -D - https://localhost:8090/api/v1/certificate/ocsp
```

Expected: HTTP `401` with `WWW-Authenticate: Bearer` and an empty body. For comparison, a token without the `management-node-access` role gets `403`.

### H. Vault unavailable

```bash
docker stop vault
ocsp && openssl ocsp -respin resp.der -resp_text -noverify
```

Expected: `Responder Error: trylater (3)` and `Cache-Control: no-store`.

Then bring Vault back:

```bash
docker start vault
docker exec vault vault operator unseal <unseal key>
```

The signer is kept in memory, so once Vault is unsealed, responses go back to `successful` without restarting the application. If Vault was unavailable when the application started, there is no signer in memory, so responses stay `tryLater` until the next reload, up to 5 minutes after Vault is back.

---

## Metrics and logs

After scenarios A–H, every OCSP metric should have a non-zero value. Metrics are served on the management port 8081 over plain HTTP, without mTLS:

```bash
curl -s http://localhost:8081/actuator/metrics/ocsp.requests
curl -s "http://localhost:8081/actuator/metrics/ocsp.requests?tag=response_status:tryLater"
curl -s http://localhost:8081/actuator/metrics/ocsp.cert.status
curl -s http://localhost:8081/actuator/metrics/ocsp.vault.errors
curl -s http://localhost:8081/actuator/metrics/ocsp.signer.expiry.seconds
```

| Metric | Expected after A–H |
|---|---|
| `ocsp.requests` | tag `response_status` has values `successful`, `malformedRequest` and `tryLater` |
| `ocsp.cert.status` | tag `cert_status` has values `good`, `unknown` and `revoked` |
| `ocsp.vault.errors` | ≥ 1 (after H) |
| `ocsp.signer.expiry.seconds` | about 86400 minus the time since the signer was issued; `-1` means no signer is loaded |

Every response writes one log line per certificate ID. The `clientId` comes from the MDC, so the line is prefixed with `[FEDERATOR_ENV]` for the token above. A request that cannot be parsed (D, E) writes one line with `-` placeholders:

```
ocsp_response serial=0567a4f9... issuerKeyHash=797bed38... certStatus=unknown responseStatus=successful latencyMs=4 requesterIp=127.0.0.1
```

---

## Load test

`load-test/ocsp.js` is a [k6](https://k6.io) script that sends the same DER request at a constant rate. Run it after scenario A, while `ocsp-test.pem` is still `good`. The request is built without a nonce, so the same body can be replayed:

```bash
openssl ocsp -issuer ca.pem -cert ocsp-test.pem -reqout load-test/req.der
k6 run -e TOKEN="$TOKEN" -e INSECURE=true -e RATE=100 -e DURATION=1m load-test/ocsp.js
```

The run fails if p99 latency exceeds `P99_MS` (default 100 ms), or if any request fails or is not `successful`. The token must stay valid for the whole run, so get a fresh one first. See the header of the script for every option.

---

## Automated tests

Run all OCSP tests with one command. With Docker running, the integration test also starts its own Vault through Testcontainers:

```bash
./mvnw test -Dtest='Ocsp*,VaultCertificateStatusProviderTest'
```

| Class | Covers |
|---|---|
| `OcspResponderServiceImplTest` | request parsing, certificate statuses, nonce, `tryLater` and `malformedRequest` |
| `OcspSignerServiceImplTest` | signer issuance through the default role, validation (issuer, validity, key), renewal at 70% of lifetime |
| `VaultCertificateStatusProviderTest` | reading `pki-int/cert/:serial`, serial format, missing certificate → `unknown` |
| `OcspControllerTest` | response headers, `Content-Type`, `Cache-Control` |
| `OcspControllerSecurityTest` | `@PreAuthorize` with the `management-node-access` role |
| `OcspPropertiesTest` | `validity` must be > 0 and ≤ `PT10M`; signer `ttl` at least four times `refresh-interval` |
| `OcspVaultIntegrationTest` | the full flow against a real Vault; skipped without Docker |

---

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `curl` prints nothing and `resp.der` is not created | a client certificate file is missing and `-s` hides the error | use `-sS`, check `echo $?` and the file paths; run from the repository root |
| `401` | the token is empty or expired | get a new token; `echo ${TOKEN:0:20}` should start with `eyJ` |
| `403` with a JSON body | the token lacks `management-node-access` | assign the role to the federator client's service account in Keycloak, then get a new token |
| Keycloak returns `invalid_client` or `unauthorized_client` | the client's authentication type does not match the request | send `client_secret` only to a client with client authentication by secret; a `client-x509` client needs a certificate whose subject DN matches its `x509.subjectdn` |
| `tryLater` in A–C | no signer issued, or Vault is sealed | look for `Failed to issue OCSP signer` in the log; check `vault status` and that the AppRole can sign with `default-role` |
| `Cannot login using AppRole: permission denied` in the log | 5 failed logins locked the AppRole | wait 15 minutes or lift the lock, as described in [Vault Access](VAULT_ACCESS.md#authentication) |
| `Nonce Verify error` | `verify` was run with `-cert` or `-serial` | verify with `-reqin req.der` only |
| `Response Verify Failure` with `missing ocspsigning usage` | the response was verified with `-CAfile` only | verify with `-VAfile signer.pem`, as `verify` does |
| `signer.pem` verification fails | `ca-chain.pem` is not the `pki-int` chain | fetch it again with `vault read -field=ca_chain pki-int/cert/ca_chain` |
| `curl: (35)` or `(56)` | the server rejected the client certificate during mTLS | check that `client.crt` is signed by a CA in `truststore.jks` |
| `-subj` becomes `C:/Program Files/Git/CN=...` | Git Bash path conversion | `export MSYS_NO_PATHCONV=1` |
| `unknown` for a certificate issued by Vault | the certificate was issued by a different CA or PKI mount | compare `Issuer Key Hash` in the response with the subject key identifier of the `pki-int` CA |

---

## Clean up

```bash
rm -f req.der resp.der big.der bad.der garbage.bin ocsp-test.pem ca.pem ca-chain.pem signer.pem client-test.p12 load-test/req.der
```

The signer lives only in the application's memory, so nothing about it needs cleaning up in Vault.
