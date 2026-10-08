#!/usr/bin/env bash
# Deploys IMAGE to the EC2 instance through AWS Systems Manager Run Command and waits for the result.
# Runs in GitHub Actions after configure-aws-credentials. No inbound SSH port or stored key is needed.
#
# Required environment: IMAGE, EC2_INSTANCE_ID, REGISTRY_USER, REGISTRY_TOKEN.
set -euo pipefail

: "${IMAGE:?}" "${EC2_INSTANCE_ID:?}" "${REGISTRY_USER:?}" "${REGISTRY_TOKEN:?}"

here="$(cd "$(dirname "$0")" && pwd)"
script="$(mktemp)"
trap 'rm -f "$script"' EXIT

# Compressed to keep the command well under the Run Command parameter size limit.
compose_b64="$(gzip -9c "$here/docker-compose.ec2.yml" | base64 | tr -d '\n')"
sed -e "s|__IMAGE__|$IMAGE|" \
    -e "s|__COMPOSE_B64__|$compose_b64|" \
    -e "s|__REGISTRY_USER__|$REGISTRY_USER|" \
    -e "s|__REGISTRY_TOKEN__|$REGISTRY_TOKEN|" \
    "$here/remote-deploy.sh" > "$script"

command_id="$(aws ssm send-command \
  --instance-ids "$EC2_INSTANCE_ID" \
  --document-name AWS-RunShellScript \
  --comment "Deploy $IMAGE" \
  --parameters "$(jq -n --rawfile s "$script" '{commands: [$s], executionTimeout: ["900"]}')" \
  --query Command.CommandId --output text)"
echo "SSM command $command_id sent"

# Poll for up to 15 minutes.
for _ in $(seq 1 180); do
  sleep 5
  status="$(aws ssm get-command-invocation --command-id "$command_id" --instance-id "$EC2_INSTANCE_ID" \
    --query Status --output text 2>/dev/null || echo Pending)"
  case "$status" in
    Pending|InProgress|Delayed|Cancelling) continue ;;
  esac

  aws ssm get-command-invocation --command-id "$command_id" --instance-id "$EC2_INSTANCE_ID" \
    --query '[StandardOutputContent, StandardErrorContent]' --output text
  if [ "$status" = Success ]; then
    exit 0
  fi
  echo "Deploy finished with status $status" >&2
  exit 1
done

echo "Timed out waiting for SSM command $command_id" >&2
exit 1
