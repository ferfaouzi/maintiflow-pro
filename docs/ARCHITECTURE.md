# Architecture

## Vue d'ensemble

```
 Terminaux Android (chefs d'équipe, techniciens, responsable, tablette de supervision)
   │  Room (SQLite) : cache des référentiels et des bons + file d'envoi hors ligne
   │  Jeton de session chiffré (Android Keystore)
   │
   │  HTTPS (TLS) sur le Wi-Fi de l'usine – aucune exposition Internet nécessaire
   ▼
 Reverse proxy Caddy (TLS, autorité de certification interne)
   ▼
 API REST FastAPI (Python)  ── règles métier, autorisations, workflow, chrono, PDF, audit
   │                         └─ stockage fichiers contrôlé : photos + PDF archivés (hors racine web)
   ▼
 PostgreSQL 16  ── données, historique append-only, journal d'audit, triggers de protection
```

## Choix techniques

| Sujet | Choix | Raison |
|---|---|---|
| Mobile | Kotlin + Jetpack Compose | Natif Android (cible unique), accès direct caméra / impression / Keystore, pas de couche intermédiaire, outillage officiel Google Play. Flutter n'apporterait rien tant qu'iOS n'est pas visé. |
| Base locale | Room (SQLite) | Standard Android, requêtes typées, `Flow` pour l'interface. |
| Réseau | Retrofit + OkHttp + kotlinx.serialization | Pas de réflexion, compatible R8. |
| Backend | FastAPI | Validation stricte (Pydantic), documentation OpenAPI automatique, simple à maintenir. |
| Base serveur | PostgreSQL | Transactions, verrous de ligne (numérotation), triggers de protection, sauvegarde `pg_dump`. |
| PDF | ReportLab + police DejaVu embarquée | Génération côté serveur : un seul rendu, identique pour tous, archivable et vérifiable. |
| Déploiement | Docker Compose (db + api + proxy TLS) | Installation pilote sur un seul serveur du réseau local. |

## Principes métier appliqués dans le code

- **Une seule source de vérité** : toutes les règles (états, rôles, champs modifiables) sont dans
  `backend/app/workflow.py`. Le serveur renvoie, pour chaque bon, `allowed_actions` et `editable_fields` :
  l'application n'affiche que les boutons autorisés, et le serveur refuse tout le reste (403/409).
- **Horodatage serveur** : les événements sont datés par le serveur ; seuls la déclaration hors ligne
  (`declared_at`, bornée à 7 jours) et l'heure de reprise production saisie par le chef viennent du terminal.
- **Durées calculées, jamais saisies** : `backend/app/timing.py` rejoue l'historique.
- **Traçabilité** : historique `intervention_events` en ajout seul, `audit_log` en ajout seul, triggers PostgreSQL
  interdisant suppression et modification d'un bon clôturé. Une correction passe par une réouverture
  (nouvelle révision, anciennes signatures conservées et marquées « remplacées »).
- **Concurrence** : verrou optimiste (`version`) ; un conflit renvoie 409 et l'application recharge le bon.
- **Signatures** : chaque signature enregistre l'empreinte SHA-256 du contenu signé. Toute modification
  ultérieure (pièce ajoutée, diagnostic corrigé) annule la signature, qui doit être refaite.

## Sécurité

- Connexion individuelle, mot de passe haché bcrypt, politique minimale, changement forcé au 1er login
  pour les comptes créés par l'admin.
- Sessions côté serveur (table `user_sessions`) : déconnexion = révocation immédiate ; désactivation d'un
  compte = refus immédiat.
- Verrouillage 15 min après 5 échecs ; réponse identique que le compte existe ou non.
- Isolation par site : un utilisateur ne voit jamais les bons d'un autre site (404).
- Aucun secret dans l'APK : l'adresse du serveur est saisie au premier lancement ; `JWT_SECRET` et mot de passe
  base dans `.env` côté serveur (refus de démarrer si le secret est faible).
- TLS obligatoire en release (`network_security_config.xml`) ; certificats d'une AC interne acceptés.
- Fichiers : type vérifié (en-tête + type MIME), taille limitée, stockés hors racine web, servis seulement
  après authentification.
- Sauvegarde Android et transfert d'appareil désactivés pour les données de l'application.
- Production : rôle base de données sans DELETE/TRUNCATE (`deploy/db_least_privilege.sql`).
- `/docs` (Swagger) désactivé quand `APP_ENV=pilot|production`.

## Hors ligne (fondations posées en phase 1)

- Référentiels, listes et fiches consultées sont mis en cache (lecture hors ligne avec bandeau orange).
- Un bon créé sans réseau est stocké localement avec un `client_uuid` et une heure de déclaration réelle,
  puis envoyé par `SyncWorker` (WorkManager) dès que le réseau revient. Le serveur renvoie le même bon si le
  `client_uuid` existe déjà : **pas de doublon** même en cas de renvoi.
- Le numéro BI définitif est attribué par le serveur à la réception.
- Phase 2 : file d'actions hors ligne (prise en charge, fin d'intervention) avec résolution de conflits par version.

## Évolution multi-usines

- Le modèle porte déjà `site_id` partout (lignes, catégories, utilisateurs, bons, paramètres, compteurs).
- Pilote : 1 serveur local par usine (données qui ne quittent pas l'usine).
- Multi-usines : soit un serveur par usine (isolation forte, recommandé au départ), soit un serveur central
  multi-sites derrière VPN, la numérotation étant déjà unique par site et par année.
