package com.maintiflow.server

/**
 * Schéma SQLite du serveur embarqué : mêmes tables et mêmes règles de traçabilité que le serveur
 * PostgreSQL (backend/alembic/versions/0001), adaptées à SQLite.
 */
object Schema {
    const val VERSION = 1

    private val TABLES = listOf(
        """CREATE TABLE sites (
            id INTEGER PRIMARY KEY,
            code TEXT NOT NULL UNIQUE,
            name TEXT NOT NULL,
            timezone TEXT NOT NULL DEFAULT 'Africa/Tunis',
            active INTEGER NOT NULL DEFAULT 1)""",
        """CREATE TABLE users (
            id INTEGER PRIMARY KEY,
            site_id INTEGER NOT NULL REFERENCES sites(id),
            username TEXT NOT NULL UNIQUE,
            full_name TEXT NOT NULL,
            role TEXT NOT NULL,
            password_hash TEXT NOT NULL,
            active INTEGER NOT NULL DEFAULT 1,
            must_change_password INTEGER NOT NULL DEFAULT 1,
            failed_logins INTEGER NOT NULL DEFAULT 0,
            locked_until INTEGER,
            created_at INTEGER NOT NULL)""",
        """CREATE TABLE user_sessions (
            id TEXT PRIMARY KEY,
            user_id INTEGER NOT NULL REFERENCES users(id),
            created_at INTEGER NOT NULL,
            expires_at INTEGER NOT NULL,
            revoked_at INTEGER,
            device TEXT)""",
        "CREATE INDEX ix_user_sessions_user_id ON user_sessions(user_id)",
        """CREATE TABLE lines (
            id INTEGER PRIMARY KEY,
            site_id INTEGER NOT NULL REFERENCES sites(id),
            code TEXT NOT NULL,
            name TEXT NOT NULL,
            active INTEGER NOT NULL DEFAULT 1,
            UNIQUE (site_id, code))""",
        """CREATE TABLE machines (
            id INTEGER PRIMARY KEY,
            line_id INTEGER NOT NULL REFERENCES lines(id),
            code TEXT NOT NULL,
            name TEXT NOT NULL,
            qr_token TEXT NOT NULL UNIQUE,
            active INTEGER NOT NULL DEFAULT 1,
            UNIQUE (line_id, code))""",
        """CREATE TABLE failure_categories (
            id INTEGER PRIMARY KEY,
            site_id INTEGER NOT NULL REFERENCES sites(id),
            code TEXT NOT NULL,
            name TEXT NOT NULL,
            default_priority TEXT NOT NULL DEFAULT 'NORMALE',
            active INTEGER NOT NULL DEFAULT 1,
            UNIQUE (site_id, code))""",
        """CREATE TABLE number_sequences (
            site_id INTEGER NOT NULL REFERENCES sites(id),
            year INTEGER NOT NULL,
            last_value INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY (site_id, year))""",
        """CREATE TABLE interventions (
            id TEXT PRIMARY KEY,
            number TEXT NOT NULL,
            client_uuid TEXT UNIQUE,
            site_id INTEGER NOT NULL REFERENCES sites(id),
            line_id INTEGER NOT NULL REFERENCES lines(id),
            machine_id INTEGER NOT NULL REFERENCES machines(id),
            category_id INTEGER NOT NULL REFERENCES failure_categories(id),
            priority TEXT NOT NULL,
            status TEXT NOT NULL,
            description TEXT NOT NULL,
            reported_by_id INTEGER NOT NULL REFERENCES users(id),
            assigned_to_id INTEGER REFERENCES users(id),
            production_stopped INTEGER NOT NULL DEFAULT 0,
            stop_start_at INTEGER,
            stop_end_at INTEGER,
            declared_at INTEGER NOT NULL,
            accepted_at INTEGER,
            arrived_at INTEGER,
            started_at INTEGER,
            repaired_at INTEGER,
            maintenance_validated_at INTEGER,
            production_confirmed_at INTEGER,
            closed_at INTEGER,
            cancelled_at INTEGER,
            cancel_reason TEXT,
            paused INTEGER NOT NULL DEFAULT 0,
            diagnosis TEXT,
            root_cause TEXT,
            corrective_action TEXT,
            observations TEXT,
            machine_final_state TEXT,
            production_comment TEXT,
            recurring INTEGER NOT NULL DEFAULT 0,
            reopen_count INTEGER NOT NULL DEFAULT 0,
            revision INTEGER NOT NULL DEFAULT 1,
            version INTEGER NOT NULL DEFAULT 1,
            archived_pdf_sha256 TEXT,
            archived_pdf_path TEXT,
            created_at INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            UNIQUE (site_id, number))""",
        "CREATE INDEX ix_interventions_status ON interventions(status)",
        "CREATE INDEX ix_interventions_machine_id ON interventions(machine_id)",
        "CREATE INDEX ix_interventions_assigned_to_id ON interventions(assigned_to_id)",
        """CREATE TABLE intervention_events (
            id INTEGER PRIMARY KEY,
            intervention_id TEXT NOT NULL REFERENCES interventions(id),
            at INTEGER NOT NULL,
            user_id INTEGER REFERENCES users(id),
            type TEXT NOT NULL,
            from_status TEXT,
            to_status TEXT,
            comment TEXT,
            data TEXT)""",
        "CREATE INDEX ix_events_intervention_id ON intervention_events(intervention_id)",
        """CREATE TABLE parts_used (
            id INTEGER PRIMARY KEY,
            intervention_id TEXT NOT NULL REFERENCES interventions(id),
            reference TEXT NOT NULL,
            designation TEXT NOT NULL,
            quantity TEXT NOT NULL,
            unit TEXT NOT NULL DEFAULT 'pce',
            added_by_id INTEGER NOT NULL REFERENCES users(id),
            added_at INTEGER NOT NULL)""",
        "CREATE INDEX ix_parts_intervention_id ON parts_used(intervention_id)",
        """CREATE TABLE signatures (
            id INTEGER PRIMARY KEY,
            intervention_id TEXT NOT NULL REFERENCES interventions(id),
            role TEXT NOT NULL,
            user_id INTEGER NOT NULL REFERENCES users(id),
            signed_at INTEGER NOT NULL,
            revision INTEGER NOT NULL,
            content_sha256 TEXT NOT NULL,
            image_png BLOB,
            superseded INTEGER NOT NULL DEFAULT 0)""",
        "CREATE INDEX ix_signatures_intervention_id ON signatures(intervention_id)",
        """CREATE TABLE attachments (
            id TEXT PRIMARY KEY,
            intervention_id TEXT NOT NULL REFERENCES interventions(id),
            kind TEXT NOT NULL,
            content_type TEXT NOT NULL,
            size_bytes INTEGER NOT NULL,
            sha256 TEXT NOT NULL,
            storage_path TEXT NOT NULL,
            uploaded_by_id INTEGER NOT NULL REFERENCES users(id),
            uploaded_at INTEGER NOT NULL)""",
        "CREATE INDEX ix_attachments_intervention_id ON attachments(intervention_id)",
        """CREATE TABLE audit_log (
            id INTEGER PRIMARY KEY,
            at INTEGER NOT NULL,
            user_id INTEGER REFERENCES users(id),
            username TEXT,
            action TEXT NOT NULL,
            entity TEXT,
            entity_id TEXT,
            ip TEXT,
            detail TEXT)""",
        "CREATE INDEX ix_audit_log_at ON audit_log(at)",
        """CREATE TABLE app_settings (
            site_id INTEGER NOT NULL REFERENCES sites(id),
            key TEXT NOT NULL,
            value TEXT NOT NULL,
            PRIMARY KEY (site_id, key))""",
    )

