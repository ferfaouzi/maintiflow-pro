package com.maintiflow.server

import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID

/**
 * Mise en service du téléphone maître. Aucun mot de passe n'est écrit dans l'APK : l'administrateur
 * le choisit sur le téléphone au premier démarrage. Le jeu de démonstration est 100 % fictif
 * (mêmes lignes, machines et comptes que backend/scripts/seed_demo.py).
 */
object Seed {
    private val LINES = listOf(
        Triple("L1", "Ligne 1 – Coupe et sertissage", listOf(
            "L1-COUP-01" to "Coupeuse-dénudeuse automatique", "L1-SERT-01" to "Presse de sertissage 1",
            "L1-SERT-02" to "Presse de sertissage 2", "L1-ULTRA-01" to "Soudeuse à ultrasons")),
        Triple("L2", "Ligne 2 – Assemblage faisceaux", listOf(
            "L2-PLAN-01" to "Planche d'assemblage rotative", "L2-CONV-01" to "Convoyeur principal",
            "L2-RUBAN-01" to "Rubaneuse automatique")),
        Triple("L3", "Ligne 3 – Contrôle électrique", listOf(
            "L3-TEST-01" to "Banc de test électrique 1", "L3-TEST-02" to "Banc de test électrique 2",
            "L3-VISION-01" to "Poste de contrôle vision")),
    )
    private val CATEGORIES = listOf(
        Triple("MECA", "Mécanique", "NORMALE"), Triple("ELEC", "Électrique", "HAUTE"),
        Triple("PNEU", "Pneumatique", "NORMALE"), Triple("AUTO", "Automatisme / capteur", "HAUTE"),
        Triple("OUTIL", "Outillage / applicateur", "NORMALE"), Triple("QUAL", "Défaut qualité produit", "HAUTE"),
        Triple("SECU", "Sécurité machine", "CRITIQUE"), Triple("AUTRE", "Autre", "BASSE"),
    )
    val DEMO_USERS = listOf(
        Triple("chef.l1", "Amine Chef L1 (fictif)", Role.CHEF_EQUIPE),
        Triple("chef.l2", "Nadia Chef L2 (fictif)", Role.CHEF_EQUIPE),
        Triple("tech.1", "Karim Technicien (fictif)", Role.TECHNICIEN),
        Triple("tech.2", "Youssef Technicien (fictif)", Role.TECHNICIEN),
        Triple("resp.maint", "Leila Responsable Maintenance (fictif)", Role.RESPONSABLE),
    )

    fun isInitialized(sql: Sql): Boolean {
        Schema.migrate(sql)
        return sql.one("SELECT id FROM sites LIMIT 1") != null
    }

    /**
     * Crée le site et le compte « admin ». Avec [withDemo], ajoute aussi les lignes, machines, types de
     * pannes et comptes fictifs (chef.l1, tech.1…), avec le même mot de passe que l'administrateur.
     */
    fun initialize(sql: Sql, adminPassword: String, company: String?, withDemo: Boolean, iterations: Int, now: Long) {
        Security.checkPasswordPolicy(adminPassword)
        Schema.migrate(sql)
        sql.transaction {
            if (sql.one("SELECT id FROM sites LIMIT 1") != null) throw ApiException(409, "Serveur déjà initialisé")
            val siteName = if (withDemo) "Usine de démonstration – Faisceaux automobiles" else company?.ifBlank { null } ?: "Mon usine"
            sql.exec("INSERT INTO sites(code, name) VALUES (?, ?)", if (withDemo) "DEMO" else "SITE", siteName)
            val siteId = sql.lastInsertId()
            company?.trim()?.takeIf { it.isNotEmpty() }?.let {
                SettingsStore(sql).set(siteId, "company_display_name", JsonPrimitive(it.take(120)))
            }
            val hash = Security.hashPassword(adminPassword, iterations)
            val users = mutableListOf(Triple("admin", "Administrateur", Role.ADMIN))
            if (withDemo) {
                users += DEMO_USERS
                for ((code, name, machines) in LINES) {
                    sql.exec("INSERT INTO lines(site_id, code, name) VALUES (?,?,?)", siteId, code, name)
                    val lineId = sql.lastInsertId()
                    machines.forEach { (mc, mn) ->
                        sql.exec("INSERT INTO machines(line_id, code, name, qr_token) VALUES (?,?,?,?)",
                            lineId, mc, mn, UUID.randomUUID().toString())
                    }
                }
                CATEGORIES.forEach { (code, name, prio) ->
                    sql.exec("INSERT INTO failure_categories(site_id, code, name, default_priority) VALUES (?,?,?,?)",
                        siteId, code, name, prio)
                }
            }
            users.forEach { (username, fullName, role) ->
                sql.exec(
                    "INSERT INTO users(site_id, username, full_name, role, password_hash, must_change_password, created_at) " +
                        "VALUES (?,?,?,?,?,0,?)", siteId, username, fullName, role, hash, now,
                )
            }
            sql.exec(
                "INSERT INTO audit_log(at, username, action, entity, detail) VALUES (?,?,?,?,?)",
                now, "admin", "SERVER_INITIALIZED", "site", "{\"demo\":$withDemo}",
            )
        }
    }

    /** Depuis le téléphone maître lui-même (accès physique) : nouveau mot de passe pour « admin ». */
    fun resetAdminPassword(sql: Sql, newPassword: String, iterations: Int, now: Long) {
        Security.checkPasswordPolicy(newPassword)
        sql.transaction {
            sql.exec(
                "UPDATE users SET password_hash=?, active=1, failed_logins=0, locked_until=NULL, must_change_password=0 " +
                    "WHERE username='admin'", Security.hashPassword(newPassword, iterations),
            )
            if (sql.changes() == 0L) throw ApiException(404, "Compte admin introuvable")
            sql.exec("INSERT INTO audit_log(at, username, action, entity) VALUES (?,?,?,?)",
                now, "admin", "ADMIN_PASSWORD_RESET_ON_DEVICE", "user")
        }
    }
}
