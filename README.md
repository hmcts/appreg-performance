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
| `RUN_MODE` | Select `framework-proof`, `application-diagnostic`, `prototype` or `performance` |
| `MAX_USERS` | Cap users for `application-diagnostic` or `prototype` |
| `RUN_DURATION_MINUTES` | Cap duration for `application-diagnostic` |
| `STEADY_STATE_MINUTES` | Set the measured steady-state duration for `prototype` and `performance`; defaults to 30 minutes |
| `RESET_DATABASE` | Optionally restore the masked Test baseline before seeding; ignored by `prototype` |

Current modes:

| Mode | Execution |
| --- | --- |
| `framework-proof` | Seed data, authenticate one user once and run the twelve-proof set sequentially in one Gatling session |
| `application-diagnostic` | Seed data and run a bounded deterministic workload using the `performance` profile scaled to the requested users and minutes |
| `prototype` | Run the read-only phase-measurement prototype without resetting or seeding the database |
| `performance` | Seed isolated ramp-up and measured data, then run the phase-based workload with 500 users and a configurable measured period |

For `application-diagnostic`, users must be between 1 and 500, duration must be
from 1 to 70 minutes, and users multiplied by minutes must not exceed 35,000.

The `prototype` mode authenticates users progressively. Each user immediately
starts a read-only search workload using the same Gatling session and cookies;
there is no wait-for-all gate or later workload release. Searches remain under
an unmeasured ramp-up group until the requested authenticated-session count is
reached. The same paced sessions then continue under the measured group for the
common `STEADY_STATE_MINUTES` window.

The measured search group's p95 elapsed duration must be less than five seconds
and all requests, including authentication and ramp-up traffic, must succeed.
Effective configuration and phase changes are printed as prominent prototype
banners.

Run the prototype locally with the group-duration metric enabled:

```bash
./gradlew gatlingRun \
  -DappRegPrototypeUsers=2 \
  -DappRegPrototypeSteadyStateMinutes=1 \
  -DappRegPrototypeAuthenticationRatePerSecond=1 \
  -DappRegPrototypeAuthenticationSetupTimeoutMinutes=15 \
  -DappRegPrototypeActionPaceSeconds=60 \
  -DappRegPrototypeRampDownGraceSeconds=60 \
  -Dgatling.charting.useGroupDurationMetric=true \
  --simulation simulations.PhaseMeasurementPrototypeSimulation
```

The last four prototype properties are internal controls rather than Jenkins
parameters. Their shown values are the defaults. The authentication setup
timeout must be long enough to contain the configured user-injection ramp.

The workload modes use the same lifecycle with the mixed workload. Each user
authenticates once and immediately starts its assigned actions. Before the
authenticated-session target is reached, actions appear below the
`AppReg_Ramp_Up` group and consume only `build/workload-data/ramp-up` feeders.
The same sessions continue into the measured window using the separately
reserved `build/workload-data/measured` feeders. There is no shared gate,
workload release, spare account or final authentication retry.

The workload's internal phase controls are parameterised and logged:

| System property | Default | Purpose |
| --- | --- | --- |
| `appRegWorkloadAuthenticationSetupTimeoutMinutes` | `15` | Deadline for reaching the authenticated-session target |
| `appRegWorkloadActionPaceSeconds` | `60` | Minimum interval between action starts; values below 60 are rejected |
| `appRegWorkloadRampDownGraceSeconds` | `60` | Time allowed for an in-flight final action to complete |

Jenkins supplies these from internal environment defaults named
`WORKLOAD_AUTHENTICATION_SETUP_TIMEOUT_MINUTES`, `WORKLOAD_ACTION_PACE_SECONDS`
and `WORKLOAD_RAMP_DOWN_GRACE_SECONDS`. They are not user-facing build
parameters.

Validate its defaults, boundaries and phase transitions without authenticating
or sending HTTP traffic:

```bash
./gradlew prototypeSelfCheck workloadSelfCheck gatlingClasses
```

The prototype is read-only and does not seed data. Other CNP modes seed data;
database reset remains optional and runs before their seed stage. Workload seed
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
report directories and publishes a Gatling HTML index even when execution fails.

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
