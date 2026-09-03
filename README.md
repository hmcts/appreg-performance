# AppReg Gatling performance tests

Java 21 Gatling tests for the HMCTS Applications Register. The suite sends HTTP
requests directly to AppReg; it does not drive a browser.

See [OVERVIEW.md](OVERVIEW.md) for the current architecture, implemented
journeys, applicable non-functional requirements and coverage gaps,
phase lifecycle, workload scheduling and Jenkins execution flow.

## Prerequisites

- Java 21
- Git
- Network access to the target AppReg environment and Gradle dependencies
- Dedicated test accounts for authenticated runs
- Approved, isolated test data for any destructive simulation

The Gradle wrapper downloads the required Gradle and Gatling dependencies.

## Compile

From the repository root:

```bash
./gradlew gatlingClasses
```

## Basic local run

The default Gradle target runs `simulations.AppRegSimulation`. This loads the
Applications List HTML and JavaScript. Authentication defaults to `none`.

```bash
./gradlew gatlingRun -Ddebug=on
```

`-Ddebug=on` runs one virtual user with pauses disabled.

The local smoke wrapper runs the read-only authenticated Application List search
proof:

```bash
./scripts/run-smoketest.sh
```

Read its supported options before changing the defaults:

```bash
./scripts/run-smoketest.sh --help
```

The wrapper loads `.env.local` when present. It can also accept an explicit
configuration file, simulation class, search value or target URL. It does not
load the AppReg API test configuration unless requested.

## Target and authentication configuration

`TEST_URL` is the AppReg application origin, without an application path:

```bash
TEST_URL='https://appreg.test.apps.hmcts.net'
```

Authenticated simulations require:

- `APPREG_TEST_ACCOUNT_TEMPLATE` or `TEST_USER_EMAIL`
- `APPREG_TEST_USER_PASSWORD` or `TEST_USERS_PASSWORD`
- `APPREG_TENANT_ID` or `TENANT_ID`

A multi-user account template must contain `{index}`. Each virtual user receives
a different formatted account name. `APPREG_ACCOUNT_START_INDEX` changes the
first index.

`APPREG_USER_AGENT` or `-DappRegUserAgent` overrides the HTTP User-Agent.
Entra may return the complete login configuration immediately or require a
bootstrap reload, depending on the client. The authentication chain supports
both page shapes. When an Entra configuration page is missing required
continuation fields, the test logs only its shape and field presence; it does
not log the response body or token values.
Set `APPREG_SSO_DIAGNOSTICS=true` to include the same sanitized summary for
other HTML continuation steps.

Passwords are read from the environment and are not written to feeders, reports
or logs.

## Running a specific simulation

Use Gatling's simulation selector:

```bash
./gradlew gatlingRun \
  --simulation simulations.ApplicationListSearchProofSimulation
```

Seed-dependent proof simulations also require the allocation environment
variables named in their source classes. Jenkins normally creates and exports
those values during its seed stage.

Do not run a simulation that changes persistent data until the target
environment and data scope have been explicitly approved.

## Jenkins CNP pipeline

`Jenkinsfile_CNP` exposes these parameters:

| Parameter | Purpose |
| --- | --- |
| `RUN_MODE` | Select `framework-proof`, `application-diagnostic` or `performance` |
| `MAX_USERS` | Concurrent actors for `application-diagnostic`, from 1 to 500; ignored by the other modes |
| `SESSION_POOL_SIZE` | Authenticated sessions shared by actors, from 1 to the actor count; blank means one per diagnostic actor or 100 in `performance` |
| `AUTHENTICATION_SPARE_USERS` | Additional SSO candidates available to replace failed workload authentications; defaults to 10 and required sessions plus spares must not exceed 500 |
| `AUTHENTICATION_RATE_PER_SECOND` | Progressive workload SSO rate, greater than 0 and no more than 1; defaults to 0.15 and is ignored by `framework-proof` |
| `RUN_DURATION_MINUTES` | Measured workload duration from 1 to 70 minutes; defaults to 30 and is ignored by `framework-proof` |
| `ACTION_PACE_SECONDS` | Minimum interval between action starts, from 60 to 3,600 seconds; defaults to 60 and is ignored by `framework-proof` |
| `ACTION_SPREAD_SECONDS` | Deterministic initial actor spread from 0 to 3,600 seconds; defaults to 60, while 0 deliberately requests a burst |
| `RESET_DATABASE` | Optionally restore the masked Test baseline before seeding; destructive and disabled by default |

Current modes:

| Mode | Execution |
| --- | --- |
| `framework-proof` | Seed data, authenticate one user once and run the twelve-proof set sequentially in one Gatling session |
| `application-diagnostic` | Seed data and run a bounded pooled-session workload using the `performance` profile scaled to the requested actors and minutes |
| `performance` | Seed isolated ramp-up and measured data, then run the pooled-session phase-based workload with 500 actors and a configurable measured period |

The phase-based modes follow an attack, sustain and release load profile:

