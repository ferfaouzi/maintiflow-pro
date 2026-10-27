# MaintiFlow Pro – Phase 1

Bons d'intervention numériques « autocopiants » entre la Production et la Maintenance industrielle.
Application Android (Kotlin / Jetpack Compose) + serveur central (FastAPI / PostgreSQL).

> Données 100 % fictives. Aucun nom, logo ni donnée d'une entreprise réelle n'est intégré.

## Contenu de la phase 1

| Fonction | État |
|---|---|
| Bon numérique BI-AAAA-NNNNNN (numéro unique, sans doublon même en concurrence) | ✅ backend testé · ✅ écran Android |
| 4 rôles (Chef d'équipe, Technicien, Responsable, Admin) et permissions strictes | ✅ testé (backend) |
| Workflow 9 états + annulation motivée + réouverture tracée + refus production | ✅ testé |
| Validation Maintenance ≠ confirmation de reprise Production (2 signatures distinctes) | ✅ testé |
| Chronométrage calculé depuis les horodatages (prise en charge, intervention, attente, arrêt production) | ✅ testé |
| PDF autocopiant : exemplaire PRODUCTION + exemplaire MAINTENANCE, même numéro, même empreinte SHA-256 | ✅ testé |
| PDF figé et contrôlé à la clôture, filigrane « PROVISOIRE » avant | ✅ testé |
| Historique horodaté append-only + journal d'audit | ✅ testé (protégé aussi au niveau base) |
| Bons clôturés non modifiables, aucune suppression possible (triggers PostgreSQL) | ✅ testé |
| Photos (problème / avant / après), contrôle type et taille | ✅ testé |
| Pièces de rechange utilisées | ✅ testé |
| Création hors connexion + envoi automatique sans doublon (client_uuid) | ✅ backend testé · Android écrit, non exécuté |
| Détection simple de panne récurrente + interventions similaires (présentées comme pistes) | ✅ testé |
| Sauvegarde / restauration | ✅ testée (voir docs/BACKUP_RESTORE.md) |
| Application Android | ⚠️ code source complet, **non compilé dans cet environnement** (voir ci-dessous) |

Prévu en phase 2 : QR Code machine à l'écran (l'API `machines/by-qr` et le jeton QR existent déjà), notifications,
historique enrichi, synchronisation hors ligne des actions (pas seulement de la création).
Phase 3 : tableau de bord, MTTR/MTBF, exports Excel, version publique Google Play.

## APK : ce qui a été fait et ce qui reste

L'environnement de développement utilisé n'a pas accès à `dl.google.com` (SDK Android et dépôt Maven Google bloqués
par la politique réseau). **Aucun APK n'a donc été compilé ni testé ici.** Le projet `android/` est complet ;
la compilation se fait avec Android Studio en suivant [docs/BUILD_ANDROID.md](docs/BUILD_ANDROID.md).
Le backend, lui, a été exécuté et testé (25 tests automatisés + scénario de bout en bout).

## Arborescence

```
backend/            API FastAPI, modèle, workflow, PDF, migrations, tests, scripts
  app/              code de l'API (workflow.py = règles métier, pdf.py = PDF autocopiant)
  alembic/          migrations (schéma + protections de traçabilité)
  scripts/          seed_demo, create_admin, demo_scenario, backup.sh, restore.sh, verify_restore
  tests/            tests pytest (workflow complet, sécurité, PDF)
android/            application Kotlin / Jetpack Compose
deploy/             docker-compose (PostgreSQL + API + HTTPS Caddy), rôles base de données
docs/               architecture, données, workflow, écrans, API, installation, guides, tests
samples/            exemple de PDF généré (données fictives)
```

## Démarrage rapide (poste de développement)

```bash
cd backend
python3 -m venv .venv && . .venv/bin/activate
pip install -r requirements-dev.txt
cp .env.example .env        # renseigner DATABASE_URL et JWT_SECRET
alembic upgrade head
python -m scripts.seed_demo  # comptes de démo -> demo_credentials.txt
uvicorn app.main:app --reload
# Documentation interactive de l'API : http://localhost:8000/docs
pytest -q                    # nécessite une base de test (voir docs/INSTALL_SERVER.md)
```

## Documentation

- [Architecture](docs/ARCHITECTURE.md) · [Modèle de données](docs/DATA_MODEL.md) · [Workflow](docs/WORKFLOW.md) · [Écrans](docs/SCREENS.md)
- [API](docs/API.md) · [Installation serveur](docs/INSTALL_SERVER.md) · [Compiler l'APK](docs/BUILD_ANDROID.md)
- [Sauvegarde / restauration](docs/BACKUP_RESTORE.md) · [Tests](docs/TESTS.md) · [Guide utilisateur](docs/USER_GUIDE.md)
- [Comptes de démonstration](docs/DEMO_ACCOUNTS.md) · [Pilote et feuille de route](docs/ROADMAP.md) · [Préparation Google Play](docs/GOOGLE_PLAY.md)
