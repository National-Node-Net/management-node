# README

**Repository:** `management-node`  
**Description:** `Provides APIs to be accessed by Consumer and Producer Federators for the purpose of dynamic configuration management `  
**SPDX-License-Identifier:** `Apache-2.0 AND OGL-UK-3.0 `

---

## Overview

The Management Node Module is a Spring Boot application that provides APIs to be accessed by Consumer and Producer Federators. It implements a secure communication architecture using Mutual TLS (MTLS) connectivity between Federator instances and itself, as well as establishing zero trust connectivity with Keycloak for authentication and authorization.   

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
Note. see lower for setting up prerequisites for local deployment certs, keycloak etc.

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
   keytool -importcert -file rootCA.crt -alias clientca -keystore localhost.p12 -storetype PKCS12 -storepass changeit
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

After generating the certificates, place them in the appropriate locations:

if you've followed the above then follow with
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

   you will need to first import your client.p12 digital certificate file into your local browser, else the request will be rejected. for chrome got to. settings - privacy and security - security - manage certificate - manage imported certificates from windows, then import and follow the wizard.




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
8. After the import is complete, verify that the `management-node` realm has been created with all the necessary configurations
9. Note the client secret for the `management-node` client from the Credentials tab (Clients → management-node → Credentials) click regenerate, view it and do ```export KEYCLOAK_CLIENTID=*************``` 

### Option 2: Manual Configuration

If you prefer to set up the realm manually (updated for Keycloak 26.x):

1. Log in to the Keycloak admin console at https://localhost:8443/admin (you must first import your `client.p12` certificate into your browser)

2. Create a new realm named `management-node` by clicking the dropdown in the top-left and selecting "Create Realm"

3. Create the **management-node** client:
   - In the `management-node` realm, navigate to **Clients** and click **Create client**
   
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
     - `access_producer_configurations`
     - `access_consumer_configurations`

6. Assign roles to the service account:
   - Go to **Clients** → **management-node** → **Service accounts roles** tab
   - Click **Assign role**
   - Filter by **Filter by clients** and select **management-node**
   - Check both roles (`access_producer_configurations` and `access_consumer_configurations`)
   - Click **Assign**

7. Update your `application.yml` with the client configuration, if needed (or do ```export KEYCLOAK_CLIENTID=*************```):
   ```yaml
   spring:
     security:
       oauth2:
         resourceserver:
           jwt:
             issuer-uri: https://localhost:8443/realms/management-node
             jwk-set-uri: https://localhost:8443/realms/management-node/protocol/openid-connect/certs
             audiences: account
           opaquetoken:
             introspection-uri: https://localhost:8443/realms/management-node/protocol/openid-connect/token/introspect
             client-secret: "client_secret=${KEYCLOAK_CLIENTID}"
             client-id: management-node
   
   application:
     client:
       key-store: keystore.jks
       key-store-password: changeit
       keyStoreType: JKS
   ```

### Testing mTLS connectivity:

Once Keycloak is running and configured, you can test mTLS connectivity using the command below. Replace `YOUR_CLIENT_SECRET` with the actual client secret obtained from the Keycloak Credentials tab (step 4 in the manual configuration above):

```bash
export KEYCLOAK_CLIENTID=`YOUR_CLIENT_SECRET`
cd docker # or where your certificates are stored
```

```bash
curl -k --location 'https://localhost:8443/realms/management-node/protocol/openid-connect/token' \
  --cert client.crt --key client.key \
  --header 'Content-Type: application/x-www-form-urlencoded' \
  --data-urlencode 'client_id=management-node' \
  --data-urlencode "client_secret=${KEYCLOAK_CLIENTID}" \
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

Note: if running with defaults export your passwords first.eg
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

2. Using the Maven Spring Boot plugin:
   ```bash
   mvn spring-boot:run
   ```

The application will be available at https://localhost:8090

### Testing API Endpoints:

Once you have a valid token, you can test the protected API endpoints:

**Step 1: Get your Keycloak Client Secret**

1. Log in to Keycloak admin console at https://localhost:8443/admin
2. Navigate to: **management-node realm** → **Clients** → **management-node** → **Credentials** tab
3. Copy the **Client Secret** value (you can regenerate if needed)
4. Export it as an environment variable:

```bash
export KEYCLOAK_CLIENTID=your_actual_client_secret_here
```

**Step 2: Get a JWT token and test the endpoints**

```bash
# Navigate to the root directory where client certificates are located
cd /path/to/management-node

# First, verify you can get a token (view the full response)
curl -k https://localhost:8443/realms/management-node/protocol/openid-connect/token \
  --cert client.crt --key client.key \
  --data-urlencode 'grant_type=client_credentials' \
  --data-urlencode 'client_id=management-node' \
  --data-urlencode "client_secret=${KEYCLOAK_CLIENTID}" \
  -s | jq .

