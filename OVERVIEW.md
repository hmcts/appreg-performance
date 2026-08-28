# AppReg performance framework overview

This document describes what the current framework does. It is an implementation
inventory, not an endorsement of the workload design or a plan for future work.

## Purpose

`appreg-performance` is a Java Gatling suite that exercises the HMCTS
Applications Register at the HTTP layer. It provides:

- a basic Applications List smoke journey;
- focused one-user proofs for individual business actions;
- a deterministic multi-user workload;
- AppReg and Microsoft Entra authentication replay;
- PostgreSQL-backed test-data allocation; and
- Jenkins entrypoints for proof, diagnostic and performance execution.

The default target is `https://appreg.test.apps.hmcts.net`.

## Technology

- Java 21 toolchain
- Gradle wrapper
- Gatling Java DSL 3.15.1
- Gatling Gradle plugin 3.15.1.2
- OWASP Dependency Check Gradle plugin 13.0.0
- PostgreSQL seed and reset scripts
- `Jenkinsfile_CNP` and `Jenkinsfile_nightly`

The repository contains no Scala test source or Scala Gradle plugin.

## Source layout

- `src/gatling/simulations/scenarios/` contains reusable business actions as
  Gatling `ChainBuilder` classes.
- `src/gatling/simulations/simulations/` contains executable smoke, proof,
  setup and workload simulations.
- `src/gatling/simulations/utils/` contains shared HTTP configuration,
  authentication, diagnostics, workload planning and coordination.
- `data/seed/` contains canonical and scalable PostgreSQL seed scripts plus the
  workload allocation profiles.
- `db/postgres/reload_data.sql` contains the guarded masked-database reset.

Business actions use named Gatling groups. Supporting requests that are part of
the user journey are included in the business-action group; authentication and
unrelated setup are separate.

## HTTP behaviour

The shared HTTP protocol:

- uses the configured AppReg base URL;
- sends the configured User-Agent;
- infers HTML resources and marks inferred resources silent; and
- shares the AppReg API media type and XSRF header conventions across actions.

`AppRegSimulation` loads `/applications-list`, checks the page title, extracts
and loads the current main JavaScript asset, and optionally calls the
authenticated `/application-lists` API.

## Authentication

`SsoAuthentication.login()` replays the AppReg and Microsoft Entra HTTP flow. It
extracts the changing Entra continuation values, submits the configured account
credentials, follows the AppReg callback, verifies the authenticated home page
and checks `/sso/me`.

Credentials come from environment variables. Account names may be generated
from a `{index}` template so each virtual user receives a dedicated identity.
The password is read directly by the authentication chain and is not placed in
a feeder.

### Workload authentication gate

`AppRegWorkloadSimulation` authenticates and retains the same Gatling sessions
that later perform workload actions:

1. Primary users authenticate at one account per second.
2. Successful sessions wait at a shared target gate while retaining their
   cookies.
3. Failed primary workload slots can be claimed by up to ten spare accounts.
4. If a claimed spare fails, the corresponding primary account can be retried
   once.
5. A positive HTTP `Retry-After` value delays recovery.
6. If the authentication target is reached, retained sessions are staggered
   into their assigned workload actions.
7. If the target cannot be reached, the simulation fails without releasing
   business actions.

The 500-user profile plus ten spare accounts fits the current
`SsoAuthentication` limit of 510 accounts.

The Jenkins seed stage runs before Gatling starts. The gate protects release of
workload actions; it does not protect or roll back the earlier seed operation.

## Executable simulations

| Simulation | Behaviour | Persistent-data effect | CNP proof mode |
| --- | --- | --- | --- |
| `AppRegSimulation` | Basic Applications List HTML, JavaScript and optional API journey | Read-only | Yes |
| `ApplicationListSearchProofSimulation` | Search Application Lists | Read-only | No |
| `ActivityAuditReportProofSimulation` | Generate, poll and download an Activity Audit report | Does not change AppReg records | Yes |
| `ApplicationListCreateProofSimulation` | Create an Application List | Creates data | No |
| `UpdateApplicationProofSimulation` | Update one seeded Application | Changes data | Yes |
| `UpdateApplicationListProofSimulation` | Update one seeded open Application List | Changes data | Yes |
| `UpdateAndCloseApplicationListProofSimulation` | Update and close a seeded close-ready list | Changes data | Yes |
| `AddApplicationProofSimulation` | Add an Application to a seeded list | Creates data | Yes |
| `ResultApplicationProofSimulation` | Apply a Result to one seeded Application | Changes data | Yes |
| `ResultMultipleApplicationsProofSimulation` | Apply a Result to three seeded Applications | Changes data | Yes |
| `UpdateApplicationResultProofSimulation` | Update an existing Result | Changes data | Yes |
| `BulkUpdateOfficialsProofSimulation` | Update officials on three seeded Applications | Changes data | Yes |
| `BulkUpdateFeesProofSimulation` | Update fee details on three seeded Applications | Changes data | Yes |
| `BulkApplicationUploadProofSimulation` | Upload Applications into a seeded empty list | Creates data | Yes |
| `ResultMultipleApplicationsSetupSimulation` | Create a list and three Applications for manual setup | Creates data | No |
| `AppRegWorkloadSimulation` | Execute the deterministic mixed workload | Creates and changes data | Workload modes |

