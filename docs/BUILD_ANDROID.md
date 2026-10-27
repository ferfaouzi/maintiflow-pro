# Compiler et installer l'APK

> État : le code source est complet mais **n'a pas été compilé** dans l'environnement de développement
> (accès à dl.google.com bloqué : ni SDK Android ni dépôt Maven Google). La première compilation doit être faite
> avec Android Studio ; s'il signale une erreur, envoyez le message exact pour correction.

## Prérequis

- Android Studio (version récente, 2025.1 ou plus) avec Android SDK Platform 36 et Build-Tools.
- JDK 17 ou 21 (celui fourni avec Android Studio convient).
- Accès Internet pour le premier téléchargement des dépendances Gradle.

Versions utilisées (`android/gradle/libs.versions.toml`) : AGP 8.10.1, Kotlin 2.1.21, KSP 2.1.21-2.0.1,
Compose BOM 2025.05.00, Room 2.7.1, Gradle 8.14.3 (wrapper fourni), compileSdk/targetSdk 36, minSdk 26 (Android 8.0).
Si Android Studio propose une mise à jour (« AGP Upgrade Assistant »), elle peut être acceptée.

## APK de test (debug)

```bash
cd android
./gradlew assembleDebug          # Windows : gradlew.bat assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # tests unitaires JVM
```

Ou dans Android Studio : *File › Open* → dossier `android/` → *Run ▶*.

La version debug (`com.maintiflow.pro.debug`) autorise HTTP pour pointer vers un poste de développement
(`http://10.0.2.2:8000` depuis l'émulateur). La version release n'accepte que HTTPS.

## APK / AAB de publication (signés)

1. Créer la clé de signature (une seule fois, **à conserver précieusement et sauvegarder hors du dépôt**) :
   ```bash
   keytool -genkeypair -v -keystore maintiflow-release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias maintiflow
   ```
2. Créer `android/keystore.properties` (ignoré par git) :
   ```properties
   storeFile=../maintiflow-release.jks
   storePassword=...
   keyAlias=maintiflow
   keyPassword=...
   ```
3. Compiler :
   ```bash
   ./gradlew assembleRelease   # -> app/build/outputs/apk/release/app-release.apk (installation directe pilote)
   ./gradlew bundleRelease     # -> app/build/outputs/bundle/release/app-release.aab (Google Play)
   ```

## Installation sur les terminaux du pilote

- Par câble : `adb install -r app-release.apk`.
- Par fichier : copier l'APK sur le terminal, autoriser « Installer des applications inconnues » pour le
  gestionnaire de fichiers, ouvrir l'APK.
- Flotte gérée (MDM / Android Enterprise) : déployer l'APK signé depuis la console MDM.

Au premier lancement : adresse du serveur `https://maintiflow.usine.local` (certificat de l'AC interne installé,
voir INSTALL_SERVER.md), puis identifiant et mot de passe personnels.

## Vérifications après la première compilation

Suivre la liste de contrôle manuelle de [TESTS.md](TESTS.md) (section Android) sur un appareil réel et une tablette.
