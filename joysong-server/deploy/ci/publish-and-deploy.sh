#!/usr/bin/env bash
set -Eeuo pipefail

: "${ALIYUN_REGION:?ALIYUN_REGION is required}"
: "${RELEASE_BUCKET:?RELEASE_BUCKET is required}"
: "${RELEASE_PREFIX:?RELEASE_PREFIX is required}"
: "${ALIBABA_CLOUD_ACCESS_KEY_ID:?OIDC AccessKey ID is required}"
: "${ALIBABA_CLOUD_ACCESS_KEY_SECRET:?OIDC AccessKey secret is required}"
: "${ALIBABA_CLOUD_SECURITY_TOKEN:?OIDC security token is required}"

environment="${1:-}"
tag="${2:-}"
commit="${3:-}"
bundle="${4:-}"
[[ "$environment" == "uat" || "$environment" == "prod" ]] || {
  printf 'environment must be uat or prod\n' >&2
  exit 2
}
if [[ "$environment" == "uat" ]]; then
  [[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+-uat\.[0-9]+$ ]] || { printf 'invalid UAT tag\n' >&2; exit 2; }
else
  [[ "$tag" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || { printf 'invalid production tag\n' >&2; exit 2; }
fi
[[ "$commit" =~ ^[0-9a-f]{40}$ ]] || { printf 'commit must be a full lowercase Git SHA\n' >&2; exit 2; }
[[ "$RELEASE_BUCKET" =~ ^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$ ]] || { printf 'invalid release bucket\n' >&2; exit 2; }
[[ "$RELEASE_PREFIX" =~ ^[A-Za-z0-9][A-Za-z0-9._/-]*$ ]] || { printf 'invalid release prefix\n' >&2; exit 2; }
[[ "$RELEASE_PREFIX" != *..* && "$RELEASE_PREFIX" != */ ]] || { printf 'unsafe release prefix\n' >&2; exit 2; }
[[ -f "$bundle" && ! -L "$bundle" ]] || { printf 'release bundle must be a regular file\n' >&2; exit 2; }
(( $(stat -c '%s' "$bundle") < 5 * 1024 * 1024 * 1024 )) || { printf 'release bundle exceeds PutObject limit\n' >&2; exit 2; }

endpoint="oss-${ALIYUN_REGION}.aliyuncs.com"
object_key="${RELEASE_PREFIX}/${environment}/${tag}/$(basename "$bundle")"
object="oss://${RELEASE_BUCKET}/${object_key}"
bundle_path="$(realpath "$bundle")"
bundle_sha="$(sha256sum "$bundle" | awk '{print $1}')"

# ossutil uses its own environment names. Values remain the short-lived OIDC
# credentials provisioned by the pinned GitHub Action.
export OSS_ACCESS_KEY_ID="$ALIBABA_CLOUD_ACCESS_KEY_ID"
export OSS_ACCESS_KEY_SECRET="$ALIBABA_CLOUD_ACCESS_KEY_SECRET"
export OSS_SESSION_TOKEN="$ALIBABA_CLOUD_SECURITY_TOKEN"
export OSS_REGION="$ALIYUN_REGION"
export OSS_ENDPOINT="https://${endpoint}"

acl_json="$(ossutil api get-bucket-acl --bucket "$RELEASE_BUCKET" --output-format json)"
bucket_acl="$(jq -er '[.. | strings | select(. == "private" or . == "public-read" or . == "public-read-write")] | unique | if length == 1 then .[0] else error("missing or ambiguous bucket ACL") end' <<<"$acl_json")"
[[ "$bucket_acl" == "private" ]] || {
  printf 'release bucket ACL must be private; acl=%s\n' "$bucket_acl" >&2
  exit 2
}

# x-oss-forbid-overwrite is not a useful immutability primitive on a versioned
# bucket because another version can become current. Require never-enabled
# versioning, then perform one atomic conditional PutObject (no HEAD/PUT race).
versioning_json="$(ossutil api get-bucket-versioning --bucket "$RELEASE_BUCKET" --output-format json)"
versioning_status="$(jq -er '[.. | objects | .Status? // .status? // empty] | unique | if length == 0 then "Disabled" elif length == 1 then .[0] else error("ambiguous versioning status") end' <<<"$versioning_json")"
[[ "$versioning_status" == "Disabled" ]] || {
  printf 'private release bucket versioning must never be enabled or suspended; status=%s\n' "$versioning_status" >&2
  exit 2
}
ossutil api put-object \
  --bucket "$RELEASE_BUCKET" \
  --key "$object_key" \
  --body "file://${bundle_path}" \
  --forbid-overwrite true >/dev/null

signed_url="$(aliyun oss sign "$object" --timeout 1800 --region "$ALIYUN_REGION" --endpoint "$endpoint" |
  awk '/^https:\/\// {print; exit}')"
[[ -n "$signed_url" ]] || { printf 'failed to create a signed release URL\n' >&2; exit 2; }
remote_copy="$(mktemp "${RUNNER_TEMP:?RUNNER_TEMP is required}/joysong-oss-verify.XXXXXX")"
trap 'rm -f "$remote_copy"' EXIT
curl --fail --silent --show-error --location --proto '=https' --tlsv1.2 \
  --connect-timeout 10 --max-time 600 --output "$remote_copy" "$signed_url"
printf '%s  %s\n' "$bundle_sha" "$remote_copy" | sha256sum --check --status || {
  printf 'uploaded immutable OSS object does not match the local release bundle\n' >&2
  exit 2
}

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
bash "$script_dir/cloud-assistant.sh" deploy "$environment" "$tag" "$commit" "$signed_url" "$bundle_sha"
