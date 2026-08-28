#!/usr/bin/env bash

# Shared defaults consumed by the entrypoint scripts after sourcing this file.
# shellcheck disable=SC2034
APPREG_SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APPREG_REPO_ROOT="$(cd "${APPREG_SCRIPT_DIR}/.." && pwd)"
APPREG_DEFAULT_ENV_FILE="${APPREG_REPO_ROOT}/.env.local"
APPREG_DEFAULT_TEST_URL="https://appreg.test.apps.hmcts.net"
APPREG_DEFAULT_TEST_USER_EMAIL="appreg-001@dev.platform.hmcts.net"
APPREG_DEFAULT_TENANT_ID="e575f663-b30a-4786-89ad-319842dfe853"
APPREG_DEFAULT_SSO_USER_AGENT="Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36"
APPREG_DEFAULT_ACCOUNT_TEMPLATE="appreg-{index}@dev.platform.hmcts.net"

appreg_require_env() {
  local name="$1"
  if [[ -z "${!name:-}" ]]; then
    echo "Missing required environment variable: ${name}" >&2
    exit 1
  fi
}

appreg_derive_test_url() {
  local candidate="$1"
  if [[ -z "${candidate}" ]]; then
    return 1
  fi

  if [[ "${candidate}" =~ ^https://appreg-api\.([^.]+)\.platform\.hmcts\.net/?$ ]]; then
    printf 'https://appreg.%s.apps.hmcts.net' "${BASH_REMATCH[1]}"
    return 0
  fi

  printf '%s' "${candidate%/}"
}

appreg_source_if_present() {
  local file="$1"
  if [[ -f "${file}" ]]; then
    set -a
    # shellcheck disable=SC1090
    source "${file}"
    set +a
  fi
}

appreg_load_runtime_inputs() {
  local env_file="$1"
  local config_file="$2"
  local test_url_override="$3"

  if [[ -n "${env_file}" ]]; then
    appreg_source_if_present "${env_file}"
  fi

  if [[ -n "${config_file}" ]]; then
    appreg_source_if_present "${config_file}"
  fi

  if [[ -n "${test_url_override}" ]]; then
    TEST_URL="$(appreg_derive_test_url "${test_url_override}")"
    export TEST_URL
  elif [[ -n "${BASE_URL:-}" && -z "${TEST_URL:-}" ]]; then
    TEST_URL="$(appreg_derive_test_url "${BASE_URL}")"
    export TEST_URL
  fi
}

appreg_apply_sso_defaults() {
  export TEST_URL="${TEST_URL:-${APPREG_DEFAULT_TEST_URL}}"
  export APPREG_USER_AGENT="${APPREG_USER_AGENT:-${APPREG_DEFAULT_SSO_USER_AGENT}}"
  export TEST_USER_EMAIL="${TEST_USER_EMAIL:-${USER_NAME:-${APPREG_DEFAULT_TEST_USER_EMAIL}}}"
  export TEST_USERS_PASSWORD="${TEST_USERS_PASSWORD:-${PASSWORD:-}}"
  export TENANT_ID="${TENANT_ID:-${APPREG_TENANT_ID:-${APPREG_DEFAULT_TENANT_ID}}}"
  appreg_require_env "TEST_USERS_PASSWORD"
  appreg_require_env "TENANT_ID"
}

appreg_print_sso_context() {
  echo "  TEST_URL: ${TEST_URL}"
  echo "  TEST_USER_EMAIL: ${TEST_USER_EMAIL}"
  echo "  TENANT_ID: ${TENANT_ID}"
  echo "  APPREG_USER_AGENT: ${APPREG_USER_AGENT}"
}
