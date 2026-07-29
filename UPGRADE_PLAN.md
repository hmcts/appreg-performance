# AppReg performance-test upgrade plan

## Purpose

Keep the Gatling performance-test project supportable and able to identify dependency vulnerabilities, without disrupting performance-test delivery.

## Current position

- Java 21 is the build target. Client Platform now enforces Java 21 for Gradle-based performance tests; Java 17 support was removed on 1 June 2026.
- The Gradle wrapper is pinned to 8.14.5.
- Gatling is pinned to 3.15.1 and the matching Gatling Gradle plugin to 3.15.1.2.
- The OWASP Dependency-Check Gradle plugin is pinned to 12.1.3.
- `gatling.scalaVersion` is aligned to Scala 2.13.16, the version resolved by the Gatling runtime.
- The one-user SSO/UI proof completed successfully in Client Platform Jenkins on Temurin Java 21.0.12 on 29 July 2026.
- A local OWASP scan could not complete because it could not retrieve NVD CVE data. This is a scan-data access issue, not a confirmed finding of vulnerable dependencies.

## Java runtime compatibility

**Status: required Client Platform constraint**

The project targets Java 21 to comply with the Client Platform Jenkins policy. A previous direct Jenkins proof used a Java 17 agent and consequently could not load Java 21 classes; that agent configuration is no longer supported by the shared pipeline library.

Before changing this target from Java 21:

1. Confirm that the Client Platform performance-test agent launches both Gradle and Gatling with that Java release.
2. Run `./gradlew clean gatlingClasses` on the target agent.
3. Run the one-user authenticated SSO/UI proof and an unauthenticated smoke run.
4. Record the Java version used by the Jenkins agent in the change or pipeline output.

**Success criteria:** Gatling loads `AppRegSimulation` on the Client Platform Java 21 runtime without a class-version error.

## Phase 1 — restore reliable vulnerability scanning

**Priority: high**

**Decision gate:** before implementing this phase, confirm with the relevant HMRC/HMCTS security or platform team whether an NVD-backed Dependency-Check scan is required and approved for this repository. Check whether a centrally managed scanner, shared NVD cache, or existing organisational process should be used instead. Do not request or configure a new NVD API key until that decision is confirmed.

1. Request an NVD API key and store it as a masked Jenkins secret.
2. Configure the Dependency-Check task to read that value only at runtime.
3. Run `./gradlew dependencyCheckAnalyze` in Jenkins and publish the resulting report.
4. Agree a CVSS threshold and exception/suppression process with the service team.
5. Do not merge suppressions without recording the dependency, CVE, rationale, owner, and expiry/review date.

Dependency-Check requires external vulnerability data and supports NVD API keys. See the [Dependency-Check command-line options](https://dependency-check.github.io/DependencyCheck/dependency-check-cli/arguments.html) and [remote data-source guidance](https://dependency-check.github.io/DependencyCheck/data/index.html).

**Success criteria:** the scan completes reliably in Jenkins and produces an auditable report for every relevant build.

## Phase 2 — configuration alignment

**Priority: medium; low risk**

**Status: completed for the current versions.**

1. Align the declared Scala version with the version resolved by Gatling (2.13.16). **Completed.**
2. Keep the Gatling Gradle plugin version and `gatlingVersion` aligned when upgrading. They are currently 3.15.1.2 and 3.15.1 respectively. **Completed for the current version.**
3. Run `./gradlew gatlingClasses` and the one-user SSO/UI proof on the Client Platform Java 21 agent. **Completed on Temurin 21.0.12, 29 July 2026.**

**Success criteria:** the dependency graph has no unintended Scala version substitution and the smoke journey remains successful.

## Phase 3 — dependency maintenance

**Priority: medium**

**Status: Gatling 3.15.1 upgrade applied; Jenkins SSO/UI proof pending.**

Renovate is enabled through `renovate.json`, which extends the shared HMCTS preset at `local>hmcts/.github:renovate-config`. It should raise dependency-update pull requests according to the centrally managed HMCTS rules.

1. Review available Gatling releases and upgrade the Gradle plugin and Gatling runtime together in a dedicated pull request. **Gatling 3.15.1 / plugin 3.15.1.2 applied; validate in Jenkins.**
2. Upgrade the Dependency-Check plugin to the current compatible release.
3. Run the full vulnerability scan, compile, and one-user smoke test.
4. Compare the Gatling report structure and request metrics before and after the upgrade.

Renovate pull requests are inputs to this process, not automatic approval to merge: apply the validation steps above before merging any upgrade.

Do not force individual transitive versions such as Netty or Jackson unless a completed vulnerability scan or an upstream Gatling advisory requires it. Gatling owns compatibility across these libraries.

**Success criteria:** no newly introduced scan findings, successful smoke test, and no unexplained change to reported request metrics.

## Phase 4 — Gradle 9 migration

**Priority: planned work**

The project currently emits deprecation warnings that say it will be incompatible with Gradle 9. Upgrade Gradle in a separate change, resolving those warnings before moving to the current Gradle major release.

1. Record the existing `./gradlew tasks`, `gatlingClasses`, `gatlingRun -Ddebug=on`, authenticated one-user SSO/UI proof, and Dependency-Check results.
2. Upgrade the wrapper to a supported Gradle 9 release.
3. Resolve all Gradle 9 compatibility warnings and plugin incompatibilities.
4. Repeat the baseline commands and review generated reports.

The current Gradle release can be checked at the [official Gradle versions endpoint](https://services.gradle.org/versions/current).

**Success criteria:** no Gradle 9 compatibility warnings, successful compilation and smoke test, and a working dependency scan.

## Proposed order and ownership

| Order | Work | Suggested owner | Dependency |
| --- | --- | --- | --- |
| 1 | Configure the NVD API key and Jenkins scan reporting | Platform/CI | NVD API key and Jenkins secret access |
| 2 | Confirm Client Platform Java runtime before any Java-target change | Platform/CI and performance-test maintainers | Client Platform agent configuration |
| 3 | Align Scala configuration | Performance-test maintainers | None |
| 4 | Upgrade Gatling and Dependency-Check | Performance-test maintainers | Working vulnerability scan and Java runtime compatibility |
| 5 | Migrate Gradle to version 9 | Performance-test maintainers and CI | Compatible Gatling and Dependency-Check plugins |

## Out of scope

- Changing application authentication behaviour.
- Independent or high-rate load-testing of Microsoft Entra ID. The production-like UI journey performs one real login per virtual user, with its ramp kept below AppReg and Entra agreed limits.
- Committing credentials, certificates, access tokens, or NVD API keys to the repository.
