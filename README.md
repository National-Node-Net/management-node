# README

**Repository:** `management-node`  
**Description:** `Provides APIs to be accessed by Consumer and Producer Federators for the purpose of dynamic configuration management `  
**SPDX-License-Identifier:** `Apache-2.0 AND OGL-UK-3.0 `  

---

## Overview

The Management Node is a Spring Boot application that provides REST APIs for federator configuration management and certificate lifecycle operations (key generation, CSR signing, and bootstrap onboarding). It implements secure communication using Mutual TLS (mTLS) and zero-trust authentication via Keycloak.

---

## Database Schema

For a full description of the database tables, relationships, and constraints, see the Database Schema documentation: [docs/DATABASE_SCHEMA.md](docs/DATABASE_SCHEMA.md).

---

## Prerequisites
- Java 21
- Maven 3.9+
- Docker and Docker Compose
- OpenSSL (for certificate generation)
- Keycloak (for authentication and authorization)
- The below assumes your running in Linux - bash, it has been tested under WSL2.
---

## Quick Start

> **Note:** see [lower](#prerequisites-setup) for setting up prerequisites for local deployment certs, keycloak etc.

### Run the Spring Boot application

This project is a Spring Boot application. You can run it by supplying configuration via either:
- A default application.yml file, or
- A profile-specific file application-{profile}.yml and passing the profile argument at startup.

Quick options:

1. Provide a default config:
   - Modify src/main/resources/application.yml with your local settings (SSL keystore/truststore, Keycloak client, DB, etc.). See the Configuration and Certificate Setup sections below.
   - Run: (change to suit your local if different)
      ```
      export POSTGRES_PASSWORD=keycloak_db_user_password
      export CERTPASSWORD=changeit
      ```
     ```bash
     mvn spring-boot:run
     ```
     or:
     ```bash
     java -jar target/management-node-1.0.1.jar
     ```

2. Use a profile-specific config:
   - Create src/main/resources/application-local.yml (replace "local" with your profile name) with your settings.
   - Run with the profile:
     ```bash
     mvn spring-boot:run -Dspring-boot.run.profiles=local
     ```
     or:
     ```bash
     java -jar target/management-node-1.0.1.jar --spring.profiles.active=local
     ```
   - You can also set the environment variable:
     ```bash
     export SPRING_PROFILES_ACTIVE=local
     ```

Notes:
- Spring Boot will load application.yml and then override with application-{profile}.yml if a profile is active.
- You may also point to an external YAML using:
  ```bash
  java -jar target/management-node-0.0.1.jar --spring.config.location=/path/to/your.yml
  ```
- The application serves HTTPS on port 8090 by default (see server.ssl in configuration).



# Prerequisites setup
## Certificate Setup

The Management Node Module implements a zero-trust security architecture using Mutual TLS (MTLS) for secure communication between all components. This section explains why certificates are needed, how to generate them, and where they are used in the system.

> **Note:** For detailed instructions on configuring MTLS for both Keycloak and the Management Node, see the [MTLS Configuration Guide](docs/MTLS_CONFIGURATION.md).

### Why Certificates Are Needed

1. **Zero-Trust Security Model**: The system follows a zero-trust approach where all communications must be authenticated and encrypted, regardless of whether they occur inside or outside the network perimeter.

2. **Mutual TLS (MTLS)**: Unlike standard TLS where only the server authenticates itself to the client, MTLS requires both parties to authenticate each other using X.509 certificates.

3. **Service-to-Service Authentication**: Certificates provide a secure way for services to verify each other's identity without relying on passwords or API keys.

### Certificate Types and Their Purpose

The system requires several certificate files:

1. **Private Key (`localhost.key`)**:
   - The private key used to sign and decrypt data
   - Must be kept secure and never shared
   - Used by both Keycloak and the Management Node

2. **Certificate (`localhost.crt`)**:
   - The public certificate containing the public key
   - Shared with other services to verify the identity
   - Used in both server and client authentication

3. **PKCS12 Keystore (`localhost.p12`)**:
   - A container format that stores the private key and certificate
   - Used primarily for client authentication
   - Imported by Keycloak for client certificate validation

4. **Java Keystore (`keystore.jks`)**:
   - Java-specific format for storing the server's private key and certificate
   - Used by both Keycloak and the Management Node for their TLS endpoints

5. **Java Truststore (`truststore.jks`)**:
   - Contains certificates that the server trusts
   - Used to validate client certificates during MTLS

### Step-by-Step Certificate Generation

#### Note : Updating existing certs
To update existing certs see **./scripts/cert-readme.md** and follow the notes.
TL;DR - download certs.zip from s3 copy the scripts to the ./certs unzipped folder and run (dont make up a password as the apps are already expecting a value)
```
cd ./certs
./generate-certs.sh --cert client-org2 --password ****** --rootpasswdfile client-rootCA.passwd --extfile localhost.ext

# new certs generated
ls ./client-org2 
client-org2.crt  client-org2.csr  client-org2.key  client-org2-keystore.jks  client-org2.p12  client-org2.srl  client.p12

# patch the application (you will only need to patch the management node 1 time - for subsequent runs say no)
./update-k8s-secrets.sh --certname client-org2 --namespace ia-federation-org2 --certdir ./client-org2 --federation-deployment federator-client-org2-client --patch-management-node false

```

### Certificate Generation from start
For development purposes, follow these steps to generate certificates for mTLS. All passwords used are `changeit`. When generating these certficates, for the `Country Name`, you can use the value of 'UK'. All remaining certificate fields can be left to their default values.

move to the docker folder
```bash
cd docker
```

1. **Generate a Root CA certificate**:
   ```bash
   openssl req -x509 -sha256 -days 3650 -newkey rsa:4096 -keyout rootCA.key -out rootCA.crt
   ```
   This creates a Root Certificate Authority (CA) that will be used to sign other certificates. The certificate is valid for 10 years (3650 days).

2. **Generate a host certificate**:
   ```bash
   openssl req -new -newkey rsa:4096 -keyout localhost.key -out localhost.csr -nodes
   ```
   This creates a private key and certificate signing request (CSR) for the host.

3. **Sign the host certificate with the Root CA**:

   Create a file called `localhost.ext` file should contain:
   ```
   authorityKeyIdentifier=keyid,issuer
   basicConstraints=CA:FALSE
   subjectAltName = @alt_names
   [alt_names]
   DNS.1 = localhost
   DNS.2 = keycloak
   ```

   ```bash
   openssl x509 -req -CA rootCA.crt -CAkey rootCA.key -in localhost.csr -out localhost.crt -days 365 -CAcreateserial -extfile localhost.ext
   ```
   This signs the host CSR with the Root CA, creating a certificate valid for 365 days.


   This configuration specifies that the certificate is valid for both `localhost` and `keycloak` hostnames.

4. **Create a PKCS12 keystore for the server**:
   ```bash
   openssl pkcs12 -export -out localhost.p12 -name "localhost" -inkey localhost.key -in localhost.crt
   ```
   This bundles the host certificate and private key into a PKCS12 format.

5. **Create a PEM file for Linux keystore**:
   ```bash
   openssl pkcs12 -in localhost.p12 -clcerts -nokeys -out localhost.pem
   ```
   This extracts the certificate (without the private key) in PEM format.

6. **Add the Root CA to the Trust Store**:
   ```bash
   keytool -importcert -noprompt -file rootCA.crt -alias clientca -keystore localhost.p12 -storetype PKCS12 -storepass changeit
   ```
   This adds the Root CA to the trust store so that clients signed by this CA will be trusted.

7. **Generate a client certificate**:
   ```bash
   openssl req -new -newkey rsa:4096 -nodes -keyout client.key -out client.csr
   ```
   This creates a private key and CSR for the client.

8. **Sign the client certificate with the Root CA**:
   ```bash
   openssl x509 -req -CA rootCA.crt -CAkey rootCA.key -in client.csr -out client.crt -days 365 -CAcreateserial
   ```
   This signs the client CSR with the Root CA, creating a certificate valid for 365 days.

9. **Create a PKCS12 keystore for the client**:
   ```bash
   openssl pkcs12 -export -out client.p12 -name "client" -inkey client.key -in client.crt
   ```
   This bundles the client certificate and private key into a PKCS12 format for use in browsers or client applications.

10. **Create a Java keystore using keytool** (PKCS12 format, compatible with modern Java):
    ```bash
    keytool -importkeystore -destkeystore keystore.jks -deststoretype PKCS12 -srckeystore localhost.p12 -srcstoretype PKCS12 -alias "localhost"
    ```
    This converts the PKCS12 keystore. Note: Despite the `.jks` extension, modern keytool creates PKCS12 format by default, which is more secure and standard.

11. **Create a Java truststore using keytool** (PKCS12 format):
    ```bash
    keytool -import -trustcacerts -noprompt -alias ca -ext san=dns:localhost,ip:127.0.0.1 -file rootCA.crt -keystore truststore.jks -storetype PKCS12
    ```
    This creates a truststore containing the Root CA certificate in PKCS12 format, which will be used to validate client certificates.

12. **Verify the truststore** (optional but recommended):
    ```bash
    keytool -list -keystore truststore.jks -storetype PKCS12 -storepass changeit
    ```
    This verifies that the Root CA is properly imported into the truststore.

### Certificate Placement and Configuration

After generating the certificates, ensure they are readable by Docker containers (Keycloak runs as a non-root user):

```bash
chmod 644 localhost.key localhost.crt localhost.p12 keystore.jks truststore.jks rootCA.crt rootCA.key
```

Then copy them to the management-node root directory:
```bash
cp keystore.jks ../keystore.jks
cp truststore.jks ../truststore.jks
cp client.crt ../client.crt
cp client.key ../client.key
```

This copies the necessary files to the management-node root directory:
- `keystore.jks` - Used by the Management Node application for its SSL server configuration
- `truststore.jks` - Used by the Management Node to validate client certificates
- `client.crt` and `client.key` - Used for testing API endpoints with mTLS authentication

1. **For Keycloak**:
   - All the certificate files should now be in the `docker` directory
   - The docker-compose.yml maps these files into the Keycloak container:
     ```yaml
     volumes:
       - ./localhost.p12:/keystores/localhost.p12
       - ./localhost.crt:/cert/localhost.crt
       - ./localhost.key:/key/localhost.key
       - ./keystore.jks:/cert/keystore.jks
       - ./truststore.jks:/cert/truststore.jks
     ```
   - Keycloak uses these certificates for:
     - Securing its HTTPS endpoint (port 8443)
     - Validating client certificates for MTLS

2. **For Management Node**:
   - The application.yml references the certificate files:
     ```yaml
     server:
       ssl:
         key-store: /path/to/keystore.jks
         key-store-password: changeit
         trust-store: /path/to/truststore.jks
         trust-store-password: changeit
     ```
   - When running in Docker, the Dockerfile copies these files:
     ```dockerfile
     COPY docker/keystore.jks /app/docker/keystore.jks
     COPY docker/truststore.jks /app/docker/truststore.jks
     ```

3. **For Client Applications**:
   - Client applications connecting to the Management Node need:
     - The client certificate and private key for authentication
     - The server's certificate in their truststore to validate the server

### Certificate Password Management

All certificates use the password "changeit" for development. These passwords are configured in the `.env` file:

```
SERVER_SSL_KEY_STORE_PASSWORD=changeit
SERVER_SSL_TRUST_STORE_PASSWORD=changeit
KC_HTTPS_KEY_STORE_PASSWORD=changeit
KC_HTTPS_TRUST_STORE_PASSWORD=changeit
KC_SPI_TRUSTSTORE_FILE_PASSWORD=changeit
```

For production environments, use strong, unique passwords and secure storage solutions for managing these credentials.


### Setting up Keycloak with Docker Compose

#### Configuration

For Docker Compose to run successfully, you need to create a `.env` file in the `docker/keycloak` directory with the following settings:

```
POSTGRES_DB=keycloak_db
POSTGRES_USER=keycloak_db_user
POSTGRES_PASSWORD=keycloak_db_user_password
KEYCLOAK_ADMIN=admin
KEYCLOAK_ADMIN_PASSWORD=password
KC_HOSTNAME_STRICT_BACKCHANNEL=false
SERVER_SSL_KEY_STORE_PASSWORD=changeit
SERVER_SSL_TRUST_STORE_PASSWORD=changeit
KC_HTTPS_KEY_STORE_PASSWORD=changeit
KC_HTTPS_TRUST_STORE_PASSWORD=changeit
KC_SPI_TRUSTSTORE_FILE_PASSWORD=changeit
KC_HOSTNAME=keycloak
KC_HOSTNAME_PORT=8080
KC_HTTP_ENABLED=false
KC_HOSTNAME_STRICT_HTTPS=false
KC_HEALTH_ENABLED=true
KC_DB=postgres
KC_HTTPS_CLIENT_AUTH=required
KC_HTTPS_ENABLED=true
KC_HTTPS_PORT=8443
KC_LOG_LEVEL=INFO
```

This file contains essential environment variables for both PostgreSQL and Keycloak configuration. You can modify these values as needed for your environment, but make sure to create this file before running Docker Compose.

The application uses Keycloak for authentication and authorization. Follow these steps to set up Keycloak using Docker Compose:

1. Navigate to the docker directory:
   ```bash
   cd docker
   ```

2. Make sure you have the required certificates in the `docker` directory: see lower for local certificate setup
   - `keystore.jks` - Java keystore containing the server certificate
   - `truststore.jks` - Java truststore containing trusted certificates
   - `localhost.p12` - PKCS12 keystore for client authentication
   - `localhost.crt` - Certificate file
   - `localhost.key` - Private key file

   If you need to generate these files for development, see the [Certificate Setup](#certificate-setup) section.

3. Start Keycloak and PostgreSQL using Docker Compose:
   ```bash
   docker compose -f keycloak/docker-compose.yml up -d
   ```

4. Verify that Keycloak is running:
   ```bash
   curl -k https://localhost:8443/realms/master --cert client.crt --key client.key
   ```
   Note: Keycloak takes about 30 seconds before its ready and the client certificate files (client.crt and client.key) must be in your current directory or provide the full path. If you haven't generated these yet, see the [Certificate Setup](#certificate-setup) section.

5. Access the Keycloak admin console at https://localhost:8443/admin with the following credentials:
   - Username: `admin`
   - Password: `password`

   You must import your `client.p12` certificate into your browser before accessing the admin console, otherwise the request will be rejected by mTLS.

   **Chrome:**
   1. Navigate to `chrome://certificate-manager/clientcerts/platformclientcerts`
   2. Click **Import** and select the `client.p12` file from the `docker/` directory
   3. When prompted for a password, enter `changeit`





## Keycloak Realm Setup

After starting Keycloak, you need to set up a realm for the Management Node. You can either import the pre-configured realm or create it manually. To access the administrative interface at https://localhost:8443/admin.

### Option 1: Import the Realm Configuration (Recommended)

1. Log in to the Keycloak admin console at https://localhost:8443/admin
2. Click on the dropdown menu in the top-left corner (it may show "master" if you haven't created any realms yet)
3. Click on "Manage realms"
4. Click on "Create Realm" or "Add realm" button
5. Click on the "Browse" or "Select file" button
6. Navigate to and select the `docker/keycloak/management-node.json` file from your project directory
7. Click "Create" or "Import"
8. After the import is complete, verify that the `mng-node` realm has been created with all the necessary configurations
9. Note the client secret for the `management-node` client from the Credentials tab (Clients → management-node → Credentials) click regenerate, view it and do ```export KEYCLOAK_CLIENT_SECRET=*************```

### Option 2: Manual Configuration

If you prefer to set up the realm manually (updated for Keycloak 26.x):

1. Log in to the Keycloak admin console at https://localhost:8443/admin (you must first import your `client.p12` certificate into your browser)

2. Create a new realm named `mng-node` by clicking the dropdown in the top-left and selecting "Create Realm"

3. Create the **management-node** client:
   - In the `mng-node` realm, navigate to **Clients** and click **Create client**

   **General Settings:**
   - Client type: `OpenID Connect`
   - Client ID: `management-node`
   - Click **Next**

   **Capability config:**
   - Client authentication: **ON** (this enables the Credentials tab)
   - Authorization: **OFF**
   - Authentication flow: Enable **Service accounts roles**
   - Click **Next**

   **Login settings:**
   - Valid redirect URIs: `https://localhost:8090/*`
   - Valid post logout redirect URIs: `+`
   - Web origins: `+`
   - Click **Save**

4. After saving, click on the **Credentials** tab to view the **Client Secret**. Copy this secret.

5. Add required roles to the client:
   - Go to **Clients** → **management-node** → **Roles** tab
   - Click **Create role** and add the following roles:
     - `access_producer_configurations` — access producer configuration endpoint
     - `access_consumer_configurations` — access consumer configuration endpoint
     - `create_keys` — generate key pairs and create CSRs
     - `sign_certificate` — sign CSRs via the PKI engine
     - `access_public_certificates` — retrieve the intermediate CA certificate
     - `request_bootstrap_certificate` — issue bootstrap certificate packages (website service account only)

   See [Authentication Requirements](docs/AUTHENTICATION_REQUIREMENTS.md) for full details on which roles map to which endpoints.

6. Assign roles to the service account:
   - Go to **Clients** → **management-node** → **Service accounts roles** tab
   - Click **Assign role**
   - Filter by **Filter by clients** and select **management-node**
   - Check the roles needed for this client:
     - `access_producer_configurations`
     - `access_consumer_configurations`
     - `create_keys`
     - `sign_certificate`
     - `access_public_certificates`
     - `request_bootstrap_certificate`
   - Click **Assign**

   > **Note:** For local development, assign all 6 roles if you will be testing all endpoints with the single `management-node` client credentials. In production, roles should be split across separate clients (e.g. `request_bootstrap_certificate` only for the website/onboarding service).

7. Create `src/main/resources/application-local.yml` with all local development overrides:
   ```yaml
   spring:
     cloud:
       vault:
         token: not_setup # replace with Vault root token if using certificate endpoints
     security:
       oauth2:
         resourceserver:
           jwt:
             audiences: account
           opaquetoken:
             client-secret: <your_client_secret>
     datasource:
       password: keycloak_db_user_password

   server:
     ssl:
       key-store-password: changeit
       trust-store-password: changeit

   application:
     client:
       key-store-password: changeit
       keyStoreType: PKCS12
   ```

   > **Note:** The `audiences` is set to `account` because Keycloak includes `account` as the default audience in service account tokens. Without this override, JWT validation will reject tokens with an audience mismatch. In production, configure a client scope audience mapper in Keycloak to use a custom audience instead.

### Testing mTLS connectivity:

Once Keycloak is running and configured, you can test mTLS connectivity using the command below. Replace `YOUR_CLIENT_SECRET` with the actual client secret obtained from the Keycloak Credentials tab (step 4 in the manual configuration above):

```bash
export KEYCLOAK_CLIENT_SECRET=`YOUR_CLIENT_SECRET`
cd docker # or where your certificates are stored
```

```bash
curl -k --location 'https://localhost:8443/realms/mng-node/protocol/openid-connect/token' \
  --cert client.crt --key client.key \
  --header 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'client_id=management-node' \
  --data-urlencode "client_secret=${KEYCLOAK_CLIENT_SECRET}" \
  --data-urlencode 'grant_type=client_credentials'
```

**Note:** The `client_secret` parameter is required for confidential clients. Make sure to:
1. Copy the client secret from Keycloak admin console: **Clients** → **management-node** → **Credentials** tab
2. Replace `YOUR_CLIENT_SECRET` in the command above with your actual client secret
3. The `-k` flag is used to allow insecure connections (self-signed certificates) for development

If successful, you will receive a JSON response containing an `access_token` with the assigned roles in the `resource_access.management-node.roles` claim. This confirms that:
- ✅ mTLS authentication is working (client certificates validated)
- ✅ Client credentials are correct
- ✅ Keycloak is properly configured
- ✅ Service account has the required roles assigned

## Vault Setup (Optional — required for Certificate Manager)

This section is only needed if you plan to use the certificate endpoints (`/api/v1/certificate/*`) or run the federator-certificate-manager. If you only need the configuration endpoints, skip to [Building and Running with Maven](#building-and-running-with-maven).

### Setting up Vault with Docker Compose

1. Create a directory called `config` in the `docker/vault` directory

```sh
mkdir docker/vault/config
```

2. Create a file called `vault.hcl` in the `docker/vault/config` directory with the following content:

```
ui = true

listener "tcp" {
  address     = "0.0.0.0:8200"
  tls_disable = 1
}

storage "file" {
  path = "/vault/file"
}

# Optional but helpful: disables mlock warnings in containers
disable_mlock = true
```

3. Start vault using Docker compose:

```sh
docker compose -f docker/vault/docker-compose.yaml up -d
```

4. Verify that vault is running

```sh
docker exec vault vault status -format=json
```

5. Initialise vault & generate unseal keys and root token:

```sh
# copy the Keys and root token to somewhere safe
docker exec vault vault operator init -key-shares=1 -key-threshold=1 -format=json
```

> **Note:** A single key share is used here for convenience. In production, use multiple key shares (e.g. `-key-shares=5 -key-threshold=3`) to distribute unseal keys across different operators via [Shamir's secret sharing](https://developer.hashicorp.com/vault/docs/concepts/seal).

6. Unseal vault using the unseal key from the previous step:

```sh
docker exec vault vault operator unseal <unseal_key>
```

> **Note:** Vault seals itself whenever the container is stopped or restarted. You will need to run the unseal command again each time you bring the container back up. The init and PKI setup steps do not need to be repeated — only the unseal.

You can then access vault using the Web UI and the root token at [http://localhost:8200](http://localhost:8200). Add the Vault root token to your `application-local.yml`:

   ```yaml
   spring:
     cloud:
       vault:
         token: <your_vault_root_token>
   ```

### Setting up the Vault PKI Secrets Engine

The Management Node uses Vault's PKI secrets engine to sign certificate requests. After initialising and unsealing Vault, you need to enable the PKI engine, import the root CA, and create a signing role.

All commands below assume you are using the root token for authentication. Replace `<root_token>` with the token from the init step above.

7. Set the Vault address and token for the CLI:

```sh
export VAULT_ADDR=http://localhost:8200
export VAULT_TOKEN=<root_token>
```

8. Enable the PKI secrets engine at the `pki-int` mount path. This path must match the `application.vault.pki-mount` property in `application.yml` (default: `pki-int`):

```sh
docker exec -e VAULT_TOKEN=$VAULT_TOKEN vault vault secrets enable -path=pki-int pki
```

9. Set the maximum TTL for the PKI engine. This controls how long certificates issued by this CA can be valid:

```sh
docker exec -e VAULT_TOKEN=$VAULT_TOKEN vault vault secrets tune -max-lease-ttl=87600h pki-int
```

10. Import the existing root CA into Vault's PKI engine. This reuses the same root CA generated during [Certificate Setup](#certificate-setup), so certificates signed by Vault are trusted by the same truststore used for mTLS:

```sh
docker exec -e VAULT_TOKEN=$VAULT_TOKEN vault vault write pki-int/config/ca \
  pem_bundle="$(openssl rsa -in docker/rootCA.key -passin pass:changeit 2>/dev/null && cat docker/rootCA.crt)"
```

11. Configure the PKI issuing certificate and CRL distribution URLs. These are embedded in certificates issued by the CA:

```sh
docker exec -e VAULT_TOKEN=$VAULT_TOKEN vault vault write pki-int/config/urls \
  issuing_certificates="http://localhost:8200/v1/pki-int/ca" \
  crl_distribution_points="http://localhost:8200/v1/pki-int/crl"
```

12. Create a signing role. The role name must match `application.vault.default-role` in `application.yml` (default: `default-role`). This role defines what the CA is allowed to sign:

```sh
docker exec -e VAULT_TOKEN=$VAULT_TOKEN vault vault write pki-int/roles/default-role \
  allow_any_name=true \
  allow_subdomains=true \
  max_ttl=8760h \
  key_type=any \
  allow_ip_sans=true \
  allow_glob_domains=true \
  server_flag=true \
  client_flag=true \
  allowed_other_sans="1.3.6.1.4.1.32473.1.1;utf8:*"
```

- `allow_any_name=true` permits signing CSRs with any common name — appropriate for development. In production, restrict this to specific domains.
- `key_type=any` allows both RSA and EC keys.
- `allowed_other_sans` whitelists the bootstrap OID so the bootstrap flow can embed it in signed certificates. This matches the default `application.bootstrap.oid` value — if you override `BOOTSTRAP_OID`, update this Vault role to match.

13. Verify the PKI engine is working by reading back the CA certificate:

```sh
docker exec -e VAULT_TOKEN=$VAULT_TOKEN vault vault read pki-int/cert/ca
```

You should see the root CA certificate in PEM format.

14. Test that the PKI engine can sign a certificate:

```sh
docker exec -e VAULT_TOKEN=$VAULT_TOKEN vault vault write -format=json pki-int/issue/default-role \
  common_name="test.example.com" \
  ttl=1h | jq -r '.data.certificate'
```

You should see a signed PEM certificate. This confirms the Vault PKI engine is working.

14. To verify the full integration with the Management Node, restart the app (with the real Vault token in `application-local.yml`) and test the certificate endpoints:

```bash
# Get a token
TOKEN=$(curl -k https://localhost:8443/realms/mng-node/protocol/openid-connect/token \
  --cert client.crt --key client.key \
  --data-urlencode 'grant_type=client_credentials' \
  --data-urlencode 'client_id=management-node' \
  --data-urlencode "client_secret=${KEYCLOAK_CLIENT_SECRET}" \
  -s | jq -r '.access_token')

# Generate a key pair via the Management Node
curl -k https://localhost:8090/api/v1/certificate/keyPair \
  --cert client.crt --key client.key \
  -H "Authorization: Bearer $TOKEN" | jq .
```

If this returns 200 with a JSON response containing the key pair, the Management Node is fully connected to Vault's PKI engine.

> **Note:** This setup imports the development root CA directly at the `pki-int` mount point. For production, you would typically create a separate root CA and then generate an intermediate CA signed by it. Both configurations work with the Management Node — the only difference is whether `pki-int/cert/ca_chain` returns a chain or is empty.

## Building and Running with Maven

### Building the Application

The Management Node Module uses Maven for dependency management and build automation. To build the application:

1. Ensure you have Maven 3.9+ installed:
   ```bash
   mvn --version
   ```

2. Build the application:
   ```bash
   cd management-node # change to suit, if following along do cd ../ (from the docker folder)
   mvn clean package
   ```
   This command will:
   - Clean the target directory
   - Compile the source code
   - Run the tests
   - Package the application into a JAR file

3. If you want to skip tests during the build:
   ```bash
   mvn clean package -DskipTests
   ```

### Running the Application

After building, you can run the application using one of these methods:

Note: if using `application-local.yml` (see [Keycloak Realm Setup](#keycloak-realm-setup) step 7), passwords are already configured. Otherwise, export them first:
   ```
   export POSTGRES_PASSWORD=keycloak_db_user_password
   export CERTPASSWORD=changeit
   ```
Ensure certificate files are in the management-node root directory (if not already there from certificate setup):
```sh
cp docker/keystore.jks keystore.jks
cp docker/truststore.jks truststore.jks
cp docker/client.crt client.crt
cp docker/client.key client.key
```

1. Using the Java command:
   ```bash
   java -jar target/management-node-1.0.1.jar
   ```

2. Using the Maven Spring Boot plugin (with the local profile if using `application-local.yml`):
   ```bash
   mvn spring-boot:run -Dspring-boot.run.profiles=local
   ```

The application will be available at https://localhost:8090

### Testing API Endpoints:

Once you have a valid token, you can test the protected API endpoints:

**Step 1: Get your Keycloak Client Secret**

1. Log in to Keycloak admin console at https://localhost:8443/admin
2. Navigate to: **mng-node realm** → **Clients** → **management-node** → **Credentials** tab
3. Copy the **Client Secret** value (you can regenerate if needed)
4. Export it as an environment variable:

```bash
export KEYCLOAK_CLIENT_SECRET=your_actual_client_secret_here
```

**Step 2: Get a JWT token and test the endpoints**

```bash
# Navigate to the root directory where client certificates are located
cd /path/to/management-node

# First, verify you can get a token (view the full response)
curl -k https://localhost:8443/realms/mng-node/protocol/openid-connect/token \
  --cert client.crt --key client.key \
  --data-urlencode 'grant_type=client_credentials' \
  --data-urlencode 'client_id=management-node' \
  --data-urlencode "client_secret=${KEYCLOAK_CLIENT_SECRET}" \
  -s | jq .

# Get a token and save it
TOKEN=$(curl -k https://localhost:8443/realms/mng-node/protocol/openid-connect/token \
  --cert client.crt --key client.key \
  --data-urlencode 'grant_type=client_credentials' \
  --data-urlencode 'client_id=management-node' \
  --data-urlencode "client_secret=${KEYCLOAK_CLIENT_SECRET}" \
  -s | jq -r '.access_token')

# Verify the token was retrieved successfully
echo "Token (first 50 chars): ${TOKEN:0:50}..."

# If TOKEN is "null", check that KEYCLOAK_CLIENT_SECRET is set correctly

# Test the producer endpoint
curl -k https://localhost:8090/api/v1/configuration/producer \
  --cert client.crt --key client.key \
  -H "Authorization: Bearer $TOKEN" | jq .

# Test the consumer endpoint
curl -k https://localhost:8090/api/v1/configuration/consumer \
  --cert client.crt --key client.key \
  -H "Authorization: Bearer $TOKEN" | jq .
```

The configuration endpoints will return a **403 Forbidden** with `"No organisation certificate found"`. This is expected — the `management-node` client is not mapped to a Producer or Consumer organisation in the database. At this point the setup is confirmed working:
- ✅ mTLS authentication is working (client certificates validated)
- ✅ JWT token was issued with correct roles
- ✅ Keycloak is properly configured
- ✅ The 403 is the interceptor correctly rejecting an unmapped client

**Testing further with a federator client (optional):**

To test the full configuration response, create a Keycloak client that matches one of the seeded organisations:

1. In the Keycloak admin console, navigate to **mng-node realm** → **Clients** → **Create client**
2. Set Client ID to `FEDERATOR_ENV` (matches the sample data for Environment Agency)
3. Enable **Client authentication** and **Service accounts roles**
4. Save, then go to the **Service accounts roles** tab
5. Assign the `management-node` roles: `access_producer_configurations` and `access_consumer_configurations`
6. Copy the client secret from the **Credentials** tab

Then test with the new client:

```bash
export FEDERATOR_ENV_SECRET=<your_federator_env_secret>

# Get a token for the FEDERATOR_ENV client
TOKEN=$(curl -k https://localhost:8443/realms/mng-node/protocol/openid-connect/token \
  --cert client.crt --key client.key \
  --data-urlencode 'grant_type=client_credentials' \
  --data-urlencode 'client_id=FEDERATOR_ENV' \
  --data-urlencode "client_secret=${FEDERATOR_ENV_SECRET}" \
  -s | jq -r '.access_token')

# This should now return the configuration for the Environment Agency organisation
curl -k https://localhost:8090/api/v1/configuration/producer \
  --cert client.crt --key client.key \
  -H "Authorization: Bearer $TOKEN" | jq .
```

### Using Profile-Specific Configuration Files

Spring Boot supports profile-specific property files, which are essential for local development environments where you need to configure sensitive information like passwords and URLs without committing them to version control.

- **Security**: Keep sensitive information like passwords and API keys out of version control
- **Environment-Specific Settings**: Configure different settings for development, testing, and production
- **Local Development**: Each developer can have their own configuration without affecting others

For local development, create `src/main/resources/application-local.yml` to override secrets and passwords. See [Keycloak Realm Setup](#keycloak-realm-setup) step 7 for the full example.

Make sure to add this file to `.gitignore`:
```
src/main/resources/application-local.yml
```

Then run with the `local` profile:
```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```
Or:
```bash
java -jar target/management-node-1.0.1.jar --spring.profiles.active=local
```

## Code Coverage with JaCoCo

The project uses JaCoCo for code coverage analysis. For detailed information about the JaCoCo setup, thresholds, and recommendations, see the [JaCoCo Coverage Documentation](docs/JACOCO_COVERAGE.md).

### Running Code Coverage

To generate code coverage reports:

1. Run the Maven verify goal:
   ```bash
   mvn clean verify
   ```

2. The JaCoCo report will be generated in the `target/site/jacoco` directory.

3. Open `target/site/jacoco/index.html` in a web browser to view the detailed coverage report.

The current configuration aims for 80% code coverage across instructions, branches, lines, methods, and 50% for classes.

## Troubleshooting

### Common Issues

1. **Certificate Issues**:
   - **Error**: `SSL routines::sslv3 alert certificate unknown`
     - The server doesn't trust your client certificate
     - **Solution**: Regenerate the truststore with the current rootCA:
       ```bash
       cd docker
       mv truststore.jks truststore.jks.old
       keytool -import -trustcacerts -noprompt -alias ca -file rootCA.crt -keystore truststore.jks -storepass changeit
       # Rebuild the Docker image
       cd ..
       docker build -t management-node -f docker/Dockerfile-dev .
       ```
   - Ensure that the paths to the keystore and truststore files in application.yml are correct
   - Verify that the certificate passwords match those in the .env file
   - If certificates were regenerated, ensure the truststore contains the new rootCA

2. **Keycloak Connection Issues**:
   - **Error**: `Connection refused` when trying to reach Keycloak
     - **From Docker container**: Use `--network keycloak_keycloak_network` and connect to `keycloak:8443`
     - **From host machine**: Use `localhost:8443` or `host.docker.internal:8443`
   - **Error**: Token validation fails with 401 Unauthorized
     - Check that `KEYCLOAK_CLIENT_SECRET` environment variable is set correctly
     - Verify the token contains required roles using: `echo $TOKEN | cut -d. -f2 | base64 -d | jq .`
   - Check that Keycloak is running: `docker ps | grep keycloak`
   - Verify that the client secret matches the one in Keycloak admin console

3. **Database Connection Issues**:
   - **Error**: `Connection to localhost:5433 refused` from Docker container
     - Docker containers can't reach `localhost` on the host
     - **Solution**: Use `--network keycloak_keycloak_network` and `jdbc:postgresql://keycloak-postgres-1:5432/keycloak_db`
     - Or use `--add-host=host.docker.internal:host-gateway` and `jdbc:postgresql://host.docker.internal:5433/keycloak_db`
   - Ensure PostgreSQL is running: `docker ps | grep postgres`
   - Check the database credentials match those in the .env file
   - Verify you can connect manually: `docker exec -it keycloak-postgres-1 psql -U keycloak_db_user -d keycloak_db`

4. **Docker-Specific Issues**:
   - **Issue**: Management Node can't fetch JWKs from Keycloak (SSL trust issues between containers)
     - **Symptom**: Application starts but JWT validation fails silently
     - **Workaround**: Run the application directly using Maven instead of Docker for local development
     - **Alternative**: Use docker-compose to set up all services with proper SSL configuration
   - **Issue**: Environment variables not being passed to container
     - Ensure you use `-e` flag for each environment variable
     - Verify with: `docker exec <container_id> env | grep VARIABLE_NAME`

## Security Considerations

This setup implements a zero-trust security model with:
- MTLS for all service-to-service communication
- JWT-based authentication and authorization via Keycloak
- HTTPS for all endpoints
- Client certificate authentication

For production deployments, consider:
- Using properly signed certificates from a trusted CA
- Implementing network segmentation
- Regularly rotating secrets and certificates
- Setting up monitoring and alerting for security events
## API Documentation

The project includes interactive API documentation powered by Springdoc OpenAPI (OAS 3.1). This exposes both a human-friendly Swagger UI and machine-readable OpenAPI definitions.

How to access locally (default settings):
- Swagger UI: https://localhost:8090/swagger-ui.html
- OpenAPI JSON: https://localhost:8090/v3/api-docs


Notes
- HTTPS: The application serves over HTTPS by default (see server.ssl in application.yml). If you use development certificates, your browser may warn about trust; proceed after trusting the dev CA as described in Certificate Setup.
- Security: The security configuration explicitly permits unauthenticated access to the documentation endpoints (/v3/api-docs/**, /swagger-ui/**, /swagger-ui.html) while keeping all other endpoints protected via OAuth2 Resource Server (JWT). See src/main/java/.../config/SecurityConfig.java for details.
- Port/environment: If you run on a different port or behind a reverse proxy, adjust the base URL accordingly.

How Springdoc OpenAPI works in this project
- Auto-scanning: The springdoc-openapi-starter-webmvc-ui dependency scans Spring MVC controllers at startup and automatically builds an OpenAPI 3.1 specification from your request mappings, parameters, request/response bodies, and status codes.
- Annotations (optional but recommended):
  - @Operation(summary = "...", description = "...") adds summaries, descriptions, and operation-level metadata.
  - @Tag(name = "...") groups endpoints in the UI.
  - @Parameter, @Schema, @ApiResponse add fine-grained control over params, models, and responses.
- Security schema: Because this app is an OAuth2 Resource Server (JWT), you can declare a bearerAuth security scheme to document Authorization: Bearer <token>. Example:

  @io.swagger.v3.oas.annotations.security.SecurityScheme(
      name = "bearerAuth",
      type = io.swagger.v3.oas.annotations.enums.SecuritySchemeType.HTTP,
      scheme = "bearer",
      bearerFormat = "JWT"
  )

  Then add @SecurityRequirement(name = "bearerAuth") on secured controllers or operations.
- Global metadata: You can set title, version, and contact details using @OpenAPIDefinition on a @Configuration class if desired.


## Authentication Requirements

All protected endpoints require JWT bearer tokens. Tokens must:
- Include the audience (aud) claim with value `account` (default Keycloak audience for service accounts).
- Contain a `resource_access` claim with client-specific roles under `resource_access.management-node.roles`.

**Required Client Roles:**
- `access_producer_configurations` — access `/api/v1/configuration/producer`
- `access_consumer_configurations` — access `/api/v1/configuration/consumer`
- `create_keys` — `GET /api/v1/certificate/keyPair`, `POST /api/v1/certificate/csr/create`
- `sign_certificate` — `POST /api/v1/certificate/csr/sign`
- `access_public_certificates` — `GET /api/v1/certificate/intermediate`
- `request_bootstrap_certificate` — `POST /api/v1/certificate/bootstrap`

**Token Structure Example:**
```json
{
  "aud": "account",
  "resource_access": {
    "management-node": {
      "roles": [
        "access_producer_configurations",
        "access_consumer_configurations",
        "sign_certificate",
        "access_public_certificates"
      ]
    }
  },
  "client_id": "management-node"
}
```

These roles must be:
1. Created as client roles in the Keycloak `management-node` client
2. Assigned to the appropriate service accounts

Read the full details, examples, and Keycloak mapping guidance in [Authentication Requirements](docs/AUTHENTICATION_REQUIREMENTS.md).

## Public Funding Acknowledgment
This repository has been developed with public funding as part of the National Digital Twin Programme (NDTP), a UK Government initiative. NDTP, alongside its partners, has invested in this work to advance open, secure, and reusable digital twin technologies for any organisation, whether from the public or private sector, irrespective of size.
## License
This repository contains both source code and documentation, which are covered by different licenses:
- **Code:** Developed and maintained by National Digital Twin Programme. Licensed under the Apache License 2.0.
- **Documentation:** Licensed under the Open Government Licence v3.0.
  See `LICENSE.md`, `OGL_LICENCE.md`, and `NOTICE.md` for details.
## Security and Responsible Disclosure
We take security seriously. If you believe you have found a security vulnerability in this repository, please follow our responsible disclosure process outlined in `SECURITY.md`.
## Software Bill of Materials (SBOM)
This project provides a Software Bill of Materials (SBOM) to help users and integrators understand its dependencies.
### Current SBOM
Download the [latest SBOM for this codebase](https://github.com/National-digital-twin/management-node/dependency-graph/sbom) to view the current list of components used in this repository.
## Contributing
We welcome contributions that align with the Programme’s objectives. Please read our `CONTRIBUTING.md` guidelines before submitting pull requests.
## Acknowledgements
This repository has benefited from collaboration with various organisations. For a list of acknowledgments, see `ACKNOWLEDGEMENTS.md`.
## Support and Contact
For questions or support, check our Issues or contact the NDTP team on ndtp@businessandtrade.gov.uk.

**Maintained by the National Digital Twin Programme (NDTP).**
© Crown Copyright 2025. This work has been developed by the National Digital Twin Programme and is legally attributed to the Department for Business and Trade (UK) as the governing entityright 2025. This work has been developed by the National Digital Twin Programme and is legally attributed to the Department for Business and Trade (UK) as the governing entity.
