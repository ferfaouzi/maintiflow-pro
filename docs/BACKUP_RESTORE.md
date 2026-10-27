# Sauvegarde et restauration

Une sauvegarde = base PostgreSQL (`database.dump`, format custom) + fichiers (photos, PDF archivés : `storage.tar.gz`)
+ `SHA256SUMS`. Les deux sont nécessaires : la base référence les fichiers et contient leur empreinte.

## Sauvegarder

```bash
cd backend
./scripts/backup.sh /chemin/sauvegardes        # conserve les 30 dernières
```

Installation Docker (pilote) : depuis la racine du projet, `deploy/backup-docker.sh` (pg_dump exécuté dans le
conteneur PostgreSQL, donc toujours de la même version que le serveur) et `deploy/restore-docker.sh <dossier>`.

Planification quotidienne (crontab de l'hôte, 2 h du matin) :
```
0 2 * * * cd /opt/maintiflow && deploy/backup-docker.sh >> /var/log/maintiflow-backup.log 2>&1
```
Copier ensuite `deploy/backups/` vers un second support (NAS, disque externe) selon la politique de l'usine.

## Restaurer

```bash
cd backend
./scripts/restore.sh /chemin/sauvegardes/maintiflow-AAAAMMJJ-HHMMSS      # demande confirmation "oui"
python -m scripts.verify_restore      # nb de bons, événements, audit, intégrité des PDF archivés
```

Le script vérifie d'abord les empreintes SHA-256 de la sauvegarde, recrée le schéma, restaure la base, puis
remplace le dossier de fichiers (l'ancien est conservé sous `storage.before-restore-<horodatage>`).
Arrêter l'API pendant la restauration en production (`docker compose stop api`), la redémarrer ensuite.

## Test réalisé (27/10/2026, environnement de développement, scripts `backend/scripts/*.sh`)

Les scripts Docker suivent la même procédure mais n'ont pas pu être exécutés ici (pas de démon Docker).

1. Base de démonstration : 4 bons, 17 événements, 21 entrées d'audit, 1 bon clôturé avec PDF archivé.
2. `backup.sh` → sauvegarde créée.
3. Incident simulé : suppression du dossier `storage/` et vidage de la table des bons.
   `verify_restore` → `bons=0 événements=0`.
4. `restore.sh --yes` → `verify_restore` → `bons=4 événements=17 audit=21 clôturés=1 pdf_invalides=0`,
   `/health` OK.

À refaire sur le serveur pilote avant la mise en service, puis une fois par trimestre.
