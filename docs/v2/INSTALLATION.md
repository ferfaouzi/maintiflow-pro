# MaintiFlow Pro 2 – Installation

Deux façons d'héberger le serveur, avec **le même code et la même base** : une sauvegarde de l'une se
restaure sur l'autre.

| | Téléphone maître | Serveur PC / Raspberry Pi |
|---|---|---|
| Matériel | un téléphone Android branché sur secteur | un PC Windows, Linux ou un Raspberry Pi 4 |
| Accès | `http://<adresse-du-téléphone>:8000` | `http://<adresse-du-PC>:8080` |
| Interface web (PC, tablette) | `…:8000/app/` | `…:8080/app/` |
| Recommandé pour | essai, petit atelier | usage quotidien, plusieurs lignes |

Le Wi-Fi de l'atelier suffit ; aucune connexion Internet n'est nécessaire.

## Compte administrateur initial

Il n'existe **aucun mot de passe par défaut**, ni dans l'APK ni dans le serveur. Au premier lancement, la
personne qui installe choisit le mot de passe du compte `admin` (8 caractères minimum, lettres et chiffres) :
sur l'écran « Téléphone maître » de l'application, ou dans la console du PC (saisie masquée). Pour une
installation sans écran, la variable d'environnement `MAINTIFLOW_ADMIN_PASSWORD` peut le fournir une fois.

L'administrateur crée ensuite les comptes (matricule, nom, prénom, rôle, lignes). Chaque personne choisit
son propre mot de passe à la première connexion.

Mot de passe administrateur oublié : `java -jar maintiflow-server.jar reset-admin` (PC, accès physique) ou
« Mot de passe admin oublié » sur l'écran « Téléphone maître ».

## Téléphone maître

Voir [../TELEPHONE_MAITRE.md](../TELEPHONE_MAITRE.md). Après mise à jour de l'APK 2.0, la base existante
est migrée automatiquement (les techniciens deviennent « Maintenancier », les statuts sont convertis,
l'historique est conservé).

## Windows

1. Installer Java 17 (Temurin) : https://adoptium.net/fr/temurin/releases/?version=17
2. Télécharger `MaintiFlow-serveur-PC.zip` depuis la dernière release « apk-N » du dépôt et le décompresser,
   par exemple dans `C:\MaintiFlow`.
3. Double-cliquer `demarrer-maintiflow.bat`. Au premier lancement, choisir le mot de passe administrateur.
4. Autoriser le port 8080 dans le pare-feu (une fois, invite de commandes en administrateur) :
   `netsh advfirewall firewall add rule name="MaintiFlow" dir=in action=allow protocol=TCP localport=8080`
5. Sur les téléphones : Paramètres → Serveur → `http://<adresse-du-PC>:8080`.
6. Sauvegarde : `sauvegarder-maintiflow.bat` (copie dans `sauvegardes\`), ou bouton « Sauvegarde » de
   l'administration web.

Ajouter `--demo` à la première ligne de commande pour des données fictives de démonstration.

## Linux / Raspberry Pi

```sh
unzip MaintiFlow-serveur-PC.zip -d maintiflow && cd maintiflow
sudo sh install-linux.sh          # --demo pour des données fictives
```

Le script installe Java 17 si besoin, crée l'utilisateur système `maintiflow`, le service `maintiflow`
(démarrage automatique) et une sauvegarde chaque nuit dans `/opt/maintiflow/sauvegardes` (30 conservées).

## Commandes du serveur PC

```
java -jar maintiflow-server.jar [--data DOSSIER] [--port 8080]   démarrer
java -jar maintiflow-server.jar init [--demo] [--company NOM]    initialiser seulement
java -jar maintiflow-server.jar reset-admin                      nouveau mot de passe admin
java -jar maintiflow-server.jar backup FICHIER.zip               sauvegarder
java -jar maintiflow-server.jar restore FICHIER.zip              restaurer (serveur arrêté)
```

La restauration conserve l'ancien dossier sous `maintiflow-data.avant-restauration-<date>`.

## Paramètres (administration web → Paramètres)

- déconnexion automatique après N minutes sans action (30 par défaut, 0 = jamais) ;
- validation du Responsable maintenance avant clôture ;
- délais de prise en charge avant alerte, par priorité ;
- fenêtre et seuil de détection des récidives ;
- nom affiché sur les fiches et PDF.

## Données confidentielles

Les données de démonstration sont fictives. Ne pas saisir le nom ni le logo d'une entreprise réelle dans
une version publique sans autorisation écrite de celle-ci.
