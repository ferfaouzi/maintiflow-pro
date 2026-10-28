# Serveur MaintiFlow sur un Raspberry Pi (sans PC)

But : un petit boîtier Raspberry Pi, branché en permanence sur le point d'accès, fait tourner le serveur.
Tous les téléphones connectés au même Wi-Fi utilisent l'application en même temps et partagent les mêmes bons.
Un PC (ou un écran + clavier) n'est nécessaire qu'**une seule fois**, pour l'installation.

## Matériel (environ 80 à 120 €)
- Raspberry Pi 5 (4 Go suffisent) + son alimentation officielle 27 W.
- Carte microSD 64 Go de bonne qualité (classe A2), ou mieux un SSD USB.
- Un câble réseau vers le point d'accès (plus fiable que le Wi-Fi).
- Un boîtier avec ventilateur.

## Étape 1 – Préparer la carte SD
1. Sur un PC, installer **Raspberry Pi Imager** (https://www.raspberrypi.com/software/).
2. Choisir : appareil *Raspberry Pi 5*, système *Raspberry Pi OS Lite (64-bit)*, la carte SD.
3. Dans les réglages (roue dentée / « Modifier les réglages ») :
   - nom d'hôte : `maintiflow`
   - nom d'utilisateur `admin` et un mot de passe à noter
   - activer **SSH** (authentification par mot de passe)
   - Wi-Fi : seulement si pas de câble réseau
4. Écrire la carte, la mettre dans le Raspberry Pi, brancher le câble réseau puis l'alimentation.

## Étape 2 – Se connecter au Raspberry Pi
Depuis le PC (même réseau), ouvrir PowerShell :
```
ssh admin@maintiflow.local
```
Répondre `yes`, puis taper le mot de passe. Toutes les commandes suivantes se tapent dans cette fenêtre.

## Étape 3 – Installer Docker
```
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker admin
exit
```
Se reconnecter avec `ssh admin@maintiflow.local`.

## Étape 4 – Copier le code
Le dépôt GitHub est privé. Le plus simple : depuis le PC, télécharger le ZIP du dépôt (bouton **Code → Download ZIP**),
puis, dans PowerShell **sur le PC** (dans le dossier Téléchargements) :
```
scp maintiflow-pro-main.zip admin@maintiflow.local:~
```
Puis sur le Raspberry Pi :
```
sudo apt install -y unzip
unzip maintiflow-pro-main.zip && mv maintiflow-pro-main maintiflow && cd maintiflow
```

## Étape 5 – Configurer
```
cp deploy/.env.example deploy/.env
nano deploy/.env
```
Pour un **essai avec des données fictives**, mettre :
```
APP_ENV=demo
SERVER_NAME=maintiflow.local
DB_PASSWORD=<un mot de passe long>
JWT_SECRET=<une phrase secrète de plus de 32 caractères>
ACCESS_TOKEN_HOURS=12
```
Enregistrer : Ctrl+O, Entrée, Ctrl+X. Pour générer des valeurs aléatoires :
`python3 -c "import secrets;print(secrets.token_urlsafe(48))"`.

## Étape 6 – Démarrer (redémarre tout seul après une coupure de courant)
```
docker compose -f deploy/docker-compose.yml -f deploy/docker-compose.essai.yml --env-file deploy/.env up -d --build
```
La première fois : 5 à 10 minutes. Vérifier : `docker compose -f deploy/docker-compose.yml ps` → `db`, `api`, `proxy` « running ».

Créer les comptes de démonstration (mot de passe `Demo2026x` pour tous) :
```
docker compose -f deploy/docker-compose.yml --env-file deploy/.env exec -e DEMO_PASSWORD=Demo2026x api python -m scripts.seed_demo
```

## Étape 7 – Adresse fixe
Dans la page d'administration du point d'accès / routeur, menu **DHCP → Réservation d'adresse**, réserver une adresse
pour l'appareil `maintiflow` (par exemple `192.168.1.50`). Ainsi l'adresse ne change jamais.
Pour voir l'adresse actuelle : `hostname -I` sur le Raspberry Pi.

## Étape 8 – Téléphones
Sur chaque téléphone, connecté au même Wi-Fi :
1. Chrome → `http://192.168.1.50:8000/health` → `{"status":"ok"...}`.
2. Application MaintiFlow Pro → adresse du serveur `http://192.168.1.50:8000` → identifiant (`chef.l1`, `tech.1`…).

Il n'y a pas de limite pratique au nombre de téléphones pour une ligne ou une usine.

## Sauvegarde
Voir BACKUP_RESTORE.md (`deploy/backup-docker.sh`). Copier régulièrement les sauvegardes hors du Raspberry Pi
(clé USB) : si la carte SD lâche, c'est la seule copie.

## Passage au pilote réel
- `APP_ENV=pilot`, ne pas utiliser `docker-compose.essai.yml` (HTTP), utiliser l'APK release en HTTPS
  (certificat de Caddy à installer sur les téléphones, voir INSTALL_SERVER.md).
- Créer l'administrateur avec `scripts.create_admin` au lieu des comptes de démo.
