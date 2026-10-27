#!/usr/bin/env bash
# Sauvegarde de l'installation Docker : pg_dump exécuté dans le conteneur db (même version que le serveur)
# + archive du volume de fichiers. Usage : deploy/backup-docker.sh [dossier]   (depuis la racine du projet)
set -euo pipefail
DC="docker compose -f deploy/docker-compose.yml --env-file deploy/.env"
DEST="${1:-deploy/backups}"
OUT="$DEST/maintiflow-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$OUT"
$DC exec -T db pg_dump -U maintiflow --format=custom --no-owner maintiflow > "$OUT/database.dump"
$DC exec -T api tar -czf - -C /data storage > "$OUT/storage.tar.gz"
( cd "$OUT" && sha256sum database.dump storage.tar.gz > SHA256SUMS )
echo "Sauvegarde : $OUT"
ls -1dt "$DEST"/maintiflow-* | tail -n +31 | xargs -r rm -rf
