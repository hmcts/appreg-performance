# AppReg performance-test contributor guide

## Purpose

This is a Java-only Gatling performance-test project for the HMCTS Applications Register. The Gradle toolchain is Java 21. Do not add Scala sources, the Scala Gradle plugin, or a Scala Gatling DSL.

## Design approach

- Put reusable Gatling `ChainBuilder` actions in `src/gatling/java/scenarios/` and name them by business capability.
- Put executable load profiles and focused one-user proofs in `src/gatling/java/simulations/`.
- Keep cross-cutting code (SSO, headers, environment configuration and test-data helpers) in `src/gatling/java/utils/`.
- Use a separate Gatling group for each reported business action. Keep authentication and unrelated test-data setup outside that group; include supporting requests when they are part of the user journey being measured.

Do not use raw browser recordings as executable performance tests. Extract only the necessary requests into curated scenarios, capture server-generated values in the Gatling session, supply controlled inputs through configuration or test data, remove browser-only noise and add meaningful response checks.

## Test data and safety

- Treat any action that changes persistent data as destructive.
- Use dedicated approved test accounts and isolated, known data. Never allow multiple virtual users to update the same record unintentionally.
- Generate unique disposable data for created entities and capture identifiers for follow-up calls or cleanup.
- Keep passwords, access tokens, cookies, client secrets, captured personal data and response bodies out of Git, logs, reports and issue trackers.
- Do not run a destructive action unless the user has explicitly approved the target environment and data scope.

## Validation

- Run `./gradlew gatlingClasses` after source changes.
- Provide and run a focused one-user proof for each reusable business action before adding it to a workload or pipeline profile.
- Run focused validation when pipeline behaviour, workload composition or success thresholds change.

## Documentation

Update `README.md` when the contributor workflow, required configuration, recording procedure or runnable scenario changes. Keep the documentation specific about whether a scenario creates or changes persistent data.