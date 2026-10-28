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
    /** Code, nom, priorité proposée, validation Qualité obligatoire, causes possibles. */
    private class Cat(val code: String, val name: String, val prio: String, val quality: Boolean, val causes: List<String>)

    private val CATEGORIES = listOf(
        Cat("MECA", "Mécanique", "NORMALE", false, listOf("Usure", "Casse", "Desserrage", "Défaut de lubrification")),
        Cat("ELEC", "Électrique", "HAUTE", false, listOf("Câble endommagé", "Fusible / disjoncteur", "Connecteur défectueux")),
        Cat("PNEU", "Pneumatique", "NORMALE", false, listOf("Fuite d'air", "Vérin défectueux", "Pression insuffisante")),
        Cat("AUTO", "Automatisme / capteur", "HAUTE", false, listOf("Capteur défectueux", "Capteur déréglé", "Programme automate")),
        Cat("OUTIL", "Outillage / applicateur", "NORMALE", true, listOf("Applicateur usé", "Réglage hauteur de sertissage", "Lame de coupe usée")),
        Cat("QUAL", "Défaut qualité produit", "HAUTE", true, listOf("Sertissage non conforme", "Longueur de coupe hors tolérance", "Mauvais câble")),
        Cat("SECU", "Sécurité machine", "CRITIQUE", true, listOf("Barrière immatérielle", "Arrêt d'urgence", "Carter de protection")),
        Cat("AUTRE", "Autre", "BASSE", false, emptyList()),
    )

    /** Comptes fictifs : matricule, prénom, nom, rôle, lignes attribuées (codes). */
    val DEMO_USERS = listOf(
        listOf("op.l1", "Sami", "Opérateur L1 (fictif)", Role.OPERATEUR, "L1"),
        listOf("op.l2", "Rania", "Opératrice L2 (fictif)", Role.OPERATEUR, "L2"),
        listOf("cl.l1", "Hichem", "Chef de ligne L1 (fictif)", Role.CHEF_LIGNE, "L1"),
        listOf("chef.l1", "Amine", "Chef d'équipe L1 (fictif)", Role.CHEF_EQUIPE, ""),
        listOf("chef.l2", "Nadia", "Chef d'équipe L2 (fictif)", Role.CHEF_EQUIPE, ""),
        listOf("tech.1", "Karim", "Maintenancier (fictif)", Role.MAINTENANCIER, ""),
        listOf("tech.2", "Youssef", "Maintenancier (fictif)", Role.MAINTENANCIER, ""),
        listOf("methode.1", "Ines", "Méthode (fictif)", Role.METHODE, ""),
        listOf("qualite.1", "Mehdi", "Qualité (fictif)", Role.QUALITE, ""),
        listOf("logistique.1", "Salma", "Logistique (fictif)", Role.LOGISTIQUE, ""),
        listOf("resp.maint", "Leila", "Responsable maintenance (fictif)", Role.RESPONSABLE, ""),
    )

    fun isInitialized(sql: Sql): Boolean {
        Schema.migrate(sql)
        return sql.one("SELECT id FROM sites LIMIT 1") != null
    }

    /**
     * Crée le site et le compte « admin ». Avec [withDemo], ajoute aussi une usine fictive complète (lignes,
     * postes, machines, références câble, catégories et causes) et un compte fictif par rôle (op.l1, chef.l1, tech.1…), avec le même mot de passe que l'administrateur.
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
            val users = mutableListOf(listOf("admin", "", "Administrateur", Role.ADMIN, ""))
            val lineIds = HashMap<String, Long>()
            if (withDemo) {
                users += DEMO_USERS
                for ((code, name, machines) in LINES) {
                    sql.exec("INSERT INTO lines(site_id, code, name) VALUES (?,?,?)", siteId, code, name)
                    val lineId = sql.lastInsertId()
                    lineIds[code] = lineId
                    val postes = listOf("A", "B", "C").map { p ->
                        sql.exec("INSERT INTO postes(line_id, code, name) VALUES (?,?,?)", lineId, "$code-$p", "Poste $p")
                        sql.lastInsertId()
                    }
                    machines.forEachIndexed { i, (mc, mn) ->
                        sql.exec("INSERT INTO machines(line_id, poste_id, code, name, qr_token) VALUES (?,?,?,?,?)",
                            lineId, postes[minOf(i, postes.size - 1)], mc, mn, UUID.randomUUID().toString())
                        if (mc.contains("SERT")) {
                            sql.exec("INSERT INTO tools(site_id, machine_id, code, name) VALUES (?,?,?,?)",
                                siteId, sql.lastInsertId(), "APP-$mc", "Applicateur de sertissage $mc")
                        }
                    }
                }
                for (c in CATEGORIES) {
                    sql.exec("INSERT INTO failure_categories(site_id, code, name, default_priority, requires_quality) VALUES (?,?,?,?,?)",
                        siteId, c.code, c.name, c.prio, c.quality)
                    val catId = sql.lastInsertId()
                    c.causes.forEach { sql.exec("INSERT INTO failure_causes(category_id, name) VALUES (?,?)", catId, it) }
                }
                (1..6).forEach {
                    sql.exec("INSERT INTO cable_refs(site_id, code, name) VALUES (?,?,?)",
                        siteId, "CBL-DEMO-${it.toString().padStart(4, '0')}", "Câble fictif n° $it")
                }
                listOf("CORR" to "Corrective", "REGL" to "Réglage", "OUTIL" to "Changement d'outil",
                    "ELEC" to "Dépannage électrique", "ANALYSE" to "Analyse Méthode").forEach { (code, name) ->
                    sql.exec("INSERT INTO intervention_types(site_id, code, name) VALUES (?,?,?)", siteId, code, name)
                }
            }
            users.forEach { (username, first, last, role, lines) ->
                sql.exec(
                    "INSERT INTO users(site_id, username, first_name, last_name, full_name, role, password_hash, must_change_password, created_at) " +
                        "VALUES (?,?,?,?,?,?,?,0,?)", siteId, username, first, last, User.displayName(first, last), role, hash, now,
                )
                val userId = sql.lastInsertId()
                lines.split(',').filter { it.isNotBlank() }.forEach {
                    sql.exec("INSERT INTO user_lines(user_id, line_id) VALUES (?,?)", userId, lineIds[it])
                }
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
