#!/usr/bin/env bash

set -euo pipefail

host="${APPREG_PT_HOST:-appreg.test.apps.hmcts.net}"
subscription="${APPREG_PT_LOGS_SUBSCRIPTION:-DCD-CNP-DEV}"
resource_group="${APPREG_PT_LOGS_RESOURCE_GROUP:-oms-automation}"
workspace="${APPREG_PT_LOGS_WORKSPACE:-hmcts-nonprod}"

log_file=""
output_file=""
self_check=false

usage() {
  cat <<'EOF'
Usage: ./scripts/analyse_pt.sh JENKINS_LOG [--output FILE]

Prove whether HTTP 502/504 responses in a downloaded Jenkins console log were
Application Gateway timeouts. Output defaults to JENKINS_LOG-analysis.txt.

Environment overrides:
  APPREG_PT_HOST                 Default: appreg.test.apps.hmcts.net
  APPREG_PT_LOGS_SUBSCRIPTION    Default: DCD-CNP-DEV
  APPREG_PT_LOGS_RESOURCE_GROUP  Default: oms-automation
  APPREG_PT_LOGS_WORKSPACE       Default: hmcts-nonprod

Options: --output FILE, --self-check, --help

Requires az, curl, jq and a current Azure login with read access to the Log
Analytics workspace. The script is standalone and does not call another script.

The gateway's ERRORINFO_UPSTREAM_TIMED_OUT is preferred. Older gateway rows
that omit error_info require a unique match, no backend status, and matching
Gatling/gateway durations of at least 20 seconds.
EOF
}

# Parse the input artifact and optional output path.
while (( $# > 0 )); do
  case "$1" in
    --output)
      [[ $# -ge 2 ]] || { printf 'Missing value for --output\n' >&2; exit 1; }
      output_file="$2"
      shift 2
      ;;
    --self-check)
      self_check=true
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    --*)
      printf 'Unknown option: %s\n' "$1" >&2
      exit 1
      ;;
    *)
      [[ -z "${log_file}" ]] || { printf 'Supply one Jenkins log only\n' >&2; exit 1; }
      log_file="$1"
      shift
      ;;
  esac
done

# Convert self-contained Gatling diagnostic rows into KQL datatable rows.
extract_failures() {
  awk '
    /APPREG_HTTP_DIAGNOSTIC/ && / status=(502|504) / && / responseMillis=/ {
      for (i = 1; i <= NF; i++) {
        split($i, field, "=")
        value[field[1]] = field[2]
      }
      if (value["path"] != "" && value["phase"] != "") {
        printf "datetime(%s), \"%s\", \"%s\", \"%s\", \"%s\", \"%s\", \"%s\", %s, %s\n", \
          value["timestamp"], value["traceId"], value["phase"], value["action"], \
          value["actor"], value["request"], value["path"], value["status"], value["responseMillis"]
      }
      delete value
    }
  ' "$1"
}

# Test the parser without requiring Azure access.
if [[ "${self_check}" == true ]]; then
  fixture="$(mktemp "${TMPDIR:-/tmp}/appreg-analyse-pt.XXXXXX")"
  trap 'rm -f "${fixture}"' EXIT
  printf '%s\n' 'APPREG_HTTP_DIAGNOSTIC timestamp=2026-09-04T10:00:20Z traceId=11111111111111111111111111111111 phase=measured action=search actor=1 request=Search path=/application-lists status=504 responseMillis=20000 retryAfter=-' > "${fixture}"
  parsed="$(extract_failures "${fixture}")"
  [[ "${parsed}" == *'"search", "1", "Search", "/application-lists", 504, 20000'* ]]
  printf 'AppReg performance timeout analysis self-check passed\n'
  exit 0
fi

# Validate the local prerequisites and input artifact.
for command in az curl jq awk; do
  command -v "${command}" >/dev/null 2>&1 || {
    printf 'Required command not found: %s\n' "${command}" >&2
    exit 1
  }
done
[[ -f "${log_file}" ]] || { printf 'Jenkins log not found: %s\n' "${log_file}" >&2; exit 1; }
[[ "${host}" =~ ^[A-Za-z0-9.-]+$ ]] || { printf 'Invalid APPREG_PT_HOST\n' >&2; exit 1; }
[[ -n "${output_file}" ]] || output_file="${log_file%.*}-analysis.txt"

# Reject logs produced before the enhanced diagnostic format was available.
failure_rows="$(extract_failures "${log_file}")"
failure_count="$(printf '%s\n' "${failure_rows}" | awk 'NF { count++ } END { print count + 0 }')"
observed_count="$(awk '/APPREG_HTTP_DIAGNOSTIC/ && / status=(502|504) / { count++ } END { print count + 0 }' "${log_file}")"
if (( observed_count != failure_count )); then
  printf 'The Jenkins log contains 502/504 rows without the required phase, path or duration metadata; it predates this analyser.\n' >&2
  exit 2
fi
if (( failure_count == 0 )); then
  {
    printf '========== APPREG PERFORMANCE TIMEOUT ANALYSIS ==========\n'
    printf 'Source Gatling log: %s\n' "${log_file}"
    printf 'Gatling 502/504 attempts: 0\nNo gateway query was required.\n'
    printf '=========================================================\n'
  } > "${output_file}"
  printf '%s\n' "${output_file}"
  exit 0
fi

# Query only gateway rows close to the Gatling failures.
failure_rows="$(printf '%s\n' "${failure_rows}" | sed '$!s/$/,/')"
workspace_id="$(AZURE_CORE_ONLY_SHOW_ERRORS=true az monitor log-analytics workspace show \
  --subscription "${subscription}" --resource-group "${resource_group}" \
  --workspace-name "${workspace}" --query customerId --output tsv)"
