#!/usr/bin/env bash
# Restauration Docker. ATTENTION : remplace la base et les fichiers. Usage : deploy/restore-docker.sh <dossier> [--yes]
set -euo pipefail
DC="docker compose -f deploy/docker-compose.yml --env-file deploy/.env"
SRC="${1:?dossier de sauvegarde}"
( cd "$SRC" && sha256sum -c SHA256SUMS )
if [ "${2:-}" != "--yes" ]; then read -r -p "Remplacer les données actuelles ? (oui/non) " ok; [ "$ok" = "oui" ] || exit 1; fi
$DC stop api
$DC exec -T db psql -U maintiflow -d maintiflow -v ON_ERROR_STOP=1 -q -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"
$DC exec -T db pg_restore -U maintiflow --no-owner --exit-on-error -d maintiflow < "$SRC/database.dump"
$DC run --rm --no-deps -T api sh -c "rm -rf /data/storage/* && tar -xzf - -C /data" < "$SRC/storage.tar.gz"
$DC start api
$DC exec -T api python -m scripts.verify_restore
