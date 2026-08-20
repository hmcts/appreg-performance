# Performance-test implementation plan

## Objective

All runnable modes use one common authentication routine that accepts a `targetUsers` argument. A virtual user authenticates once and keeps that same session for its later work; no mode performs a separate preflight login followed by a second workload login.

Authentication is separate from measured business work. A successfully authenticated user waits on the landing page/session until the configured target is reached, then the same authenticated sessions begin their assigned work.

## Framework design

- Model business capabilities as reusable Gatling `ChainBuilder` actions. Executable simulations compose those actions with authentication, data allocation, pacing and a load profile.
- Keep proof simulations separate from the feeder-backed `AppRegWorkloadSimulation`. Every new reusable business-action scenario must include a matching one-user `*ProofSimulation` before it is composed into a workload.
- Setup traffic may be composed where required, but it must not silently become part of a measured business transaction.
- Cross-cutting concerns—SSO, headers, environment configuration and data helpers—remain shared utilities. Business actions remain independent of browser-test page objects.

## Data and workload design

- Create, update, result, upload, move and bulk actions are destructive. Each workload action consumes one isolated allocation; queue feeders contain only the exact deterministic schedule rows and never reuse mutable records.
- The seed stage reserves proof rows before producing workload feeders. The workload consumes only `build/workload-data/` after that reservation and trimming step.
- The deterministic workload applies the agreed action mix at one planned action per authenticated user per minute. Reporting remains a separate non-destructive benchmark until a reporting workload expectation is agreed.
- The application-list update and close actions remain separate allocations: ordinary open mutable lists for updates and fully close-ready lists for closure.

## Common authentication routine

1. Start primary account authentication at the configured controlled rate.
2. Confirm the landing page and authenticated session for each account.
3. Hold successful sessions idle while the routine works towards `targetUsers`.
4. For each failed primary authentication, use one spare account only while the target is short.
5. If a spare fails and the target remains short, retry the corresponding failed primary account once.
6. For HTTP 429, log the sanitized `Retry-After` value and wait at least that long before the applicable recovery phase. Other authentication failures receive the same bounded pool/one-retry policy without repeated looping.
7. If the target cannot be reached, fail before any destructive action begins.
8. When the target is reached, release only the authenticated sessions into their deterministic action or proof flow. Failed accounts never consume an action/data allocation.

The routine must not log passwords, tokens, cookies, client secrets, or response bodies. Recovery queues remain workspace-only and contain only approved account identifiers and safe scheduling metadata.

## Modes

| Mode | `targetUsers` | Work after authentication | Data and safety |
| --- | ---: | --- | --- |
| `framework-proof` | 1 | Run the focused one-user proof suite using the authenticated session. | Each proof retains its dedicated canonical data and destructive safeguards. |
| `application-diagnostic` | Requested `MAX_USERS`, capped at 500 | Run the bounded deterministic workload for `RUN_DURATION_MINUTES`. | The deterministic action plan and queue feeders must be sized to the same target. |
| `performance` | 500 | Run the fixed 70-minute, 35,000-action deterministic workload. | Use isolated feeder allocations; no record reuse; release work only after all 500 sessions are authenticated. |

`framework-proof` is always the default mode and always has a one-user authentication target. Do not make a code or PR push trigger a multi-user authentication stage, diagnostic workload, or performance workload; larger runs must remain an explicit operator-selected mode.

## Current controlled settings

- Primary authentication, spares and retries: one login per second.
- Workload release: after the target is reached, retained sessions begin business work evenly over two minutes; this avoids an all-at-once application burst.
- Spare pool: ten approved accounts beyond the selected primary target.
- Recovery: one spare replacement per failed primary account, then at most one retry if the target is still short.
- Performance workload action pace: one planned action per authenticated user per minute.
- Functional business-operation failures remain test failures. Authentication recovery must not weaken those checks.

## Completion and validation

The implementation is complete only when every runnable mode uses the common routine, proves that it does not perform a second workload login, and passes `./gradlew gatlingClasses` plus the smallest relevant one-user proof. Validate a bounded diagnostic run before a 500-user performance run.

The workload must fail for functional HTTP errors even though it has no response-time NFR threshold. Do not change pipeline behaviour, workload weights or success thresholds without documenting the reason and validating the outcome.

Update this plan only with user permission when the agreed design changes.
