# Préparation Google Play (à finaliser en phase 3)

L'application n'est **pas publiée**. Ce document liste ce qui est prêt et ce qui reste à faire. Les règles
Google Play évoluent : vérifier la Play Console et les règles en vigueur au moment de la soumission.

## Déjà en place

- `applicationId` générique `com.maintiflow.pro`, aucun nom ni logo d'entreprise cliente.
- `targetSdk 36`, App Bundle via `./gradlew bundleRelease`, signature par `keystore.properties` (hors dépôt).
- Icône adaptative (vecteur) et écran de démarrage (Android 12+ SplashScreen API).
- HTTPS obligatoire en release, aucune donnée sauvegardée dans le cloud Android, aucun SDK publicitaire ni
  analytique, aucune collecte de localisation ni de contacts.
- Interface française, arabe préparée.

## Reste à faire

- [ ] Compte développeur Google Play (organisation) et vérification d'identité.
- [ ] Inscription à Play App Signing ; sauvegarder la clé d'importation.
- [ ] Icône haute résolution 512×512 PNG et bannière 1024×500 (dériver de `ic_launcher_foreground.xml`).
- [ ] Captures d'écran téléphone et tablette 7"/10" (accueil, nouveau bon, fiche, PDF) avec données fictives.
- [ ] Fiche : description courte et longue en français et en arabe (relecture native).
- [ ] Formulaire « Sécurité des données » (voir ci-dessous) et politique de confidentialité hébergée sur une URL publique.
- [ ] Questionnaire de classification du contenu ; public cible : professionnels, non destiné aux enfants.
- [ ] Accès pour la revue Google : l'application exige un serveur ; fournir un serveur de démonstration et un
      compte de test dans « Accès à l'application ».
- [ ] Tests fermés : vérifier dans la Play Console l'exigence en vigueur pour les nouveaux comptes personnels
      (nombre de testeurs et durée) avant la production.
- [ ] Distribution d'entreprise alternative : Google Play privé (Managed Google Play) via la console MDM de l'usine.

## Données réellement traitées (base de la politique de confidentialité)

| Donnée | Finalité | Où |
|---|---|---|
| Nom, identifiant, rôle de l'utilisateur | authentification, traçabilité des bons | serveur de l'usine |
| Mot de passe (haché, jamais en clair) | authentification | serveur |
| Horodatages et actions sur les bons, adresse IP de connexion | traçabilité, audit | serveur |
| Photos prises volontairement (machines) | documentation de la panne | serveur |
| Signature manuscrite (image) | validation des bons | serveur |
| Modèle de l'appareil | journal des sessions | serveur |
| Jeton de session, cache des bons | fonctionnement hors ligne | appareil (chiffré pour le jeton) |

Aucune donnée n'est vendue, partagée avec des tiers ni utilisée à des fins publicitaires. Les données sont
hébergées par l'entreprise utilisatrice sur son propre serveur ; c'est elle le responsable de traitement.
Durées de conservation et droits des personnes : à définir avec l'entreprise (en Tunisie, loi organique
n° 2004-63 sur la protection des données à caractère personnel ; selon le pays d'exploitation, RGPD).
