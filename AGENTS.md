# AppReg performance-test contributor guide

## Purpose

This is a Java-only Gatling performance-test project for the HMCTS Applications Register. The Gradle toolchain is Java 21. Do not add Scala sources, the Scala Gradle plugin, or a Scala Gatling DSL.

## Jira work-item map

Use the Jira ticket as the boundary for a business action. Keep each action reusable even where a later workflow composes it with others.

| Jira Ticket | UI Journey | Gatling Weight | Aligned Workflow | Updates Test Data? | Classification | Data Ticket |
| --- | --- | ---: | --- | --- | --- |
| ARCPOC-1615 | Update Application | 39.32% | Open existing application entry and update Applicant, Application Code, Wording, Respondent, Civil Fee, Notes and Officials, then Save Complete Application | Yes | Destructive | ARCPOC-1656 |
| ARCPOC-1616 | Add Application | 18.47% | Create a new Application within an existing Application List by completing Applicant, Application Code, Wording, Respondent (if required), Civil Fee (if required), Notes, Results and Officials, then Save Complete Application | Yes | Destructive | ARCPOC-1658 |
| ARCPOC-1617 | Result Multiple Applications | 7.75% | Search/Open Application List → Select multiple Applications → Search Result Code → Apply Result(s) to all selected Applications → Save | Yes | Destructive | ARCPOC-1659 |
| ARCPOC-1618 | Update Result Application | 7.28% | Open an existing Application → Update Result Code, Result Wording and Result Officer → Save Changes | Yes | Destructive | ARCPOC-1660 |
| ARCPOC-1597 | Create Application List | 7.19% | Create a new Application List by entering Date, Time, Description, Status and Court/Location, then Save | Yes | Destructive | ARCPOC-1640 |
| ARCPOC-1620 | Update Application List | 6.71% | Open an existing Application List → Update Date, Time, Description, Status, Court/Location or Duration → Save Changes (or Close List) | Yes | Destructive | ARCPOC-1661 |
| ARCPOC-1621 | Result Application | 4.45% | Open a single Application → Search Result Code → Apply Result → Enter Result Wording (if required) → Save | Yes | Destructive / Data Heavy | ARCPOC-1662 |
| ARCPOC-1622 | Bulk Update Officials | 4.21% | Select multiple Applications → Update Magistrates and Court Official details → Save | Yes | Destructive | ARCPOC-1663 |
| ARCPOC-1623 | Bulk Update Fees Status | 1.38% | Select multiple Applications → Update Fee Status, Status Date, Payment Reference and Off-site Fee → Save | Yes | Destructive | ARCPOC-1667 |
| ARCPOC-1598 | Bulk Application Upload | 0.67% | Select CSV File → Validate → Upload Application Entries into an existing Application List | Yes | Destructive | ARCPOC-1668 |
| ARCPOC-1599 / ARCPOC-1597 | Other UI Operations | 2.57% | Search Applications, Open Lists, Move Applications, Standard Applicant Search/View, Reporting, Printing and other low-frequency UI operations | Depends on operation | Mixed (Read & Write) | ARCPOC-1669 |

ARCPOC-1599 / ARCPOC-1597 is intentionally a shared row. Within that scope, performance reporting is a low-level, non-destructive journey: it needs one reusable search and generate-report set rather than destructive test-data provisioning.

ARCPOC-1620 has an agreed design split within its 6.71% allocation: 90% simple Application List update (6.039% of the total workload) and 10% close Application List (0.671% of the total workload). Implement them as separate actions with separate allocated data: ordinary open mutable lists for updates, and fully close-ready lists for closure. This is a documented design weight only; do not add it to an executable mixed workload until the required seed data and feeder allocation are in place.

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
- Retain every `*ProofSimulation`. Re-enable a Test proof in Jenkins only after its matching Data ticket provides one valid canonical data shape and the one-user proof passes. ARCPOC-1633 provides canonical SQL and allocation; ARCPOC-1706 builds the exact deterministic workload schedule from it.

## Recording conventions

- Use Java format in Gatling Recorder; its Java 17 source template is compiled by this project's Java 21 toolchain.
- Save raw recordings below `src/gatling/java/recorded/` with a `Recorded` class-name prefix. That directory is intentionally ignored by Git.
- Follow the certificate and disposable Chrome-profile instructions in `README.adoc`.
- The Gatling Recorder certificate-authority setup is per user and local-only. A new developer or machine must create and trust its own CA and configure its own `certificatePath` and `privateKeyPath`; never reuse or commit another user's private key.

## Validation

- Run `./gradlew gatlingClasses` after source changes.
- Run the smallest relevant one-user proof before adding a flow to a mixed workload or pipeline profile.
- Keep the feeder-backed `AppRegWorkloadSimulation` separate from `*ProofSimulation` classes. It must consume queue feeders from `build/workload-data/` only after the seed stage has reserved any proof rows and trimmed each feeder to its exact deterministic schedule count.
- Run the `validation` workload profile before the `initial` 500-user benchmark. The workload has no response-time NFR threshold; it must still fail on any functional HTTP error.
- Run the read-only 500-account `RUN_LOGIN_PREFLIGHT` gate before the destructive initial benchmark. Do not increase the initial login ramp rate after a preflight failure without recording the evidence and validating a lower controlled rate.
- Use approved environments only. `TEST_URL` is an origin, not a path.
- Do not modify pipeline behaviour, workload weights or success thresholds without documenting the reason and validating the outcome.

## Documentation

Update `README.adoc` when the contributor workflow, required configuration, recording procedure or runnable scenario changes. Keep the documentation specific about whether a scenario creates or changes persistent data.
