@echo off
rem Sauvegarde complete (base, photos, PDF archives) dans le dossier sauvegardes\, serveur en marche ou non.
chcp 65001 > nul
cd /d "%~dp0"
if not exist sauvegardes mkdir sauvegardes
for /f %%i in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd-HHmm"') do set STAMP=%%i
java -jar maintiflow-server.jar backup "sauvegardes\maintiflow-%STAMP%.zip" --data "%~dp0maintiflow-data"
pause
