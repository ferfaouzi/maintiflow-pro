package com.maintiflow.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Base64

/** Paramètres d'un site, stockés en JSON {"v": valeur} comme sur le serveur PC. */
class SettingsStore(private val sql: Sql) {
    fun get(siteId: Long, key: String): JsonElement {
        val row = sql.one("SELECT value FROM app_settings WHERE site_id=? AND key=?", siteId, key)
            ?: return DEFAULT_SETTINGS[key] ?: JsonNull
        return (Json.parseToJsonElement(row.str("value")) as JsonObject)["v"] ?: JsonNull
    }

    fun all(siteId: Long): Map<String, JsonElement> {
        val out = LinkedHashMap(DEFAULT_SETTINGS)
        sql.query("SELECT key, value FROM app_settings WHERE site_id=?", siteId).forEach {
            if (it.str("key") in DEFAULT_SETTINGS) {
                out[it.str("key")] = (Json.parseToJsonElement(it.str("value")) as JsonObject)["v"] ?: JsonNull
            }
        }
        return out
    }

    fun set(siteId: Long, key: String, value: JsonElement) {
        require(key in DEFAULT_SETTINGS) { key }
        val v = JsonObject(mapOf("v" to value)).toString()
        sql.exec(
            "INSERT INTO app_settings(site_id, key, value) VALUES (?,?,?) " +
                "ON CONFLICT(site_id, key) DO UPDATE SET value=excluded.value", siteId, key, v,
        )
    }

    fun bool(siteId: Long, key: String) = (get(siteId, key) as? JsonPrimitive)?.content == "true"
    fun int(siteId: Long, key: String) = (get(siteId, key) as? JsonPrimitive)?.intOrNull ?: 0
}

/**
 * Règles métier d'une panne : qui peut faire quelle action, à quel statut, et ce que l'action enregistre.
 * Le serveur est la seule autorité : l'écran ne fait qu'afficher [allowedActions].
 *
 *   NOUVEAU / EN_ATTENTE / REOUVERTE --prise en charge--> EN_DIAGNOSTIC --démarrage--> INTERVENTION_MAINTENANCE
 *   ou INTERVENTION_METHODE (transfert possible) <-> EN_ATTENTE_PIECE ; fin --> EN_ATTENTE_QUALITE (si la
 *   catégorie l'exige) --> RESOLUE --clôture justifiée--> CLOTURE --récidive--> REOUVERTE ; ANNULE avec motif.
 */
class Workflow(private val sql: Sql, private val settings: SettingsStore) {

    class Action(val name: String, val states: Set<String>, val roles: Set<String>, val label: String)

    companion object {
        private val S = St
        private val R = Role

        /** Page rose, modifiable tant que personne n'a pris la panne en charge. */
        val PINK_FIELDS = setOf("title", "description", "category_id", "poste_id", "cable_ref_id", "production_stopped", "stop_start_at")
        val STOP_FIELDS = setOf("production_stopped", "stop_start_at")
        /** Page jaune, remplie par l'intervenant. */
        val YELLOW_FIELDS = setOf("intervention_type_id", "diagnosis", "cause_id", "root_cause", "tool_id",
            "corrective_action", "test_result", "observations", "machine_final_state")
        val ALL_FIELDS = PINK_FIELDS + YELLOW_FIELDS + setOf("priority", "quality_observations")
        /** États où l'intervenant affecté remplit la page jaune. */
        val INTERVENANT_STATES = setOf(S.EN_DIAGNOSTIC, S.EN_ATTENTE_PIECE) + S.WORK
        /** Actions réservées à l'intervenant affecté. */
        val ASSIGNEE_ONLY = setOf("arrive", "start", "pause", "resume", "wait_part", "finish")
        const val MAX_SIGNATURE_BYTES = 200_000

        val ACTIONS: Map<String, Action> = listOf(
            Action("assign", setOf(S.NOUVEAU, S.EN_ATTENTE, S.REOUVERTE, S.EN_DIAGNOSTIC), R.CHEFS + R.RESPONSABLE, "Affecter"),
            Action("accept", S.WAITING_TAKEOVER, R.INTERVENANTS, "Prendre en charge"),
            Action("arrive", setOf(S.EN_DIAGNOSTIC), R.INTERVENANTS, "Arrivé sur machine"),
            Action("start", setOf(S.EN_DIAGNOSTIC), R.INTERVENANTS, "Démarrer l'intervention"),
            Action("transfer", setOf(S.EN_DIAGNOSTIC) + S.WORK, R.INTERVENANTS + R.CHEF_EQUIPE + R.RESPONSABLE,
                "Transférer Maintenance / Méthode"),
            Action("pause", S.WORK, R.INTERVENANTS, "Suspendre"),
            Action("resume", S.WORK + S.EN_ATTENTE_PIECE, R.INTERVENANTS, "Reprendre"),
            Action("wait_part", S.WORK, R.INTERVENANTS, "Demander une pièce"),
            Action("part_delivered", setOf(S.EN_ATTENTE_PIECE), setOf(R.LOGISTIQUE), "Pièce livrée"),
            Action("part_unavailable", setOf(S.EN_ATTENTE_PIECE), setOf(R.LOGISTIQUE), "Pièce indisponible"),
            Action("finish", S.WORK, R.INTERVENANTS, "Terminer l'intervention"),
            Action("quality_validate", setOf(S.EN_ATTENTE_QUALITE), setOf(R.QUALITE), "Valider (Qualité)"),
            Action("quality_reject", setOf(S.EN_ATTENTE_QUALITE), setOf(R.QUALITE), "Refuser (Qualité)"),
            Action("validate_maintenance", setOf(S.RESOLUE), setOf(R.RESPONSABLE), "Valider (Responsable)"),
            Action("close", setOf(S.RESOLUE), R.CHEFS + R.RESPONSABLE, "Clôturer"),
            Action("reject_resolution", setOf(S.RESOLUE), R.CHEFS + R.RESPONSABLE, "Refuser : panne non résolue"),
            Action("cancel", S.OPEN, R.CHEFS + R.RESPONSABLE, "Annuler"),
            Action("reopen", setOf(S.CLOTURE), R.CHEFS + R.METHODE + R.QUALITE + R.RESPONSABLE, "Réouvrir (récidive)"),
            Action("flag_recurring", S.OPEN, R.DECLARANTS, "Signaler panne récurrente"),
        ).associateBy { it.name }

        /** Motif obligatoire (5 caractères minimum) pour ces actions. */
        val NEEDS_REASON = setOf("transfer", "pause", "part_unavailable", "quality_reject", "close",
            "reject_resolution", "cancel", "reopen")
    }

