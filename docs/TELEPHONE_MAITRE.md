# Mode « téléphone maître » (sans PC)

Un téléphone Android fait office de serveur : il garde la base de données et les autres téléphones,
connectés au même point d'accès Wi-Fi, s'y connectent. Aucun PC n'est nécessaire.

```
 Téléphone maître (serveur + appli)      Point d'accès Wi-Fi       Téléphones chefs / techniciens
 http://192.168.1.23:8000  <───────────────────  ───────────────────>  appli MaintiFlow
```

## Mise en service (5 minutes)

1. Installer l'APK sur tous les téléphones.
2. Sur le téléphone choisi comme maître : écran de connexion → **« Ce téléphone est le téléphone maître »**.
3. **Mise en service** : choisir le mot de passe du compte `admin` (8 caractères, lettres et chiffres),
   cocher « données de démonstration fictives » pour un essai, puis **CRÉER LE SERVEUR**.
   Avec les données de démonstration, les comptes `chef.l1`, `chef.l2`, `tech.1`, `tech.2`, `resp.maint`
   ont le même mot de passe que `admin`.
4. Activer **Serveur** (accepter les notifications). L'adresse à donner aux autres s'affiche en gros,
   par exemple `http://192.168.1.23:8000`.
5. **Rester actif** : appuyer sur « Autoriser (batterie) », cocher le redémarrage automatique,
   laisser le téléphone sur son chargeur.
6. Sur ce même téléphone : **SE CONNECTER SUR CE TÉLÉPHONE** (adresse `http://127.0.0.1:8000`).
7. Sur chaque autre téléphone : adresse du serveur = l'adresse affichée, puis identifiant et mot de passe.

L'adresse ne change pas si on réserve l'adresse du téléphone maître dans le point d'accès
(menu DHCP → réservation). Le téléphone maître peut aussi être lui-même le point d'accès
(partage de connexion) : les autres se connectent à son Wi-Fi.

## Ce qui est identique au serveur PC

Même API (`/api/v1`), mêmes règles : workflow et autorisations par rôle, numérotation `BI-AAAA-NNNNNN`
sans doublon, verrou de version, historique et journal d'audit non modifiables (déclencheurs SQLite),
bon clôturé figé, PDF « autocopiant » en deux exemplaires archivé avec son empreinte à la clôture,
photos, pièces, détection des pannes récurrentes, verrouillage après 5 échecs de connexion.
Le code est dans `android/server` (Kotlin pur) ; les tests `android/server/src/test` reprennent les
scénarios de `backend/tests`, et `app/src/test/.../MasterServerContractTest.kt` fait tourner le client
Retrofit de l'application contre ce serveur.

## Limites à connaître

- **Une seule copie des données** : exporter une sauvegarde chaque semaine (écran du téléphone maître →
  Sauvegarde). « Restaurer une sauvegarde » la recharge sur un nouveau téléphone.
- **Le trafic Wi-Fi n'est pas chiffré (HTTP)** : réservé à l'APK de test et à un réseau d'atelier fermé.
  La version publique (phase 3) devra passer en HTTPS.
- Android peut ralentir un téléphone écran éteint : téléphone dédié, sur chargeur, optimisation
  de batterie désactivée pour MaintiFlow (et le « gestionnaire de batterie » de la marque si présent).
- Mot de passe `admin` oublié : écran du téléphone maître → « Mot de passe admin oublié »
  (réservé à la personne qui a le téléphone maître en main).
- Au-delà du pilote (plusieurs lignes, beaucoup d'utilisateurs), le serveur PC / Raspberry Pi
  (`INSTALL_SERVER.md`, `INSTALL_RASPBERRY_PI.md`) reste recommandé.
