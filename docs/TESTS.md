# Tests

## Backend – automatisés (exécutés : 25/25 réussis, 27/10/2026)

```bash
cd backend && TEST_DATABASE_URL=postgresql+psycopg://…/maintiflow_test pytest -v
```
La base de test est recréée par la migration Alembic (le test couvre donc aussi la migration et les triggers).

| Fichier | Test | Ce qui est vérifié |
|---|---|---|
| test_workflow | full_workflow_signalement_a_cloture | déclaration → affectation → prise en charge → arrivée → démarrage → pause → attente pièce → pièce → réparé → signature technicien → confirmation production → clôture ; numéro BI-AAAA-000001 ; 14 types d'événements ; bon clôturé non modifiable |
| | manager_validation_when_configured | paramètre « validation Responsable » : 3 signatures, ordre imposé |
| | production_refuses_restart | refus production avec motif → retour EN COURS, signatures annulées |
| | edit_after_signature_requires_new_signature | modification après signature → re-signature exigée, historique ancien/nouveau |
| | cancel_rules | motif obligatoire ; chef : seulement ses bons non pris en charge ; Responsable ; annulé non réouvrable |
| | reopen_creates_new_revision | réouverture motivée, révision 2, anciennes signatures conservées, re-clôture |
| | numbering_is_unique_under_concurrency | 20 créations en parallèle (4 fils) → numéros 1 à 20 sans trou ni doublon |
| | client_uuid_makes_creation_idempotent | renvoi hors ligne → même bon, pas de doublon |
| | offline_declared_at_is_kept_and_bounded | heure de déclaration hors ligne conservée, date future refusée |
| | recurrence_detection_and_similar | panne récurrente détectée, pistes similaires avec avertissement |
| | optimistic_locking | deux modifications concurrentes → 409 |
| | field_permissions | champs modifiables par rôle et par état |
| | search_filters | n°, machine, « mes bons », état, technicien, tri accueil par priorité |
| | durations_from_timestamps | calcul exact des durées (prise en charge 300 s, attente 900 s, intervention 1800 s…) |
| test_security | requires_authentication | 401 sans jeton / jeton invalide |
| | role_permissions | chaque rôle limité à ses actions ; admin sans action atelier |
| | site_isolation | aucun accès aux bons / machines d'un autre site |
| | logout_revokes_session | jeton inutilisable après déconnexion |
| | bruteforce_lockout | verrouillage après 5 échecs, audit des échecs |
| | admin_user_lifecycle_and_forced_password_change | création, doublon, mot de passe faible, changement forcé, désactivation immédiate |
| | audit_trail_records_actions | création, action, export PDF journalisés |
| | database_forbids_silent_deletion_and_closed_edits | DELETE/UPDATE directs en SQL refusés par la base |
| | photo_upload_rules | types autorisés, contenu vérifié, droits par rôle, accès autre site refusé |
| test_pdf | provisional_pdf_has_two_identical_copies | 2 exemplaires, numéro, accents, filigrane, même empreinte |
| | closed_pdf_is_archived_and_immutable | PDF figé identique pour tous ; altération du fichier détectée (500) |

Aussi exécutés : `scripts/demo_scenario.py` contre un serveur uvicorn réel (HTTP), et le test de
sauvegarde / restauration (docs/BACKUP_RESTORE.md).

## Android – à exécuter après la première compilation

Test unitaire JVM : `./gradlew testDebugUnitTest` (normalisation de l'adresse serveur).

Liste de contrôle manuelle (téléphone + tablette, comptes de démo) :

| # | Scénario | Attendu |
|---|---|---|
| A1 | Connexion avec mauvais mot de passe ×5 | message d'erreur puis compte verrouillé |
| A2 | chef.l1 : NOUVEAU BON complet avec photo | numéro BI affiché, bon visible chez tech.1 en ≤ 20 s |
| A3 | tech.1 : prendre en charge, démarrer, suspendre, attente pièce, reprendre | boutons cohérents à chaque état |
| A4 | tech.1 : TERMINER sans diagnostic | refus avec liste des champs manquants |
| A5 | tech.1 : rapport + pièce + photos avant/après, terminer, signer | état « Validation prod. » |
| A6 | chef.l1 : confirmer la reprise + signature | « Clôturé », plus aucun bouton d'action |
| A7 | PDF : ouvrir, imprimer, partager | 2 exemplaires identiques, signatures visibles |
| A8 | Mode avion : NOUVEAU BON | « enregistré, envoi automatique » ; réseau rétabli → bon créé une seule fois |
| A9 | Mode avion : ouvrir un bon déjà consulté | affichage depuis le cache avec bandeau orange, pas d'actions |
| A10 | Deux téléphones modifient le même bon | le second reçoit « modifié entre-temps », le bon est rechargé |
| A11 | Admin : désactiver un compte connecté | l'utilisateur est renvoyé à la connexion |
| A12 | Mode sombre, rotation, tablette | lisible, liste en colonnes sur tablette |
| A13 | Langue arabe (paramètres Android de l'app) | libellés traduits affichés en RTL |
