# AppReg performance framework overview

This document describes what the current framework does. It is an implementation
inventory, not an endorsement of the workload design or a plan for future work.

## Purpose

`appreg-performance` is a Java Gatling suite that exercises the HMCTS
Applications Register at the HTTP layer. It provides:

- a basic Applications List smoke journey;
- focused one-user proofs for individual business actions;
- an isolated read-only prototype for proving phase-specific measurement;
- a deterministic multi-user workload;
- AppReg and Microsoft Entra authentication replay;
- PostgreSQL-backed test-data allocation; and
- Jenkins entrypoints for proof, prototype, diagnostic and performance execution.

The default target is `https://appreg.test.apps.hmcts.net`.

## Applicable non-functional requirements

The supplied non-functional requirements are:

| ID | Requirement |
| --- | --- |
| `NFR001` | The system shall maintain at least 98.5% availability, excluding planned maintenance. |
| `NFR004` | The system shall support at least 500 concurrent users without performance degradation. |
| `NFR005` | The system shall complete trivial operations with no backend interaction in less than 0.25 seconds. |
| `NFR006` | The system shall complete simple backend operations, such as GET or PUT operations, in less than 2 seconds. |
| `NFR007` | The system shall complete complex backend operations, such as search or reporting, in less than 5 seconds. |

These requirements are not evidence that the current framework demonstrates
compliance. The agreed acceptance interpretation for the Gatling-owned
requirements is:

- a concurrent user is an authenticated user with an active Gatling session,
  including while that user is in think time;
- all 500 sessions must remain active throughout a measured steady-state period;
- the steady-state duration will be configurable and will default to 30 minutes;
- authentication, setup and pre-target ramp-up workload are outside measured
  business-operation timings, but their functional failures remain failures and
  failure to establish 500 authenticated sessions prevents an `NFR004` pass;
- users begin work as soon as their individual login succeeds; reaching the
  authenticated-session target changes the classification of their subsequent
  actions without releasing, restarting or reauthenticating the population;
- the p95 duration of each complete named business-action group must be below
  the applicable `NFR006` or `NFR007` limit;
- measured business requests must be 100% successful; and
- the initial interpretation of “without performance degradation” is that the
  absolute `NFR006` and `NFR007` limits still pass at 500 users. The measurement
  and reporting model must allow a later comparison with a low-load baseline,
  but no permitted percentage increase has yet been agreed.

`NFR001` and `NFR005` remain to be defined. A single Gatling run cannot
establish availability, and a no-backend browser interaction cannot be measured
accurately by this HTTP-only suite.

Current framework coverage is limited:

| Requirement | Current coverage |
| --- | --- |
| `NFR001` | Not measured. |
| `NFR004` | The performance mode now defines a 500-session target and a common measured steady-state window. The lifecycle has not yet been proven with the mixed workload at 500 users, and no degradation comparison has been agreed. |
| `NFR005` | Not measured. |
| `NFR006` | Simple backend operations are exercised, but the two-second limit is not asserted. |
| `NFR007` | The prototype asserts the five-second p95 limit for read-only search, but the full mixed workload and reporting operations do not yet have NFR assertions. |

### Workload operation classification

The acceptance boundary is the complete named Gatling group, including the
supporting requests that form that business action. Authentication, unrelated
setup and pre-target ramp-up traffic are excluded. The phase-specific prototype
confirms that Gatling reports and asserts the intended elapsed group duration.

| Workload action | Gatling group | Classification | p95 limit |
| --- | --- | --- | --- |
| `update_application` | `AppReg_040_Application_Update` | `NFR006` simple single-application operation | Less than 2 seconds |
| `add_application` | `AppReg_050_Application_Add` | `NFR006` simple single-application operation | Less than 2 seconds |
| `result_multiple` | `AppReg_060_Applications_Bulk_Result` | `NFR007` complex multi-application operation | Less than 5 seconds |
| `update_result` | `AppReg_070_Application_Result_Update` | `NFR006` simple single-application operation | Less than 2 seconds |
| `create_list` | `AppReg_020_Application_List_Create` | `NFR006` simple single-list operation | Less than 2 seconds |
| `update_list` | `AppReg_080_Application_List_Update` | `NFR006` simple single-list operation | Less than 2 seconds |
| `close_list` | `AppReg_090_Application_List_Close` | `NFR006` simple single-list operation | Less than 2 seconds |
| `result_application` | `AppReg_065_Application_Result` | `NFR006` simple single-application operation | Less than 2 seconds |
| `bulk_officials` | `AppReg_065_Applications_Bulk_Officials` | `NFR007` complex multi-application operation | Less than 5 seconds |
| `bulk_fees` | `AppReg_070_Applications_Bulk_Fees` | `NFR007` complex multi-application operation | Less than 5 seconds |
| `bulk_upload` | `AppReg_085_Applications_Bulk_Upload` | `NFR007` complex asynchronous bulk operation | Less than 5 seconds |
| `other_operations` | `AppReg_030_Application_List_Search` | `NFR007` complex search operation | Less than 5 seconds |

The Activity Audit report proof group,
`AppReg_090_Reports_Activity_Audit`, is also a complex `NFR007` operation when
it is included in an NFR workload. It is not currently a `WorkloadAction`.

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
- `data/seed/` contains the selective PostgreSQL test-data generator and the
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

### Workload phase lifecycle

`AppRegWorkloadSimulation` authenticates and retains the same Gatling sessions
that later perform workload actions:

