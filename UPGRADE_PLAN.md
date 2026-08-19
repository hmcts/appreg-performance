# AppReg performance-test upgrade plan

## Purpose

Keep the Gatling performance-test project supportable and able to identify dependency vulnerabilities, without disrupting performance-test delivery.

## Current position

- Java 21 is the build target. Client Platform now enforces Java 21 for Gradle-based performance tests; Java 17 support was removed on 1 June 2026.
- The Gradle wrapper is pinned to 8.14.5.
- Gatling is pinned to 3.15.1 and the matching Gatling Gradle plugin to 3.15.1.2.
- The OWASP Dependency-Check Gradle plugin is pinned to 12.2.2.
- The project source and Gradle configuration are Java-only. Gatling may retain Scala implementation dependencies internally, but the repository has no Scala source or Scala Gradle plugin.
- The one-user SSO/UI proof completed successfully in Client Platform Jenkins on Temurin Java 21.0.12 on 29 July 2026.
- The one-user Application List create proof compiled and completed successfully against the approved staging environment on Java 21 on 3 August 2026.
- ARCPOC-1633 established canonical synthetic Test data, reset/seed controls and deterministic feeder allocation through Jenkins. ARCPOC-1706 builds the exact bounded workload schedule from those allocations; final destructive workload journeys consume allocated seeded data rather than creating their own persistent setup data.
- Jenkins runs the centrally managed OWASP Dependency-Check validation and archives its report. It uses shared platform credentials and cache configuration; no repository-managed NVD API key is required.

## Java runtime compatibility

**Status: required Client Platform constraint**

The project targets Java 21 to comply with the Client Platform Jenkins policy. Gatling simulations are authored in Java; Gatling Recorder's Java 17 template is source-compatible with the Java 21 toolchain. A previous direct Jenkins proof used a Java 17 agent and consequently could not load Java 21 classes; that agent configuration is no longer supported by the shared pipeline library.

Before changing this target from Java 21:

1. Confirm that the Client Platform performance-test agent launches both Gradle and Gatling with that Java release.
2. Run `./gradlew clean gatlingClasses` on the target agent.
3. Run the one-user authenticated SSO/UI proof and an unauthenticated smoke run.
4. Record the Java version used by the Jenkins agent in the change or pipeline output.

**Success criteria:** Gatling loads the Java simulations on the Client Platform Java 21 runtime without a class-version error.

## Phase 1 — vulnerability-scanning decision

**Priority: high**

**Status: centrally managed validation active.**

Do not request, store, or configure an NVD API key for this repository. Jenkins invokes the centrally managed Dependency-Check validation with shared platform credentials and cache configuration. Any repository-level scan configuration or suppression remains subject to agreement with Security and Platform.

If scanning is enabled later, agree the CVSS threshold and exception/suppression process with the service team. Do not merge suppressions without recording the dependency, CVE, rationale, owner, and expiry/review date.

**Re-entry criteria:** a documented need to change the centrally managed scanning behaviour, report publication, threshold or suppression process.

## Phase 2 — Java-only configuration alignment

**Priority: medium; low risk**

**Status: completed for the current versions.**

1. Remove Scala source and the Scala Gradle plugin. **Completed.**
2. Keep the Gatling Gradle plugin version and `gatlingVersion` aligned when upgrading. They are currently 3.15.1.2 and 3.15.1 respectively. **Completed for the current version.**
3. Run `./gradlew gatlingClasses`, the one-user SSO/UI proof, and the one-user Application List create proof on Java 21. **Completed.**

**Success criteria:** Java Gatling source compiles and the smoke and create-list proof journeys remain successful.

## Phase 3 — dependency maintenance

**Priority: medium**

**Status: Gatling 3.15.1 upgrade validated; Dependency-Check 12.2.2 is validated through the shared Jenkins scan.**

Renovate is enabled through `renovate.json`, which extends the shared HMCTS preset at `local>hmcts/.github:renovate-config`. It should raise dependency-update pull requests according to the centrally managed HMCTS rules.

1. Review available Gatling releases and upgrade the Gradle plugin and Gatling runtime together in a dedicated pull request. **Completed: Gatling 3.15.1 / plugin 3.15.1.2 compiled locally and completed the Jenkins one-user SSO/UI proof.**
2. Upgrade the Dependency-Check plugin to the current compatible release. **Current version: 12.2.2.** Validate an upgrade against the shared Jenkins scan and its archived report before merging.
3. Run the shared vulnerability scan, compile, and one-user smoke and create-list proof tests. **Partially completed: the shared scan and report publication run in Jenkins; compile and the one-user proofs have passed.**
4. Compare the Gatling report structure and request metrics before and after the upgrade. **Pending: retain the current Jenkins run as the post-upgrade baseline when the next recorded journey is added.**

Renovate pull requests are inputs to this process, not automatic approval to merge: apply the validation steps above before merging any upgrade.

Do not force individual transitive versions such as Netty or Jackson unless a completed vulnerability scan or an upstream Gatling advisory requires it. Gatling owns compatibility across these libraries.

**Success criteria:** successful compile and one-user SSO/UI proof, no unexplained change to reported request metrics, and—if Phase 1 is re-entered—no newly introduced scan findings.

## Phase 4 — Gradle 9 migration

**Priority: planned work**

**Status: deferred pending Gatling Gradle-plugin compatibility.**

