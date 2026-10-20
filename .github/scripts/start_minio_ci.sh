#!/usr/bin/env bash
set -euo pipefail
umask 077
# Git Bash must pass container paths such as /tmp to Docker unchanged.
export MSYS_NO_PATHCONV=1

container=td-issue32-ci-minio
bucket=td-issue32-ci-private
image=td-issue32-minio-ci:local
output_file=${GITHUB_ENV:-${TD_ISSUE32_CI_ENV_FILE:-}}

if [[ -z "$output_file" ]]; then
  echo 'GITHUB_ENV or TD_ISSUE32_CI_ENV_FILE is required' >&2
  exit 1
fi
if [[ -z "${GITHUB_ENV:-}" && -e "$output_file" ]]; then
  echo 'Refusing to overwrite an existing local credential file' >&2
  exit 1
fi
if docker container inspect "$container" >/dev/null 2>&1; then
  echo 'The dedicated MinIO CI container name is already in use' >&2
  exit 1
fi

root_user="td32root$(openssl rand -hex 8)"
root_password="$(openssl rand -hex 24)"
app_user="td32app$(openssl rand -hex 8)"
app_password="$(openssl rand -hex 24)"

if [[ -n "${GITHUB_ENV:-}" ]]; then
  printf '::add-mask::%s\n' "$root_user"
  printf '::add-mask::%s\n' "$root_password"
  printf '::add-mask::%s\n' "$app_user"
  printf '::add-mask::%s\n' "$app_password"
fi

started=false
initialized=false
cleanup_on_exit() {
  if [[ "$started" == true && "$initialized" != true ]]; then
    docker rm -f "$container" >/dev/null 2>&1 || true
  fi
  if [[ "$initialized" != true && -z "${GITHUB_ENV:-}" ]]; then
    rm -f -- "$output_file"
  fi
}
trap cleanup_on_exit EXIT

docker run --detach --rm --name "$container" \
  --publish 127.0.0.1:19000:9000 \
  --env "MINIO_ROOT_USER=$root_user" \
  --env "MINIO_ROOT_PASSWORD=$root_password" \
  "$image" server /data --address ':9000' >/dev/null
started=true

ready=false
for attempt in {1..30}; do
  if docker exec "$container" mc alias set ci http://127.0.0.1:9000 \
      "$root_user" "$root_password" >/dev/null 2>&1; then
    ready=true
    break
  fi
  sleep 2
done
if [[ "$ready" != true ]]; then
  echo 'MinIO did not become ready' >&2
  exit 1
fi

docker cp .github/scripts/minio-ci-policy.json "$container:/tmp/minio-ci-policy.json" >/dev/null
docker exec "$container" mc mb "ci/$bucket" >/dev/null
docker exec "$container" mc anonymous set none "ci/$bucket" >/dev/null
docker exec "$container" mc admin policy create ci td-issue32-ci /tmp/minio-ci-policy.json >/dev/null
docker exec "$container" mc admin user add ci "$app_user" "$app_password" >/dev/null
docker exec "$container" mc admin policy attach ci td-issue32-ci --user "$app_user" >/dev/null

{
  printf 'TD_ISSUE32_MINIO_ENDPOINT=http://127.0.0.1:19000\n'
  printf 'TD_ISSUE32_MINIO_BUCKET=%s\n' "$bucket"
  printf 'TD_ISSUE32_MINIO_ACCESS_KEY=%s\n' "$app_user"
  printf 'TD_ISSUE32_MINIO_SECRET_KEY=%s\n' "$app_password"
  printf 'TD_ISSUE32_MINIO_ADMIN_ACCESS_KEY=%s\n' "$root_user"
  printf 'TD_ISSUE32_MINIO_ADMIN_SECRET_KEY=%s\n' "$root_password"
} >> "$output_file"

initialized=true
echo 'Private MinIO CI bucket and least-privilege test account are ready'