    // ---------------------------------------------------------------- visibilité
    fun userLines(userId: Long): List<Long> =
        sql.query("SELECT line_id FROM user_lines WHERE user_id=? ORDER BY line_id", userId).map { it.long("line_id") }

    /** Condition SQL (sur l'alias i) qui restreint une recherche à ce que l'utilisateur peut voir. */
    fun visibilitySql(user: User): Pair<String, List<Any?>> {
        val cond = mutableListOf("i.site_id = ?")
        val args = mutableListOf<Any?>(user.siteId)
        if (user.role in Role.LINE_SCOPED) {
            cond += "(NOT EXISTS (SELECT 1 FROM user_lines ul WHERE ul.user_id = ?) OR " +
                "i.line_id IN (SELECT line_id FROM user_lines WHERE user_id = ?))"
            args += user.id; args += user.id
        }
        if (user.role == Role.LOGISTIQUE) cond += "EXISTS (SELECT 1 FROM part_requests pr WHERE pr.intervention_id = i.id)"
        return cond.joinToString(" AND ") to args
    }

    fun canView(user: User, iv: Iv): Boolean {
        if (user.siteId != iv.siteId) return false
        if (user.role in Role.LINE_SCOPED) {
            val lines = userLines(user.id)
            if (lines.isNotEmpty() && iv.lineId !in lines) return false
        }
        if (user.role == Role.LOGISTIQUE) {
            return sql.one("SELECT 1 AS x FROM part_requests WHERE intervention_id=? LIMIT 1", iv.id) != null
        }
        return true
    }

    /** Lignes sur lesquelles l'utilisateur peut déclarer (null = toutes les lignes du site). */
    fun declarableLines(user: User): List<Long>? =
        if (user.role in Role.LINE_SCOPED) userLines(user.id).takeIf { it.isNotEmpty() } else null

    fun isAssignee(user: User, iv: Iv) = user.role in Role.INTERVENANTS && iv.assignedToId == user.id