# Get a token and save it
TOKEN=$(curl -k https://localhost:8443/realms/management-node/protocol/openid-connect/token \
  --cert client.crt --key client.key \
  --data-urlencode 'grant_type=client_credentials' \
  --data-urlencode 'client_id=management-node' \
  --data-urlencode "client_secret=${KEYCLOAK_CLIENTID}" \
  -s | jq -r '.access_token')

# Verify the token was retrieved successfully
echo "Token (first 50 chars): ${TOKEN:0:50}..."

# If TOKEN is "null", check that KEYCLOAK_CLIENTID is set correctly

# Test the producer endpoint
curl -k https://localhost:8090/api/v1/configuration/producer \
  --cert client.crt --key client.key \
  -H "Authorization: Bearer $TOKEN" | jq .

# Test the consumer endpoint
curl -k https://localhost:8090/api/v1/configuration/consumer \
  --cert client.crt --key client.key \
  -H "Authorization: Bearer $TOKEN" | jq .
```

Expected response (if no configuration data exists yet):
```json
{
  "clientId": "management-node",
  "producers": []
}
```

If successful, you will receive a JSON response containing an `access_token`. This confirms that:
- ✅ mTLS authentication is working (client certificates validated)
- ✅ Client credentials are correct
- ✅ Keycloak is properly configured

### Using Profile-Specific Configuration Files

Spring Boot supports profile-specific property files, which are essential for local development environments where you need to configure sensitive information like passwords and URLs without committing them to version control.

#### Why Use Profile-Specific Configuration?

1. **Security**: Keep sensitive information like passwords and API keys out of version control
2. **Environment-Specific Settings**: Configure different settings for development, testing, and production
3. **Local Development**: Each developer can have their own configuration without affecting others

#### Creating a Profile-Specific YAML File

1. Create a file named `application-{profile}.yml` in the `src/main/resources` directory, where `{profile}` is the name of your profile (e.g., `application-local.yml` for a "local" profile)

2. Add your environment-specific configuration to this file. For example:

   ```yaml
   spring:
     security:
       oauth2:
         resourceserver:
           opaquetoken:
             client-secret: your-client-secret-here
             client-id: ztf-client
     datasource:
       password: your-database-password-here
   
   server:
     ssl:
       key-store-password: your-keystore-password-here
       trust-store-password: your-truststore-password-here
       key-store: /path/to/your/local/keystore.jks
       trust-store: /path/to/your/local/truststore.jks
   ```

3. Make sure not to commit this file to version control by adding it to your `.gitignore` file:
   ```
   src/main/resources/application-local.yml
   ```

#### Running the Application with a Specific Profile

To run the application with your profile, use one of these methods:

1. Using the Java command with the `spring.profiles.active` parameter:
   ```bash
   java -jar target/management-node-0.0.1.jar --spring.profiles.active=local
   ```

2. Using the Maven Spring Boot plugin:
   ```bash
   mvn spring-boot:run -Dspring-boot.run.profiles=local
   ```

3. Using environment variables:
   ```bash
   export SPRING_PROFILES_ACTIVE=local
   java -jar target/management-node-0.0.1.jar
   ```

4. When running with Docker, you can pass the profile as an environment variable:
   ```bash
   docker run -p 8090:8090 -e "SPRING_PROFILES_ACTIVE=local" management-node
   ```

The application will load both the default `application.yml` and your profile-specific `application-local.yml`, with the latter overriding any duplicate properties.

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
     - Check that `KEYCLOAK_CLIENTID` environment variable is set correctly
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
- `access_producer_configurations` - Required to access `/api/v1/configuration/producer` endpoint
- `access_consumer_configurations` - Required to access `/api/v1/configuration/consumer` endpoint

**Token Structure Example:**
```json
{
  "aud": "account",
  "resource_access": {
    "management-node": {
      "roles": [
        "access_producer_configurations",
        "access_consumer_configurations"
      ]
    }
  },
  "client_id": "management-node"
}
```

These roles must be:
1. Created as client roles in the Keycloak `management-node` client
2. Assigned to the service account of the `management-node` client

Read the full details, examples, and Keycloak mapping guidance in [Authentication Requirements](docs/AUTHENTICATION_REQUIREMENTS.md).

For how authorisation decisions are sent to the PDP (OPA) - the two enforcement points, the `input` document, and where each of its fields comes from - see [Policy Enforcement](docs/POLICY_ENFORCEMENT.md).

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
© Crown Copyright 2026. This work has been developed by the National Digital Twin Programme and is legally attributed to the UK's Department for Business, Innovation, Science and Trade (BIST) as the governing entity.