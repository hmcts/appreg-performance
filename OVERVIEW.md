# AppReg performance framework overview

This document describes what the current framework does. It is an implementation
inventory, not an endorsement of the workload design or a plan for future work.

## Purpose

`appreg-performance` is a Java Gatling suite that exercises the HMCTS
Applications Register at the HTTP layer. It provides:

- a basic Applications List smoke journey;
- focused one-user proofs for individual business actions;
- a prototype mode with a read-only pooling control and seeded mixed-action experiment;
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

- concurrent access is represented by a Gatling workload actor participating in
  the representative workload, including while that actor is in think time;
- all 500 actors must remain active throughout a measured steady-state period;
- actors may share a smaller authenticated session pool, and reports must state
  actor count, pool size and SSO journey count separately;
- the steady-state duration will be configurable and will default to 30 minutes;
- authentication, setup and pre-target ramp-up workload are outside measured
  business-operation timings, but their functional failures remain failures and
  failure to establish the configured pool or validate 500 actors prevents an
  `NFR004` pass;
- the session pool authenticates first; actors then receive pooled state
  round-robin, validate `/sso/me`, complete their stable initial offsets, and
  begin work without another SSO journey;
- the p95 duration of each complete named business-action group must be below
  the applicable `NFR006` or `NFR007` limit;
- measured business requests must be 100% successful; and
- the initial interpretation of “without performance degradation” is that the
  absolute `NFR006` and `NFR007` limits still pass at 500 actors. The measurement
  and reporting model must allow a later comparison with a low-load baseline,
  but no permitted percentage increase has yet been agreed.

`NFR001` and `NFR005` remain to be defined. A single Gatling run cannot
establish availability, and a no-backend browser interaction cannot be measured
accurately by this HTTP-only suite.

Current framework coverage is limited:

| Requirement | Current coverage |
| --- | --- |
| `NFR001` | Not measured. |
| `NFR004` | The read-only prototype has passed ten-actor runs with two, five and ten sessions, including a paced 15-minute two-session control. A five-minute seeded mixed run has also passed with ten actors sharing two sessions. A matching one-session-per-actor control, intermediate pooled-write load and 500 actors remain outstanding, and no degradation comparison has been agreed. |
| `NFR005` | Not measured. |
| `NFR006` | Each scheduled simple workload operation has a logical p95 assertion against the two-second limit. Staged load evidence remains outstanding. |
| `NFR007` | Each scheduled complex workload operation has a logical p95 assertion against the five-second limit. The Activity Audit report is not currently part of the mixed workload, and staged load evidence remains outstanding. |

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

Reusable business-scenario GET requests may retry a gateway HTTP 502/504 under
a bounded policy. POST, PUT and other non-GET requests are never retried.
Retries are recorded as gateway evidence; an exhausted retry fails normally.

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

`AppRegWorkloadSimulation` now separates pool authentication from the Gatling
actors that perform workload actions:

1. A configurable pool population is injected at one SSO journey per second.
2. Every pool entry receives one dedicated identity and independent AppReg
   session.
3. Workload actors wait without sending HTTP traffic until the complete pool is
   ready.
4. Each actor receives pool cookies round-robin in its own Gatling cookie jar,
   validates `/sso/me`, and waits for a stable index-based initial offset.
5. The actor counts as ready only after that offset. It then starts its paced,
   actor-specific deterministic plan; authentication state may be shared but
   mutable feeder rows are not.
6. Actions started before the ready-actor target is reached are
   nested under `AppReg_Ramp_Up` and consume ramp-up-only feeder rows.
7. When the final required actor is ready, the common measured window
   opens. Existing actors retain their cadence; their next actions use the
   measured groups and measured-only feeder rows.
8. Failure to authenticate the pool and ready every actor within the setup
   deadline fails the run.
9. At the measured deadline, no new action starts and in-flight work must finish
   within the completion grace.

There are no spare identities, authentication-retry population, shared gate or
post-login workload release. Setting the pool size equal to the actor count
reproduces one independently authenticated session per actor.
`SsoAuthentication` accepts at most 500 accounts.
The Jenkins seed stage still runs before Gatling and is not rolled back if
authentication later fails.

## Executable simulations

| Simulation | Behaviour | Persistent-data effect | Included in `framework-proof` sequence |
| --- | --- | --- | --- |
| `FrameworkProofSimulation` | Authenticate once and orchestrate the selected proof journeys sequentially | Includes the effects listed below | Orchestrator |
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
| `PhaseMeasurementPrototypeSimulation` | Authenticate a smaller session pool, assign it round-robin to concurrent-access actors, validate every actor and run paced searches through a common measured window | Read-only | `prototype` only |
| `AppRegWorkloadSimulation` | Execute the deterministic mixed workload | Creates and changes data | Workload modes |

All executable proof simulations assert 100% successful requests. Proofs that
use seeded data read their allocation identifiers from required environment
variables and fail at startup when those variables are absent.

The CNP proof sequence does not currently include the journeys from
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

Actors also receive deterministic initial offsets distributed by actor index
across `ACTION_SPREAD_SECONDS`, defaulting to 60 seconds. This reduces accidental
cadence alignment while keeping runs repeatable; zero deliberately requests a
burst. `ACTION_PACE_SECONDS`, also defaulting to 60 seconds, remains the minimum
interval between action starts for each actor.

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

The workload requires 100% functional success. For every action with scheduled
measured samples, it reports attempted, succeeded and failed counts, calculates
the nearest-rank logical p95 across successful complete business-action chains
and applies the classified `NFR006` or `NFR007` limit. A failed action remains a
failure but does not discard the actor's remaining independently seeded plan.
Recovered GET attempt time and retry delay are subtracted from the logical
duration, while successful responses and intentional journey pauses remain.
Operations with zero scheduled samples are reported as not measured.

