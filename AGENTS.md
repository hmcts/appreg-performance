# AppReg performance-test contributor guide

## Purpose

This is a Java-only Gatling performance-test project for the HMCTS Applications Register. The Gradle toolchain is Java 21. Do not add Scala sources, the Scala Gradle plugin, or a Scala Gatling DSL.

## Jira work-item map

Use the Jira ticket as the boundary for a business action. Keep each action reusable even where a later workflow composes it with others.

| Ticket | Business action | Weight | Data effect |
| --- | --- | ---: | --- |
| ARCPOC-1596 | Search applications/lists | To be confirmed | Read-only; intended as reusable setup for other flows |
| ARCPOC-1597 | Create Application List | 7.19% | Destructive |
| ARCPOC-1598 | Bulk Application Upload | 0.67% | Destructive |
| ARCPOC-1599 | Other low-frequency UI operations | 2.57% | Mixed; split into named actions before adding to a workload |
| ARCPOC-1615 | Update Application | 39.32% | Destructive |
| ARCPOC-1616 | Add Application | 18.47% | Destructive |
| ARCPOC-1617 | Result multiple Applications | 7.75% | Destructive and data-heavy |
| ARCPOC-1618 | Update Result on an Application | 7.28% | Destructive and data-heavy |
| ARCPOC-1620 | Update or close Application List | 6.71% | Destructive |
| ARCPOC-1621 | Result one Application | 4.45% | Destructive and data-heavy |
| ARCPOC-1622 | Bulk update Officials | 4.21% | Destructive |
| ARCPOC-1623 | Bulk update Fees Status | 1.38% | Destructive |

The listed weights total 100% when ARCPOC-1596 is accounted for within the current ARCPOC-1599 allocation. Before building a mixed workload, assign ARCPOC-1596 its own agreed weight and reduce ARCPOC-1599 accordingly. Do not associate ARCPOC-1597 with the “other UI operations” scope: it is already the Create Application List ticket.

## Design approach

Use modular business-action scenarios, not a browser-test Page Object Model.

- Put reusable Gatling `ChainBuilder` actions in `src/gatling/simulations/scenarios/` and name them by business capability, such as `SearchScenario` or `ApplicationScenario`.
- Put executable load profiles and one-user validation proofs in `src/gatling/simulations/simulations/`.
- Keep cross-cutting code (SSO, headers, environment configuration and test-data helpers) in `src/gatling/simulations/utils/`.
- Compose actions in simulations. A scenario action may be used as setup for another action, but do not silently include setup traffic in a measured transaction unless that is intentional and documented.

Recordings are raw browser evidence only. They reveal the HTTP flow but must not be run as production performance tests. Extract only the necessary requests into a curated scenario, replace dynamic values with Gatling session values or feeders, remove browser-only noise, and add meaningful response checks.

## Test data and safety

- Treat create, update, result, upload, move and bulk actions as destructive unless demonstrated otherwise.
- Use dedicated approved test accounts and isolated, known data. Never allow multiple virtual users to update the same record unintentionally.
- Generate unique disposable data for created entities and capture identifiers for follow-up calls or cleanup.
- Keep passwords, access tokens, cookies, client secrets, captured personal data and response bodies out of Git, logs, reports and issue trackers.
- Do not remove data or change an environment without explicit approval and a known target scope.

## Recording conventions

- Use Java format in Gatling Recorder; its Java 17 source template is compiled by this project's Java 21 toolchain.
- Save raw recordings below `src/gatling/java/recorded/` with a `Recorded` class-name prefix. That directory is intentionally ignored by Git.
- Follow the certificate and disposable Chrome-profile instructions in `README.adoc`.

## Validation

- Run `./gradlew gatlingClasses` after source changes.
- Run the smallest relevant one-user proof before adding a flow to a mixed workload or pipeline profile.
- Use approved environments only. `TEST_URL` is an origin, not a path.
- Do not modify pipeline behaviour, workload weights or success thresholds without documenting the reason and validating the outcome.

## Documentation

Update `README.adoc` when the contributor workflow, required configuration, recording procedure or runnable scenario changes. Keep the documentation specific about whether a scenario creates or changes persistent data.
