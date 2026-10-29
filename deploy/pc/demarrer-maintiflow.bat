@echo off
rem MaintiFlow Pro - serveur PC Windows. Placez ce fichier a cote de maintiflow-server.jar.
rem Premier lancement : le mot de passe administrateur est demande ici (jamais de mot de passe par defaut).
chcp 65001 > nul
cd /d "%~dp0"
where java > nul 2>&1
if errorlevel 1 (
  echo Java 17 ou plus recent est necessaire : https://adoptium.net/fr/temurin/releases/?version=17
  pause
  exit /b 1
)
if not exist maintiflow-server.jar (
  echo maintiflow-server.jar introuvable dans %~dp0
  pause
  exit /b 1
)
rem Autoriser le port dans le pare-feu Windows (une fois, en administrateur) :
rem   netsh advfirewall firewall add rule name="MaintiFlow" dir=in action=allow protocol=TCP localport=8080
java -jar maintiflow-server.jar --data "%~dp0maintiflow-data" --port 8080 %*
pause
