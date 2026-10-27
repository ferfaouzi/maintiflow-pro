# Écrans de l'application Android

Interface en français (arabe préparé : `values-ar/strings.xml`, mise en page RTL supportée), boutons de 56–64 dp
utilisables avec des gants légers, couleurs d'état identiques sur la liste, la fiche et le PDF, mode sombre.
Sur tablette (poste de supervision), les listes passent automatiquement en plusieurs colonnes.

| Écran | Qui | Contenu |
|---|---|---|
| Connexion | tous | adresse du serveur (1re fois), identifiant, mot de passe ; changement de mot de passe forcé si demandé |
| Accueil | tous | bons ouverts triés par priorité puis ancienneté ; badges EN RETARD / PRODUCTION ARRÊTÉE / RÉCURRENTE ; rafraîchissement auto toutes les 20 s ; bandeau hors ligne et « bons en attente d'envoi » |
| Accueil – chef | Chef d'équipe | gros bouton orange **＋ NOUVEAU BON** ; onglets « Prioritaires » / « Mes demandes » |
| Nouveau bon | Chef d'équipe | 1 Ligne → 2 Machine → 3 Type de panne → 4 Problème (phrases rapides) ; interrupteur « production arrêtée » (activé par défaut) ; priorité auto ; photo ; **ENVOYER LE BON**. Moins de 30 s, fonctionne hors connexion |
| Fiche du bon | tous | état, priorité, machine, déclaration ; **seules les actions autorisées** pour l'utilisateur et l'état ; rapport technique, pièces, photos, temps calculés, signatures, interventions similaires (pistes), historique horodaté ; PDF / impression / partage |
| Signature | Technicien, Responsable, Chef | cadre de signature au doigt + commentaire |
| Recherche | tous | n° de bon, machine, texte ; filtre d'état ; ouverts seulement |
| Paramètres | tous | utilisateur connecté, serveur, mode sombre, langue, version, déconnexion |
| Administration | Admin | utilisateurs (création, activation, réinitialisation), lignes, machines, catégories de pannes, règle de validation Responsable |

Parcours type :

1. **Chef** : NOUVEAU BON → ligne → machine → type → description → ENVOYER (le bon apparaît chez tous les techniciens).
2. **Technicien** : PRENDRE EN CHARGE → DÉMARRER → (suspendre / attente pièce) → rapport + pièces + photos →
   TERMINER – RÉPARÉ → SIGNER LE RAPPORT.
3. **Chef** : vérifie la machine → CONFIRMER LA REPRISE PRODUCTION (signature) → bon clôturé, PDF figé.
