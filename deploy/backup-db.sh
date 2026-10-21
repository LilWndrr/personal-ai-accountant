#!/usr/bin/env bash
# Dumps the compose Postgres and uploads it to S3. Meant for the EC2 host's crontab:
#   0 3 * * * bash /home/ubuntu/personal-ai-accountant/deploy/backup-db.sh >> /home/ubuntu/backup.log 2>&1
# Needs BACKUP_BUCKET in .env, the AWS CLI, and an instance role allowed to
# s3:PutObject on that bucket (no access keys are stored on the server).
set -euo pipefail

cd "$(dirname "$0")/.."

# Read only the keys we need instead of sourcing .env, so passwords with
# shell-special characters can't break (or be executed by) this script.
env_value() { grep -E "^$1=" .env | tail -n 1 | cut -d= -f2- | tr -d '\r'; }
POSTGRES_DB=$(env_value POSTGRES_DB)
POSTGRES_USER=$(env_value POSTGRES_USER)
BACKUP_BUCKET=$(env_value BACKUP_BUCKET)
: "${BACKUP_BUCKET:?Set BACKUP_BUCKET in .env}"

# The dump holds users' financial data: keep the temp file private and always remove it.
umask 077
stamp=$(date -u +%Y-%m-%dT%H%MZ)
file=$(mktemp --suffix=.sql.gz "/tmp/${POSTGRES_DB}-${stamp}-XXXXXX")
trap 'rm -f "$file"' EXIT

docker compose exec -T postgres pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --no-owner | gzip > "$file"
aws s3 cp "$file" "s3://${BACKUP_BUCKET}/postgres/${stamp}.sql.gz" --only-show-errors

echo "$(date -u) backup ok: s3://${BACKUP_BUCKET}/postgres/${stamp}.sql.gz ($(du -h "$file" | cut -f1))"
