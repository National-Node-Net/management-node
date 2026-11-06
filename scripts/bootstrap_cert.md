# Bootstrap Certificate Script

`bootstrap_cert.sh` issues a bootstrap certificate for an organisation from the Management Node and
packages it as a password-protected keystore and truststore, written into one JSON document,
`bootstrap.json`:

```json
{
  "createdAt": "2026-11-06T16:47:11Z",
  "keystore.p12": "<base64 PKCS12>",
  "keystore.password": "<password>",
  "truststore.p12": "<base64 PKCS12>",
  "truststore.password": "<password>",
  "updatedAt": "2026-11-06T16:47:11Z"
}
```

It runs the whole flow in one command, over mTLS with a client certificate:

| Step | Call | Role needed | Produces |
|---|---|---|---|
| 1 | `POST <idp>/realms/mng-node/protocol/openid-connect/token` | n/a | access token (`client_credentials`) |
| 2 | `GET /api/v1/certificate/keyPair` | `create_keys` | RSA 2048 key pair |
| 3 | `POST /api/v1/certificate/csr/create` | `create_keys` | CSR built from that key pair |
| 4 | `POST /api/v1/certificate/bootstrap` | `request_bootstrap_certificate` | ZIP holding `certificate.pem` and `ca-chain.pem` |

The bootstrap endpoint takes `{organisationId, csr}`, not a key pair, which is why step 3 turns the
key pair into a CSR. The ZIP from step 4 is unzipped, and its certificate and CA chain are packed
with the private key from step 2 into the two `.p12` stores.

> **Each run issues a real certificate.** It is recorded against the organisation as type
> `BOOTSTRAP`, replacing that organisation's current certificate record. Run it only when you mean to.

## Prerequisites

1. **Tools:** `curl`, `jq`, `unzip` and `openssl`, plus `keytool` (any JDK) to build the
   truststore. Without a JDK, OpenSSL 3.2 or later builds it instead.

   ```bash
   sudo apt-get install -y curl jq unzip openssl   # Debian/Ubuntu
   brew install jq                                   # macOS (the others ship with it)
   ```

2. **A client certificate and key** trusted by the environment, for example `client-org1.crt` and
   `client-org1.key`. Both the identity provider and the Management Node require mTLS. The script's
   default path points at one developer's machine, so pass your own with `--cert` and `--key`.

3. **A Keycloak client with the right roles** on `management-node`: `create_keys` and
   `request_bootstrap_certificate`. The default client is `FEDERATOR_BCC`. Without a role the script
   stops at that step and prints the `403`. If the client authenticates with a secret rather than its
   certificate, also pass `--client-secret`.

4. **The organisation's numeric id** (`organisation.id` in the database), e.g. `2`.

## Usage

Run from the repository root:

```bash
bash scripts/bootstrap_cert.sh [options]
```

`bash scripts/bootstrap_cert.sh --help` prints the same list. Every option can also be set by the
environment variable shown.

| Option | Environment variable | Default | Meaning |
|---|---|---|---|
| `--client-id <id>` | `CLIENT_ID` | `FEDERATOR_BCC` | OAuth client the token is requested for |
| `--client-secret <s>` | `CLIENT_SECRET` | none | client secret, only if the client has one |
| `--org-id <id>` | `ORGANISATION_ID` | `2` | organisation the bootstrap certificate is issued for |
| `--cert <file>` | `CLIENT_CERT` | `.../idp/certs/client-org1.crt` | client certificate for mTLS |
| `--key <file>` | `CLIENT_KEY` | `.../idp/certs/client-org1.key` | client private key for mTLS |
| `--cn <name>` | `CSR_CN` | `example.com` | CSR `commonName` |
| `--org <name>` | `CSR_O` | `Example Corp` | CSR `organization` |
| `--ou <name>` | `CSR_OU` | `IT` | CSR `organizationalUnit` |
| `--country <cc>` | `CSR_C` | `UK` | CSR `country` |
| `--email <addr>` | `CSR_EMAIL` | `admin@example.com` | CSR `email` |
| `--key-size <bits>` | `CSR_KEY_SIZE` | `2048` | CSR `keySize` |
| `--key-usage <usage>` | `CSR_KEY_USAGE` | `digitalSignature` | CSR `keyUsage` |
| `--san <dns>` | `CSR_SANS` (comma separated) | `federator.internal.company.com,federator.company.com` | CSR `dnsSans`; repeatable, and the first `--san` replaces the defaults |
| `--alias <name>` | `KEY_ALIAS` | the client id | alias of the key entry in `keystore.p12` |
| `--keystore-password <p>` | `KEYSTORE_PASSWORD` | random, 24 characters | password of `keystore.p12` |
| `--truststore-password <p>` | `TRUSTSTORE_PASSWORD` | random, 24 characters | password of `truststore.p12` |
| `--out <dir>` | `OUT_DIR` | `./bootstrap-<client>-<timestamp>` | output directory |
| `--insecure` | `INSECURE=1` | off | skip verification of the server's certificate |
| n/a | `IDP_URL` | `https://ia-idp.dev.ndtp.co.uk/realms/mng-node/protocol/openid-connect/token` | token endpoint |
| n/a | `MN_URL` | `https://ia-management-node.dev.ndtp.co.uk:443` | Management Node base URL |

