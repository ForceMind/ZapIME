#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-only
set -euo pipefail
cd "$(dirname "$0")"
file=${1:?Usage: restore.sh /absolute/path/backup.dump}
[[ $file == /* && -f "$file" ]] || { echo 'Use an existing absolute backup path' >&2; exit 1; }
# 只接受全新数据库，拒绝覆盖已有表；不使用 --clean 或 DROP。
tables=$(docker compose exec -T db psql -U zapime -d zapime -Atc "SELECT count(*) FROM pg_tables WHERE schemaname='public'")
[[ $tables == 0 ]] || { echo 'Restore requires a fresh empty database' >&2; exit 1; }
docker compose exec -T db pg_restore -U zapime -d zapime --single-transaction --exit-on-error --no-owner --no-acl < "$file"
echo 'Database restored; clients still need their own encryption/recovery material.'
