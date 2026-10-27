#!/usr/bin/env bash
# Sauvegarde complète MaintiFlow : base PostgreSQL (format custom) + fichiers (photos, PDF archivés).
# Usage : scripts/backup.sh [dossier_destination]      (lit DATABASE_URL et STORAGE_DIR depuis .env)
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f .env ] && set -a && . ./.env && set +a
DEST="${1:-./backups}"
STAMP="$(date +%Y%m%d-%H%M%S)"
OUT="$DEST/maintiflow-$STAMP"
PGURL="${DATABASE_URL/postgresql+psycopg:/postgresql:}"
mkdir -p "$OUT"
pg_dump --format=custom --no-owner --file "$OUT/database.dump" "$PGURL"
tar -czf "$OUT/storage.tar.gz" -C "$(dirname "${STORAGE_DIR:-./storage}")" "$(basename "${STORAGE_DIR:-./storage}")"
( cd "$OUT" && sha256sum database.dump storage.tar.gz > SHA256SUMS )
echo "Sauvegarde : $OUT"
# Rétention : conserve les 30 dernières sauvegardes
ls -1dt "$DEST"/maintiflow-* 2>/dev/null | tail -n +31 | xargs -r rm -rf