    // ---------------------------------------------------------------- empreinte de contenu
    fun contentSha256(iv: Iv): String {
        fun code(table: String, id: Long?) = id?.let { sql.one("SELECT code FROM $table WHERE id=?", it)?.str("code") }
        fun name(table: String, id: Long?) = id?.let { sql.one("SELECT name FROM $table WHERE id=?", it)?.str("name") }
        fun username(id: Long?) = id?.let { sql.one("SELECT username FROM users WHERE id=?", it)?.str("username") }
        val parts = sql.query(
            "SELECT reference, designation, quantity, unit FROM parts_used WHERE intervention_id=? ORDER BY id", iv.id,
        ).map { p -> JsonArray(listOf("reference", "designation", "quantity", "unit").map { JsonPrimitive(p.str(it)) }) }
        val payload: Map<String, JsonElement> = sortedMapOf(
            "number" to JsonPrimitive(iv.number),
            "revision" to JsonPrimitive(iv.revision),
            "line" to JsonPrimitive(code("lines", iv.lineId)),
            "poste" to JsonPrimitive(code("postes", iv.posteId)),
            "machine" to JsonPrimitive(code("machines", iv.machineId)),
            "cable_ref" to JsonPrimitive(code("cable_refs", iv.cableRefId)),
            "category" to JsonPrimitive(code("failure_categories", iv.categoryId)),
            "title" to JsonPrimitive(iv.title),
            "priority" to JsonPrimitive(iv.priority),
            "description" to JsonPrimitive(iv.description),
            "reported_by" to JsonPrimitive(username(iv.reportedById)),
            "assigned_to" to JsonPrimitive(username(iv.assignedToId)),
            "service" to JsonPrimitive(iv.service),
            "declared_at" to JsonPrimitive(iso(iv.declaredAt)),
            "production_stopped" to JsonPrimitive(iv.productionStopped),
            "stop_start_at" to JsonPrimitive(iso(iv.stopStartAt)),
            "accepted_at" to JsonPrimitive(iso(iv.acceptedAt)),
            "arrived_at" to JsonPrimitive(iso(iv.arrivedAt)),
            "started_at" to JsonPrimitive(iso(iv.startedAt)),
            "repaired_at" to JsonPrimitive(iso(iv.repairedAt)),
            "intervention_type" to JsonPrimitive(code("intervention_types", iv.interventionTypeId)),
            "diagnosis" to JsonPrimitive(iv.diagnosis),
            "cause" to JsonPrimitive(name("failure_causes", iv.causeId)),
            "root_cause" to JsonPrimitive(iv.rootCause),
            "tool" to JsonPrimitive(code("tools", iv.toolId)),
            "corrective_action" to JsonPrimitive(iv.correctiveAction),
            "test_result" to JsonPrimitive(iv.testResult),
            "observations" to JsonPrimitive(iv.observations),
            "quality_observations" to JsonPrimitive(iv.qualityObservations),
            "machine_final_state" to JsonPrimitive(iv.machineFinalState),
            "parts" to JsonArray(parts),
        )
        return Security.sha256Hex(JsonObject(payload).toString())
    }

    // ---------------------------------------------------------------- historique
    fun addEvent(
        iv: Iv, user: User?, type: String, at: Long, to: String? = null, comment: String? = null,
        data: JsonObject? = null, fromStatus: String? = if (to != null) iv.status else null,
    ) {
        sql.exec(
            "INSERT INTO intervention_events(intervention_id, at, user_id, type, from_status, to_status, comment, data) " +
                "VALUES (?,?,?,?,?,?,?,?)",
            iv.id, at, user?.id, type, fromStatus, to, comment, data?.toString(),
        )
        if (to != null) iv.status = to
    }

    fun supersedeSignatures(iv: Iv, roles: Set<String>? = null) {
        if (roles == null) {
            sql.exec("UPDATE signatures SET superseded=1 WHERE intervention_id=? AND superseded=0", iv.id)
        } else {
            roles.forEach {
                sql.exec("UPDATE signatures SET superseded=1 WHERE intervention_id=? AND superseded=0 AND role=?", iv.id, it)
            }
        }
    }

    fun hasActiveSignature(iv: Iv, role: String): Boolean = sql.one(
        "SELECT id FROM signatures WHERE intervention_id=? AND role=? AND superseded=0 AND revision=?",
        iv.id, role, iv.revision,
    ) != null

