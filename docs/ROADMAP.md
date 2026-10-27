# Pilote et feuille de route

## Pilote (une seule ligne, avec l'accord de la direction)

1. Installer le serveur sur le réseau local (INSTALL_SERVER.md), compiler et installer l'APK (BUILD_ANDROID.md).
2. Saisir uniquement la ligne pilote, ses machines et les types de pannes ; créer les comptes réels de l'équipe.
3. Former en 15 minutes chaque rôle avec USER_GUIDE.md ; garder les bons papier en secours la première semaine.
4. **Mesurer chaque semaine** avec `deploy/sql/pilot_kpi.sql` :
   - délai moyen / médian entre déclaration et prise en charge, par priorité ;
   - bons incomplets (clôturés sans cause, ouverts depuis plus de 24 h) ;
   - pannes qui reviennent le plus (machine + type) et temps d'arrêt cumulé.
   Relever les mêmes chiffres sur le papier pendant 2 à 4 semaines avant le pilote pour comparer.
5. Décider de la généralisation à partir de ces mesures et des retours des chefs d'équipe et techniciens.

## Phase 2 – QR Code, historique, notifications, hors ligne complet

- Génération / impression des étiquettes QR par machine (le jeton `qr_token` et l'API `machines/by-qr` existent) ;
  scan → fiche machine, historique, nouveau bon pré-rempli.
- Historique machine et recherche avancée à l'écran (ligne, technicien, dates).
- Notifications locales (nouvelle demande, prise en charge, retard, demande de validation) puis notifications
  distantes si le serveur est accessible (FCM ou WebSocket sur le réseau local).
- Hors ligne complet : file d'actions (prise en charge, fin, pièces) rejouée avec contrôle de version et écran de
  résolution des conflits.

## Phase 3 – Tableau de bord et version publique

- Tableau de bord tablette : bons ouverts, temps d'arrêt, MTTR, MTBF (quand l'historique est suffisant),
  machines les plus problématiques ; filtres période / ligne / machine / technicien ; export Excel et PDF.
- Gestion de stock des pièces (le modèle `parts_used` est prêt à être relié à un référentiel articles).
- Multi-usines, personnalisation des modèles de bons, sauvegarde/restauration depuis l'interface admin.
- Préparation Google Play (GOOGLE_PLAY.md), relecture de la traduction arabe.

Règle : chaque phase conserve toutes les fonctions validées précédemment ; les tests automatisés existants
doivent rester verts.
