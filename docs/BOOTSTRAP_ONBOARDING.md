# Bootstrap Onboarding Flow

**Repository:** `management-node`  
**Description:** `End-to-end bootstrap flow for onboarding new organisations with initial certificates`  
**SPDX-License-Identifier:** `Apache-2.0 AND OGL-UK-3.0`  

---

## Overview

Bootstrap onboarding enables new organisations to obtain their initial certificates through a website-mediated flow, without requiring pre-existing mTLS credentials for the target organisation. The organisation's administrator generates a private key and CSR locally, then submits the CSR via the website. The website backend requests a short-lived bootstrap certificate from the Management Node on behalf of the organisation, which is then automatically replaced with a full certificate by the Certificate Manager.

---

## Sequence Diagram

```mermaid
sequenceDiagram
    participant Admin as Org Administrator
    participant UI as Website UI
    participant WB as Website Backend
    participant KC as Keycloak (IdP)
    participant MN as Management Node
    participant V as Vault (KV v2)
    participant CM as Certificate Manager
    participant F as Federator

    Note over Admin: Step 1 — Generate credentials locally
    Admin->>Admin: Generate RSA private key + CSR
    Admin->>UI: Upload CSR

    Note over UI: Step 2 — Submit onboarding request
    UI->>WB: Send CSR (target org: HMRC)

    Note over WB: Step 3 — Authenticate as website backend
    WB->>KC: client_credentials grant (WEBSITE_CLIENT_ID, mTLS)
    KC-->>WB: JWT (role: request_bootstrap_certificate)

    Note over WB: Step 4 — Request bootstrap package
    WB->>MN: POST /api/v1/certificate/bootstrap
    Note over WB,MN: { organisationId: 1, csr: "<CSR PEM>" }<br/>Authorization: Bearer <JWT><br/>Organisation must already exist in Management Node.
    MN->>MN: Sign CSR (short TTL, OID marker in otherName SAN)
    MN-->>WB: ZIP (certificate.pem + ca-chain.pem)
    WB-->>Admin: Return ZIP

    Note over Admin: Step 5 — Deploy to organisation's Vault
    Admin->>V: Store private key, certificate, ca-chain
    Note over V: Organisation's own Vault instance.<br/>Certificate contains bootstrap OID marker.<br/>Secrets must be stored at the paths expected<br/>by the Certificate Manager (see its vault-integration docs).

    Note over CM: Step 6 — Automatic renewal
    CM->>V: Read certificate (next renewal cycle)
    CM->>CM: Detect bootstrap OID in otherName SAN
    CM->>CM: Generate new RSA key pair + CSR
    CM->>KC: client_credentials grant (HMRC_CLIENT_ID, mTLS)
    KC-->>CM: JWT (roles: sign_certificate, access_public_certificates)
    CM->>MN: POST /api/v1/certificate/csr/sign (CSR, standard TTL)
    MN-->>CM: Signed certificate (no OID marker)
    CM->>V: Persist new key pair + signed certificate
    CM->>CM: Generate PKCS#12 keystore + truststore
    CM-->>F: Write keystores + credentials to shared filesystem

    Note over F: Step 7 — Operational
    F->>MN: GET /api/v1/configuration/consumer
    MN-->>F: Configuration response (200 OK)
```

---

## Bootstrap Certificate Properties

| Property | Value | Description |
|----------|-------|-------------|
| TTL | `2h` — `application.bootstrap.ttl`, env `BOOTSTRAP_CERT_TTL` | Limits the window of exposure before automatic renewal |
| OID marker | `1.3.6.1.4.1.32473.1.1` — `application.bootstrap.oid`, env `BOOTSTRAP_OID` | Embedded in an `otherName` SAN entry, used by Certificate Manager to detect bootstrap certificates. Overriding it means updating the Vault role's `allowed_other_sans` to match (see README, PKI setup) |
| Certificate type | `BOOTSTRAP` | Recorded in `organisation_certificate.type`; changes to `AUTOMATED` after renewal |
| `other_sans` format | `<OID>;UTF8:bootstrap` | Vault PKI parameter used when signing the bootstrap CSR |

The OID `1.3.6.1.4.1.32473.1.1` uses the reserved Private Enterprise Number 32473 (RFC 5612, for documentation use). In production, NDTP would register a PEN with IANA and replace this value.

---

## Keycloak Clients

| Client ID | Purpose | Authentication | Roles |
|-----------|---------|----------------|-------|
| `WEBSITE_CLIENT_ID` | Website backend that initiates bootstrap | X.509 client certificate (`client_credentials` grant) | `request_bootstrap_certificate` |
| `HMRC_CLIENT_ID` | Target organisation's federator | X.509 client certificate (`client_credentials` grant) | `sign_certificate`, `access_public_certificates`, `access_consumer_configurations` |

---

## Security Considerations

- The **website backend** is the only actor with the `request_bootstrap_certificate` role. Individual organisations cannot self-bootstrap.
- The bootstrap certificate's **short TTL** limits the window during which the initial certificate is valid.
- The **OID marker** ensures Certificate Manager can distinguish bootstrap certificates from production certificates and trigger immediate renewal. Without it, the certificate would still be renewed but only when it approaches expiry based on the configured renewal threshold.
- All certificate endpoints (`/api/v1/certificate/**`) are **excluded from the CertificateValidationInterceptor**. These endpoints are called by service accounts (e.g. the website backend, Certificate Manager) that may not have an associated organisation certificate record, so organisation certificate validation is not applicable. Access is still secured by JWT authentication and role-based authorization (`@PreAuthorize`).
- After renewal, the replacement certificate is a standard certificate with no OID marker and a normal TTL.
