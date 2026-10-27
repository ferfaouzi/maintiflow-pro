# Guide utilisateur

## Chef d'équipe Production

**Déclarer une panne (moins de 30 secondes)**
1. Écran d'accueil → gros bouton orange **＋ NOUVEAU BON**.
2. Touchez la **ligne**, puis la **machine**, puis le **type de panne**.
3. Décrivez ce que vous voyez (les boutons « Bruit anormal », « Fuite »… complètent le texte).
4. « La production est arrêtée » est activé par défaut : désactivez-le si la ligne tourne encore.
5. Photo si utile, puis **ENVOYER LE BON**. Le numéro BI-… s'affiche ; la Maintenance le voit immédiatement.

Sans réseau : le bon est enregistré sur le téléphone (« bon(s) en attente d'envoi ») et part tout seul au retour
du réseau, avec l'heure réelle de déclaration. Ne le recréez pas.

**Suivre** : onglet « Mes demandes ». Couleur = état (rouge Nouveau, bleu Accepté / En cours, violet Attente pièce,
vert Réparé, gris Clôturé).

**Clôturer** : quand le bon passe en « Validation prod. », vérifiez la machine puis
**CONFIRMER LA REPRISE PRODUCTION** → signez → le bon est clôturé et le PDF figé.
Si le problème n'est pas résolu : **Problème non résolu** avec la raison → le bon retourne au technicien.

**Annuler** : possible seulement pour vos bons pas encore pris en charge, avec un motif.

## Technicien Maintenance

1. Accueil : les demandes ouvertes, les plus urgentes en haut (badge **EN RETARD** si le délai est dépassé).
2. Ouvrez le bon → **PRENDRE EN CHARGE**. (Optionnel : « Arrivé sur machine ».) → **DÉMARRER**.
3. Pendant l'intervention : « Suspendre » (motif) / « Reprendre » ; « Attente pièce » (pièce attendue).
4. Remplissez le **rapport technique** : diagnostic, cause, actions correctives, observations, état final ; ajoutez les
   **pièces utilisées** et les photos **AVANT / APRÈS**. « Enregistrer » à tout moment.
5. « Interventions similaires » : solutions passées sur la même machine ou la même panne, **à vérifier**, ce n'est pas
   un diagnostic.
6. **TERMINER – RÉPARÉ** puis **SIGNER LE RAPPORT**. Si vous corrigez le rapport après signature, il faudra re-signer.
7. Les temps (prise en charge, intervention, attente) sont calculés automatiquement : rien à saisir.

## Responsable Maintenance

- **Affecter** un technicien et ajuster la **priorité** d'un bon nouveau.
- Suivre les bons en cours, les retards, les pannes récurrentes ; rechercher par n°, machine, état.
- Si le site l'exige (paramètre Admin), **VALIDER (RESPONSABLE)** après la signature du technicien, ou
  **Renvoyer au technicien** avec motif.
- **Annuler** un bon ouvert (motif) ; **Réouvrir** un bon clôturé (motif) → nouvelle révision, historique conservé.
- Exporter / imprimer / partager le PDF d'un bon (icônes en haut de la fiche).

## Administrateur

Menu bouclier sur l'accueil : utilisateurs (création avec mot de passe provisoire, désactivation, réinitialisation),
lignes, machines, types de pannes, règle « validation Responsable obligatoire ».
Journal d'audit, sauvegardes : côté serveur (API `/admin/audit`, docs/BACKUP_RESTORE.md).

## Le PDF autocopiant

Un seul document de deux exemplaires identiques : **EXEMPLAIRE PRODUCTION** (bandeau bleu) et
**EXEMPLAIRE MAINTENANCE** (bandeau orange). Même numéro, même révision, même empreinte SHA-256 en pied de page.
Avant clôture : filigrane « PROVISOIRE ». Après clôture : document archivé, vérifié à chaque ouverture.