access_token="$(AZURE_CORE_ONLY_SHOW_ERRORS=true az account get-access-token \
  --resource 'https://api.loganalytics.io' --query accessToken --output tsv)"

query="let failures = datatable(
  gatlingTimestamp:datetime, traceId:string, phase:string, action:string,
  actor:string, request:string, path:string, status:int, responseMillis:long
)[
${failure_rows}
];
let queryStart = toscalar(failures | summarize min(gatlingTimestamp)) - 10s;
let queryEnd = toscalar(failures | summarize max(gatlingTimestamp)) + 10s;
let candidates = materialize(
  failures
  | join kind=leftouter (
      AzureDiagnostics
      | where Category == \"ApplicationGatewayAccessLog\" and host_s == \"${host}\"
      | where TimeGenerated between (queryStart .. queryEnd)
      | where toint(httpStatus_d) in (502, 504)
      | project gatewayTimestamp=TimeGenerated,
          path=tostring(split(originalRequestUriWithArgs_s, \"?\")[0]),
          status=toint(httpStatus_d),
          serverStatus=tostring(column_ifexists(\"serverStatus_s\", \"\")),
          errorInfo=tostring(column_ifexists(\"error_info_s\", \"\")),
          totalSeconds=todouble(column_ifexists(\"timeTaken_d\", 0.0)),
          serverLatencySeconds=todouble(column_ifexists(\"serverResponseLatency_s\", 0.0)),
          serverRouted=tostring(column_ifexists(\"serverRouted_s\", \"\")),
          resource=Resource
    ) on path, status
  | extend timestampDeltaMs=abs(datetime_diff('millisecond', gatewayTimestamp, gatlingTimestamp)),
      durationDeltaMs=abs(toint(totalSeconds * 1000) - responseMillis)
  | where isnull(gatewayTimestamp) or (timestampDeltaMs <= 5000 and durationDeltaMs <= 2000)
);
let matches = materialize(candidates
| summarize candidateCount=countif(isnotempty(gatewayTimestamp)),
    arg_min(timestampDeltaMs, gatewayTimestamp, serverStatus, errorInfo, totalSeconds,
      serverLatencySeconds, serverRouted, resource)
  by gatlingTimestamp, traceId, phase, action, actor, request, path, status, responseMillis
);
matches
| join kind=leftouter (
    matches
    | where candidateCount == 1
    | summarize gatewayUseCount=count() by gatewayTimestamp, path, status
  ) on gatewayTimestamp, path, status
| extend timeoutReasonRecorded=errorInfo == \"ERRORINFO_UPSTREAM_TIMED_OUT\",
    timeoutFingerprint=isempty(errorInfo) and totalSeconds >= 20
      and serverLatencySeconds >= 20
| extend evidence=iff(candidateCount == 1 and gatewayUseCount == 1
      and serverStatus in (\"\", \"-\", \"0\")
      and (timeoutReasonRecorded or timeoutFingerprint),
    \"PROVEN_GATEWAY_TIMEOUT\", \"NOT_PROVEN\"),
    proof=case(timeoutReasonRecorded, \"gateway_error_info\",
      timeoutFingerprint, \"no_backend_status_and_20s_gateway_wait\", \"none\")
| project gatlingTimestamp, traceId, phase, action, actor, request, path, status,
    responseMillis, evidence, proof, candidateCount, gatewayUseCount,
    gatewayTimestamp, serverStatus,
    errorInfo, totalSeconds, serverLatencySeconds, serverRouted, resource
| order by gatlingTimestamp asc"

# Call Log Analytics directly; do not depend on another repository script.
temporary_json="$(mktemp "${TMPDIR:-/tmp}/appreg-analyse-pt.XXXXXX")"
trap 'rm -f "${temporary_json}"' EXIT
payload="$(jq -cn --arg query "${query}" '{query: $query}')"
curl -sSf -H "Authorization: Bearer ${access_token}" \
  -H 'Content-Type: application/json' -X POST \
  "https://api.loganalytics.io/v1/workspaces/${workspace_id}/query" \
  --data "${payload}" --output "${temporary_json}"
jq -e '.tables[0].rows | type == "array"' "${temporary_json}" >/dev/null

proven_count="$(jq '[.tables[0] as $table
  | ($table.columns | map(.name) | index("evidence")) as $evidence
  | $table.rows[] | select(.[$evidence] == "PROVEN_GATEWAY_TIMEOUT")] | length' "${temporary_json}")"

# Render the exact evidence behind every classification.
{
  printf '========== APPREG PERFORMANCE TIMEOUT ANALYSIS ==========\n'
  printf 'Source Gatling log: %s\n' "${log_file}"
  printf 'Application Gateway host: %s\n' "${host}"
  printf 'Evidence rule: one path/status match within 5s and 2s duration tolerance, no backend status, and either ERRORINFO_UPSTREAM_TIMED_OUT or a gateway wait of at least 20s where older logs omit error_info.\n'
  printf 'Gatling 502/504 attempts: %s\n' "${failure_count}"
  printf 'Proven gateway-timeout attempts: %s\n' "${proven_count}"
  printf 'Unproven attempts: %s\n\n' "$((failure_count - proven_count))"
  jq -r '.tables[0] as $table
    | ($table.columns | map(.name)) as $columns
    | $table.rows[]
    | [range(0; $columns | length) as $index | "\($columns[$index])=\(.[$index] // "-")"]
    | join(" ")' "${temporary_json}"
  printf '\nRaw Gatling failures remain unchanged; only PROVEN_GATEWAY_TIMEOUT rows are eligible for infrastructure exclusion.\n'
  printf '=========================================================\n'
} > "${output_file}"

printf '%s\n' "${output_file}"