## Examples

### Default run against dev

Uses `FEDERATOR_BCC`, organisation `2` and the default CSR fields:

```bash
bash scripts/bootstrap_cert.sh \
  --cert /path/to/client-org1.crt \
  --key  /path/to/client-org1.key \
  --out  ./my-bootstrap
```

Output:

```text
1/4 Requesting token for FEDERATOR_BCC ...
2/4 Creating key pair ...
3/4 Creating CSR (CN=example.com) ...
4/4 Requesting bootstrap certificate for organisation 2 ...
Building keystore.p12 and truststore.p12 ...

Done. Bootstrap certificate issued for organisation 2.
  subject : CN=example.com
  serial  : 4E0961AF0CE0D0A296D8F0BBAFE1C847989B05C1
  expires : Nov  6 17:50:18 2026 GMT
  JSON    : ./my-bootstrap/bootstrap.json  (holds the keystore and its password; keep it private)
```

### Another organisation and client

```bash
bash scripts/bootstrap_cert.sh \
  --client-id FEDERATOR_ENV \
  --org-id 1 \
  --cert /path/to/client-env.crt \
  --key  /path/to/client-env.key
```

### Your own CSR subject and SANs

The first `--san` replaces the default SANs, and each further one adds to the list:

```bash
bash scripts/bootstrap_cert.sh \
  --cert /path/to/client-org1.crt --key /path/to/client-org1.key \
  --cn federator.bcc.example \
  --org "Birmingham City Council" --ou Federation --country GB \
  --email ops@bcc.example \
  --san federator.bcc.example --san federator.internal.bcc.example
```

### Your own store passwords

Passwords are generated at random unless given. Set them when the consumer of the stores expects
known values:

```bash
bash scripts/bootstrap_cert.sh --cert client.crt --key client.key \
  --keystore-password "$KEYSTORE_PW" --truststore-password "$TRUSTSTORE_PW" --alias federator
```

Pass passwords through variables rather than literally, so they stay out of your shell history.

### Settings from environment variables

```bash
export CLIENT_CERT=/path/to/client-org1.crt
export CLIENT_KEY=/path/to/client-org1.key
ORGANISATION_ID=2 CSR_CN=federator.bcc.example bash scripts/bootstrap_cert.sh
```

### A different environment

```bash
IDP_URL=https://idp.example.org/realms/mng-node/protocol/openid-connect/token \
MN_URL=https://management-node.example.org \
bash scripts/bootstrap_cert.sh --cert client.crt --key client.key --org-id 3
```

### A client with a secret, or a server certificate that is not trusted

```bash
bash scripts/bootstrap_cert.sh --cert client.crt --key client.key --client-secret "$SECRET" --insecure
```

Use `--insecure` only against a local or test environment.

## Output

The output directory is created readable by you only:

```text
my-bootstrap/
├── bootstrap.json           the JSON document below
├── keystore.p12             private key + issued certificate + CA chain, under one alias
├── truststore.p12           the CA chain, as trusted entries ca-0, ca-1, ...
├── bootstrap_bundle.zip     the ZIP returned by the bootstrap endpoint
├── bootstrap_bundle/        the ZIP unzipped: certificate.pem, ca-chain.pem
├── ca-chain.pem             the CA chain with its line breaks restored (see below)
├── private-key.pem          from the keyPair call
├── public-key.pem
└── csr.pem                  from the csr/create call
```

### `bootstrap.json`

