package com.maintiflow.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Une base de la version 1 (téléphone maître déjà en service) passe en version 2 sans perte. */
class MigrationTest {

    private fun v1Database(): JdbcSql {
        val sql = JdbcSql()
        Schema.migrate(sql, 1)
        val hash = Security.hashPassword(PWD, 1000)
        sql.exec("INSERT INTO sites(code, name) VALUES ('T', 'Site test')")
        sql.exec("INSERT INTO lines(site_id, code, name) VALUES (1, 'L1', 'Ligne 1')")
        sql.exec("INSERT INTO machines(line_id, code, name, qr_token) VALUES (1, 'M1', 'Presse', 'q1')")
        sql.exec("INSERT INTO failure_categories(site_id, code, name) VALUES (1, 'MECA', 'Mécanique')")
        for ((name, role) in listOf("chef1" to "CHEF_EQUIPE", "tech1" to "TECHNICIEN", "admin" to "ADMIN")) {
            sql.exec("INSERT INTO users(site_id, username, full_name, role, password_hash, must_change_password, created_at) " +
                "VALUES (1, ?, ?, ?, ?, 0, 0)", name, "$name fictif", role, hash)
        }
        val t0 = 1_700_000_000_000L
        for ((i, status) in listOf("NOUVEAU", "ACCEPTE", "EN_COURS", "REPARE", "VALIDATION_PRODUCTION", "CLOTURE").withIndex()) {
            val assigned = if (status == "NOUVEAU") null else 2
            sql.exec(
                "INSERT INTO interventions(id, number, site_id, line_id, machine_id, category_id, priority, status, description, " +
                    "reported_by_id, assigned_to_id, declared_at, accepted_at, repaired_at, closed_at, diagnosis, created_at, updated_at) " +
                    "VALUES (?,?,1,1,1,1,'NORMALE',?,?,1,?,?,?,?,?,?,?,?)",
                "iv$i", "BI-2026-00000${i + 1}", status, "Panne v1 n°$i", assigned, t0 + i, t0 + i + 1000,
                if (status in setOf("REPARE", "VALIDATION_PRODUCTION", "CLOTURE")) t0 + i + 5000 else null,
                if (status == "CLOTURE") t0 + i + 9000 else null, "Diag $i", t0, t0,
            )
            sql.exec("INSERT INTO intervention_events(intervention_id, at, user_id, type, to_status) VALUES (?,?,1,'CREATION','NOUVEAU')", "iv$i", t0 + i)
            if (assigned != null) {
                sql.exec("INSERT INTO intervention_events(intervention_id, at, user_id, type, from_status, to_status) " +
                    "VALUES (?,?,2,'PRISE_EN_CHARGE','NOUVEAU','ACCEPTE')", "iv$i", t0 + i + 1000)
            }
        }
        return sql
    }

    @Test fun version1DataIsConvertedAndStillProtected() {
        val sql = v1Database()
        Schema.migrate(sql)
        assertEquals("2", sql.one("SELECT value FROM mf_meta WHERE key='schema_version'")!!.str("value"))
        val statuses = sql.query("SELECT id, status FROM interventions ORDER BY id").associate { it.str("id") to it.str("status") }
        assertEquals(mapOf("iv0" to "NOUVEAU", "iv1" to "EN_DIAGNOSTIC", "iv2" to "INTERVENTION_MAINTENANCE", "iv3" to "RESOLUE",
            "iv4" to "RESOLUE", "iv5" to "CLOTURE"), statuses)
        assertEquals("MAINTENANCIER", sql.one("SELECT role FROM users WHERE username='tech1'")!!.str("role"))
        assertEquals("tech1 fictif", sql.one("SELECT last_name FROM users WHERE username='tech1'")!!.str("last_name"))
        assertEquals("MAINTENANCE", sql.one("SELECT service FROM interventions WHERE id='iv2'")!!.str("service"))
        // Historique conservé, protections toujours actives (y compris sur les nouvelles colonnes).
        assertEquals(11L, sql.one("SELECT COUNT(*) AS n FROM intervention_events")!!.long("n"))
        for (stmt in listOf("UPDATE interventions SET title='falsifié' WHERE id='iv5'", "DELETE FROM interventions WHERE id='iv0'",
            "DELETE FROM intervention_events")) {
            try { sql.exec(stmt); fail(stmt) } catch (_: SqlConstraintException) {}
        }
        // Une seconde migration ne fait rien.
        Schema.migrate(sql)
    }

    @Test fun migratedFaultsWorkThroughTheApi() {
        val sql = v1Database()
        TestEnv(sql = sql, fixtures = false).use { env ->
            val tech = env.api("tech1")
            val chef = env.api("chef1")
            assertEquals("MAINTENANCIER", tech.user.s("role"))
            val list = chef.get("/interventions?open_only=true").obj
            assertEquals("5", list.s("total"))
            // Panne ACCEPTE (v1) : le maintenancier reprend en diagnostic et termine avec la fiche jaune.
            var iv = chef.get("/interventions/iv1").obj
            assertEquals("EN_DIAGNOSTIC", iv.s("status"))
            iv = tech.act(iv, "start", 200, "diagnosis" to "Diagnostic repris")
            iv = tech.act(iv, "finish", 200, *env.YELLOW)
            iv = chef.act(iv, "close", 200, "comment" to "Clôture après migration")
            assertEquals("CLOTURE", iv.s("status"))
            // Ancienne panne clôturée lisible, avec son historique et son PDF.
            val old = chef.get("/interventions/iv5").obj
            assertTrue(old.arr("events").any { it.s("type") == "PRISE_EN_CHARGE" })
            assertEquals(200, chef.get("/interventions/iv5/pdf").status)
            assertEquals(200, chef.get("/dashboard").status)
        }
    }
}
