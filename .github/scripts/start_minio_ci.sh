#!/usr/bin/env bash
set -euo pipefail
umask 077
# Git Bash must pass container paths such as /tmp to Docker unchanged.
export MSYS_NO_PATHCONV=1

container=td-issue32-ci-minio
bucket=td-issue32-ci-private
preservation_bucket=td-issue40-ci-preservation
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
head_user="td32head$(openssl rand -hex 8)"
head_password="$(openssl rand -hex 24)"
preservation_verifier_user="td40verify$(openssl rand -hex 8)"
preservation_verifier_password="$(openssl rand -hex 24)"
preservation_reader_user="td40read$(openssl rand -hex 8)"
preservation_reader_password="$(openssl rand -hex 24)"
preservation_delete_user="td40delete$(openssl rand -hex 8)"
preservation_delete_password="$(openssl rand -hex 24)"

if [[ -n "${GITHUB_ENV:-}" ]]; then
  printf '::add-mask::%s\n' "$root_user"
  printf '::add-mask::%s\n' "$root_password"
  printf '::add-mask::%s\n' "$app_user"
  printf '::add-mask::%s\n' "$app_password"
  printf '::add-mask::%s\n' "$head_user"
  printf '::add-mask::%s\n' "$head_password"
  printf '::add-mask::%s\n' "$preservation_verifier_user"
  printf '::add-mask::%s\n' "$preservation_verifier_password"
  printf '::add-mask::%s\n' "$preservation_reader_user"
  printf '::add-mask::%s\n' "$preservation_reader_password"
  printf '::add-mask::%s\n' "$preservation_delete_user"
  printf '::add-mask::%s\n' "$preservation_delete_password"
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
docker cp .github/scripts/minio-ci-head-policy.json "$container:/tmp/minio-ci-head-policy.json" >/dev/null
docker cp .github/scripts/minio-w07-verifier-policy.json \
  "$container:/tmp/minio-w07-verifier-policy.json" >/dev/null
docker cp .github/scripts/minio-w07-reader-policy.json \
  "$container:/tmp/minio-w07-reader-policy.json" >/dev/null
docker cp .github/scripts/minio-w07-delete-policy.json \
  "$container:/tmp/minio-w07-delete-policy.json" >/dev/null
docker exec "$container" mc mb "ci/$bucket" >/dev/null
docker exec "$container" mc anonymous set none "ci/$bucket" >/dev/null
docker exec "$container" mc mb --with-lock "ci/$preservation_bucket" >/dev/null
docker exec "$container" mc anonymous set none "ci/$preservation_bucket" >/dev/null
docker exec "$container" mc admin policy create ci td-issue32-ci /tmp/minio-ci-policy.json >/dev/null
docker exec "$container" mc admin user add ci "$app_user" "$app_password" >/dev/null
docker exec "$container" mc admin policy attach ci td-issue32-ci --user "$app_user" >/dev/null
docker exec "$container" mc admin policy create ci td-issue32-ci-head /tmp/minio-ci-head-policy.json >/dev/null
docker exec "$container" mc admin user add ci "$head_user" "$head_password" >/dev/null
docker exec "$container" mc admin policy attach ci td-issue32-ci-head --user "$head_user" >/dev/null
docker exec "$container" mc admin policy create ci td-issue40-ci-verifier \
  /tmp/minio-w07-verifier-policy.json >/dev/null
docker exec "$container" mc admin user add ci \
  "$preservation_verifier_user" "$preservation_verifier_password" >/dev/null
docker exec "$container" mc admin policy attach ci td-issue40-ci-verifier \
  --user "$preservation_verifier_user" >/dev/null
docker exec "$container" mc admin policy create ci td-issue40-ci-reader \
  /tmp/minio-w07-reader-policy.json >/dev/null
docker exec "$container" mc admin user add ci \
  "$preservation_reader_user" "$preservation_reader_password" >/dev/null
docker exec "$container" mc admin policy attach ci td-issue40-ci-reader \
  --user "$preservation_reader_user" >/dev/null
docker exec "$container" mc admin policy create ci td-issue40-ci-delete \
  /tmp/minio-w07-delete-policy.json >/dev/null
docker exec "$container" mc admin user add ci \
  "$preservation_delete_user" "$preservation_delete_password" >/dev/null
docker exec "$container" mc admin policy attach ci td-issue40-ci-delete \
  --user "$preservation_delete_user" >/dev/null

{
  printf 'TD_ISSUE32_MINIO_ENDPOINT=http://127.0.0.1:19000\n'
  printf 'TD_ISSUE32_MINIO_BUCKET=%s\n' "$bucket"
  printf 'TD_ISSUE32_MINIO_ACCESS_KEY=%s\n' "$app_user"
  printf 'TD_ISSUE32_MINIO_SECRET_KEY=%s\n' "$app_password"
  printf 'TD_ISSUE32_MINIO_HEAD_ACCESS_KEY=%s\n' "$head_user"
  printf 'TD_ISSUE32_MINIO_HEAD_SECRET_KEY=%s\n' "$head_password"
  printf 'TD_ISSUE32_MINIO_ADMIN_ACCESS_KEY=%s\n' "$root_user"
  printf 'TD_ISSUE32_MINIO_ADMIN_SECRET_KEY=%s\n' "$root_password"
  printf 'TD_ISSUE40_MINIO_BUCKET=%s\n' "$preservation_bucket"
  printf 'TD_ISSUE40_MINIO_PROFILE=W07_CI_OBJECT_LOCK\n'
  printf 'TD_ISSUE40_MINIO_VERIFIER_ACCESS_KEY=%s\n' "$preservation_verifier_user"
  printf 'TD_ISSUE40_MINIO_VERIFIER_SECRET_KEY=%s\n' "$preservation_verifier_password"
  printf 'TD_ISSUE40_MINIO_READER_ACCESS_KEY=%s\n' "$preservation_reader_user"
  printf 'TD_ISSUE40_MINIO_READER_SECRET_KEY=%s\n' "$preservation_reader_password"
  printf 'TD_ISSUE40_MINIO_DELETE_ACCESS_KEY=%s\n' "$preservation_delete_user"
  printf 'TD_ISSUE40_MINIO_DELETE_SECRET_KEY=%s\n' "$preservation_delete_password"
} >> "$output_file"

initialized=true
echo 'Private W02 bucket and isolated W07 Object Lock bucket are ready'
