# Workflow du bon d'intervention

```
NOUVEAU ──affecter──► EN ATTENTE ──prendre en charge──► ACCEPTÉ ──démarrer──► EN COURS ◄──┐
   └────────────prendre en charge────────────────────────┘           │  ▲ suspendre/reprendre
                                                                     │  └──────────────┐
                                                     attente pièce ──► EN ATTENTE DE PIÈCE
                                                                     │
                                                       terminer ─────► RÉPARÉ
                                    signature technicien (+ Responsable si configuré)
                                                                     ▼
                                                         VALIDATION PRODUCTION
                                     chef d'équipe confirme la reprise + signe │ refuse (motif) → EN COURS
                                                                     ▼
                                                                CLÔTURÉ ──réouvrir (Responsable, motif)──► EN ATTENTE (révision +1)

Tout état ouvert ──annuler (motif obligatoire)──► ANNULÉ (définitif)
```

## Actions, rôles et conditions

| Action | Rôle | Depuis | Vers | Conditions |
|---|---|---|---|---|
| Déclarer | Chef d'équipe | – | NOUVEAU | machine + type de panne + description (≥ 3 car.) |
| `assign` Affecter | Responsable | NOUVEAU, EN ATTENTE, ACCEPTÉ | EN ATTENTE | technicien actif du site ; peut changer la priorité |
| `accept` Prendre en charge | Technicien | NOUVEAU, EN ATTENTE | ACCEPTÉ | si affecté à un autre technicien : refusé |
| `arrive` Arrivé sur machine | Technicien affecté | ACCEPTÉ | = | une seule fois |
| `start` Démarrer | Technicien affecté | ACCEPTÉ | EN COURS | renseigne l'arrivée si absente |
| `pause` / `resume` | Technicien affecté | EN COURS | = | motif obligatoire pour suspendre |
| `wait_part` | Technicien affecté | EN COURS | EN ATTENTE DE PIÈCE | pièce attendue obligatoire |
| `resume` | Technicien affecté | EN ATTENTE DE PIÈCE | EN COURS | |
| `repair` Terminer | Technicien affecté | EN COURS | RÉPARÉ | diagnostic, actions correctives, état final obligatoires |
| `sign_maintenance` | Technicien affecté | RÉPARÉ | VALIDATION PRODUCTION (ou reste RÉPARÉ si validation Responsable requise) | signature + empreinte du contenu |
| `validate_maintenance` | Responsable | RÉPARÉ | VALIDATION PRODUCTION | seulement si le paramètre l'exige et après signature technicien |
| `reject_maintenance` | Responsable | RÉPARÉ | EN COURS | motif ; signatures annulées |
| `confirm_production` | Chef d'équipe (du site) | VALIDATION PRODUCTION | CLÔTURÉ | signature ; heure de reprise ; PDF archivé et figé |
| `reject_production` | Chef d'équipe | VALIDATION PRODUCTION | EN COURS | motif ; signatures maintenance annulées |
| `cancel` | Chef (son bon, avant prise en charge) / Responsable (tout bon ouvert) | ouvert | ANNULÉ | motif ≥ 5 car. ; jamais supprimé |
| `reopen` | Responsable | CLÔTURÉ | EN ATTENTE | motif ; révision +1 ; anciennes signatures conservées « remplacées » |
| `flag_recurring` | Chef / Technicien / Responsable | ouvert | = | signalement manuel |

Le **chef d'équipe** confirme la reprise de production ; c'est distinct de la **validation maintenance**
(signature du technicien, et du Responsable si le site l'exige : paramètre `require_manager_validation`).

## Modifications et historique

- Chaque action et chaque modification crée un événement horodaté (qui, quand, quoi, ancien → nouveau).
- Champs modifiables : chef (description, catégorie, arrêt production) tant que le bon n'est pas pris en charge ;
  technicien affecté (rapport technique, pièces, photos avant/après) ; Responsable (priorité, catégorie, rapport).
- Modifier un bon RÉPARÉ déjà signé annule la signature : il faut re-signer.
- Bon CLÔTURÉ ou ANNULÉ : toute modification est refusée par l'API **et** par la base de données.

## Chronométrage (calculé, jamais saisi)

| Indicateur | Calcul |
|---|---|
| Délai de prise en charge | prise en charge − déclaration |
| Délai d'arrivée | arrivée − déclaration |
| Attente avant intervention | temps passé en NOUVEAU / EN ATTENTE / ACCEPTÉ |
| Intervention effective | temps EN COURS hors suspensions |
| Suspensions | temps EN COURS suspendu |
| Attente pièces | temps EN ATTENTE DE PIÈCE |
| Attente de validation | temps RÉPARÉ + VALIDATION PRODUCTION |
| Arrêt de production | reprise − début d'arrêt (si la production était arrêtée) |

## Retards et récurrence

- Un bon non pris en charge est « EN RETARD » après : CRITIQUE 5 min, HAUTE 15 min, NORMALE 60 min, BASSE 4 h
  (paramètre `late_acceptance_minutes`).
- Panne récurrente détectée automatiquement si ≥ 2 bons même machine + même catégorie en 30 jours
  (paramètres `recurrence_threshold`, `recurrence_window_days`). Les interventions similaires sont proposées
  comme **pistes**, avec l'avertissement qu'elles ne remplacent pas le diagnostic.
