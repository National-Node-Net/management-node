# Certificate Script Runbook

This directory contains two helper scripts for managing client certificate material and pushing the generated files into Kubernetes secrets.

The scripts solve two related problems:

- `generate-certs.sh` creates a signed client certificate bundle from the local root CA.
- `update-k8s-secrets.sh` updates existing Kubernetes secrets with the generated certificate files and restarts the workloads that use them.

Together, they provide a repeatable flow for rotating or creating federation client certificates without manually running each OpenSSL, keytool, and kubectl command.

## Setup First

Before running these scripts, a user needs to download `certs.zip` from S3 and extract it so the required CA files and certificate artifacts are available locally. Ask Nikan for the S3 bucket location.

The best way to run this is to copy `generate-certs.sh` and `update-k8s-secrets.sh` into the extracted `certs.zip` folder, then run the scripts from there.

## Important Notes

- Use `********` anywhere this readme shows a password placeholder. Talk to Nikan for the real password.
- Treat `.key`, `.p12`, `.jks`, and password files as sensitive.
- Keep certificate names aligned with the keys already present in Kubernetes secrets.
- `update-k8s-secrets.sh` does not create new secret keys; it only updates existing ones.
- The generated files are written relative to this directory.

## Quick Start
```
cd ./certs
./generate-certs.sh --cert client-org2 --password ****** --rootpasswdfile client-rootCA.passwd --extfile localhost.ext

# new certs generated
ls ./client-org2 
client-org2.crt  client-org2.csr  client-org2.key  client-org2-keystore.jks  client-org2.p12  client-org2.srl  client.p12

# patch the application (you will only need to patch the management node 1 time - for subsequent runs say no)
./update-k8s-secrets.sh --certname client-org2 --namespace ia-federation-org2 --certdir ./client-org2 --federation-deployment federator-client-org2-client --patch-management-node false
```

## Scripts

### `generate-certs.sh`

Generates certificate artifacts for a named client.

It creates:

- a private key
- a certificate signing request
- a CA-signed certificate
- a PKCS#12 bundle
- a copy named `client.p12`
- a Java keystore, when `keytool` or Docker is available

The script signs the generated certificate with:

- `client-rootCA.crt`
- `client-rootCA.key`
- the password supplied by `--rootpasswdfile`

Example:

```sh
./generate-certs.sh \
  --yes \
  --cert client-org1 \
  --password '********' \
  --rootpasswdfile client-rootCA.passwd
```

This writes files under a directory matching the certificate name:

```text
client-org1/
  client-org1.key
  client-org1.csr
  client-org1.crt
  client-org1.srl
  client-org1.p12
  client.p12
  client-org1-keystore.jks
```

Useful options:

- `--cert <name>` sets the certificate and output directory name.
- `--password <password>` sets the PKCS#12 and JKS password.
- `--rootpasswdfile <file>` points to the root CA key password file.
- `--days <days>` sets certificate validity. Default: `365`.
- `--subject <subject>` sets the certificate subject.
- `--extfile <file>` sets the OpenSSL extension file. Default: `localhost.ext`.
- `--keytool-image <image>` sets the Docker image used if local `keytool` is unavailable. Default: `eclipse-temurin:21-jdk`.

Safety behavior:

- Requires `--yes` before overwriting generated files.
- Verifies the CA certificate, CA key, password file, and extension file exist.
- Verifies the CA private key can be read with the supplied password.
- Runs `openssl verify` and prints the generated certificate subject, issuer, and dates.

### `update-k8s-secrets.sh`

Patches existing Kubernetes secrets with the generated `.p12` and `.jks` files.

It is intended for the second half of the certificate rotation flow, after `generate-certs.sh` has produced the certificate directory.

Example:

```sh
./update-k8s-secrets.sh \
  --yes \
  --namespace ia-federation \
  --certname client-org1 \
  --certdir client-org1 \
  --federation-deployment federator-client-org1-client
```

By default, it patches:

- `secret/federation-client-p12` in the federation namespace
  - updates `data.<certname>.p12`
- `secret/federation-cert` in the federation namespace
  - updates `data.<certname>-keystore.jks`
- `secret/management-node-cert` in the management namespace
  - updates `data.<certname>-keystore.jks`

After patching the secrets, it prompts before restarting:

- `deployment/federator-client-org1-client` in the federation namespace
- `deployment/management-node-api` in the management namespace

Useful options:

- `--namespace <namespace>` sets the federation namespace. Required.
- `--management-namespace <namespace>` sets the management namespace. Default: `ia-management-node`.
- `--certname <name>` sets the certificate file prefix. Required.
- `--certdir <dir>` points to the generated certificate directory. Required.
- `--federation-deployment <name>` sets the federation deployment restarted at the end. Required.
- `--client-p12-secret <name>` overrides the PKCS#12 secret name. Default: `federation-client-p12`.
- `--federation-cert-secret <name>` overrides the federation JKS secret name. Default: `federation-cert`.
- `--management-node-cert-secret <name>` overrides the management node JKS secret name. Default: `management-node-cert`.
- `--management-node-deployment <name>` overrides the management deployment restarted at the end. Default: `management-node-api`.
- `--patch-management-node <true|false>` controls whether the management node secret and deployment are patched. Default: `true`.

Safety behavior:

- Prints the planned secret updates before changing anything.
- Without `--yes`, prints the plan and exits.
- Checks that the local `.p12` and `.jks` files exist.
- Checks that the target Kubernetes secrets already exist.
- Checks that the expected data keys already exist in those secrets.
- Patches only the targeted keys and leaves all other secret data unchanged.
- Prompts before each patch and each rollout restart.

## End-to-End Workflow

1. Confirm that `kubectl` points at the intended cluster:

```sh
kubectl config current-context
```

2. Generate or rotate the certificate files:

```sh
./generate-certs.sh \
  --cert client-org1 \
  --password '********' \
  --rootpasswdfile client-rootCA.passwd
```

3. Patch the Kubernetes secrets:

```sh
./update-k8s-secrets.sh \
  --namespace ia-federation \
  --certname client-org1 \
  --certdir client-org1 \
  --federation-deployment federator-client-org1-client
```

4. Confirm the rollout status:

```sh
kubectl rollout status deployment/federator-client-org1-client -n ia-federation
kubectl rollout status deployment/management-node-api -n ia-management-node
```

## What This Avoids

These scripts reduce the amount of manual certificate work required during client onboarding or certificate rotation.

They help avoid:

- hand-running OpenSSL commands in the wrong order
- forgetting to include the CA certificate in the PKCS#12 bundle
- manually converting PKCS#12 files into Java keystores
- accidentally replacing a whole Kubernetes secret when only one key should change
- updating secrets without restarting the workloads that read them
