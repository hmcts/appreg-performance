# AppReg performance-test upgrade plan

## Purpose

Keep the Gatling performance-test project supportable and able to identify dependency vulnerabilities, without disrupting performance-test delivery.

## Current position

- Java 21 is used by the build.
- The Gradle wrapper is pinned to 8.14.1.
- Gatling and the Gatling Gradle plugin are both pinned to 3.14.3.
- The OWASP Dependency-Check Gradle plugin is pinned to 12.1.3.
- `gatling.scalaVersion` declares 2.13.11, although the resolved runtime graph uses Scala 2.13.16.
- A local OWASP scan could not complete because it could not retrieve NVD CVE data. This is a scan-data access issue, not a confirmed finding of vulnerable dependencies.

## Phase 1 — restore reliable vulnerability scanning

**Priority: high**

1. Request an NVD API key and store it as a masked Jenkins secret.
2. Configure the Dependency-Check task to read that value only at runtime.
3. Run `./gradlew dependencyCheckAnalyze` in Jenkins and publish the resulting report.
4. Agree a CVSS threshold and exception/suppression process with the service team.
5. Do not merge suppressions without recording the dependency, CVE, rationale, owner, and expiry/review date.

Dependency-Check requires external vulnerability data and supports NVD API keys. See the [Dependency-Check command-line options](https://dependency-check.github.io/DependencyCheck/dependency-check-cli/arguments.html) and [remote data-source guidance](https://dependency-check.github.io/DependencyCheck/data/index.html).

**Success criteria:** the scan completes reliably in Jenkins and produces an auditable report for every relevant build.

## Phase 2 — configuration alignment

**Priority: medium; low risk**

1. Align the declared Scala version with the version resolved by Gatling (currently 2.13.16), or remove the explicit Scala version if it is not needed.
2. Keep the Gatling Gradle plugin version and `gatlingVersion` aligned when upgrading.
3. Run `./gradlew gatlingClasses` and `./gradlew gatlingRun -Ddebug=on` after the change.

**Success criteria:** the dependency graph has no unintended Scala version substitution and the smoke journey remains successful.

## Phase 3 — dependency maintenance

**Priority: medium**

Renovate is enabled through `renovate.json`, which extends the shared HMCTS preset at `local>hmcts/.github:renovate-config`. It should raise dependency-update pull requests according to the centrally managed HMCTS rules.

1. Review available Gatling releases and upgrade the Gradle plugin and Gatling runtime together in a dedicated pull request.
2. Upgrade the Dependency-Check plugin to the current compatible release.
3. Run the full vulnerability scan, compile, and one-user smoke test.
4. Compare the Gatling report structure and request metrics before and after the upgrade.

Renovate pull requests are inputs to this process, not automatic approval to merge: apply the validation steps above before merging any upgrade.

Do not force individual transitive versions such as Netty or Jackson unless a completed vulnerability scan or an upstream Gatling advisory requires it. Gatling owns compatibility across these libraries.

**Success criteria:** no newly introduced scan findings, successful smoke test, and no unexplained change to reported request metrics.

## Phase 4 — Gradle 9 migration

**Priority: planned work**

The project currently emits deprecation warnings that say it will be incompatible with Gradle 9. Upgrade Gradle in a separate change, resolving those warnings before moving to the current Gradle major release.

1. Record the existing `./gradlew tasks`, `gatlingClasses`, `gatlingRun -Ddebug=on`, and Dependency-Check results.
2. Upgrade the wrapper to a supported Gradle 9 release.
3. Resolve all Gradle 9 compatibility warnings and plugin incompatibilities.
4. Repeat the baseline commands and review generated reports.

The current Gradle release can be checked at the [official Gradle versions endpoint](https://services.gradle.org/versions/current).

**Success criteria:** no Gradle 9 compatibility warnings, successful compilation and smoke test, and a working dependency scan.

## Proposed order and ownership

| Order | Work | Suggested owner | Dependency |
| --- | --- | --- | --- |
| 1 | Configure the NVD API key and Jenkins scan reporting | Platform/CI | NVD API key and Jenkins secret access |
| 2 | Align Scala configuration | Performance-test maintainers | None |
| 3 | Upgrade Gatling and Dependency-Check | Performance-test maintainers | Working vulnerability scan |
| 4 | Migrate Gradle to version 9 | Performance-test maintainers and CI | Compatible Gatling and Dependency-Check plugins |

## Out of scope

- Changing application authentication behaviour.
- Load-testing Microsoft Entra ID.
- Committing credentials, certificates, access tokens, or NVD API keys to the repository.