```text
Concurrent
users
  ^
  |                 +--------------------+
  |                /                      \
  |               /                        \
  |              /                          \
  +-------------+----------------------------+----------> time
                 Attack       Sustain         Release
                 ramp-up      measured        ramp-down
```

The vertical position represents workload-ready actors, not SSO requests or
distinct authenticated sessions. Authentication and setup occur during attack;
only the sustain plateau contributes to response-time NFR measurements. During
release, no new business action starts and any action already in flight is
allowed to complete within the configured grace period.

After `/sso/me` succeeds, each actor receives one deterministic initial offset
derived from its actor index and `ACTION_SPREAD_SECONDS`. This establishes
different action cadences without randomising the repeatable workload plan. The
final actor becomes ready after its offset, so the measured window does not open
until the complete spread has been established. `ACTION_SPREAD_SECONDS=0`
deliberately starts actors without offsets.

For `application-diagnostic`, actors must be between 1 and 500, duration must be
from 1 to 70 minutes, and actors multiplied by minutes must not exceed 35,000.

During SSO, only the final authenticated AppReg `/` and `/sso/me` validation
GETs retry once after HTTP 502/504. No Entra, credential, KMSI or callback step
is retried. Ten additional candidate users are available by default to replace
failed whole SSO journeys without changing the requested session-pool size.
Once the pool is full, later scheduled candidates exit without attempting SSO.
As soon as completed failures mean even every remaining candidate succeeding
cannot fill the pool, Gatling stops the load generator and the run fails.
Jenkins validates the complete candidate ramp plus actor spread against the
internal setup deadline.

A local seeded-mixed run requires a freshly prepared `ramp-up` and `measured`
feeder directory and explicit approval for its persistent Test-data changes:

```bash
./gradlew gatlingRun \
  -DappRegWorkloadProfile=performance \
  -DappRegMaxUsers=10 \
  -DappRegDurationMinutes=5 \
  -DappRegWorkloadSessionPoolSize=2 \
  -DappRegWorkloadAuthenticationSpareUsers=10 \
  -DappRegWorkloadAuthenticationRatePerSecond=0.15 \
  -DappRegWorkloadActionPaceSeconds=60 \
  -DappRegWorkloadActionSpreadSeconds=60 \
  -DappRegPerformanceDataDirectory="$PWD/build/workload-data" \
  --simulation simulations.AppRegWorkloadSimulation
```

Do not reuse feeder data from a completed run. Jenkins is the normal entrypoint
because it constructs fresh allocations immediately before Gatling starts.

The workload modes now use the same session-pool lifecycle with the mixed
workload. Only the configured pool performs SSO. Workload actors wait without
HTTP traffic, receive pool cookies round-robin in separate Gatling cookie jars,
validate `/sso/me`, apply their stable initial offsets and then start their
actor-specific deterministic plans. Before the complete actor target is ready,
actions appear below the `AppReg_Ramp_Up` group and consume only
`build/workload-data/ramp-up` feeders. The same actors continue into the
measured window using the separately reserved `build/workload-data/measured`
feeders. Spare candidates replace failed pool authentications only; they do not
create extra sessions or retry the failed state-changing SSO journey.

Pooling shares authentication state, not test data: every actor retains its own
plan index and every mutable feeder row remains queue-backed and single-use.
Reusable business-scenario GET requests retry HTTP 502/504 once by default.
Each transient attempt and recovery is logged, and recovered attempt time plus
the configured retry delay is excluded from the seeded workload's logical
business-operation p95. POST, PUT and other non-GET requests are never retried;
their failures remain normal functional failures. Exhausting a GET retry also
fails the request and the workload.

The workload's internal phase controls are parameterised and logged:

During the measured phase, a separate `WORKLOAD OPERATIONS` percentage bar shows
the proportion of planned operations remaining. Gatling's built-in scenario
percentages continue to represent completed virtual users, so they normally
remain at zero while those actors are sustaining the workload. The final
operations bar is followed by the total workload elapsed time, covering session
authentication, actor setup, measured steady state and ramp-down.

| System property | Default | Purpose |
| --- | --- | --- |
| `appRegWorkloadSessionPoolSize` | Actor count | Authenticated sessions assigned round-robin; setting it equal to the actor count reproduces one session per actor |
| `appRegWorkloadAuthenticationSpareUsers` | Up to `10`, within the 500-account ceiling | Additional candidate identities available to replace failed SSO journeys |
| `appRegWorkloadAuthenticationRatePerSecond` | `1` in Java; `0.15` through Jenkins and supplied runners | Progressive SSO journeys per second; must be greater than 0 and no more than 1 |
| `appRegWorkloadAuthenticationSetupTimeoutMinutes` | `15` | Deadline for authenticating the pool and readying every actor |
| `appRegWorkloadActionPaceSeconds` | `60` | Minimum interval between action starts; values below 60 are rejected |
| `appRegWorkloadActionSpreadSeconds` | `60` | Stable initial actor offsets; `0` requests an intentional burst |
| `appRegWorkloadRampDownGraceSeconds` | `60` | Time allowed for an in-flight final action to complete |
| `appRegGatewayGetRetries` | `1` | Retries allowed for a GET that returns HTTP 502/504; `0` disables recovery |
| `appRegGatewayGetRetryDelaySeconds` | `1` | Delay before a safe GET retry |
| `appRegBulkUploadPollTimeoutSeconds` | `30` | Maximum wait for a bulk-upload job to leave `RECEIVED`, `VALIDATING` or `PROCESSING` |
| `gatling.data.console.writePeriod` | `5` directly; `10` through supplied runners | Seconds between Gatling console-statistics blocks |

