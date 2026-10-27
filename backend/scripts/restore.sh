#!/usr/bin/env bash
# Restauration d'une sauvegarde MaintiFlow. ATTENTION : remplace la base et les fichiers actuels.
# Usage : scripts/restore.sh backups/maintiflow-AAAAMMJJ-HHMMSS [--yes]
set -euo pipefail
cd "$(dirname "$0")/.."
[ -f .env ] && set -a && . ./.env && set +a
SRC="${1:?Indiquez le dossier de sauvegarde}"
( cd "$SRC" && sha256sum -c SHA256SUMS ) || { echo "Sauvegarde corrompue : arrêt."; exit 1; }
if [ "${2:-}" != "--yes" ]; then
  read -r -p "Remplacer la base et les fichiers actuels par $SRC ? (oui/non) " ok
  [ "$ok" = "oui" ] || exit 1
fi
PGURL="${DATABASE_URL/postgresql+psycopg:/postgresql:}"
STORAGE="${STORAGE_DIR:-./storage}"
# Repart d'un schéma vide puis restaure (les protections anti-suppression sont des triggers par ligne :
# DROP SCHEMA ne les déclenche pas).
psql "$PGURL" -v ON_ERROR_STOP=1 -q -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"
pg_restore --no-owner --exit-on-error --dbname "$PGURL" "$SRC/database.dump"
if [ -d "$STORAGE" ]; then mv "$STORAGE" "$STORAGE.before-restore-$(date +%s)"; fi
mkdir -p "$(dirname "$STORAGE")"
tar -xzf "$SRC/storage.tar.gz" -C "$(dirname "$STORAGE")"
echo "Restauration terminée depuis $SRC. Vérifiez : curl -s http://localhost:8000/health"
