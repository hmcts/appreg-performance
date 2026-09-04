#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck disable=SC1091
source "${SCRIPT_DIR}/lib/appreg-common.sh"
DEFAULT_SIMULATION="simulations.ApplicationListSearchProofSimulation"

auth_mode="sso-login"
env_file="${APPREG_TEST_ENV_FILE:-${APPREG_DEFAULT_ENV_FILE}}"
config_file="${APPREG_TEST_CONFIG_FILE:-}"
simulation=""
search_description=""
test_url_override=""

usage() {
  cat <<EOF
Usage: ./scripts/run-smoketest.sh [options]

Run a local AppReg Gatling smoke test from ${APPREG_REPO_ROOT}.

By default this runs the read-only authenticated search proof:
  ${DEFAULT_SIMULATION}

Options:
  --env-file FILE             Source local environment values from FILE.
                              Default: ${APPREG_DEFAULT_ENV_FILE}
  --config FILE               Source runtime values from FILE.
                              Default: none
  --simulation CLASS          Override the Gatling simulation class.
  --search-description VALUE  Override the Application List search term.
  --test-url URL              Override TEST_URL for this run.
  --unauthenticated           Run the legacy one-user AppRegSimulation without SSO.
  --help                      Show this help and exit.

Environment:
  TEST_URL, TEST_USER_EMAIL, TEST_USERS_PASSWORD, TENANT_ID
  APPREG_TEST_ACCOUNT_TEMPLATE, APPREG_TEST_USER_PASSWORD, APPREG_TENANT_ID
  APPREG_USER_AGENT

Notes:
  - The wrapper sources ${APPREG_DEFAULT_ENV_FILE} automatically when present.
  - The wrapper does not source appreg-api/tests/appreg_test.config.ini unless
    you pass --config or set APPREG_TEST_CONFIG_FILE.
  - Local one-user SSO defaults to:
    TEST_URL=${APPREG_DEFAULT_TEST_URL}
    TEST_USER_EMAIL=${APPREG_DEFAULT_TEST_USER_EMAIL}
  - Local SSO defaults to the AppReg tenant:
    ${APPREG_DEFAULT_TENANT_ID}
  - Local SSO also defaults to a browser-like User-Agent to complete Entra's
    "sso_reload" bootstrap step.
  - If you want a multi-user run, supply APPREG_TEST_ACCOUNT_TEMPLATE explicitly.
  - The default smoke test is read-only. Destructive proofs should stay separate.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --env-file)
      env_file="${2:?Missing value for --env-file}"
      shift 2
      ;;
    --config)
      config_file="${2:?Missing value for --config}"
      shift 2
      ;;
    --simulation)
      simulation="${2:?Missing value for --simulation}"
      shift 2
      ;;
    --search-description)
      search_description="${2:?Missing value for --search-description}"
      shift 2
      ;;
    --test-url)
      test_url_override="${2:?Missing value for --test-url}"
      shift 2
      ;;
    --unauthenticated)
      auth_mode="none"
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

appreg_load_runtime_inputs "${env_file}" "${config_file}" "${test_url_override}"

if [[ "${auth_mode}" == "sso-login" ]]; then
  appreg_apply_sso_defaults
else
  export TEST_URL="${TEST_URL:-${APPREG_DEFAULT_TEST_URL}}"
fi

if [[ -z "${simulation}" ]]; then
  if [[ "${auth_mode}" == "sso-login" ]]; then
    simulation="${DEFAULT_SIMULATION}"
  else
    simulation="simulations.AppRegSimulation"
  fi
fi

gradle_args=("gatlingRun")

if [[ "${auth_mode}" == "sso-login" ]]; then
  gradle_args+=("-DauthMode=sso-login")
else
  gradle_args+=("-Ddebug=on" "-DauthMode=none")
fi

if [[ -n "${search_description}" ]]; then
  gradle_args+=("-DappRegApplicationListSearchDescription=${search_description}")
fi

gradle_args+=("--simulation" "${simulation}")

echo "Running AppReg smoke test"
echo "  Auth mode: ${auth_mode}"
echo "  Simulation: ${simulation}"
if [[ "${auth_mode}" == "sso-login" ]]; then
  appreg_print_sso_context
else
  echo "  TEST_URL: ${TEST_URL}"
fi

cd "${APPREG_REPO_ROOT}"
./gradlew "${gradle_args[@]}"