1. One primary population is injected at the configured login rate.
2. Every virtual user receives one dedicated identity and authenticates once.
3. A successful user immediately starts its paced mixed workload with the same
   Gatling session, cookies and XSRF state.
4. Actions started before the authenticated-session target is reached are
   nested under `AppReg_Ramp_Up` and consume ramp-up-only feeder rows.
5. When the final required session authenticates, the common measured window
   opens. Existing users retain their cadence; their next actions use the
   measured groups and measured-only feeder rows.
6. Failure to reach the target within the setup deadline fails the run.
7. At the measured deadline, no new action starts and in-flight work must finish
   within the completion grace.

There are no spare identities, authentication-retry population, shared gate or
post-login workload release. `SsoAuthentication` accepts at most 500 accounts.
The Jenkins seed stage still runs before Gatling and is not rolled back if
authentication later fails.

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
| `PhaseMeasurementPrototypeSimulation` | Authenticate users progressively, start each read-only search workload after login, and continue the same sessions through a common measured window | Read-only | `prototype` only |
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
configured users, maximum duration, login ramp and workload weighting counts.

The workload does not randomly select actions at runtime. `WorkloadProfile`
constructs two evenly interleaved deterministic plans using the same configured
mix:

- a maximum ramp-up plan bounded by the setup deadline and action pace; and
- a measured plan bounded by the common steady-state duration.

The default 15-minute setup deadline and 60-second pace reserve at most 15
ramp-up actions per user. The default 500-user, 30-minute performance run
therefore reserves 7,500 ramp-up action slots and 15,000 measured action slots.
Of these, 20,304 actions need mutable feeder rows; create-list and search actions
do not consume pre-seeded rows. The seed script inserts approximately 160,741
persistent rows for those feeder allocations before the default run, compared
with approximately 250,027 rows for the previous 500-user, 70-minute workload.

Ramp-up and measured actions consume different queue-backed CSV files. The
simulation requires every feeder row count to match its phase plan exactly and
never reuses a mutable allocation. Unused worst-case ramp-up rows remain
untouched if authentication completes before the deadline.

The workload requires 100% successful requests. It has no response-time
service-level objective or response-time pass/fail threshold.

## Test-data provisioning

The CNP pipeline runs PostgreSQL seed logic before proof and workload modes. It
is deliberately skipped by the read-only `prototype` mode. Where seeding runs,
it creates
isolated synthetic records and writes:

- proof allocation variables to `build/seed-allocation.env`;
- action-specific allocation CSV files to `build/performance-data/`; and
- exact ramp-up workload CSV files to `build/workload-data/ramp-up/`; and
- exact measured workload CSV files to `build/workload-data/measured/`.

The pipeline calculates both plans before seeding, generates their combined
capacity, splits the results into the two phase directories and validates every
file count. Mutable allocations are intended for one action only so virtual
users do not update the same record.

`RESET_DATABASE=true` runs the guarded masked-database restore before the seed
stage. Reset is optional. The setting is ignored by `prototype`.

## CNP Jenkins execution

`Jenkinsfile_CNP` exposes five parameters:

| Parameter | Behaviour |
| --- | --- |
| `RUN_MODE` | Selects `framework-proof`, `application-diagnostic`, `prototype` or `performance` |
| `MAX_USERS` | Caps diagnostic or prototype users to `1..500` |
| `RUN_DURATION_MINUTES` | Caps diagnostic duration |
| `STEADY_STATE_MINUTES` | Configures the prototype and performance measured steady state; defaults to 30 minutes |
| `RESET_DATABASE` | Optionally restores the masked Test baseline before seeding; ignored by `prototype` |

The seeded-mode execution order is:

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
- Requires a duration from 1 to 70 minutes.
- Rejects users multiplied by minutes above 35,000.
- Runs the same phase-based `AppRegWorkloadSimulation` with separately allocated
  ramp-up and measured data.

### `prototype`

- Does not reset or seed the database.
- Authenticates `MAX_USERS` progressively, defaulting to one user per second.
- Starts each user's read-only search workload immediately after that user's
  login succeeds; there is no population-wide gate or workload release.
- Retains each user's Gatling session, cookies and action cadence throughout the
  run.
- Records searches under `Prototype_Ramp_Up_Application_List_Search` until the
  requested authenticated-session count is reached.
- Opens one common measured window when the target is reached. Subsequent
  paced searches use `AppReg_030_Application_List_Search` without restarting
  the users.
- Defaults to a 15-minute authentication setup deadline, 30-minute measured
  window, 60-second action pace and 60-second completion grace. These internal
  controls are configurable with the system properties documented in
  `README.md` and are logged before authentication starts.
- Stops starting actions at the common measured deadline.
- Uses elapsed group duration and asserts that measured requests are 100%
  successful and p95 is less than five seconds.
- Requires 100% success globally so authentication and ramp-up failures still
  fail the run.
- Prints prominent configuration and phase banners once per transition.

### `performance`

- Uses the `performance` profile's action mix.
- Requests 500 users and authenticates at one account per second.
- Uses `STEADY_STATE_MINUTES`, defaulting to 30 and capped at the profile's
  70-minute envelope.
- Defaults internally to a 15-minute authentication setup deadline, 60-second
  action pace and 60-second completion grace; all effective values are logged.
- Runs the single-population phase-based `AppRegWorkloadSimulation`.

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
- The action mix and 500-user performance size are implementation inputs, not
  independently validated requirements.
