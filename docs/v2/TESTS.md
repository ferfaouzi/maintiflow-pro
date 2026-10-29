# MaintiFlow Pro 2 – Tests

## Tests automatiques (exécutés)

`android/server` (JUnit, serveur réel en HTTP sur un port local, base SQLite) : **57 tests, tous verts**.

| Fichier | Ce qui est vérifié |
|---|---|
| WorkflowTest (18) | parcours complet opérateur → maintenancier → clôture ; Qualité ; Méthode et transferts ; Logistique ; validation Responsable ; jamais de clôture d'une panne non résolue ; annulation ; réouverture avec historique ; affectation à la déclaration ; numéros uniques en concurrence ; idempotence hors ligne ; récidives ; verrou optimiste ; page rose figée après prise en charge ; recherche multicritère ; temps par statut ; retards |
| PermissionTest (6) | chaque rôle contre chaque action à chaque étape ; restriction aux lignes ; l'admin n'agit pas sur une panne ; routes admin réservées ; tableau de bord interdit à l'opérateur |
| SecurityTest (15) | authentification, verrouillage après 5 échecs, isolation des sites, cycle de vie des comptes, déclencheurs de la base, photos, PDF provisoire / archivé / falsifié, initialisation sans mot de passe par défaut |
| AdminTest (7) | comptes avec matricule / nom / prénom / lignes, désactivation au lieu de suppression, dernier admin, CRUD de toute la configuration avec refus de suppression si utilisé, site et paramètres audités (anciennes / nouvelles valeurs), déconnexion automatique, sauvegarde réservée à l'admin, historique machine |
| DashboardTest (3) | totaux, MTTR, MTBF, répartitions, filtres, taux de récurrence, exports Excel (zip valide) et PDF |
| NotificationTest (5) | destinataires à chaque étape, panne critique, auteur jamais notifié, lu / non lu, alerte de retard unique |
| MigrationTest (2) | base version 1 réelle convertie (rôles, statuts, historique), protections conservées, utilisable par l'API |
| WebTest (1) | interface web servie, redirection, en-têtes de sécurité, chemins interdits |

`android/server-pc` : **1 test** (base fichier, PDF PDFBox réel avec accents et symboles, sauvegarde ZIP
puis restauration). Contrat de l'application (`MasterServerContractTest`) : client Retrofit de l'APK
contre le serveur v2, **vert** (exécuté sur la JVM).

Lancer : `cd android && ./gradlew :server:test :server-pc:test testDebugUnitTest`.

## Essai de bout en bout dans un navigateur (exécuté)

Serveur PC (`maintiflow-server.jar`) démarré avec des données fictives, Chromium piloté par Playwright :
déclaration page rose (op.l1) → prise en charge, diagnostic, fin avec page jaune (tech.1) → clôture refusée
sans justification puis acceptée (cl.l1) → PDF archivé (2 pages rose + jaune + historique) → tableau de bord →
recherche → création d'un utilisateur et d'un poste (admin) → affichage téléphone 390 px sans défilement
horizontal. Aucune erreur JavaScript.

## Non vérifié ici

L'APK n'est pas compilé dans l'environnement de développement (dépôt Google bloqué) : il est compilé et
testé par GitHub Actions à chaque envoi sur `main` (`testDebugUnitTest` + `assembleDebug`). Les écrans
Android n'ont pas été essayés sur un vrai téléphone dans cette étape.
