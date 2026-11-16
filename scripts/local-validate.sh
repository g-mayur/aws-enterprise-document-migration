#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_DIR="$ROOT_DIR/application"
DYNAMODB_CONTAINER_NAME="aws-doc-migration-dynamodb-local"
STARTED_DYNAMODB_CONTAINER=""

cleanup() {
  if [[ -n "$STARTED_DYNAMODB_CONTAINER" ]]; then
    docker rm -f "$STARTED_DYNAMODB_CONTAINER" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

command -v java >/dev/null 2>&1 || fail "Java 17 or newer is required. Install a JDK and ensure java is on PATH."
command -v mvn >/dev/null 2>&1 || fail "Maven 3.9 or newer is required. Install Maven and ensure mvn is on PATH."

JAVA_VERSION="$(java -version 2>&1 | awk -F '[".]' '/version/ { print $2; exit }')"
[[ "$JAVA_VERSION" =~ ^[0-9]+$ ]] || fail "Could not determine the installed Java version."
(( JAVA_VERSION >= 17 )) || fail "Java 17 or newer is required; detected Java $JAVA_VERSION."

printf '\n==> Running Maven clean verify\n'
if [[ -n "${DYNAMODB_LOCAL_ENDPOINT:-}" ]]; then
  printf 'Using supplied DYNAMODB_LOCAL_ENDPOINT: %s\n' "$DYNAMODB_LOCAL_ENDPOINT"
elif (echo > /dev/tcp/127.0.0.1/8000) >/dev/null 2>&1; then
  export DYNAMODB_LOCAL_ENDPOINT="http://127.0.0.1:8000"
  printf 'Using DynamoDB Local already listening on port 8000.\n'
elif command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
  printf 'Starting temporary DynamoDB Local container for integration tests...\n'
  docker run --detach --rm --name "$DYNAMODB_CONTAINER_NAME" \
    -p 127.0.0.1:8000:8000 amazon/dynamodb-local:2.5.2 \
    -jar DynamoDBLocal.jar -sharedDb -inMemory >/dev/null
  STARTED_DYNAMODB_CONTAINER="$DYNAMODB_CONTAINER_NAME"
  export DYNAMODB_LOCAL_ENDPOINT="http://127.0.0.1:8000"

  ready=0
  for attempt in {1..30}; do
    if (echo > /dev/tcp/127.0.0.1/8000) >/dev/null 2>&1; then
      ready=1
      break
    fi
    sleep 1
  done
  (( ready == 1 )) || fail "DynamoDB Local did not become ready on port 8000. Check Docker logs."
else
  printf 'Docker/DynamoDB Local not available; DynamoDB integration tests will be skipped by JUnit.\n'
  printf 'Install Docker or set DYNAMODB_LOCAL_ENDPOINT to run those tests.\n'
fi

mvn --batch-mode --no-transfer-progress -f "$APP_DIR/pom.xml" clean verify

printf '\n==> Checking Terraform formatting (when Terraform is installed)\n'
if command -v terraform >/dev/null 2>&1; then
  terraform -chdir="$ROOT_DIR/infrastructure" fmt -check -recursive
else
  printf 'Terraform not installed; skipping Terraform formatting check.\n'
fi

printf '\nLocal validation completed successfully. No AWS account or AWS credentials were used.\n'
