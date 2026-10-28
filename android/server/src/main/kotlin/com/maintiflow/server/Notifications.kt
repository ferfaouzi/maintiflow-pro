package com.maintiflow.server

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Notifications internes : une ligne par destinataire, lue par l'application et la page web qui
 * interrogent le serveur régulièrement (pas de service Google nécessaire sur un Wi-Fi d'atelier).
 * L'auteur d'une action n'est jamais notifié de sa propre action.
 */
class Notifier(private val sql: Sql, private val wf: Workflow) {

    private fun usersWithRoles(siteId: Long, roles: Collection<String>, lineId: Long? = null): List<Long> {
        if (roles.isEmpty()) return emptyList()
        val rows = sql.query(
            "SELECT id, role FROM users WHERE site_id=? AND active=1 AND role IN (${roles.joinToString(",") { "?" }})",
            siteId, *roles.toTypedArray(),
        )
        return rows.filter { r ->
            // Chef de ligne et opérateur : seulement les lignes qui leur sont attribuées (toutes si aucune).
            lineId == null || r.str("role") !in Role.LINE_SCOPED || wf.userLines(r.long("id")).let { it.isEmpty() || lineId in it }
        }.map { it.long("id") }
    }

    private fun push(userIds: Collection<Long>, iv: Iv, type: String, title: String, body: String, now: Long, actor: User?) {
        userIds.toSet().filter { it != actor?.id }.forEach {
            sql.exec(
                "INSERT INTO notifications(user_id, intervention_id, type, title, body, created_at) VALUES (?,?,?,?,?,?)",
                it, iv.id, type, title, body, now,
            )
        }
    }

    private fun summary(iv: Iv): String {
        val r = sql.one(
            "SELECT l.code AS l, m.code AS m, c.name AS c, p.code AS p FROM interventions i JOIN lines l ON l.id = i.line_id " +
                "JOIN machines m ON m.id = i.machine_id JOIN failure_categories c ON c.id = i.category_id " +
                "LEFT JOIN postes p ON p.id = i.poste_id WHERE i.id=?", iv.id,
        )!!
        val where = listOfNotNull(r.str("l"), r.strOrNull("p"), r.str("m")).joinToString(" · ")
        return "$where – ${iv.title ?: r.str("c")} : ${iv.description.take(120)}"
    }

    private fun serviceUsers(iv: Iv, service: String?): List<Long> =
        iv.assignedToId?.let { listOf(it) } ?: usersWithRoles(iv.siteId, listOf(Service.roleOf(service ?: Service.MAINTENANCE)))

    private fun chefs(iv: Iv) = usersWithRoles(iv.siteId, Role.CHEFS + Role.RESPONSABLE, iv.lineId)

    /** Appelé après la création d'une panne. */
    fun created(iv: Iv, actor: User, now: Long) {
        val s = summary(iv)
        val critical = iv.priority == "CRITIQUE"
        val to = chefs(iv) + serviceUsers(iv, iv.service) +
            (if (critical) usersWithRoles(iv.siteId, listOf(Role.METHODE, Role.QUALITE, Role.MAINTENANCIER)) else emptyList())
        push(to, iv, if (critical) "PANNE_CRITIQUE" else "NOUVELLE_PANNE",
            "${if (critical) "Panne CRITIQUE" else "Nouvelle panne"} ${iv.number}", s, now, actor)
    }

    /** Appelé après une action ; [before] est le statut avant l'action. */
    fun afterAction(iv: Iv, actor: User, action: String, before: String, comment: String?, now: Long) {
        val s = summary(iv)
        val reason = comment?.let { " Motif : $it" }.orEmpty()
        when (action) {
            "assign", "transfer" -> push(serviceUsers(iv, iv.service), iv, "AFFECTATION",
                "Panne ${iv.number} affectée à ${Service.LABELS[iv.service] ?: "vous"}", s + reason, now, actor)
            "wait_part" -> push(usersWithRoles(iv.siteId, listOf(Role.LOGISTIQUE)) + chefs(iv), iv, "ATTENTE_PIECE",
                "Pièce demandée pour ${iv.number}", s, now, actor)
            "part_delivered", "part_unavailable" -> push(listOfNotNull(iv.assignedToId), iv, "PIECE",
                if (action == "part_delivered") "Pièce livrée pour ${iv.number}" else "Pièce indisponible pour ${iv.number}",
                s + reason, now, actor)
            "finish", "quality_validate", "reject_resolution", "quality_reject" -> when (iv.status) {
                St.EN_ATTENTE_QUALITE -> push(usersWithRoles(iv.siteId, listOf(Role.QUALITE)), iv, "ATTENTE_QUALITE",
                    "Validation Qualité attendue : ${iv.number}", s, now, actor)
                St.RESOLUE -> push(chefs(iv) + iv.reportedById, iv, "A_CLOTURER", "Panne ${iv.number} résolue, à clôturer", s, now, actor)
                in St.WORK -> push(listOfNotNull(iv.assignedToId), iv, "REFUS",
                    "Panne ${iv.number} renvoyée en intervention", s + reason, now, actor)
                else -> Unit
            }
            "close" -> push(listOfNotNull(iv.reportedById, iv.assignedToId), iv, "CLOTURE", "Panne ${iv.number} clôturée", s, now, actor)
            "reopen" -> {
                val previous = sql.one(
                    "SELECT user_id FROM intervention_events WHERE intervention_id=? AND type='PRISE_EN_CHARGE' ORDER BY id DESC LIMIT 1", iv.id,
                )?.longOrNull("user_id")
                push(chefs(iv) + usersWithRoles(iv.siteId, listOf(Role.MAINTENANCIER)) + listOfNotNull(previous), iv,
                    "REOUVERTURE", "Panne ${iv.number} réouverte (récidive)", s + reason, now, actor)
            }
            "cancel" -> if (before != St.NOUVEAU) push(listOfNotNull(iv.assignedToId, iv.reportedById), iv, "ANNULATION",
                "Panne ${iv.number} annulée", s + reason, now, actor)
        }
    }

    /** Pannes non prises en charge dans le délai de leur priorité : signalées une seule fois. */
    fun sweepLate(siteId: Long, now: Long) {
        val rows = sql.query(
            "SELECT * FROM interventions WHERE site_id=? AND status IN ('NOUVEAU','EN_ATTENTE','REOUVERTE') AND late_notified_at IS NULL",
            siteId,
        )
        for (r in rows) {
            val iv = Iv(r)
            if (!wf.isLate(iv, now)) continue
            push(chefs(iv) + serviceUsers(iv, iv.service), iv, "RETARD",
                "Retard de prise en charge : ${iv.number}", summary(iv), now, null)
            iv.lateNotifiedAt = now
            iv.save(sql, now)
        }
    }

    fun list(user: User, unreadOnly: Boolean, limit: Int): JsonObject {
        val cond = if (unreadOnly) " AND n.read_at IS NULL" else ""
        val rows = sql.query(
            "SELECT n.*, i.number FROM notifications n LEFT JOIN interventions i ON i.id = n.intervention_id " +
                "WHERE n.user_id=?$cond ORDER BY n.id DESC LIMIT ?", user.id, limit,
        )
        val unread = sql.one("SELECT COUNT(*) AS n FROM notifications WHERE user_id=? AND read_at IS NULL", user.id)!!.long("n")
        return buildJsonObject {
            put("unread", unread)
            put("items", JsonArray(rows.map { n ->
                buildJsonObject {
                    put("id", n.long("id")); put("type", n.str("type")); put("title", n.str("title")); put("body", n.str("body"))
                    put("intervention_id", n.strOrNull("intervention_id")); put("number", n.strOrNull("number"))
                    put("created_at", iso(n.long("created_at"))); put("read", n.longOrNull("read_at") != null)
                }
            }))
        }
    }

    fun markRead(user: User, ids: List<Long>?, now: Long) {
        if (ids == null) {
            sql.exec("UPDATE notifications SET read_at=? WHERE user_id=? AND read_at IS NULL", now, user.id)
        } else {
            ids.forEach { sql.exec("UPDATE notifications SET read_at=? WHERE id=? AND user_id=? AND read_at IS NULL", now, it, user.id) }
        }
    }
}