    /** Colonnes figées d'un bon clôturé ou annulé (tout sauf archive PDF, updated_at et version). */
    private val LOCKED_COLUMNS = listOf(
        "number", "client_uuid", "site_id", "line_id", "machine_id", "category_id", "priority", "status",
        "description", "reported_by_id", "assigned_to_id", "production_stopped", "stop_start_at", "stop_end_at",
        "declared_at", "accepted_at", "arrived_at", "started_at", "repaired_at", "maintenance_validated_at",
        "production_confirmed_at", "closed_at", "cancelled_at", "cancel_reason", "paused", "diagnosis",
        "root_cause", "corrective_action", "observations", "machine_final_state", "production_comment",
        "recurring", "reopen_count", "revision", "created_at",
    )

    private fun forbid(name: String, op: String, table: String) =
        "CREATE TRIGGER $name BEFORE $op ON $table BEGIN " +
            "SELECT RAISE(ABORT, 'MaintiFlow : $op interdit sur $table (traçabilité)'); END"

    private val TRIGGERS = listOf(
        forbid("mf_no_delete_interventions", "DELETE", "interventions"),
        forbid("mf_no_delete_events", "DELETE", "intervention_events"),
        forbid("mf_no_update_events", "UPDATE", "intervention_events"),
        forbid("mf_no_delete_audit", "DELETE", "audit_log"),
        forbid("mf_no_update_audit", "UPDATE", "audit_log"),
        forbid("mf_no_delete_signatures", "DELETE", "signatures"),
        forbid("mf_no_delete_attachments", "DELETE", "attachments"),
        "CREATE TRIGGER mf_locked_interventions BEFORE UPDATE ON interventions " +
            "WHEN OLD.status IN ('CLOTURE','ANNULE') AND NEW.status = OLD.status AND (" +
            LOCKED_COLUMNS.joinToString(" OR ") { "NEW.$it IS NOT OLD.$it" } +
            " OR (OLD.archived_pdf_path IS NOT NULL AND NEW.archived_pdf_path IS NOT OLD.archived_pdf_path)) " +
            "BEGIN SELECT RAISE(ABORT, 'MaintiFlow : bon clôturé/annulé, modification interdite'); END",
        "CREATE TRIGGER mf_cancel_final BEFORE UPDATE ON interventions " +
            "WHEN OLD.status = 'ANNULE' AND NEW.status <> 'ANNULE' " +
            "BEGIN SELECT RAISE(ABORT, 'MaintiFlow : un bon annulé ne peut pas être réactivé'); END",
    ) + listOf("INSERT" to "NEW", "UPDATE" to "NEW", "DELETE" to "OLD").map { (op, ref) ->
        "CREATE TRIGGER mf_locked_parts_${op.lowercase()} BEFORE $op ON parts_used " +
            "WHEN (SELECT status FROM interventions WHERE id = $ref.intervention_id) IN ('CLOTURE','ANNULE') " +
            "BEGIN SELECT RAISE(ABORT, 'MaintiFlow : pièces d''un bon clôturé non modifiables'); END"
    }

    /**
     * Crée le schéma si la base est vide. Idempotent. La version est tenue dans la table mf_meta
     * (PRAGMA user_version est réservé à l'outil d'ouverture SQLite d'Android).
     */
    fun migrate(sql: Sql) {
        sql.exec("CREATE TABLE IF NOT EXISTS mf_meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        val current = sql.one("SELECT value FROM mf_meta WHERE key='schema_version'")?.str("value")?.toInt() ?: 0
        if (current >= VERSION) return
        sql.transaction {
            (TABLES + TRIGGERS).forEach { sql.exec(it) }
            sql.exec("INSERT OR REPLACE INTO mf_meta(key, value) VALUES ('schema_version', ?)", VERSION.toString())
        }
    }
}
