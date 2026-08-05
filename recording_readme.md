# Recording an AppReg journey with Gatling

This guide records browser traffic as source material for a new Gatling scenario. It contains no credentials or environment-specific secrets.

Raw recordings are not performance tests. They must be cleaned up, parameterised and converted into reusable Java `ChainBuilder` actions before use in a simulation.

## Prerequisites

- Java 21 is installed and available as `java`.
- An approved AppReg environment and dedicated test account are available.
- Gatling Recorder has been configured with a local certificate authority (CA), as described in `README.adoc`.
- The following paths exist locally:

  ```text
  ~/.config/gatling-recorder/staging/ca-cert.pem
  ~/.config/gatling-recorder/staging/ca-key.pem
  ```

The certificate is trusted once in the macOS System keychain and survives restarts. The local, ignored Recorder configuration must use those same paths. This Recorder CA is separate from the Zscaler certificate used by Java and Gradle.

Set the approved environment origin and matching allow-list regular expression. Do not add a path to `RECORDING_APP_URL`.

```bash
export RECORDING_APP_URL='https://<approved-appreg-host>'
export RECORDING_APP_URL_REGEX='https://<approved-appreg-host-regex>/.*'
```

## 1. Authenticate without the Recorder proxy

Fully quit Chrome (`⌘Q`) first. Then launch an isolated recording profile without a proxy:

```bash
open -na "Google Chrome" --args \
  --user-data-dir=/private/tmp/gatling-recording-chrome \
  --no-proxy-server
```

Open `$RECORDING_APP_URL`, sign in using the approved test account, and confirm the intended AppReg page is available. Fully quit this Chrome instance (`⌘Q`) when complete. The isolated profile retains the authenticated session for recording.

For an HMCTS recording session, follow the approved procedure: temporarily disconnect the relevant Zscaler Private Access/security connection, reconnect through F5, then start the Recorder and proxied Chrome profile. Restore normal Zscaler/F5 connectivity immediately after recording; this is not a permanent security bypass.

## 2. Configure and start Gatling Recorder

From the repository root run:

```bash
./gradlew gatlingRecorder
```

Use these settings before clicking **Start!**:

| Setting | Value |
| --- | --- |
| Recorder mode | `HTTP Proxy` |
| Listening port | `8000` |
| HTTPS mode | `Certificate Authority` |
| Certificate | `~/.config/gatling-recorder/staging/ca-cert.pem` |
| Private key | `~/.config/gatling-recorder/staging/ca-key.pem` |
| Package | `recorded` |
| Class name | A descriptive `Recorded...` name, such as `RecordedApplicationSearch` |
| Format | `Java 17` |
| Simulations folder | `<repository>/src/gatling/java` |
| Filters | Enable filters; use `$RECORDING_APP_URL_REGEX` as the only AllowList entry |

Keep **Follow Redirects**, **Infer HTML resources**, **Automatic Referers**, and **Remove cache headers** enabled. Leave **Save & check response bodies** disabled.

Gatling Recorder's Java 17 template is correct: this project compiles and runs it with Java 21.

## 3. Record the journey

After clicking **Start!**, first confirm Gatling displays its recording screen with a **Stop** control. Then launch the same Chrome profile through the local proxy:

```bash
open -na "Google Chrome" --args \
  --user-data-dir=/private/tmp/gatling-recording-chrome \
  --proxy-server=http://127.0.0.1:8000
```

Open `$RECORDING_APP_URL` in this proxied Chrome window—not the earlier non-proxied authentication window—and verify that application requests appear in Gatling before beginning the journey.

For a search journey, record only the AppReg actions: navigate to the relevant search/list page, provide an agreed stable search criterion, submit it, and open a result only if that is part of the workflow. Do not record Entra sign-in again; the curated SSO implementation already handles it. Do not edit or save records while recording a read-only search flow.

Click **Stop**, review the request list, and save the recording. Gatling writes the generated source under `src/gatling/java/recorded/`.

## 4. Assimilate the recording

The `recorded/` directory is intentionally ignored by Git. Do not commit the generated recording.

Instead:

1. Identify the minimum AppReg HTTP requests needed for the business action.
2. Move the cleaned implementation into the appropriate class under `src/gatling/simulations/scenarios/`.
3. Replace recorded IDs, dates, text, anti-forgery tokens and search values with session variables, feeders or generated safe data.
4. Add useful checks, including expected status codes and identifiers/results required by later steps.
5. Create a dedicated one-user proof simulation under `src/gatling/simulations/simulations/`.
6. Run `./gradlew gatlingClasses`, then run the proof against an approved environment.

See `AGENTS.md` for the project's modular scenario, test-data and safety conventions.
