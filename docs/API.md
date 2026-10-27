# API REST

Base : `https://<serveur>/api/v1`. Authentification : `POST /auth/login` puis `Authorization: Bearer <jeton>`.
Documentation interactive (développement) : `http://localhost:8000/docs`. Spécification complète : [openapi.json](openapi.json).

Codes : 401 session invalide · 403 rôle ou champ non autorisé · 404 introuvable (ou autre site) ·
409 conflit (bon modifié entre-temps, bon clôturé, action impossible dans l'état) · 422 données invalides · 423 compte verrouillé.

| Méthode | Chemin | Rôle | Description |
|---|---|---|---|
| POST | `/auth/login` | – | connexion, renvoie jeton + utilisateur |
| POST | `/auth/logout` | tous | révoque la session |
| GET | `/auth/me` | tous | utilisateur courant |
| POST | `/auth/change-password` | tous | change le mot de passe, révoque les autres sessions |
| GET | `/referentials` | tous | lignes, machines, catégories, techniciens du site (mis en cache sur le terminal) |
| GET | `/machines/by-qr/{token}` | tous | machine depuis son QR Code |
| GET/POST/PUT | `/admin/lines`, `/admin/machines`, `/admin/categories` | Admin | référentiels |
| GET/POST/PATCH | `/admin/users` | Admin | comptes (création, rôle, activation, réinitialisation) |
| GET | `/admin/settings` | tous (lecture) | paramètres du site |
| PUT | `/admin/settings` | Admin | modifie les paramètres |
| GET | `/admin/audit` | Admin | journal d'audit filtrable (action, utilisateur, n° de bon, dates) |
| GET | `/interventions` | tous | recherche : `q`, `status`, `open_only`, `mine`, `line_id`, `machine_id`, `category_id`, `technician_id`, `priority`, `date_from`, `date_to`, `limit`, `offset` |
| POST | `/interventions` | Chef | déclare un bon (idempotent avec `client_uuid`) |
| GET | `/interventions/{id}` | tous | fiche complète + `allowed_actions` + `editable_fields` + durées |
| GET | `/interventions/by-number/{BI-…}` | tous | fiche par numéro |
| PATCH | `/interventions/{id}` | selon champs | modification tracée, `version` obligatoire |
| POST | `/interventions/{id}/actions/{action}` | selon action | transition du workflow (voir WORKFLOW.md) |
| POST/DELETE | `/interventions/{id}/parts` | Technicien affecté / Responsable | pièces utilisées |
| GET | `/interventions/{id}/similar` | tous | interventions passées comparables (pistes) |
| GET | `/interventions/{id}/pdf` | tous | PDF 2 exemplaires (provisoire, ou archivé et vérifié si clôturé) |
| POST | `/interventions/{id}/attachments` | Chef (PROBLEME), Technicien (AVANT/APRES) | photo JPEG/PNG/WEBP ≤ 8 Mo |
| GET | `/attachments/{id}` | tous (même site) | télécharge une photo |
| GET | `/signatures/{id}/image` | tous (même site) | image de signature |
| GET | `/health` | – | supervision |

## Exemple

```bash
TOKEN=$(curl -s -X POST $URL/auth/login -H 'Content-Type: application/json' \
  -d '{"username":"chef.l1","password":"…"}' | jq -r .access_token)
curl -s -X POST $URL/interventions -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"machine_id":2,"category_id":5,"description":"Bourrage en sortie","production_stopped":true}'
curl -s -X POST $URL/interventions/$ID/actions/accept -H "Authorization: Bearer $TECH" \
  -H 'Content-Type: application/json' -d '{"version":1}'
```

Un scénario complet est dans `backend/scripts/demo_scenario.py`.
