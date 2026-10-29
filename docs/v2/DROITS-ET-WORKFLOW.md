# MaintiFlow Pro 2 – Rôles, droits et workflow

Tous les droits sont vérifiés **par le serveur** (téléphone maître ou PC). L'application et la page web
n'affichent que les boutons que le serveur autorise (`allowed_actions`) et les champs qu'il permet de
modifier (`editable_fields`). Toute tentative directe est refusée (403) et tracée.

## Rôles

| Rôle | Peut |
|---|---|
| Opérateur | déclarer une panne (page rose) sur **ses lignes**, voir les pannes de ses lignes, signaler une récidive |
| Chef de ligne | comme l'opérateur, + affecter, annuler, clôturer, réouvrir, refuser une résolution (sur ses lignes) |
| Chef d'équipe | comme le chef de ligne, sur toutes les lignes, + transférer Maintenance ↔ Méthode |
| Maintenancier | prendre en charge, diagnostiquer, intervenir, demander une pièce, transférer, terminer (page jaune) |
| Méthode | comme le maintenancier, pour les interventions Méthode (réglage process, outil, posage) ; peut réouvrir |
| Qualité | déclarer, valider ou refuser une résolution quand la catégorie exige la Qualité ; réouvrir |
| Logistique | voir uniquement les pannes avec demande de pièce ; « pièce livrée » / « pièce indisponible » |
| Responsable maintenance | tout le suivi, affectation, validation avant clôture (si le paramètre est activé) |
| Administrateur | comptes, configuration de l'usine, paramètres, audit, sauvegarde. **N'agit jamais sur une panne.** |

« Ses lignes » : les lignes attribuées par l'administrateur ; aucune ligne attribuée = toutes les lignes.
Tableau de bord : tous les rôles sauf l'opérateur.

## Statuts

| Code | Libellé | Couleur |
|---|---|---|
| NOUVEAU | Nouvelle panne | rose |
| EN_ATTENTE | En attente de prise en charge | rose |
| EN_DIAGNOSTIC | En cours de diagnostic | jaune |
| INTERVENTION_MAINTENANCE | Intervention Maintenance en cours | jaune |
| INTERVENTION_METHODE | Intervention Méthode en cours | jaune |
| EN_ATTENTE_PIECE | En attente de pièces | orange |
| EN_ATTENTE_QUALITE | En attente de validation Qualité | orange |
| RESOLUE | Résolue, en attente de clôture | orange |
| CLOTURE | Clôturée | vert |
| REOUVERTE | Réouverte après récidive | rose |
| ANNULE | Annulée (erreur de saisie, doublon) | gris |

Priorité Critique ou panne en retard de prise en charge : rouge.

## Transitions autorisées

| Action | Depuis | Vers | Qui | Obligatoire |
|---|---|---|---|---|
| Déclarer | – | NOUVEAU (EN_ATTENTE si affectée) | déclarants | machine, catégorie, description |
| Affecter | NOUVEAU, EN_ATTENTE, REOUVERTE, EN_DIAGNOSTIC | EN_ATTENTE | chefs, responsable | service ou personne |
| Prendre en charge | NOUVEAU, EN_ATTENTE, REOUVERTE | EN_DIAGNOSTIC | maintenancier, Méthode | – |
| Démarrer | EN_DIAGNOSTIC | INTERVENTION_MAINTENANCE / _METHODE | intervenant affecté | diagnostic |
| Transférer | EN_DIAGNOSTIC, interventions | EN_ATTENTE (autre service) | intervenants, chef d'équipe, responsable | service, motif |
| Suspendre / Reprendre | interventions | (même statut) | intervenant affecté | motif (suspendre) |
| Demander une pièce | interventions | EN_ATTENTE_PIECE | intervenant affecté | référence ou désignation |
| Pièce livrée / indisponible | EN_ATTENTE_PIECE | intervention / (reste) | Logistique | motif si indisponible |
| Terminer | interventions | EN_ATTENTE_QUALITE si la catégorie l'exige, sinon RESOLUE | intervenant affecté | diagnostic, cause, actions réalisées, résultat des tests, état final |
| Valider / refuser (Qualité) | EN_ATTENTE_QUALITE | RESOLUE / retour intervention | Qualité | motif si refus |
| Valider (Responsable) | RESOLUE | (même) | responsable | – |
| Clôturer | **RESOLUE uniquement** | CLOTURE | chefs, responsable | justification (5 car. min.) ; validation Responsable si paramétrée |
| Panne non résolue | RESOLUE | retour intervention | chefs, responsable | motif |
| Annuler | statuts ouverts | ANNULE | chefs, responsable | motif |
| Réouvrir (récidive) | CLOTURE | REOUVERTE | chefs, Méthode, Qualité, responsable | motif |

Règles garanties par les tests :

- une panne non résolue ne peut jamais être clôturée ;
- la clôture exige une justification ;
- la réouverture conserve tout l'historique (nouvelle révision, anciennes signatures conservées et marquées remplacées) ;
- une panne clôturée ou annulée est figée **au niveau de la base** (déclencheurs SQLite) ;
- aucune suppression de panne, d'événement, de signature, de photo ou de ligne d'audit n'est possible ;
- un compte présent dans l'historique ne peut pas être supprimé, seulement désactivé ;
- le dernier administrateur actif ne peut pas être désactivé ni rétrogradé.

## Page rose et page jaune

Une seule panne, un seul numéro `BI-AAAA-NNNNNN` :

- **page rose (déclaration)** : déclarant, rôle, date/heure, ligne, poste, machine, référence câble,
  catégorie, nom de la panne, description, arrêt machine, photos. Modifiable par le déclarant et les chefs
  tant que la panne n'est pas prise en charge ; ensuite seuls l'arrêt / redémarrage machine restent modifiables ;
- **page jaune (intervention et clôture)** : service, intervenant, heures, type d'intervention, outil/posage,
  diagnostic, cause (liste + détail), actions réalisées, pièces, résultat des tests, état final, observations
  Qualité, validation, clôture et justification. Remplie par l'intervenant affecté.

Le PDF reprend les deux pages (rose puis jaune) sous le même numéro et la même empreinte SHA-256.
Avant clôture il porte le filigrane « PROVISOIRE – NON CLÔTURÉE » ; à la clôture il est archivé et figé.

## Notifications

| Événement | Destinataires |
|---|---|
| Nouvelle panne | chefs (de la ligne), service concerné (ou la personne affectée) |
| Panne critique | en plus : tous les maintenanciers, Méthode, Qualité |
| Affectation / transfert | service ou personne affectée |
| Demande de pièce | Logistique, chefs |
| Pièce livrée / indisponible | intervenant |
| Attente Qualité | Qualité |
| Résolue | chefs, déclarant |
| Clôture, annulation | déclarant, intervenant |
| Réouverture | chefs, maintenanciers, précédent intervenant |
| Retard de prise en charge (délai par priorité, paramétrable) | chefs, service |

L'auteur d'une action n'est jamais notifié de sa propre action. Les notifications sont lues par
l'application et la page web toutes les 20 secondes (pas de service Internet nécessaire).
