# MaintiFlow Pro 2 – Structure de la base de données

Base **SQLite** (fichier unique), identique sur le téléphone maître et sur le serveur PC. Schéma créé et
migré par `android/server/src/main/kotlin/com/maintiflow/server/Schema.kt` ; la version est dans la table
`mf_meta` (`schema_version` = 2). Une base de la version 1 est migrée automatiquement au démarrage.

## Organisation de l'usine

| Table | Colonnes principales | Rôle |
|---|---|---|
| sites | code, name, timezone | société / site |
| lines | site_id, code, name, active | lignes de production |
| postes | line_id, code, name, active | postes A, B, C… d'une ligne |
| machines | line_id, poste_id, code, name, qr_token, active | machines |
| cable_refs | site_id, code, name, active | références câble / faisceau |
| tools | site_id, machine_id, code, name, active | outils, posages, applicateurs |
| failure_categories | site_id, code, name, default_priority, requires_quality, active | catégories de panne |
| failure_causes | category_id, code, name, active | causes par catégorie |
| intervention_types | site_id, code, name, active | types d'intervention |
| app_settings | site_id, key, value | paramètres (délais, déconnexion, validation…) |

## Comptes et sécurité

| Table | Colonnes principales |
|---|---|
| users | username (= matricule), first_name, last_name, full_name, role, password_hash (PBKDF2-SHA256), active, must_change_password, failed_logins, locked_until |
| user_lines | user_id, line_id (lignes attribuées) |
| user_sessions | id (empreinte SHA-256 du jeton, jamais le jeton), created_at, expires_at, last_seen_at, revoked_at, device |
| audit_log | at, user_id, username, action, entity, entity_id, ip, detail (anciennes / nouvelles valeurs) |

## Pannes

| Table | Colonnes principales |
|---|---|
| interventions | id, **number** (BI-AAAA-NNNNNN), client_uuid, line_id, poste_id, machine_id, cable_ref_id, category_id, priority, **status**, title, description, reported_by_id, reporter_role, service, assigned_to_id, production_stopped, stop_start_at, stop_end_at, declared_at, accepted_at, arrived_at, started_at, repaired_at, quality_validated_at, quality_by_id, maintenance_validated_at, closed_at, closed_by_id, cancelled_at, cancel_reason, intervention_type_id, diagnosis, cause_id, root_cause, tool_id, corrective_action, test_result, observations, quality_observations, machine_final_state, final_comment, recurring, reopen_count, revision, version, archived_pdf_path, archived_pdf_sha256 |
| intervention_events | intervention_id, at, user_id, type, from_status, to_status, comment, data (anciennes / nouvelles valeurs) |
| signatures | intervention_id, role, user_id, signed_at, revision, content_sha256, image_png, superseded |
| parts_used | intervention_id, reference, designation, quantity, unit |
| part_requests | intervention_id, reference, designation, quantity, status (DEMANDEE / LIVREE / INDISPONIBLE), requested_by_id, handled_by_id |
| attachments | intervention_id, kind (PROBLEME / AVANT / APRES), content_type, sha256, storage_path |
| notifications | user_id, intervention_id, type, title, body, created_at, read_at |
| number_sequences | site_id, year, last_value (numérotation sans doublon) |

Le temps passé dans chaque statut est **calculé à partir de `intervention_events`** (jamais saisi).

## Protections au niveau de la base (déclencheurs)

| Déclencheur | Effet |
|---|---|
| mf_no_delete_interventions / events / signatures / attachments / part_requests / audit | suppression impossible |
| mf_no_update_events, mf_no_update_audit | historique et audit non modifiables |
| mf_locked_interventions | panne clôturée ou annulée : aucune colonne modifiable (page rose et jaune) |
| mf_locked_parts_* | pièces d'une panne clôturée figées |
| mf_cancel_final | une panne annulée ne peut pas être réactivée |

Ces protections tiennent même si quelqu'un ouvre le fichier SQLite avec un autre outil (testé dans
`SecurityTest.databaseForbidsSilentDeletionAndClosedEdits` et `MigrationTest`).