Jenkins supplies the pool, spare candidates, authentication rate, pace and
spread from `SESSION_POOL_SIZE`, `AUTHENTICATION_SPARE_USERS`,
`AUTHENTICATION_RATE_PER_SECOND`, `ACTION_PACE_SECONDS` and
`ACTION_SPREAD_SECONDS`. The setup deadline and
completion grace remain internal environment defaults named
`WORKLOAD_AUTHENTICATION_SETUP_TIMEOUT_MINUTES` and
`WORKLOAD_RAMP_DOWN_GRACE_SECONDS`. Jenkins can override the internal GET retry
defaults with `WORKLOAD_GATEWAY_GET_RETRIES` and
`WORKLOAD_GATEWAY_GET_RETRY_DELAY_SECONDS`. Bulk-upload completion polling uses
`WORKLOAD_BULK_UPLOAD_POLL_TIMEOUT_SECONDS`, defaulting to 30 seconds. Every
effective value is logged before traffic begins. Jenkins and the local runners
set the Gatling console period from `GATLING_CONSOLE_WRITE_PERIOD_SECONDS`,
defaulting to 10 seconds.

At the end of a seeded workload, the eye-catching `WORKLOAD NFR SUMMARY` is the
authoritative response-time verdict. It reports every scheduled action's
attempted, succeeded and failed counts, logical p95, applicable `NFR006` or
`NFR007` limit and PASS/FAIL result. A failed action is recorded without
discarding that actor's remaining independently seeded actions. Operations with
no scheduled samples are explicitly `NOT MEASURED`. The deterministic plan is
reserved feeder capacity, while the hard measured-window deadline determines
how many operations actually start. A final slot that falls on that boundary is
reported as unused plan capacity; it does not turn otherwise successful,
within-limit operations into failures. Every operation that starts must still
succeed, and every scheduled action must have a measured sample. Gatling's HTML
group timings deliberately retain the wall-clock effect of support retries and
remain useful for diagnosing the raw journey; they are not the retry-adjusted
NFR value.

The same summary is retained as
`build/reports/gatling/workload-nfr-summary.txt` and linked from the Jenkins
Gatling report index. Its `Result classification` distinguishes a genuine NFR
timing failure from workload functional failure and framework or setup failure.

Validate its defaults, boundaries and phase transitions without authenticating
or sending HTTP traffic:

```bash
./gradlew workloadSelfCheck traceContextSelfCheck gatlingClasses
```

All CNP modes seed data first; database reset remains optional. Workload seed
construction checks the exact ramp-up and measured feeder sizes before Gatling
starts, so a short allocation fails before authentication rather than reusing a
mutable record.

The `framework-proof` mode launches `FrameworkProofSimulation` once. It performs
one SSO journey, retains that virtual user's session and cookies, and runs the
twelve selected business proofs sequentially. Each proof starts when the
preceding proof finishes, so the sequence remains single-threaded without an
artificial delay. Authentication remains outside the business-action groups.
Individual proof simulations remain available for focused local diagnosis.
The mode does not execute every proof class present in the source tree; see
[OVERVIEW.md](OVERVIEW.md) for the exact current list.

## Reports and diagnostics

Gatling writes HTML reports below `build/reports/gatling/`. Jenkins archives the
report directories, retained workload NFR summary and Gatling HTML index even
when execution fails.

Selected failures emit sanitised diagnostic lines. Passwords, access tokens,
cookies and raw response bodies must not be logged.

## Recording browser journeys

See [recording_readme.md](recording_readme.md) for the local Gatling Recorder
procedure.

Recorder output is source evidence, not executable performance-test code. Keep
only the requests needed for the business action, capture server-generated
values in the Gatling session, provide controlled test inputs and add meaningful
response checks.

Never commit raw recordings, credentials, cookies, tokens, private keys,
captured personal data or response bodies.

## Development checks

After changing Gatling source:

```bash
./gradlew gatlingClasses
```

Run the smallest relevant one-user proof before adding or changing a workload
action. All proof and workload simulations currently require 100% successful
requests.

## Safety

- Treat every action that changes persistent data as destructive.
- Use dedicated test accounts and isolated data.
- Do not allow virtual users to share mutable records unintentionally.
- Never run these workloads against production.