All executable proof simulations assert 100% successful requests. Proofs that
use seeded data read their allocation identifiers from required environment
variables and fail at startup when those variables are absent.

The CNP proof mode does not currently run
`ApplicationListSearchProofSimulation`, `ApplicationListCreateProofSimulation`
or `ResultMultipleApplicationsSetupSimulation`.

## Deterministic mixed workload

`AppRegWorkloadSimulation` currently schedules these action keys:

- `update_application`
- `add_application`
- `result_multiple`
- `update_result`
- `create_list`
- `update_list`
- `close_list`
- `result_application`
- `bulk_officials`
- `bulk_fees`
- `bulk_upload`
- `other_operations`, implemented as Application List search

`data/seed/workload/allocation-profile.properties` is the source of the
configured users, duration, login ramp, allocation capacity and exact scheduled
action counts.

The workload does not randomly select actions at runtime. `WorkloadProfile`
constructs an evenly interleaved deterministic plan and assigns a fixed action
sequence to each account. When a diagnostic run is smaller than the configured
profile, the scheduled counts are scaled using largest-remainder allocation.

Each user performs one planned action per minute. Destructive actions consume
queue-backed CSV rows, and the simulation requires the feeder row count to match
the scheduled count exactly. Create-list and search actions do not use these
seeded feeder rows.

The workload requires 100% successful requests. It has no response-time
service-level objective or response-time pass/fail threshold.

## Test-data provisioning

The CNP pipeline runs PostgreSQL seed logic before every mode. It creates
isolated synthetic records and writes:

- proof allocation variables to `build/seed-allocation.env`;
- action-specific allocation CSV files to `build/performance-data/`; and
- exact scheduled workload CSV files to `build/workload-data/`.

The pipeline copies only the rows required by the deterministic schedule into
the workload directory. Mutable allocations are intended for one action only so
virtual users do not update the same record.

`RESET_DATABASE=true` runs the guarded masked-database restore before the seed
stage. Reset is optional; seeding is not.

## CNP Jenkins execution

`Jenkinsfile_CNP` exposes five parameters:

| Parameter | Behaviour |
| --- | --- |
| `RUN_MODE` | Selects `framework-proof`, `application-diagnostic` or `performance` |
| `MAX_USERS` | Caps diagnostic users to `1..500` |
| `RUN_DURATION_MINUTES` | Caps diagnostic duration |
| `WORKLOAD_RELEASE_INTERVAL_SECONDS` | Staggers retained sessions into business work; Jenkins defaults to one second |
| `RESET_DATABASE` | Optionally restores the masked Test baseline before seeding |

The common execution order is:

1. Optionally reset the database.
2. Seed and allocate data.
3. Run the proof set or workload simulation.
4. Publish Gatling reports in a `finally` block.

### `framework-proof`

- Uses the `smoke` allocation.
- Runs `AppRegSimulation` plus the proof simulations marked **Yes** in the table
  above.
- Uses one authenticated user per proof.
- Does not currently run every proof class in the repository.

### `application-diagnostic`

- Uses the `performance` profile scaled to the requested users and duration.
- Requires `MAX_USERS` from 1 to 500.
- Requires a positive duration.
- Rejects users multiplied by minutes above 35,000.
- Runs `AppRegWorkloadSimulation`.

### `performance`

- Uses the fixed `performance` profile.
- Requests 500 users and 70 actions per user.
- Authenticates at one primary account per second.
- Runs `AppRegWorkloadSimulation`.

The properties file also contains a ten-user, five-minute `validation` profile,
but the current user-facing CNP modes do not select it.

## Nightly Jenkins execution

`Jenkinsfile_nightly` is separate from the CNP run-mode pipeline. It targets
AppReg staging, enables SSO, fixes `PERFORMANCE_TEST_USERS` to one and runs the
default `AppRegSimulation` through the shared performance-test pipeline. It is
not the deterministic mixed workload.

## Reports and diagnostics

Gatling reports are generated below `build/reports/gatling/`. Both Jenkins
pipelines archive the report directories and publish an HTML index, including
after a failed run.

Selected HTTP failures emit sanitised request, status and session-state
diagnostics. Optional SSO diagnostics summarise continuation-page shape without
logging raw HTML, tokens, cookies or credentials.

## Current implementation observations

- CNP proof mode omits three executable proof/setup simulations named above.
- The `validation` workload profile has no matching user-facing CNP mode.
- The seed stage changes the database before workload authentication succeeds.
- The workload has functional success assertions but no response-time NFR.
- The action mix and fixed performance size are implementation inputs, not
  independently validated requirements.
