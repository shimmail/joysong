#!/usr/bin/env bash
set -Eeuo pipefail

: "${ALIYUN_REGION:?ALIYUN_REGION is required}"
: "${ALIYUN_ECS_INSTANCE_ID:?ALIYUN_ECS_INSTANCE_ID is required}"

operation="${1:-}"
environment="${2:-}"
[[ "$environment" == "uat" || "$environment" == "prod" ]] || {
  printf 'environment must be uat or prod\n' >&2
  exit 2
}

case "$operation" in
  deploy)
    (($# == 6)) || { printf 'deploy requires environment, tag, commit, URL and SHA\n' >&2; exit 2; }
    printf -v remote_command 'sudo -n /usr/local/sbin/joysong-release-dispatch deploy %q %q %q %q %q' \
      "$environment" "$3" "$4" "$5" "$6"
    ;;
  rollback)
    (($# == 3)) || { printf 'rollback requires environment and tag\n' >&2; exit 2; }
    printf -v remote_command 'sudo -n /usr/local/sbin/joysong-release-dispatch rollback %q %q' \
      "$environment" "$3"
    ;;
  rollback-previous)
    (($# == 2)) || { printf 'rollback-previous requires environment\n' >&2; exit 2; }
    printf -v remote_command 'sudo -n /usr/local/sbin/joysong-release-dispatch rollback-previous %q' \
      "$environment"
    ;;
  recover-public-failure)
    (($# == 3)) || { printf 'recover-public-failure requires environment and expected tag\n' >&2; exit 2; }
    printf -v remote_command 'sudo -n /usr/local/sbin/joysong-release-dispatch recover-public-failure %q %q' \
      "$environment" "$3"
    ;;
  verify-uat-isolation)
    [[ "$environment" == "uat" && $# == 2 ]] || { printf 'verify-uat-isolation requires the UAT environment\n' >&2; exit 2; }
    printf -v remote_command 'sudo -n /usr/local/sbin/joysong-release-dispatch verify-uat-isolation uat'
    ;;
  preflight)
    if [[ "$environment" == "prod" ]]; then
      (($# == 4)) || { printf 'production preflight requires RDS instance ID and connection host\n' >&2; exit 2; }
      printf -v remote_command 'sudo -n /usr/local/sbin/joysong-release-dispatch preflight %q %q %q' \
        "$environment" "$3" "$4"
    else
      (($# == 2)) || { printf 'UAT preflight requires only environment\n' >&2; exit 2; }
      printf -v remote_command 'sudo -n /usr/local/sbin/joysong-release-dispatch preflight %q' \
        "$environment"
    fi
    ;;
  *)
    printf 'unsupported Cloud Assistant operation\n' >&2
    exit 2
    ;;
esac

command_content="$(printf '%s' "$remote_command" | base64 -w 0)"
client_token="joysong-${GITHUB_RUN_ID:-manual}-${GITHUB_RUN_ATTEMPT:-1}-${operation}-${environment}"
client_token="${client_token:0:64}"

response="$(aliyun ecs RunCommand \
  --RegionId "$ALIYUN_REGION" \
  --Type RunShellScript \
  --ContentEncoding Base64 \
  --CommandContent "$command_content" \
  --InstanceId.1 "$ALIYUN_ECS_INSTANCE_ID" \
  --Username joysong-deploy \
  --Timeout 1200 \
  --KeepCommand false \
  --ClientToken "$client_token")"
invoke_id="$(jq -er '.InvokeId' <<<"$response")"
printf 'Cloud Assistant invocation started: %s\n' "$invoke_id"

deadline=$((SECONDS + 1200))
while ((SECONDS < deadline)); do
  result="$(aliyun ecs DescribeInvocationResults \
    --RegionId "$ALIYUN_REGION" \
    --InstanceId "$ALIYUN_ECS_INSTANCE_ID" \
    --InvokeId "$invoke_id")"
  status="$(jq -r '.Invocation.InvocationResults.InvocationResult[0].InvocationStatus // "Pending"' <<<"$result")"
  case "$status" in
    Success)
      encoded_output="$(jq -r '.Invocation.InvocationResults.InvocationResult[0].Output // ""' <<<"$result")"
      if [[ -n "$encoded_output" ]]; then
        printf '%s' "$encoded_output" | base64 --decode || true
        printf '\n'
      fi
      exit_code="$(jq -r '.Invocation.InvocationResults.InvocationResult[0].ExitCode // 0' <<<"$result")"
      [[ "$exit_code" == "0" ]] || { printf 'remote command exited %s\n' "$exit_code" >&2; exit 1; }
      printf 'Cloud Assistant invocation succeeded.\n'
      exit 0
      ;;
    Failed|Stopped|Stopping)
      error_info="$(jq -r '.Invocation.InvocationResults.InvocationResult[0].ErrorInfo // "unknown error"' <<<"$result")"
      printf 'Cloud Assistant invocation failed (%s): %s\n' "$status" "$error_info" >&2
      exit 1
      ;;
    Pending|Running|Scheduled) sleep 5 ;;
    *) printf 'Cloud Assistant returned unexpected status: %s\n' "$status" >&2; exit 1 ;;
  esac
done

printf 'Cloud Assistant invocation timed out.\n' >&2
exit 1
