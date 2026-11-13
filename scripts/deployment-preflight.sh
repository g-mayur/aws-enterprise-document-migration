#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BOOTSTRAP_DIR="$ROOT_DIR/infrastructure/bootstrap"
WORKLOAD_DIR="$ROOT_DIR/infrastructure"

usage() {
  cat <<'USAGE'
Usage:
  WORKER_IMAGE=<account>.dkr.ecr.<region>.amazonaws.com/<repo>:sha-<commit> \
    AWS_REGION=<region> scripts/deployment-preflight.sh

Read-only deployment gate. Requires:
  - AWS CLI v2 authenticated to the intended deployment account
  - Terraform initialized for infrastructure/ and infrastructure/bootstrap/
  - An applied bootstrap state with network and ECR outputs
  - A published immutable worker image using a sha- tag

The script does not apply Terraform, publish images, create AWS resources,
or start a migration. It checks the current AWS identity, Terraform
configuration, bootstrap outputs, private subnet placement, security group,
and ECR image availability.
USAGE
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "Required command not found: $1"
}

for command in aws terraform python3; do
  require_command "$command"
done

[[ -n "${WORKER_IMAGE:-}" ]] || fail "Set WORKER_IMAGE to the full immutable ECR image URI."
[[ -d "$BOOTSTRAP_DIR" && -d "$WORKLOAD_DIR" ]] || fail "Run this script from a complete repository checkout."

BOOTSTRAP_REGION="$(terraform -chdir="$BOOTSTRAP_DIR" output -raw aws_region 2>/dev/null)" \
  || fail "Bootstrap state/output unavailable. Apply the approved bootstrap stack first."
AWS_REGION="${AWS_REGION:-$BOOTSTRAP_REGION}"
[[ "$AWS_REGION" == "$BOOTSTRAP_REGION" ]] || fail "AWS_REGION ($AWS_REGION) does not match bootstrap Region ($BOOTSTRAP_REGION)."
export AWS_REGION

IDENTITY="$(aws sts get-caller-identity --output json)" || fail "AWS credentials are unavailable or expired."
ACCOUNT_ID="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["Account"])' <<< "$IDENTITY")"
CALLER_ARN="$(python3 -c 'import json,sys; print(json.load(sys.stdin)["Arn"])' <<< "$IDENTITY")"

terraform -chdir="$WORKLOAD_DIR" fmt -check -recursive >/dev/null || fail "Workload Terraform formatting check failed."
terraform -chdir="$WORKLOAD_DIR" validate -no-color
terraform -chdir="$BOOTSTRAP_DIR" fmt -check -recursive >/dev/null || fail "Bootstrap Terraform formatting check failed."
terraform -chdir="$BOOTSTRAP_DIR" validate -no-color

SUBNET_JSON="$(terraform -chdir="$BOOTSTRAP_DIR" output -json worker_subnet_ids)" || fail "Missing worker_subnet_ids bootstrap output."
SG_JSON="$(terraform -chdir="$BOOTSTRAP_DIR" output -json worker_security_group_ids)" || fail "Missing worker_security_group_ids bootstrap output."
ECR_URI="$(terraform -chdir="$BOOTSTRAP_DIR" output -raw ecr_repository_url)" || fail "Missing ecr_repository_url bootstrap output."

mapfile -t SUBNET_IDS < <(python3 -c 'import json,sys; v=json.load(sys.stdin); assert isinstance(v,list) and len(v)>=2 and all(isinstance(x,str) and x for x in v); print("\n".join(v))' <<< "$SUBNET_JSON") \
  || fail "Bootstrap worker_subnet_ids must be a non-empty list with at least two subnet IDs."
mapfile -t SECURITY_GROUP_IDS < <(python3 -c 'import json,sys; v=json.load(sys.stdin); assert isinstance(v,list) and len(v)>=1 and all(isinstance(x,str) and x for x in v); print("\n".join(v))' <<< "$SG_JSON") \
  || fail "Bootstrap worker_security_group_ids must be a non-empty list."
[[ "${#SUBNET_IDS[@]}" -ge 2 ]] || fail "At least two private worker subnets are required."
[[ "${#SECURITY_GROUP_IDS[@]}" -ge 1 ]] || fail "At least one worker security group is required."

EXPECTED_PREFIX="$ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/"
[[ "$ECR_URI" == "$EXPECTED_PREFIX"* ]] || fail "Bootstrap ECR repository does not match the active AWS account and Region."
[[ "$WORKER_IMAGE" == "$ECR_URI:sha-"* ]] || fail "WORKER_IMAGE must use the bootstrap repository and an immutable sha- tag."

REPOSITORY_NAME="${ECR_URI#*/}"
IMAGE_TAG="${WORKER_IMAGE##*:}"
TAG_MUTABILITY="$(aws ecr describe-repositories --repository-names "$REPOSITORY_NAME" --region "$AWS_REGION" --query 'repositories[0].imageTagMutability' --output text)" \
  || fail "Cannot inspect ECR repository $REPOSITORY_NAME."
[[ "$TAG_MUTABILITY" == "IMMUTABLE" ]] || fail "ECR repository tag mutability is '$TAG_MUTABILITY', expected IMMUTABLE."

IMAGE_DIGEST="$(aws ecr describe-images --repository-name "$REPOSITORY_NAME" --image-ids "imageTag=$IMAGE_TAG" --region "$AWS_REGION" --query 'imageDetails[0].imageDigest' --output text)" \
  || fail "Worker image tag $IMAGE_TAG was not found in ECR."
[[ -n "$IMAGE_DIGEST" && "$IMAGE_DIGEST" != "None" ]] || fail "ECR did not return a digest for $WORKER_IMAGE."

SUBNET_DETAILS="$(aws ec2 describe-subnets --subnet-ids "${SUBNET_IDS[@]}" --region "$AWS_REGION" --output json)" \
  || fail "Cannot describe the bootstrap worker subnets."
python3 -c '
import json,sys
items=json.load(sys.stdin).get("Subnets", [])
if len(items) < 2 or any(x.get("State") != "available" or x.get("MapPublicIpOnLaunch") for x in items):
    raise SystemExit("Worker subnets must be available and must not auto-assign public IPs.")
if len({x.get("AvailabilityZone") for x in items}) < 2:
    raise SystemExit("Worker subnets must span at least two Availability Zones.")
' <<< "$SUBNET_DETAILS" || fail "Worker subnet checks failed."

aws ec2 describe-security-groups --group-ids "${SECURITY_GROUP_IDS[@]}" --region "$AWS_REGION" --output json >/dev/null \
  || fail "One or more bootstrap worker security groups are unavailable."

printf '\nDeployment preflight passed.\n'
printf 'AWS account: %s\n' "$ACCOUNT_ID"
printf 'AWS caller:  %s\n' "$CALLER_ARN"
printf 'AWS Region:  %s\n' "$AWS_REGION"
printf 'Subnets:     %s\n' "${#SUBNET_IDS[@]}"
printf 'Security groups: %s\n' "${#SECURITY_GROUP_IDS[@]}"
printf 'Worker image: %s\n' "$WORKER_IMAGE"
printf 'Image digest: %s\n' "$IMAGE_DIGEST"
printf '\nNo AWS resources were created or changed.\n'
