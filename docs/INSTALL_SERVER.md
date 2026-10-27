# Installation du serveur

## A. Poste de développement (testé)

Prérequis : Python 3.12+, PostgreSQL 14+.

```bash
sudo -u postgres psql -c "CREATE USER maintiflow WITH PASSWORD 'motdepasse_dev' CREATEDB;"
sudo -u postgres createdb -O maintiflow maintiflow
sudo -u postgres createdb -O maintiflow maintiflow_test      # pour les tests

cd backend
python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements-dev.txt
cp .env.example .env
#  DATABASE_URL=postgresql+psycopg://maintiflow:motdepasse_dev@localhost:5432/maintiflow
#  JWT_SECRET=$(python -c "import secrets;print(secrets.token_urlsafe(48))")
alembic upgrade head
python -m scripts.seed_demo                 # données fictives + demo_credentials.txt (chmod 600)
uvicorn app.main:app --host 0.0.0.0 --port 8000
TEST_DATABASE_URL=postgresql+psycopg://maintiflow:motdepasse_dev@localhost:5432/maintiflow_test pytest -q
python -m scripts.demo_scenario             # 4 bons de démo, dont 1 clôturé avec PDF dans demo_output/
```

Émulateur Android (version debug) : adresse du serveur `http://10.0.2.2:8000`.
Téléphone sur le même Wi-Fi (debug) : `http://<IP-du-PC>:8000`.

## B. Pilote en usine (Docker Compose)

> Fichiers fournis dans `deploy/`. Non exécutés dans l'environnement de développement (pas de démon Docker) :
> à valider sur le serveur pilote.

1. Serveur Linux sur le réseau de l'usine (2 vCPU, 4 Go RAM, 50 Go disque suffisent pour un pilote), Docker installé.
2. Nom DNS interne, par ex. `maintiflow.usine.local`, pointant vers le serveur (demander au service informatique).
3. Configuration :
   ```bash
   cp deploy/.env.example deploy/.env
   # renseigner DB_PASSWORD, JWT_SECRET (valeurs aléatoires), SERVER_NAME, APP_ENV=pilot
   docker compose -f deploy/docker-compose.yml --env-file deploy/.env up -d --build
   docker compose -f deploy/docker-compose.yml exec api python -m scripts.create_admin \
       --site-code PILOTE --site-name "Usine pilote" --username admin.pilote
   ```
4. Certificat : Caddy génère une autorité interne. Récupérer son certificat racine
   `docker compose -f deploy/docker-compose.yml cp proxy:/data/caddy/pki/authorities/local/root.crt .`
   et l'installer sur chaque terminal (Paramètres Android › Sécurité › Chiffrement › Installer un certificat CA).
   Si l'usine a déjà une AC interne, remplacer `tls internal` par ses certificats.
5. Sur chaque terminal : saisir `https://maintiflow.usine.local` au premier lancement.
6. L'administrateur crée les lignes, machines, catégories et comptes depuis l'application (menu Administration).
   Chaque nouvel utilisateur doit changer son mot de passe à la première connexion.
7. Sauvegardes automatiques : voir [BACKUP_RESTORE.md](BACKUP_RESTORE.md).

Le serveur n'a besoin d'aucun accès Internet entrant ; les données restent dans l'usine.

## C. Durcissement production

- Appliquer `deploy/db_least_privilege.sql` et faire tourner l'API avec le rôle `maintiflow_app`.
- Garder `APP_ENV=production` (désactive `/docs`).
- Restreindre le port 443 au VLAN des terminaux ; pas de port 5432 exposé.
- Superviser `GET /health` ; journaux applicatifs via `docker compose logs api` (chaque requête a un `X-Request-ID`).