    private fun decodeSignature(b64: String?): ByteArray? {
        if (b64.isNullOrEmpty()) return null
        val raw = try {
            Base64.getDecoder().decode(b64)
        } catch (_: IllegalArgumentException) {
            throw ApiException(422, "Signature invalide (base64)")
        }
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())
        if (raw.size > MAX_SIGNATURE_BYTES || raw.size < 4 || !raw.copyOfRange(0, 4).contentEquals(png)) {
            throw ApiException(422, "Signature invalide : image PNG de 200 Ko maximum attendue")
        }
        return raw
    }

    private fun sign(iv: Iv, user: User, role: String, imageB64: String?, now: Long) {
        val image = decodeSignature(imageB64)
        val sha = contentSha256(iv)
        sql.exec(
            "INSERT INTO signatures(intervention_id, role, user_id, signed_at, revision, content_sha256, image_png, superseded) " +
                "VALUES (?,?,?,?,?,?,?,0)", iv.id, role, user.id, now, iv.revision, sha, image,
        )
        addEvent(iv, user, "SIGNATURE", now, data = jsonObj {
            put("role", JsonPrimitive(role)); put("sha256", JsonPrimitive(sha))
        })
    }

    private fun requiresQuality(iv: Iv): Boolean =
        sql.one("SELECT requires_quality FROM failure_categories WHERE id=?", iv.categoryId)?.bool("requires_quality") ?: false

    // ---------------------------------------------------------------- autorisations
    /** Erreur 403 (droits) ou 409 (état) ; le message est affiché tel quel à l'utilisateur. */
    class Refusal(val status: Int, val reason: String)

    fun check(user: User, iv: Iv, name: String): Refusal? {
        val a = ACTIONS[name] ?: return Refusal(404, "Action inconnue")
        if (!canView(user, iv)) return Refusal(404, "Panne introuvable")
        if (user.role !in a.roles) return Refusal(403, "Action « ${a.label} » non autorisée pour le rôle ${Role.LABELS[user.role] ?: user.role}")
        if (iv.status !in a.states) {
            return Refusal(409, "Action « ${a.label} » impossible au statut « ${St.LABELS[iv.status] ?: iv.status} »")
        }
        if (name == "accept") {
            if (iv.assignedToId != null && iv.assignedToId != user.id) return Refusal(403, "Cette panne est affectée à une autre personne")
            if (iv.assignedToId == null && iv.service != null && iv.service != Role.service(user.role)) {
                return Refusal(403, "Cette panne est adressée au service ${Service.LABELS[iv.service]}")
            }
        }
        if (name in ASSIGNEE_ONLY && !isAssignee(user, iv)) return Refusal(403, "Seul l'intervenant qui a pris la panne en charge peut faire cette action")
        if (name == "transfer" && user.role in Role.INTERVENANTS && !isAssignee(user, iv)) {
            return Refusal(403, "Seul l'intervenant qui a pris la panne en charge peut la transférer")
        }
        if (name == "arrive" && iv.arrivedAt != null) return Refusal(409, "Arrivée déjà enregistrée")
        if (name == "pause" && iv.paused) return Refusal(409, "Intervention déjà suspendue")
        if (name == "resume" && iv.status in St.WORK && !iv.paused) return Refusal(409, "Intervention non suspendue")
        if (name == "validate_maintenance") {
            if (!settings.bool(iv.siteId, "require_manager_validation")) return Refusal(409, "La validation Responsable n'est pas requise sur ce site")
            if (iv.maintenanceValidatedAt != null) return Refusal(409, "Déjà validée par le Responsable")
        }
        if (name == "close" && settings.bool(iv.siteId, "require_manager_validation") && iv.maintenanceValidatedAt == null) {
            return Refusal(409, "Le Responsable maintenance doit valider avant la clôture")
        }
        if (name == "flag_recurring" && iv.recurring) return Refusal(409, "Déjà signalée comme récurrente")
        return null
    }

    fun allowedActions(user: User, iv: Iv): List<String> = ACTIONS.keys.filter { check(user, iv, it) == null }

    fun editableFields(user: User, iv: Iv): Set<String> {
        if (iv.locked || !canView(user, iv)) return emptySet()
        val out = mutableSetOf<String>()
        val chef = user.role in Role.CHEFS || user.role == Role.RESPONSABLE
        val reporter = iv.reportedById == user.id
        if (reporter || chef) {
            out += if (iv.status in setOf(St.NOUVEAU, St.EN_ATTENTE)) PINK_FIELDS else STOP_FIELDS
        }
        if (chef) out += "priority"
        if (isAssignee(user, iv) && iv.status in INTERVENANT_STATES) out += YELLOW_FIELDS
        if (user.role == Role.RESPONSABLE) out += YELLOW_FIELDS + "category_id"
        if (user.role == Role.QUALITE && iv.status == St.EN_ATTENTE_QUALITE) out += "quality_observations"
        return out
    }

    fun checkVersion(iv: Iv, version: Long?) {
        if (version != null && version != iv.version) {
            throw ApiException(409, "La panne a été modifiée entre-temps (version serveur ${iv.version}). Rechargez-la.")
        }
    }

    fun ensureNotLocked(iv: Iv) {
        if (iv.locked) throw ApiException(409, "Panne clôturée ou annulée : modification interdite (réouverture tracée nécessaire)")
    }

    // ---------------------------------------------------------------- vérification des références
    fun checkRef(table: String, id: Long?, siteId: Long, what: String, extra: String = "", vararg args: Any?): Row? {
        if (id == null) return null
        val siteJoin = when (table) {
            "postes" -> "SELECT t.*, l.site_id AS sid FROM postes t JOIN lines l ON l.id = t.line_id WHERE t.id=?"
            "failure_causes" -> "SELECT t.*, c.site_id AS sid FROM failure_causes t JOIN failure_categories c ON c.id = t.category_id WHERE t.id=?"
            else -> "SELECT t.*, t.site_id AS sid FROM $table t WHERE t.id=?"
        }
        val r = sql.one(siteJoin + extra, id, *args)
        if (r == null || r.long("sid") != siteId || !r.bool("active")) throw ApiException(422, "$what invalide")
        return r
    }

    private fun intervenant(id: Long?, siteId: Long, service: String?): User? {
        if (id == null) return null
        val u = sql.one("SELECT * FROM users WHERE id=?", id)?.let { User.of(it) }
        if (u == null || u.role !in Role.INTERVENANTS || !u.active || u.siteId != siteId) {
            throw ApiException(422, "Intervenant invalide : choisissez un maintenancier ou une personne Méthode active")
        }
        if (service != null && Role.service(u.role) != service) {
            throw ApiException(422, "Cet intervenant n'appartient pas au service ${Service.LABELS[service]}")
        }
        return u
    }

    // ---------------------------------------------------------------- exécution
    fun perform(user: User, iv: Iv, name: String, p: Body, t: Long) {
        check(user, iv, name)?.let { throw ApiException(it.status, it.reason) }
        checkVersion(iv, p.long("version"))
        val comment = p.str("comment", max = 2000)?.trim()?.ifEmpty { null }
        if (name in NEEDS_REASON && (comment == null || comment.length < 5)) {
            throw ApiException(422, "Motif obligatoire (5 caractères minimum)")
        }
        val priority = p.enum("priority", Priority.ALL)
        val signature = p.str("signature_png_base64")

        when (name) {
            "assign" -> {
                val serviceIn = p.enum("service", Service.ALL)
                val tech = intervenant(p.long("technician_id"), iv.siteId, serviceIn)
                if (tech == null && serviceIn == null) throw ApiException(422, "Choisissez une personne ou un service")
                val service = tech?.let { Role.service(it.role) } ?: serviceIn!!
                val data = LinkedHashMap<String, JsonElement>()
                data["service"] = JsonPrimitive(service)
                tech?.let { data["technician"] = JsonPrimitive(it.username) }
                if (priority != null && priority != iv.priority) {
                    data["priority"] = JsonArray(listOf(JsonPrimitive(iv.priority), JsonPrimitive(priority)))
                    iv.priority = priority
                }
                val changed = tech?.id != iv.assignedToId || service != iv.service
                if (iv.status == St.EN_DIAGNOSTIC && !changed) throw ApiException(409, "Cette personne a déjà pris la panne en charge")
                if (iv.status == St.EN_DIAGNOSTIC) iv.arrivedAt = null
                iv.assignedToId = tech?.id
                iv.service = service
                addEvent(iv, user, "AFFECTATION", t, to = St.EN_ATTENTE, comment = comment, data = JsonObject(data))
            }
            "accept" -> {
                iv.assignedToId = user.id
                iv.service = Role.service(user.role)
                iv.acceptedAt = iv.acceptedAt ?: t
                addEvent(iv, user, "PRISE_EN_CHARGE", t, to = St.EN_DIAGNOSTIC, comment = comment,
                    data = jsonObj { put("service", JsonPrimitive(iv.service)) })
            }
            "arrive" -> {
                iv.arrivedAt = t
                addEvent(iv, user, "ARRIVEE", t, comment = comment)
            }
            "start" -> {
                p.str("diagnosis", max = 4000)?.trim()?.ifEmpty { null }?.let { iv.diagnosis = it }
                p.long("intervention_type_id")?.let { checkRef("intervention_types", it, iv.siteId, "Type d'intervention"); iv.interventionTypeId = it }
                if (iv.diagnosis.isNullOrBlank()) throw ApiException(422, "Saisissez le diagnostic avant de démarrer l'intervention")
                iv.arrivedAt = iv.arrivedAt ?: t
                iv.startedAt = iv.startedAt ?: t
                addEvent(iv, user, "DEMARRAGE", t, to = St.work(iv.service), comment = comment)
            }
            "transfer" -> {
                val target = p.enum("service", Service.ALL) ?: throw ApiException(422, "Choisissez le service destinataire")
                if (target == iv.service) throw ApiException(422, "La panne est déjà au service ${Service.LABELS[target]}")
                val tech = intervenant(p.long("technician_id"), iv.siteId, target)
                val from = iv.service
                iv.service = target
                iv.assignedToId = tech?.id
                iv.paused = false
                iv.arrivedAt = null
                addEvent(iv, user, "TRANSFERT", t, to = St.EN_ATTENTE, comment = comment, data = jsonObj {
                    put("from", JsonPrimitive(from)); put("to", JsonPrimitive(target))
                    tech?.let { put("technician", JsonPrimitive(it.username)) }
                })
            }
            "pause" -> {
                iv.paused = true
                addEvent(iv, user, "PAUSE", t, comment = comment)
            }
            "resume" -> {
                if (iv.status == St.EN_ATTENTE_PIECE) {
                    addEvent(iv, user, "REPRISE", t, to = St.work(iv.service), comment = comment)
                } else {
                    iv.paused = false
                    addEvent(iv, user, "RESUME", t, comment = comment)
                }
            }
            "wait_part" -> {
                val reference = p.str("reference", max = 60)?.trim()?.ifEmpty { null }
                val designation = p.str("designation", max = 160)?.trim()?.ifEmpty { null }
                if (reference == null && designation == null && comment == null) {
                    throw ApiException(422, "Indiquez la pièce demandée (référence ou désignation)")
                }
                val qty = quantity(p, "quantity") ?: "1"
                sql.exec(
                    "INSERT INTO part_requests(intervention_id, reference, designation, quantity, requested_by_id, requested_at, comment) " +
                        "VALUES (?,?,?,?,?,?,?)", iv.id, reference ?: "—", designation ?: comment, qty, user.id, t, comment,
                )
                iv.paused = false
                addEvent(iv, user, "ATTENTE_PIECE", t, to = St.EN_ATTENTE_PIECE, comment = comment, data = jsonObj {
                    put("reference", JsonPrimitive(reference)); put("designation", JsonPrimitive(designation))
                    put("quantity", JsonPrimitive(qty))
                })
            }
            "part_delivered" -> {
                sql.exec("UPDATE part_requests SET status='LIVREE', handled_by_id=?, handled_at=?, comment=COALESCE(?, comment) " +
                    "WHERE intervention_id=? AND status IN ('DEMANDEE','INDISPONIBLE')", user.id, t, comment, iv.id)
                addEvent(iv, user, "PIECE_LIVREE", t, to = St.work(iv.service), comment = comment)
            }
            "part_unavailable" -> {
                sql.exec("UPDATE part_requests SET status='INDISPONIBLE', handled_by_id=?, handled_at=?, comment=? " +
                    "WHERE intervention_id=? AND status='DEMANDEE'", user.id, t, comment, iv.id)
                addEvent(iv, user, "PIECE_INDISPONIBLE", t, comment = comment)
            }
            "finish" -> {
                p.str("diagnosis", max = 4000)?.trim()?.ifEmpty { null }?.let { iv.diagnosis = it }
                p.str("root_cause", max = 4000)?.trim()?.ifEmpty { null }?.let { iv.rootCause = it }
                p.long("cause_id")?.let { checkCause(iv, it); iv.causeId = it }
                p.str("corrective_action", max = 4000)?.trim()?.ifEmpty { null }?.let { iv.correctiveAction = it }
                p.str("test_result", max = 4000)?.trim()?.ifEmpty { null }?.let { iv.testResult = it }
                p.enum("machine_final_state", MachineState.ALL)?.let { iv.machineFinalState = it }
                val missing = listOf(
                    iv.diagnosis to "diagnostic", (iv.rootCause ?: iv.causeId?.toString()) to "cause",
                    iv.correctiveAction to "actions réalisées", iv.testResult to "résultat des tests",
                    iv.machineFinalState to "état final de la machine",
                ).filter { it.first.isNullOrEmpty() }.map { it.second }
                if (missing.isNotEmpty()) {
                    throw ApiException(422, "Champs obligatoires avant de terminer : " + missing.joinToString(", "))
                }
                iv.paused = false
                iv.repairedAt = t
                sign(iv, user, SigRole.MAINTENANCE, signature, t)
                val to = if (requiresQuality(iv)) St.EN_ATTENTE_QUALITE else St.RESOLUE
                addEvent(iv, user, "INTERVENTION_TERMINEE", t, to = to, comment = comment)
            }
            "quality_validate" -> {
                iv.qualityObservations = listOfNotNull(iv.qualityObservations, comment).joinToString("\n")
                iv.qualityValidatedAt = t
                iv.qualityById = user.id
                sign(iv, user, SigRole.QUALITE, signature, t)
                addEvent(iv, user, "VALIDATION_QUALITE", t, to = St.RESOLUE, comment = comment)
            }
            "quality_reject" -> {
                iv.qualityObservations = listOfNotNull(iv.qualityObservations, "Refus : $comment").joinToString("\n")
                supersedeSignatures(iv, setOf(SigRole.MAINTENANCE))
                iv.repairedAt = null
                addEvent(iv, user, "REFUS_QUALITE", t, to = St.work(iv.service), comment = comment)
            }
            "validate_maintenance" -> {
                sign(iv, user, SigRole.RESPONSABLE, signature, t)
                iv.maintenanceValidatedAt = t
                addEvent(iv, user, "VALIDATION_MAINTENANCE", t, comment = comment)
            }
            "close" -> {
                var stopEnd: Long? = null
                if (iv.productionStopped) {
                    stopEnd = p.instant("stop_end_at")?.toEpochMilli() ?: iv.stopEndAt ?: t
                    if (stopEnd > t + 5 * 60_000 || (iv.stopStartAt != null && stopEnd < iv.stopStartAt!!)) {
                        throw ApiException(422, "Heure de reprise de production incohérente")
                    }
                    iv.stopEndAt = stopEnd
                }
                iv.finalComment = comment
                iv.productionComment = comment
                sign(iv, user, SigRole.PRODUCTION, signature, t)
                iv.productionConfirmedAt = t
                iv.closedAt = t
                iv.closedById = user.id
                addEvent(iv, user, "CLOTURE", t, to = St.CLOTURE, comment = comment,
                    data = jsonObj { put("stop_end_at", JsonPrimitive(iso(stopEnd))) })
            }
            "reject_resolution" -> {
                supersedeSignatures(iv, setOf(SigRole.MAINTENANCE, SigRole.RESPONSABLE, SigRole.QUALITE))
                iv.repairedAt = null
                iv.maintenanceValidatedAt = null
                iv.qualityValidatedAt = null
                addEvent(iv, user, "REFUS_CLOTURE", t, to = St.work(iv.service), comment = comment)
            }
            "cancel" -> {
                iv.cancelReason = comment
                iv.cancelledAt = t
                iv.paused = false
                addEvent(iv, user, "ANNULATION", t, to = St.ANNULE, comment = comment)
            }
            "reopen" -> {
                val old = jsonObj {
                    put("revision", JsonPrimitive(iv.revision))
                    put("closed_at", JsonPrimitive(iso(iv.closedAt)))
                    put("final_comment", JsonPrimitive(iv.finalComment))
                    put("archived_pdf_sha256", JsonPrimitive(iv.archivedPdfSha256))
                }
                supersedeSignatures(iv)
                iv.revision += 1
                iv.reopenCount += 1
                iv.recurring = true
                iv.closedAt = null
                iv.closedById = null
                iv.finalComment = null
                iv.productionConfirmedAt = null
                iv.maintenanceValidatedAt = null
                iv.qualityValidatedAt = null
                iv.repairedAt = null
                iv.acceptedAt = null
                iv.arrivedAt = null
                iv.assignedToId = null
                iv.service = null
                iv.lateNotifiedAt = null
                iv.archivedPdfPath = null
                iv.archivedPdfSha256 = null
                if (iv.productionStopped) iv.stopEndAt = null
                addEvent(iv, user, "REOUVERTURE", t, to = St.REOUVERTE, comment = comment, data = old)
            }
            "flag_recurring" -> {
                iv.recurring = true
                addEvent(iv, user, "PANNE_RECURRENTE", t, comment = comment, data = jsonObj { put("source", JsonPrimitive("manuel")) })
            }
        }
    }

    fun checkCause(iv: Iv, causeId: Long) {
        val c = checkRef("failure_causes", causeId, iv.siteId, "Cause")!!
        if (c.long("category_id") != iv.categoryId) throw ApiException(422, "Cette cause n'appartient pas à la catégorie de la panne")
    }

    fun quantity(p: Body, k: String): String? {
        val qty = p.decimal(k) ?: return null
        if (qty <= BigDecimal.ZERO || qty.stripTrailingZeros().scale() > 2 || qty >= BigDecimal("100000000")) {
            throw ApiException(422, "Quantité invalide")
        }
        return qty.setScale(2, RoundingMode.UNNECESSARY).stripTrailingZeros().toPlainString()
    }

    fun detectRecurrence(iv: Iv): Int {
        val window = settings.int(iv.siteId, "recurrence_window_days")
        val threshold = settings.int(iv.siteId, "recurrence_threshold")
        val since = iv.declaredAt - window * 86_400_000L
        val count = sql.one(
            "SELECT COUNT(*) AS n FROM interventions WHERE machine_id=? AND category_id=? AND status<>'ANNULE' " +
                "AND declared_at>=? AND id<>?", iv.machineId, iv.categoryId, since, iv.id,
        )!!.int("n")
        return if (count + 1 >= threshold) count else 0
    }

    fun lateLimitMinutes(iv: Iv, limits: JsonObject? = null): Int? {
        val l = limits ?: (settings.get(iv.siteId, "late_acceptance_minutes") as? JsonObject) ?: return null
        return l[iv.priority]?.jsonPrimitive?.intOrNull
    }

    /** En retard : pas encore prise en charge après le délai fixé pour sa priorité. */
    fun isLate(iv: Iv, now: Long, limits: JsonObject? = null): Boolean {
        if (iv.status !in St.WAITING_TAKEOVER) return false
        val limit = lateLimitMinutes(iv, limits) ?: return false
        val since = if (iv.status == St.REOUVERTE) iv.closedAt ?: iv.declaredAt else iv.declaredAt
        return now - since > limit * 60_000L
    }
}

