# MaintiFlow Pro 2 – Guide d'utilisation

Connexion avec votre **matricule** et votre mot de passe. À la première connexion, choisissez votre mot de
passe personnel. Sans action pendant 30 minutes (paramétrable), la session se ferme.

Disponible dans l'application Android et dans le navigateur (PC, tablette) à l'adresse `…/app/`.
La cloche 🔔 indique les notifications non lues ; un clic ouvre la panne concernée.

## Opérateur, chef de ligne, chef d'équipe : déclarer une panne (page rose)

1. « + Déclarer une panne ».
2. Choisir la ligne, le poste, la machine (la liste se filtre), la référence câble si utile.
3. Choisir la catégorie (la priorité suit la catégorie, modifiable), donner un nom court et décrire.
4. Cocher « Machine arrêtée » si la production est arrêtée.
5. Chefs : affecter directement au service Maintenance / Méthode ou à une personne (facultatif).
6. « Enregistrer la déclaration » : le numéro BI-AAAA-NNNNNN est attribué. Ajouter des photos si besoin.

Tant que la panne n'est pas prise en charge, la page rose reste modifiable. Hors réseau, la déclaration
faite dans l'application est envoyée automatiquement au retour du Wi-Fi, sans doublon.

## Maintenancier, Méthode : intervenir (page jaune)

1. « Prendre en charge » → statut « En cours de diagnostic ».
2. « Démarrer l'intervention » avec le diagnostic.
3. Remplir la page jaune : type d'intervention, outil/posage, cause (liste + détail), actions réalisées,
   pièces utilisées, photos avant/après. « Enregistrer les modifications ».
4. Besoin d'une pièce : « Demander une pièce » (la Logistique est prévenue). Mauvais service :
   « Transférer » avec un motif.
5. « Terminer l'intervention » : résultat des tests et état final obligatoires. La panne passe en
   « Attente Qualité » si la catégorie l'exige, sinon en « Résolue, en attente de clôture ».

## Qualité

« Valider (Qualité) » avec vos observations, ou « Refuser (Qualité) » avec un motif : la panne retourne
à l'intervenant.

## Logistique

Vous voyez les pannes avec une demande de pièce : « Pièce livrée » (l'intervention reprend) ou
« Pièce indisponible » avec un motif.

## Chef de ligne, chef d'équipe, responsable : clôturer

Sur une panne « Résolue » : « Clôturer » avec une justification (obligatoire) et une signature facultative,
ou « Panne non résolue » avec un motif. Une panne non résolue ne peut pas être clôturée.
Récidive après clôture : « Réouvrir (récidive) » avec un motif ; l'historique est conservé.

## Historique et recherche

- Liste « Pannes » : recherche par numéro, machine, matricule ou texte ; filtres ligne, poste, machine,
  catégorie, priorité, service, statut, dates ; « En cours », « Récurrentes », « Mes pannes » ; export Excel.
- Fiche : journal non modifiable (qui, quand, rôle, ancien → nouveau statut), temps passé dans chaque
  statut, signatures, PDF (rose + jaune, même numéro) à imprimer ou partager.
- « historique machine » : toutes les pannes d'une machine, arrêt cumulé, récidives.

## Tableau de bord (tous sauf opérateur)

En direct : pannes en cours, critiques, machines arrêtées, en retard, en attente de pièces / Qualité /
clôture. Sur la période : déclarées, clôturées, MTTR, MTBF, délai moyen de prise en charge, taux de
récurrence, arrêt cumulé, répartitions par machine, ligne, poste, catégorie, priorité, chronologie.
Filtres par dates, ligne, machine, poste, catégorie. Exports Excel et PDF.

## Administrateur

Administration → Utilisateurs (matricule, nom, prénom, rôle, lignes, actif / désactivé, mot de passe
provisoire), Lignes, Postes, Machines, Réf. câbles, Outils / posages, Catégories (priorité, validation
Qualité), Causes, Types d'intervention, Société / site, Paramètres, Journal d'audit, Sauvegarde.
Une valeur utilisée dans une panne ne se supprime pas : désactivez-la. L'administrateur n'agit pas sur
les pannes.
