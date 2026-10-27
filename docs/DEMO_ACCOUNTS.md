# Comptes de démonstration (données fictives)

Créés par `python -m scripts.seed_demo` (refusé si `APP_ENV=pilot` ou `production`).
Les mots de passe ne sont **pas** dans le code : ils sont générés aléatoirement et écrits dans
`backend/demo_credentials.txt` (droits 600, exclu du dépôt), ou fixés pour un poste de test avec
`DEMO_PASSWORD='…' python -m scripts.seed_demo`.

| Identifiant | Rôle | Nom affiché |
|---|---|---|
| `chef.l1` | Chef d'équipe Production | Amine Chef L1 (fictif) |
| `chef.l2` | Chef d'équipe Production | Nadia Chef L2 (fictif) |
| `tech.1` | Technicien Maintenance | Karim Technicien (fictif) |
| `tech.2` | Technicien Maintenance | Youssef Technicien (fictif) |
| `resp.maint` | Responsable Maintenance | Leila Responsable Maintenance (fictif) |
| `admin` | Administrateur | Administrateur démo |

Référentiel fictif : site « Usine de démonstration – Faisceaux automobiles », 3 lignes (coupe et sertissage,
assemblage, contrôle électrique), 10 machines, 8 types de pannes.

En pilote / production : aucun compte de démo ; le premier administrateur est créé avec
`python -m scripts.create_admin` (mot de passe saisi de façon masquée), puis les comptes depuis l'application
(changement de mot de passe obligatoire à la première connexion).