```json
{
  "createdAt": "2026-11-06T16:47:11Z",
  "keystore.p12": "MIIYegIBAzCCGDAGCSqG…",
  "keystore.password": "<24 random characters>",
  "truststore.p12": "MIINMgIBAzCCDNwGCSqG…",
  "truststore.password": "<24 random characters>",
  "updatedAt": "2026-11-06T16:47:11Z"
}
```

| Key | Value |
|---|---|
| `createdAt`, `updatedAt` | when the document was written, ISO 8601 UTC; equal for a fresh run |
| `keystore.p12` | base64 of `keystore.p12`, a PKCS12 file encrypted with `keystore.password` |
| `keystore.password` | the keystore's password |
| `truststore.p12` | base64 of `truststore.p12`, a PKCS12 file encrypted with `truststore.password` |
| `truststore.password` | the truststore's password |

What the two stores hold, as `keytool -list -v` reports them for the dev run:

| Store | Entries |
|---|---|
| `keystore.p12` | one `PrivateKeyEntry`, alias `federator_bcc` (keytool lower-cases aliases), chain length 3: the issued certificate, `CN=Management Node Intermediate CA`, `CN=Management Node Root CA` |
| `truststore.p12` | two `trustedCertEntry`s: `ca-0` = `CN=Management Node Intermediate CA`, `ca-1` = `CN=Management Node Root CA` |

### Reading values out of it

```bash
J=my-bootstrap/bootstrap.json

# Recreate the .p12 files from the JSON
jq -r '."keystore.p12"'   "$J" | base64 -d > keystore.p12
jq -r '."truststore.p12"' "$J" | base64 -d > truststore.p12

# Inspect them
keytool -list -v -storetype PKCS12 -keystore keystore.p12   -storepass "$(jq -r '."keystore.password"' "$J")"
keytool -list -v -storetype PKCS12 -keystore truststore.p12 -storepass "$(jq -r '."truststore.password"' "$J")"

# Get PEMs back out of the keystore
openssl pkcs12 -in keystore.p12 -nodes -passin "pass:$(jq -r '."keystore.password"' "$J")" > keystore-contents.pem
```

The keys contain dots, so in `jq` they must be quoted: `."keystore.p12"`, not `.keystore.p12`.

## Things to know

- **The certificate is short-lived.** A bootstrap certificate is valid for about two hours; the script prints its expiry, and
  `openssl x509 -in my-bootstrap/bootstrap_bundle/certificate.pem -noout -enddate` shows it again.
- **The output holds secrets.** `bootstrap.json` holds the keystore together with its password,
  which together give the private key; `private-key.pem` is the key in the clear. Keep them private
  and never commit them.
- **The server's `ca-chain.pem` is malformed.** `CertificateSigningProviderImpl.assembleBootstrapBundle`
  joins the chain with no separator, so one certificate's `END` line runs into the next one's
  `BEGIN` line and openssl cannot read the file. The script writes a repaired copy to
  `ca-chain.pem` in the output directory and builds the stores from that.
- **Some CSR fields are ignored by the server.** `email`, `keySize` and `keyUsage` are sent as
  requested, but `CreateCsrRequestDTO` does not declare them, so they do not reach the CSR. The key
  is always RSA 2048, from the keyPair call.
- **The certificate's subject may be narrower than the CSR's.** The signing role in Vault decides
  what is kept; in dev only the common name survives (`CN=example.com`).

## Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `ERROR: client certificate not readable` | wrong `--cert` or `--key` path |
| `ERROR: jq is required but not installed` | install the missing tool; see [Prerequisites](#prerequisites) |
| `ERROR: building truststore.p12 needs keytool (a JDK) or openssl 3.2+` | install a JDK, or put its `bin` on `PATH` |
| `keytool error: ... keystore password was incorrect` | the wrong password for that store; each store has its own |
| `HTTP 401` at step 1, `unauthorized_client` or `invalid_client` | the client id is wrong, or the client needs `--client-secret` |
| `HTTP 403` at step 2 or 3 | the client lacks the `create_keys` role on `management-node` in Keycloak |
| `HTTP 403` at step 4, `Access denied: insufficient permissions` | the client lacks the `request_bootstrap_certificate` role on `management-node` in Keycloak |
| `HTTP 403` at step 4, `Organisation not found: <id>` | the `--org-id` does not exist in that environment's database |
| `curl: (60) SSL certificate problem` | the server's certificate is not trusted on this machine; install its CA, or use `--insecure` against a test environment |
| `curl: (56)` or a TLS handshake failure | the client certificate is not trusted by that environment |
