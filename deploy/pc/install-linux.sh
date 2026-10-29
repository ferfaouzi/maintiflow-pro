#!/bin/sh
# MaintiFlow Pro – installation du serveur sur Linux (Debian, Ubuntu, Raspberry Pi OS).
# Usage : sudo sh install-linux.sh [--demo]   (maintiflow-server.jar dans le même dossier)
# Crée l'utilisateur système « maintiflow », installe /opt/maintiflow, un service systemd (démarrage
# automatique) et une sauvegarde quotidienne. Le mot de passe administrateur est demandé dans la console.
set -eu
DEMO=""
[ "${1:-}" = "--demo" ] && DEMO="--demo"
HERE=$(cd "$(dirname "$0")" && pwd)
[ "$(id -u)" -eq 0 ] || { echo "Lancez avec sudo."; exit 1; }
[ -f "$HERE/maintiflow-server.jar" ] || { echo "maintiflow-server.jar introuvable dans $HERE"; exit 1; }
if ! command -v java >/dev/null 2>&1; then
  echo "Installation de Java 17…"
  apt-get update && apt-get install -y openjdk-17-jre-headless
fi
id maintiflow >/dev/null 2>&1 || useradd --system --home /opt/maintiflow --shell /usr/sbin/nologin maintiflow
install -d -o maintiflow -g maintiflow -m 750 /opt/maintiflow /opt/maintiflow/data /opt/maintiflow/sauvegardes
install -o root -g root -m 644 "$HERE/maintiflow-server.jar" /opt/maintiflow/maintiflow-server.jar

if [ ! -f /opt/maintiflow/data/maintiflow.db ]; then
  echo "Création du compte administrateur « admin » (mot de passe choisi maintenant, jamais affiché)."
  runuser -u maintiflow -- java -jar /opt/maintiflow/maintiflow-server.jar init --data /opt/maintiflow/data $DEMO
fi

cat > /etc/systemd/system/maintiflow.service <<UNIT
[Unit]
Description=MaintiFlow Pro (serveur de gestion des pannes)
After=network-online.target
Wants=network-online.target

[Service]
User=maintiflow
WorkingDirectory=/opt/maintiflow
ExecStart=/usr/bin/java -Xmx256m -jar /opt/maintiflow/maintiflow-server.jar --data /opt/maintiflow/data --port 8080
Restart=on-failure
NoNewPrivileges=true
ProtectSystem=strict
ReadWritePaths=/opt/maintiflow/data /opt/maintiflow/sauvegardes
PrivateTmp=true

[Install]
WantedBy=multi-user.target
UNIT

# Sauvegarde chaque nuit à 2 h, 30 dernières conservées.
cat > /etc/cron.d/maintiflow-sauvegarde <<'CRON'
0 2 * * * maintiflow java -jar /opt/maintiflow/maintiflow-server.jar backup /opt/maintiflow/sauvegardes/maintiflow-$(date +\%Y\%m\%d).zip --data /opt/maintiflow/data >/dev/null 2>&1; ls -1t /opt/maintiflow/sauvegardes/*.zip | tail -n +31 | xargs -r rm -f
CRON

systemctl daemon-reload
systemctl enable --now maintiflow
sleep 3
if curl -fsS http://127.0.0.1:8080/health >/dev/null 2>&1; then
  echo "MaintiFlow est en marche. Interface web : http://$(hostname -I | awk '{print $1}'):8080/app/"
else
  echo "Le service ne répond pas encore : journalctl -u maintiflow -n 50"
fi