Renovate PR #8 proposes a wrapper upgrade from Gradle 8.14.5 to 9.6.1. Java 21 satisfies Gradle 9's Java 17-or-later runtime requirement. However, the project's current `io.gatling.gradle` plugin version (3.15.1.2) is documented by Gatling as tested through Gradle 8.6; Gradle 9 is outside its published tested range. Do not merge the Renovate update as an automatic dependency update.

First confirm a Gatling Gradle-plugin version with explicit Gradle 9 support, or demonstrate compatibility through the validation below and record the evidence in the upgrade pull request. Review the [Gradle 9 migration guide](https://docs.gradle.org/current/userguide/upgrading_major_version_9.html) and [Gatling Gradle-plugin compatibility guidance](https://docs.gatling.io/integrations/build-tools/gradle-plugin/) at the time of the upgrade.

1. On Gradle 8.14.5, record `./gradlew help --warning-mode=all`, `./gradlew tasks`, `gatlingClasses`, the authenticated one-user SSO/UI proof, and existing report output.
2. Upgrade the wrapper and, if required, the Gatling Gradle plugin and Gatling runtime together in a dedicated pull request.
3. Resolve Gradle 9 errors, warnings and plugin incompatibilities; do not suppress them without a documented rationale.
4. Run `./gradlew clean gatlingClasses` on Java 21.
5. Run the authenticated one-user SSO/UI, Application List create, and Application List search proofs against the approved environment. Confirm the CNP pipeline executes the same proofs successfully.
6. Review generated Gatling reports and compare request names, response outcomes and metrics against the pre-upgrade baseline.

The current Gradle release can be checked at the [official Gradle versions endpoint](https://services.gradle.org/versions/current).

**Success criteria:** a Gatling plugin with demonstrated Gradle 9 compatibility; no unresolved Gradle 9 warnings; successful Java 21 compilation and all one-user proofs; no unexplained change to Gatling report structure or request outcomes; and, if Phase 1 is re-entered, a working dependency scan.

## Phase 5 — performance-test data provisioning and reset

**Priority: high for multi-user and weighted workloads**

**Status: implemented for canonical proof data and deterministic allocation — [ARCPOC-1633](https://tools.hmcts.net/jira/browse/ARCPOC-1633), with bounded workload scheduling in [ARCPOC-1706](https://tools.hmcts.net/jira/browse/ARCPOC-1706).**

The pipeline provisions synthetic, workflow-specific data through a dedicated Jenkins seed stage before Gatling execution. Reset, seed, proof-smoke and workload controls are derived from the selected Jenkins run mode. The seed stage applies reviewed SQL and writes a deterministic allocation of safe records to Gatling queue feeders.

The final workload journeys must use their allocated Application Lists and Applications rather than creating setup data while measurements are being taken. One-user proof simulations may retain isolated runtime setup only where it is explicitly documented as proof-only behaviour.

1. Store reviewed, non-sensitive PostgreSQL seed statements in this repository, organised by workflow data ticket and based on one canonical synthetic data shape that can be scaled. **Completed.** The current workflow-to-data-ticket mapping is maintained in `AGENTS.md`.
2. Define the reset boundary, execution identity, target environment and rollback/recovery approach with Platform and the AppReg service team. **Completed for controlled Jenkins execution; reset remains an explicit build parameter.**
3. Add a Jenkins provisioning/reset stage that runs before Gatling and fails safely if the expected data is not available. **Completed.**
4. Produce one feeder allocation per virtual user so destructive actions cannot target the same record unintentionally. **Completed for the bounded deterministic workload.**
5. Record the dataset version, volume, allocation assumptions and any retention/cleanup expectations in the build output or accompanying run documentation. **Completed for profile-controlled allocation; formal-run retention remains owned by the client Jenkins service.**

**Success criteria:** the approved environment can be reset and seeded repeatedly; each run receives the required deterministic data volume; and destructive performance journeys do not collide or depend on data left by prior runs. **Achieved for canonical proofs and the current bounded diagnostic workload.**

## Proposed order and ownership

| Order | Work | Suggested owner | Dependency |
| --- | --- | --- | --- |
| 1 | Confirm a centrally managed or repository-specific scanning requirement | Security/Platform and performance-test maintainers | Security or platform decision |
| 2 | Confirm Client Platform Java runtime before any Java-target change | Platform/CI and performance-test maintainers | Client Platform agent configuration |
| 3 | Maintain Java-only Gatling configuration | Performance-test maintainers | None |
| 4 | Upgrade Gatling | Performance-test maintainers | Java runtime compatibility |
| 5 | Maintain Dependency-Check if scanning becomes required | Performance-test maintainers | Phase 1 re-entry criteria |
| 6 | Migrate Gradle to version 9 | Performance-test maintainers and CI | Compatible Gatling and Dependency-Check plugins |
| 7 | Provision and reset deterministic performance-test data (ARCPOC-1633) | Performance-test maintainers, Platform/CI and AppReg service team | Approved reset mechanism, reviewed seed SQL and workflow data tickets |

## Out of scope

- Changing application authentication behaviour.
- Independent or high-rate load-testing of Microsoft Entra ID. The production-like UI journey performs one real login per virtual user, with its ramp kept below AppReg and Entra agreed limits.
- Committing credentials, certificates, access tokens, or NVD API keys to the repository.
