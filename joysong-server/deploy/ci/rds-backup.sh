#!/usr/bin/env bash
set -Eeuo pipefail

: "${ALIYUN_REGION:?ALIYUN_REGION is required}"
: "${ALIYUN_PROD_RDS_INSTANCE_ID:?ALIYUN_PROD_RDS_INSTANCE_ID is required}"

[[ "$ALIYUN_PROD_RDS_INSTANCE_ID" =~ ^rm-[A-Za-z0-9]+$ ]] || {
  printf 'ALIYUN_PROD_RDS_INSTANCE_ID has an unsafe format\n' >&2
  exit 2
}
attribute_response="$(aliyun rds DescribeDBInstanceAttribute \
  --RegionId "$ALIYUN_REGION" \
  --DBInstanceId "$ALIYUN_PROD_RDS_INSTANCE_ID")"
actual_instance_id="$(jq -er '.Items.DBInstanceAttribute | if length == 1 then .[0].DBInstanceId else error("expected exactly one RDS instance") end' <<<"$attribute_response")"
connection_host="$(jq -er '.Items.DBInstanceAttribute[0].ConnectionString' <<<"$attribute_response")"
engine="$(jq -er '.Items.DBInstanceAttribute[0].Engine' <<<"$attribute_response")"
status="$(jq -er '.Items.DBInstanceAttribute[0].DBInstanceStatus' <<<"$attribute_response")"
[[ "$actual_instance_id" == "$ALIYUN_PROD_RDS_INSTANCE_ID" ]] || { printf 'RDS instance identity mismatch\n' >&2; exit 2; }
[[ "$connection_host" =~ ^[a-z0-9][a-z0-9.-]*\.rds\.aliyuncs\.com$ ]] || { printf 'RDS internal endpoint is unsafe\n' >&2; exit 2; }
[[ "$engine" == "MySQL" && "$status" == "Running" ]] || { printf 'production RDS must be a running MySQL instance\n' >&2; exit 2; }

if [[ "${1:-}" == "resolve-host" ]]; then
  (($# == 1)) || { printf 'resolve-host takes no additional arguments\n' >&2; exit 2; }
  printf '%s\n' "$connection_host"
  exit 0
fi
(($# == 0)) || { printf 'unsupported RDS backup operation\n' >&2; exit 2; }
printf 'Validated production RDS binding candidate: instance=%s host=%s\n' \
  "$actual_instance_id" "$connection_host"

response="$(aliyun rds CreateBackup \
  --RegionId "$ALIYUN_REGION" \
  --DBInstanceId "$ALIYUN_PROD_RDS_INSTANCE_ID")"
job_id="$(jq -er '.BackupJobId' <<<"$response")"
printf 'RDS backup job started: %s\n' "$job_id"

deadline=$((SECONDS + 3000))
while ((SECONDS < deadline)); do
  response="$(aliyun rds DescribeBackupTasks \
    --RegionId "$ALIYUN_REGION" \
    --DBInstanceId "$ALIYUN_PROD_RDS_INSTANCE_ID" \
    --BackupJobId "$job_id")"
  status="$(jq -r '.Items.BackupJob[0].BackupStatus // "NoStart"' <<<"$response")"
  progress="$(jq -r '.Items.BackupJob[0].Process // "0"' <<<"$response")"
  printf 'RDS backup status: %s (%s%%)\n' "$status" "$progress"
  case "$status" in
    Finished)
      backup_id="$(jq -er '.Items.BackupJob[0].BackupId' <<<"$response")"
      printf 'RDS backup completed: %s\n' "$backup_id"
      printf 'RDS_BACKUP_ID=%s\n' "$backup_id"
      exit 0
      ;;
    Failed) printf 'RDS backup failed.\n' >&2; exit 1 ;;
    NoStart|Checking|Preparing|Waiting|Uploading) sleep 15 ;;
    *) printf 'Unexpected RDS backup status: %s\n' "$status" >&2; exit 1 ;;
  esac
done

printf 'RDS backup did not finish within the credential window.\n' >&2
exit 1