/** Chronométrage recalculé depuis l'historique (les anciens statuts de la version 1 sont convertis). */
object Timing {
    private fun secs(a: Long?, b: Long?): Long? = if (a == null || b == null) null else maxOf(0L, (b - a) / 1000)

    /** Temps passé dans chaque statut (secondes), suspensions comprises dans le statut d'intervention. */
    fun perStatus(iv: Iv, events: List<Event>, now: Long): Map<String, Long> {
        val end = iv.closedAt ?: iv.cancelledAt ?: now
        val out = LinkedHashMap<String, Long>()
        var status = St.NOUVEAU
        var cursor = iv.declaredAt
        for (ev in events.sortedWith(compareBy({ it.at }, { it.id }))) {
            out[status] = (out[status] ?: 0) + (secs(cursor, ev.at) ?: 0)
            cursor = maxOf(cursor, ev.at)
            St.normalize(ev.toStatus)?.let { status = it }
        }
        if (status != St.CLOTURE && status != St.ANNULE) out[status] = (out[status] ?: 0) + (secs(cursor, end) ?: 0)
        return out.filterValues { it > 0 }
    }

    fun compute(iv: Iv, events: List<Event>, now: Long): Map<String, JsonElement> {
        val end = iv.closedAt ?: iv.cancelledAt ?: now
        val b = linkedMapOf(
            "waiting_takeover_s" to 0L, "diagnosis_s" to 0L, "active_maintenance_s" to 0L, "paused_s" to 0L,
            "waiting_parts_s" to 0L, "waiting_quality_s" to 0L, "waiting_closure_s" to 0L,
        )
        fun acc(status: String, paused: Boolean, start: Long, stop: Long) {
            val d = secs(start, stop) ?: 0
            if (d == 0L) return
            val key = when (status) {
                in St.WAITING_TAKEOVER -> "waiting_takeover_s"
                St.EN_DIAGNOSTIC -> "diagnosis_s"
                in St.WORK -> if (paused) "paused_s" else "active_maintenance_s"
                St.EN_ATTENTE_PIECE -> "waiting_parts_s"
                St.EN_ATTENTE_QUALITE -> "waiting_quality_s"
                St.RESOLUE -> "waiting_closure_s"
                else -> return
            }
            b[key] = b[key]!! + d
        }
        var status = St.NOUVEAU
        var paused = false
        var cursor = iv.declaredAt
        for (ev in events.sortedWith(compareBy({ it.at }, { it.id }))) {
            acc(status, paused, cursor, ev.at)
            cursor = maxOf(cursor, ev.at)
            St.normalize(ev.toStatus)?.let {
                status = it
                if (status !in St.WORK) paused = false
            }
            if (ev.type == "PAUSE") paused = true else if (ev.type == "RESUME") paused = false
        }
        if (status != St.CLOTURE && status != St.ANNULE) acc(status, paused, cursor, end)

        val stopEnd = iv.stopEndAt ?: if (iv.locked) null else now
        val downtime = if (iv.productionStopped) secs(iv.stopStartAt, stopEnd) else 0L
        val out = LinkedHashMap<String, JsonElement>()
        b.forEach { (k, v) -> out[k] = JsonPrimitive(v) }
        // Compatibilité version 1 : attente de validation = Qualité + clôture.
        out["waiting_validation_s"] = JsonPrimitive(b["waiting_quality_s"]!! + b["waiting_closure_s"]!!)
        out["response_s"] = JsonPrimitive(secs(iv.declaredAt, iv.acceptedAt))
        out["arrival_s"] = JsonPrimitive(secs(iv.declaredAt, iv.arrivedAt))
        out["repair_window_s"] = JsonPrimitive(secs(iv.startedAt, iv.repairedAt))
        out["production_downtime_s"] = JsonPrimitive(downtime)
        out["total_s"] = JsonPrimitive(secs(iv.declaredAt, end))
        out["running"] = JsonPrimitive(!iv.locked)
        return out
    }

    fun format(seconds: Long?): String {
        if (seconds == null) return "—"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return if (h > 0) "$h h ${"%02d".format(m)} min" else "$m min"
    }

    fun long(d: Map<String, JsonElement>, k: String): Long? = (d[k] as? JsonPrimitive)?.content?.toLongOrNull()
}
