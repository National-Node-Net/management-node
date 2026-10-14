# Changelog 

**Repository:** `management-node`  
**Description:** `Tracks all notable changes, version history, and roadmap toward 1.0.0 following Semantic Versioning.`  
**SPDX-License-Identifier:** OGL-UK-3.0 


All notable changes to this repository will be documented in this file.

This project follows **Semantic Versioning (SemVer)** ([semver.org](https://semver.org/)), using the format:


`[MAJOR].[MINOR].[PATCH]` 
- **MAJOR** (`X.0.0`) – Incompatible API/feature changes that break backward compatibility. 
- **MINOR** (`0.X.0`) – Backward-compatible new features, enhancements, or functionality changes. 
- **PATCH** (`0.0.X`) – Backward-compatible bug fixes, security updates, or minor corrections. 
- **Pre-release versions** – Use suffixes such as `-alpha`, `-beta`, `-rc.1` (e.g., `2.1.0-beta.1`). 
- **Build metadata** – If needed, use `+build` (e.g., `2.1.0+20250314`). 

---

## [1.2.2] - 2026-07-24

### Security

- Bumped `org.postgresql:postgresql` from 42.7.11 to 42.7.12 to resolve CVE-2026-54291.

## [1.2.1] - 2026-07-16

### Changed

- Alignment of GitHub actions to new organisation.


## [1.2.0] - 2026-03-26

### Added

- Vault PKI service with enhancements and tests
- Missing docker attributes
- Missing documentation regarding vault setup
- Organization certificates
- Trigger release workflow from main and trivy check
- Protect certificate endpoints with role-based access and add Keycloak TF roles
- Bootstrap certificate endpoint
- Bootstrap endpoint changes and interceptor exemption
- Set bootstrap event performed by to requester clientId
- Trivy vulnerability check to management-node pipeline
- Get image tag from branch name

### Fixed

- Fix to vulnerability and update to trivy scan config

### Changed

- Management Node - Resolve CVEs
- Non root access for dev container
- Replace version in GitHub workflows
- Update documentation and development setup guide

### Dependencies

- Added `org.springframework.cloud.spring-cloud-starter-vault-config` version ``
- Added `org.bouncycastle.bcpkix-jdk18on` version `1.83`
- Added `org.bouncycastle.bcprov-jdk18on` version `1.83`
- Bumped `spring-boot-starter` to version `3.5.11`
- Bumped `org.apache.commons.commons-lang3` to version `3.18.0`
- Bumped `org.springframework.security.spring-security` to version `6.5.9`
- Bumped `com.fasterxml.jackson` to version `2.21.1`

## [1.1.0] - 2026-02-20

### Added
- Support for job scheduling with `schedule_type` and `schedule_expression` fields in configurations.
- `ProductType` domain entity and expanded `Product`, `Consumer`, and `ProductConsumer` models.
- Comprehensive documentation site using MkDocs, including setup guides, architecture overview, and API documentation.
- GitHub Actions workflows for:
    - SonarCloud static code analysis and quality gate verification.
    - Automated Docker image builds and deployment to GitHub Container Registry (GHCR).
    - MkDocs documentation publishing.
    - Automated release processes and tagging.
- Keycloak realm configuration for local development and testing.

### Changed
- Refactored `KeycloakJwtAuthenticationConverter` to remove client secret dependency and improve security.
- Updated Maven workflow to include SonarCloud analysis and optimized JaCoCo reporting phases.
- Enhanced GitHub workflows with job-level permission definitions for improved security.
- Standardized pull request templates and repository metadata.
- Improved local development setup documentation and scripts.

### Removed
- `OrganisationServiceImpl` and related tests, streamlining the service layer.
- Redundant Maven settings references in CI workflows.

## [1.0.1] - 2025-10-1

### Initial release
- This is the first initial changelog entry for the management-node. It introduces the baseline feature set and establishes the changelog structure following Semantic Versioning.

### Added
- Core domain and persistence for producers and consumers (JPA entities, repositories, and services).
- REST APIs for managing producers/consumers and related configurations (v1 controllers and DTOs).
- Configuration management provider for node settings and environment-driven overrides.
- Security integration with Keycloak (realm configuration and OAuth2/OIDC resource server setup).
- TLS/Mutual‑TLS support and related documentation (see docs/MTLS_CONFIGURATION.md).
- Health, readiness, and metrics endpoints (Spring Boot Actuator defaults where applicable).
- Test coverage setup and guidance (Mockito usage and JaCoCo reporting docs).
- Docker and local development assets (compose files, Keycloak realm, publish script, local certs/truststore).

---

## Future Roadmap to `1.0.0` 

The `0.90.x` series is part of NDTP’s **pre-stable development cycle**, meaning: 
- **Minor versions (`0.91.0`, `0.92.0`...) introduce features and improvements** leading to a stable `1.0.0`. 
- **Patch versions (`0.90.1`, `0.90.2`...) contain only bug fixes and security updates**. 
- **Backward compatibility is NOT guaranteed until `1.0.0`**, though NDTP aims to minimise breaking changes. 

Once `1.0.0` is reached, future versions will follow **strict SemVer rules**. 

---

## Versioning Policy
1. **MAJOR updates (`X.0.0`)** – Typically introduce breaking changes that require users to modify their code or configurations.
    - **Breaking changes (default rule)**: Any backward-incompatible modifications require a major version bump.
    - **Non-breaking major updates (exceptional cases)**: A major version may also be incremented if the update represents a significant milestone, such as a shift in governance, a long-term stability commitment, or substantial new functionality that redefines the project’s scope.
2. **MINOR updates (`0.X.0`)** – New functionality that is backward-compatible.
3. **PATCH updates (`0.0.X`)** – Bug fixes, performance improvements, or security patches.
4. **Dependency updates** – A **major dependency upgrade** that introduces breaking changes should trigger a **MAJOR** version bump (once at `1.0.0`).

---

## How to Update This Changelog 

1. When making changes, update this file under the **Unreleased** section. 
2. Before a new release, move changes from **Unreleased** to a new dated section with a version number. 
3. Follow **Semantic Versioning** rules to categorise changes correctly. 
4. If pre-release versions are used, clearly mark them as `-alpha`, `-beta`, or `-rc.X`. 

---

**Maintained by the National Digital Twin Programme (NDTP).** 

© Crown Copyright 2025. This work has been developed by the National Digital Twin Programme and is legally attributed to the Department for Business and Trade (UK) as the governing entity.

Licensed under the Open Government Licence v3.0.

For full licensing terms, see [LICENSE.md](LICENSE.md).