## Test-data provisioning

The CNP pipeline runs PostgreSQL seed logic before proof and workload modes. It
is deliberately skipped by `prototype/read-only` and used by
`prototype/seeded-mixed`. Where seeding runs,
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
stage. Reset is optional and ignored by `prototype/read-only`.

## CNP Jenkins execution

`Jenkinsfile_CNP` exposes nine parameters:

| Parameter | Behaviour |
| --- | --- |
| `RUN_MODE` | Selects `framework-proof`, `application-diagnostic`, `prototype` or `performance` |
| `PROTOTYPE_WORKLOAD` | Selects the `read-only` control or `seeded-mixed` experiment for prototype mode |
| `MAX_USERS` | Caps diagnostic users or prototype concurrent-access actors to `1..500` |
| `SESSION_POOL_SIZE` | Configures authenticated sessions for phase-based modes; blank means 2 for `prototype` and one session per actor for seeded workloads |
| `RUN_DURATION_MINUTES` | Caps diagnostic duration |
| `STEADY_STATE_MINUTES` | Configures the prototype and performance measured steady state; defaults to 30 minutes |
| `ACTION_PACE_SECONDS` | Configures each actor's minimum action-start interval; defaults to 60 seconds |
| `ACTION_SPREAD_SECONDS` | Configures deterministic initial actor offsets; defaults to 60 seconds and zero requests a burst |
| `RESET_DATABASE` | Optionally restores the masked Test baseline before seeding; ignored by `prototype/read-only` |

The seeded-mode execution order is:

1. Optionally reset the database.
2. Seed and allocate data.
3. Run the proof set or workload simulation.
4. Publish Gatling reports in a `finally` block.

### `framework-proof`

- Uses the `smoke` allocation.
- Launches `FrameworkProofSimulation` once and runs the same twelve business
  proofs previously listed as separate Jenkins invocations.
- Authenticates one virtual user once, then retains that Gatling session and
  cookie jar for the complete sequence.
- Starts each proof when the preceding proof completes. The sequence remains
  single-threaded, and authentication remains outside the business-action
  groups.
- Keeps the individual proof simulations available for focused diagnosis.
- Does not currently run every proof class in the repository.

### `application-diagnostic`

- Uses the `performance` profile scaled to the requested users and duration.
- Requires `MAX_USERS` from 1 to 500.
- Requires a duration from 1 to 70 minutes.
- Rejects users multiplied by minutes above 35,000.
- Runs the same pooled-session `AppRegWorkloadSimulation` with separately
  allocated ramp-up and measured data.
- Uses one authenticated session per actor when `SESSION_POOL_SIZE` is blank;
  an explicit smaller value enables staged pooled-write diagnosis.

### `prototype`

- Uses `PROTOTYPE_WORKLOAD=read-only` as the safe default control or
  `PROTOTYPE_WORKLOAD=seeded-mixed` for the staged pooled-write experiment.
- Authenticates only `SESSION_POOL_SIZE` sessions, defaulting to two at one SSO
  journey per second.
- Starts `MAX_USERS` concurrent-access actors, defaulting to ten. They wait
  without HTTP traffic until the complete session pool is ready.
- Assigns pool entries round-robin into separate Gatling actor cookie jars and
  verifies `/sso/me` for every actor without another SSO journey.
- Gives every actor a stable initial offset across `ACTION_SPREAD_SECONDS` after
  its pooled session validates.
- In `read-only`, begins the search workload and records pre-target searches
  under `Prototype_Ramp_Up_Application_List_Search`.
- In `seeded-mixed`, seeds isolated ramp-up and measured allocations before SSO,
  then runs each actor's deterministic mixed-action plan. Authentication state
  may be shared; mutable feeder rows and actor plan positions are never shared.
- Opens one common measured window when all actors are ready without restarting
  them. Subsequent actions use measured groups; the read-only control uses
  `AppReg_030_Application_List_Search`.
- Defaults to a 15-minute authentication setup deadline, 30-minute measured
  window, 60-second action pace, 60-second action spread and 60-second
  completion grace. These controls are configurable with the parameters and
  system properties documented in `README.md` and are logged before
  authentication starts.
- Stops starting actions at the common measured deadline.
- In `read-only`, uses elapsed group duration and asserts that measured requests
  are 100% successful and p95 is less than five seconds.
- In `seeded-mixed`, asserts each sampled operation's retry-adjusted logical p95
  against its classified `NFR006` or `NFR007` threshold and reports unsampled
  operations as not measured.
- Applies bounded 502/504 recovery to GET requests only. Recovered attempt time
  and delay are excluded from logical NFR timing; writes are never retried.
- Requires 100% success globally so authentication and ramp-up failures still
  fail the run.
- Reports actor count, authenticated pool size, SSO journeys and reuse ratio,
  and explicitly does not claim distinct-user or distinct-session equivalence.
- Prints prominent configuration and phase banners once per transition.

### `performance`

- Uses the `performance` profile's action mix.
- Requests 500 actors. A blank `SESSION_POOL_SIZE` authenticates 500 accounts as
  the control; an explicit smaller pool enables staged session reuse.
- Uses `STEADY_STATE_MINUTES`, defaulting to 30 and capped at the profile's
  70-minute envelope.
- Defaults to a 15-minute authentication setup deadline, 60-second action pace,
  60-second initial action spread and 60-second completion grace; all effective
  values are logged.
- Runs the pooled-session phase-based `AppRegWorkloadSimulation`.

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
- The action mix and 500-user performance size are implementation inputs, not
  independently validated requirements.
