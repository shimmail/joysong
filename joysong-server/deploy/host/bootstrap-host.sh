#!/usr/bin/env bash
set -Eeuo pipefail
umask 027

release_url_host="${1:-}"
web_user="${2:-www-data}"
web_group="${3:-$web_user}"
[[ "$EUID" -eq 0 ]] || { printf 'run as root\n' >&2; exit 2; }
[[ "$release_url_host" =~ ^[A-Za-z0-9.-]+$ ]] || {
  printf 'usage: %s <private-release-bucket-host> [nginx-user] [nginx-group]\n' "$0" >&2
  exit 2
}
id "$web_user" >/dev/null 2>&1 || { printf 'Nginx user does not exist: %s\n' "$web_user" >&2; exit 2; }
getent group "$web_group" >/dev/null || { printf 'Nginx group does not exist: %s\n' "$web_group" >&2; exit 2; }

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
deploy_root="$(cd "$script_dir/.." && pwd)"

command -v java >/dev/null || { printf 'Java 17 must be installed first\n' >&2; exit 2; }
command -v python3 >/dev/null || { printf 'python3 must be installed first\n' >&2; exit 2; }
command -v curl >/dev/null || { printf 'curl must be installed first\n' >&2; exit 2; }
command -v flock >/dev/null || { printf 'flock must be installed first\n' >&2; exit 2; }
command -v visudo >/dev/null || { printf 'sudo/visudo must be installed first\n' >&2; exit 2; }
command -v ss >/dev/null || { printf 'iproute/ss must be installed first\n' >&2; exit 2; }
command -v mysqldump >/dev/null || { printf 'MySQL client/mysqldump must be installed first\n' >&2; exit 2; }
command -v gzip >/dev/null || { printf 'gzip must be installed first\n' >&2; exit 2; }
command -v rsync >/dev/null || { printf 'rsync must be installed first\n' >&2; exit 2; }

id joysong-deploy >/dev/null 2>&1 || useradd --system --create-home --shell /usr/sbin/nologin joysong-deploy
for environment in uat prod; do
  service_user="joysong-${environment}"
  release_group="joysong-${environment}-release"
  getent group "$release_group" >/dev/null || groupadd --system "$release_group"
  getent group "$service_user" >/dev/null || groupadd --system "$service_user"
  id "$service_user" >/dev/null 2>&1 || useradd --system --home-dir "/var/lib/joysong/$environment" \
    --create-home --gid "$service_user" --shell /usr/sbin/nologin "$service_user"
  usermod -a -G "$service_user" "$service_user"
  usermod -a -G "$release_group" "$service_user"
  usermod -a -G "$release_group" "$web_user"
  install -d -o root -g "$release_group" -m 0750 "/opt/joysong/$environment"
  install -d -o root -g "$release_group" -m 0750 "/opt/joysong/$environment/releases"
  # The application must not be able to replace its fixed top-level data
  # directories. Both the service and Nginx can traverse via the release group;
  # only the intended child directories are writable/readable as configured.
  install -d -o root -g "$release_group" -m 0710 "/var/lib/joysong/$environment"
  install -d -o "$service_user" -g "$web_group" -m 2750 "/var/lib/joysong/$environment/uploads"
  install -d -o "$service_user" -g "$service_user" -m 0700 "/var/lib/joysong/$environment/upload-staging"
  install -d -o "$service_user" -g "$service_user" -m 0700 "/var/lib/joysong/$environment/private"
  install -d -o root -g "$service_user" -m 0750 "/etc/joysong/$environment"
  if [[ ! -e "/etc/joysong/$environment/joysong.env" ]]; then
    install -o root -g "$service_user" -m 0640 /dev/null "/etc/joysong/$environment/joysong.env"
  fi
  if [[ "$environment" == "prod" && ! -e /etc/joysong/prod/rds-binding.env ]]; then
    install -o root -g root -m 0600 /dev/null /etc/joysong/prod/rds-binding.env
  fi
done

install -d -o root -g root -m 0755 /usr/local/lib/joysong /usr/local/sbin /etc/joysong /var/lib/joysong-maintenance
install -o root -g root -m 0755 "$deploy_root/host/deploy-release.sh" /usr/local/lib/joysong/deploy-release.sh
install -o root -g root -m 0755 "$deploy_root/host/validate-runtime-config.sh" /usr/local/lib/joysong/validate-runtime-config.sh
install -o root -g root -m 0755 "$deploy_root/host/backup-runtime-state.sh" /usr/local/lib/joysong/backup-runtime-state.sh
install -o root -g root -m 0755 "$deploy_root/host/restore-uat-backup-to-new-db.sh" /usr/local/sbin/joysong-restore-uat-backup
install -o root -g root -m 0755 "$deploy_root/host/joysong-release-dispatch" /usr/local/sbin/joysong-release-dispatch
install -o root -g root -m 0755 "$deploy_root/host/capture-existing-uat-baseline.sh" /usr/local/sbin/joysong-capture-uat-baseline
install -o root -g root -m 0755 "$deploy_root/host/finalize-uat-cutover.sh" /usr/local/sbin/joysong-finalize-uat-cutover
install -o root -g root -m 0644 "$deploy_root/systemd/joysong@.service" /etc/systemd/system/joysong@.service

{
  printf 'RELEASE_URL_HOST=%s\n' "$release_url_host"
  printf 'WEB_USER=%s\n' "$web_user"
  printf 'WEB_GROUP=%s\n' "$web_group"
} >/etc/joysong/deploy.env
chown root:root /etc/joysong/deploy.env
chmod 0600 /etc/joysong/deploy.env

sudoers_tmp="$(mktemp)"
trap 'rm -f "$sudoers_tmp"' EXIT
printf 'joysong-deploy ALL=(root) NOPASSWD: /usr/local/sbin/joysong-release-dispatch *\n' >"$sudoers_tmp"
chmod 0440 "$sudoers_tmp"
visudo -cf "$sudoers_tmp"
install -o root -g root -m 0440 "$sudoers_tmp" /etc/sudoers.d/joysong-deploy

systemctl daemon-reload
printf '%s\n' 'Host deployment assets installed.'
printf '%s\n' 'Populate /etc/joysong/{uat,prod}/joysong.env before enabling services.'
printf '%s\n' 'Restart Nginx after validating its configuration so new group membership takes effect.'
printf '%s\n' 'Production remains disabled until /etc/joysong/prod/DEPLOY_ENABLED is created manually.'
printf '%s\n' 'Production also requires root-owned /etc/joysong/prod/rds-binding.env with exact RDS_INSTANCE_ID and RDS_CONNECTION_HOST.'
