package com.maintiflow.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
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
            out[it.str("key")] = (Json.parseToJsonElement(it.str("value")) as JsonObject)["v"] ?: JsonNull
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
 * Règles métier du bon d'intervention : portage fidèle de backend/app/workflow.py.
 *
 * NOUVEAU -> EN_ATTENTE -> ACCEPTE -> EN_COURS -> (EN_ATTENTE_PIECE) -> REPARE
 *         -> VALIDATION_PRODUCTION -> CLOTURE ; + ANNULE ; réouverture CLOTURE -> EN_ATTENTE.
 */
class Workflow(private val sql: Sql, private val settings: SettingsStore) {

    class Action(val name: String, val states: Set<String>, val roles: Set<String>, val label: String)

    companion object {
        private val S = St
        val TECH_WORK_STATES = setOf(S.ACCEPTE, S.EN_COURS, S.EN_ATTENTE_PIECE, S.REPARE)
        val CHEF_FIELDS = setOf("description", "category_id", "production_stopped", "stop_start_at")
        val TECH_FIELDS = setOf("diagnosis", "root_cause", "corrective_action", "observations", "machine_final_state")
        val RESP_FIELDS = setOf("priority", "category_id") + TECH_FIELDS
        const val MAX_SIGNATURE_BYTES = 200_000

        val ACTIONS: Map<String, Action> = listOf(
            Action("assign", setOf(S.NOUVEAU, S.EN_ATTENTE, S.ACCEPTE), setOf(Role.RESPONSABLE), "Affecter"),
            Action("accept", setOf(S.NOUVEAU, S.EN_ATTENTE), setOf(Role.TECHNICIEN), "Prendre en charge"),
            Action("arrive", setOf(S.ACCEPTE), setOf(Role.TECHNICIEN), "Arrivé sur machine"),
            Action("start", setOf(S.ACCEPTE), setOf(Role.TECHNICIEN), "Démarrer"),
            Action("pause", setOf(S.EN_COURS), setOf(Role.TECHNICIEN), "Suspendre"),
            Action("resume", setOf(S.EN_COURS, S.EN_ATTENTE_PIECE), setOf(Role.TECHNICIEN), "Reprendre"),
            Action("wait_part", setOf(S.EN_COURS), setOf(Role.TECHNICIEN), "Attente pièce"),
            Action("repair", setOf(S.EN_COURS), setOf(Role.TECHNICIEN), "Terminer (réparé)"),
            Action("sign_maintenance", setOf(S.REPARE), setOf(Role.TECHNICIEN), "Signer le rapport maintenance"),
            Action("validate_maintenance", setOf(S.REPARE), setOf(Role.RESPONSABLE), "Valider (Responsable)"),
            Action("reject_maintenance", setOf(S.REPARE), setOf(Role.RESPONSABLE), "Renvoyer au technicien"),
            Action("confirm_production", setOf(S.VALIDATION_PRODUCTION), setOf(Role.CHEF_EQUIPE),
                "Confirmer la reprise de production"),
            Action("reject_production", setOf(S.VALIDATION_PRODUCTION), setOf(Role.CHEF_EQUIPE),
                "Refuser : problème non résolu"),
            Action("cancel", S.OPEN, setOf(Role.CHEF_EQUIPE, Role.RESPONSABLE), "Annuler"),
            Action("reopen", setOf(S.CLOTURE), setOf(Role.RESPONSABLE), "Réouvrir"),
            Action("flag_recurring", S.OPEN, setOf(Role.CHEF_EQUIPE, Role.TECHNICIEN, Role.RESPONSABLE),
                "Signaler panne récurrente"),
        ).associateBy { it.name }
    }

    // ---------------------------------------------------------------- visibilité
    fun canView(user: User, iv: Iv) = user.siteId == iv.siteId

    fun isAssignee(user: User, iv: Iv) = user.role == Role.TECHNICIEN && iv.assignedToId == user.id

    // ---------------------------------------------------------------- empreinte de contenu
    fun contentSha256(iv: Iv): String {
        fun code(table: String, id: Long) = sql.one("SELECT code FROM $table WHERE id=?", id)!!.str("code")
        fun username(id: Long?) = id?.let { sql.one("SELECT username FROM users WHERE id=?", it)?.str("username") }
        val parts = sql.query(
            "SELECT reference, designation, quantity, unit FROM parts_used WHERE intervention_id=? ORDER BY id", iv.id,
        ).map { p -> JsonArray(listOf("reference", "designation", "quantity", "unit").map { JsonPrimitive(p.str(it)) }) }
        val payload: Map<String, JsonElement> = sortedMapOf(
            "number" to JsonPrimitive(iv.number),
            "revision" to JsonPrimitive(iv.revision),
            "line" to JsonPrimitive(code("lines", iv.lineId)),
            "machine" to JsonPrimitive(code("machines", iv.machineId)),
            "category" to JsonPrimitive(code("failure_categories", iv.categoryId)),
            "priority" to JsonPrimitive(iv.priority),
            "description" to JsonPrimitive(iv.description),
            "reported_by" to JsonPrimitive(username(iv.reportedById)),
            "assigned_to" to JsonPrimitive(username(iv.assignedToId)),
            "declared_at" to JsonPrimitive(iso(iv.declaredAt)),
            "production_stopped" to JsonPrimitive(iv.productionStopped),
            "stop_start_at" to JsonPrimitive(iso(iv.stopStartAt)),
            "accepted_at" to JsonPrimitive(iso(iv.acceptedAt)),
            "arrived_at" to JsonPrimitive(iso(iv.arrivedAt)),
            "started_at" to JsonPrimitive(iso(iv.startedAt)),
            "repaired_at" to JsonPrimitive(iso(iv.repairedAt)),
            "diagnosis" to JsonPrimitive(iv.diagnosis),
            "root_cause" to JsonPrimitive(iv.rootCause),
            "corrective_action" to JsonPrimitive(iv.correctiveAction),
            "observations" to JsonPrimitive(iv.observations),
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

    // ---------------------------------------------------------------- autorisations
    fun actionAllowed(user: User, iv: Iv, name: String): Pair<Boolean, String> {
        val a = ACTIONS[name] ?: return false to "Action inconnue"
        if (!canView(user, iv)) return false to "Bon introuvable"
        if (user.role !in a.roles) return false to "Action non autorisée pour votre rôle"
        if (iv.status !in a.states) return false to "Action « ${a.label} » impossible à l'état ${iv.status}"

        if (name == "accept" && iv.assignedToId != null && iv.assignedToId != user.id) {
            return false to "Ce bon est affecté à un autre technicien"
        }
        if (name in setOf("arrive", "start", "pause", "resume", "wait_part", "repair", "sign_maintenance") &&
            !isAssignee(user, iv)
        ) return false to "Seul le technicien affecté peut faire cette action"
        if (name == "arrive" && iv.arrivedAt != null) return false to "Arrivée déjà enregistrée"
        if (name == "pause" && iv.paused) return false to "Intervention déjà suspendue"
        if (name == "resume" && iv.status == St.EN_COURS && !iv.paused) return false to "Intervention non suspendue"
        if (name == "sign_maintenance" && hasActiveSignature(iv, SigRole.MAINTENANCE)) {
            return false to "Rapport déjà signé par le technicien"
        }
        if (name == "validate_maintenance") {
            if (!settings.bool(iv.siteId, "require_manager_validation")) {
                return false to "La validation Responsable n'est pas requise sur ce site"
            }
            if (!hasActiveSignature(iv, SigRole.MAINTENANCE)) return false to "Le technicien doit d'abord signer le rapport"
        }
        if (name == "cancel" && user.role == Role.CHEF_EQUIPE) {
            if (iv.reportedById != user.id || iv.status !in setOf(St.NOUVEAU, St.EN_ATTENTE)) {
                return false to "Le chef d'équipe ne peut annuler que ses bons non encore pris en charge"
            }
        }
        if (name == "flag_recurring" && iv.recurring) return false to "Déjà signalée comme récurrente"
        return true to ""
    }

    fun allowedActions(user: User, iv: Iv): List<String> = ACTIONS.keys.filter { actionAllowed(user, iv, it).first }

    fun editableFields(user: User, iv: Iv): Set<String> {
        if (iv.locked || !canView(user, iv)) return emptySet()
        if (user.role == Role.CHEF_EQUIPE && iv.reportedById == user.id) {
            if (iv.status in setOf(St.NOUVEAU, St.EN_ATTENTE)) return CHEF_FIELDS
            if (iv.status in St.OPEN - St.VALIDATION_PRODUCTION) return setOf("production_stopped", "stop_start_at")
        }
        if (user.role == Role.TECHNICIEN && isAssignee(user, iv) && iv.status in TECH_WORK_STATES) return TECH_FIELDS
        if (user.role == Role.RESPONSABLE && iv.status in St.OPEN) return RESP_FIELDS
        return emptySet()
    }

    fun checkVersion(iv: Iv, version: Long?) {
        if (version != null && version != iv.version) {
            throw ApiException(409, "Le bon a été modifié entre-temps (version serveur ${iv.version}). Rechargez-le.")
        }
    }

    fun ensureNotLocked(iv: Iv) {
        if (iv.locked) throw ApiException(409, "Bon clôturé ou annulé : modification interdite (réouverture par le Responsable)")
    }

    // ---------------------------------------------------------------- exécution
    fun perform(user: User, iv: Iv, name: String, p: Body, t: Long) {
        val (ok, why) = actionAllowed(user, iv, name)
        if (!ok) {
            val code = when {
                why == "Bon introuvable" -> 404
                "rôle" in why || "Seul" in why || "autre technicien" in why || "chef d'équipe ne peut" in why -> 403
                else -> 409
            }
            throw ApiException(code, why)
        }
        checkVersion(iv, p.long("version"))
        val comment = p.str("comment", max = 2000)?.trim()?.ifEmpty { null }
        val priority = p.enum("priority", Priority.ALL)
        val signature = p.str("signature_png_base64")

        when (name) {
            "assign" -> {
                val techId = p.long("technician_id")
                val tech = techId?.let { id -> sql.one("SELECT * FROM users WHERE id=?", id)?.let { User.of(it) } }
                if (tech == null || tech.role != Role.TECHNICIEN || !tech.active || tech.siteId != iv.siteId) {
                    throw ApiException(422, "Technicien invalide")
                }
                val data = LinkedHashMap<String, JsonElement>()
                data["technician"] = JsonPrimitive(tech.username)
                if (priority != null && priority != iv.priority) {
                    data["priority"] = JsonArray(listOf(JsonPrimitive(iv.priority), JsonPrimitive(priority)))
                    iv.priority = priority
                }
                val changed = tech.id != iv.assignedToId
                if (iv.status == St.ACCEPTE && !changed) throw ApiException(409, "Ce technicien a déjà pris en charge le bon")
                val to = if (iv.status in setOf(St.NOUVEAU, St.EN_ATTENTE) || changed) St.EN_ATTENTE else null
                if (iv.status == St.ACCEPTE) {
                    iv.acceptedAt = null
                    iv.arrivedAt = null
                }
                iv.assignedToId = tech.id
                addEvent(iv, user, "AFFECTATION", t, to = to, comment = comment, data = JsonObject(data))
            }
            "accept" -> {
                iv.assignedToId = user.id
                iv.acceptedAt = t
                addEvent(iv, user, "PRISE_EN_CHARGE", t, to = St.ACCEPTE, comment = comment)
            }
            "arrive" -> {
                iv.arrivedAt = t
                addEvent(iv, user, "ARRIVEE", t, comment = comment)
            }
            "start" -> {
                iv.arrivedAt = iv.arrivedAt ?: t
                iv.startedAt = t
                addEvent(iv, user, "DEMARRAGE", t, to = St.EN_COURS, comment = comment)
            }
            "pause" -> {
                if (comment == null) throw ApiException(422, "Motif de suspension obligatoire")
                iv.paused = true
                addEvent(iv, user, "PAUSE", t, comment = comment)
            }
            "resume" -> {
                if (iv.status == St.EN_ATTENTE_PIECE) {
                    addEvent(iv, user, "REPRISE", t, to = St.EN_COURS, comment = comment)
                } else {
                    iv.paused = false
                    addEvent(iv, user, "RESUME", t, comment = comment)
                }
            }
            "wait_part" -> {
                if (comment == null) throw ApiException(422, "Indiquez la pièce attendue")
                iv.paused = false
                addEvent(iv, user, "ATTENTE_PIECE", t, to = St.EN_ATTENTE_PIECE, comment = comment)
            }
            "repair" -> {
                p.str("diagnosis", max = 4000)?.let { iv.diagnosis = it.trim() }
                p.str("root_cause", max = 4000)?.let { iv.rootCause = it.trim() }
                p.str("corrective_action", max = 4000)?.let { iv.correctiveAction = it.trim() }
                p.enum("machine_final_state", MachineState.ALL)?.let { iv.machineFinalState = it }
                val missing = listOf(
                    iv.diagnosis to "diagnostic", iv.correctiveAction to "actions correctives",
                    iv.machineFinalState to "état final de la machine",
                ).filter { it.first.isNullOrEmpty() }.map { it.second }
                if (missing.isNotEmpty()) {
                    throw ApiException(422, "Champs obligatoires avant de terminer : " + missing.joinToString(", "))
                }
                iv.paused = false
                iv.repairedAt = t
                addEvent(iv, user, "REPARATION_TERMINEE", t, to = St.REPARE, comment = comment)
            }
            "sign_maintenance" -> {
                sign(iv, user, SigRole.MAINTENANCE, signature, t)
                if (!settings.bool(iv.siteId, "require_manager_validation")) {
                    iv.maintenanceValidatedAt = t
                    addEvent(iv, user, "VALIDATION_MAINTENANCE", t, to = St.VALIDATION_PRODUCTION, comment = comment)
                }
            }
            "validate_maintenance" -> {
                sign(iv, user, SigRole.RESPONSABLE, signature, t)
                iv.maintenanceValidatedAt = t
                addEvent(iv, user, "VALIDATION_MAINTENANCE", t, to = St.VALIDATION_PRODUCTION, comment = comment)
            }
            "reject_maintenance" -> {
                if (comment == null) throw ApiException(422, "Motif obligatoire")
                supersedeSignatures(iv, setOf(SigRole.MAINTENANCE, SigRole.RESPONSABLE))
                iv.repairedAt = null
                addEvent(iv, user, "RENVOI_TECHNICIEN", t, to = St.EN_COURS, comment = comment)
            }
            "confirm_production" -> {
                var stopEnd: Long? = null
                if (iv.productionStopped) {
                    stopEnd = p.instant("stop_end_at")?.toEpochMilli() ?: iv.stopEndAt ?: t
                    if (stopEnd > t + 5 * 60_000 || (iv.stopStartAt != null && stopEnd < iv.stopStartAt!!)) {
                        throw ApiException(422, "Heure de reprise de production incohérente")
                    }
                    iv.stopEndAt = stopEnd
                }
                iv.productionComment = comment
                sign(iv, user, SigRole.PRODUCTION, signature, t)
                iv.productionConfirmedAt = t
                iv.closedAt = t
                addEvent(iv, user, "CONFIRMATION_PRODUCTION", t, to = St.CLOTURE, comment = comment,
                    data = jsonObj { put("stop_end_at", JsonPrimitive(iso(stopEnd))) })
            }
            "reject_production" -> {
                if (comment == null) throw ApiException(422, "Décrivez le problème constaté")
                supersedeSignatures(iv, setOf(SigRole.MAINTENANCE, SigRole.RESPONSABLE))
                iv.repairedAt = null
                iv.maintenanceValidatedAt = null
                addEvent(iv, user, "REFUS_PRODUCTION", t, to = St.EN_COURS, comment = comment)
            }
            "cancel" -> {
                if (comment == null || comment.length < 5) {
                    throw ApiException(422, "Motif d'annulation obligatoire (5 caractères minimum)")
                }
                iv.cancelReason = comment
                iv.cancelledAt = t
                iv.paused = false
                addEvent(iv, user, "ANNULATION", t, to = St.ANNULE, comment = comment)
            }
            "reopen" -> {
                if (comment == null || comment.length < 5) {
                    throw ApiException(422, "Motif de réouverture obligatoire (5 caractères minimum)")
                }
                val old = jsonObj {
                    put("revision", JsonPrimitive(iv.revision))
                    put("closed_at", JsonPrimitive(iso(iv.closedAt)))
                    put("archived_pdf_sha256", JsonPrimitive(iv.archivedPdfSha256))
                }
                supersedeSignatures(iv)
                iv.revision += 1
                iv.reopenCount += 1
                iv.closedAt = null
                iv.productionConfirmedAt = null
                iv.maintenanceValidatedAt = null
                iv.repairedAt = null
                iv.acceptedAt = null
                iv.archivedPdfPath = null
                iv.archivedPdfSha256 = null
                if (iv.productionStopped) iv.stopEndAt = null
                addEvent(iv, user, "REOUVERTURE", t, to = St.EN_ATTENTE, comment = comment, data = old)
            }
            "flag_recurring" -> {
                iv.recurring = true
                addEvent(iv, user, "PANNE_RECURRENTE", t, comment = comment, data = jsonObj { put("source", JsonPrimitive("manuel")) })
            }
        }
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

    fun isLate(iv: Iv, now: Long, limits: JsonObject? = null): Boolean {
        if (iv.status !in setOf(St.NOUVEAU, St.EN_ATTENTE)) return false
        val l = limits ?: (settings.get(iv.siteId, "late_acceptance_minutes") as? JsonObject) ?: return false
        val limit = l[iv.priority]?.jsonPrimitive?.intOrNull ?: return false
        return now - iv.declaredAt > limit * 60_000L
    }
}

/** Chronométrage recalculé depuis l'historique (portage de backend/app/timing.py). */
object Timing {
    private val WAITING_TAKEOVER = setOf(St.NOUVEAU, St.EN_ATTENTE, St.ACCEPTE)
    private val WAITING_VALIDATION = setOf(St.REPARE, St.VALIDATION_PRODUCTION)

    private fun secs(a: Long?, b: Long?): Long? = if (a == null || b == null) null else maxOf(0L, (b - a) / 1000)

    fun compute(iv: Iv, events: List<Event>, now: Long): Map<String, JsonElement> {
        val end = iv.closedAt ?: iv.cancelledAt ?: now
        val b = linkedMapOf(
            "waiting_takeover_s" to 0L, "active_maintenance_s" to 0L, "paused_s" to 0L,
            "waiting_parts_s" to 0L, "waiting_validation_s" to 0L,
        )
        fun acc(status: String, paused: Boolean, start: Long, stop: Long) {
            val d = secs(start, stop) ?: 0
            if (d == 0L) return
            val key = when {
                status in WAITING_TAKEOVER -> "waiting_takeover_s"
                status == St.EN_COURS -> if (paused) "paused_s" else "active_maintenance_s"
                status == St.EN_ATTENTE_PIECE -> "waiting_parts_s"
                status in WAITING_VALIDATION -> "waiting_validation_s"
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
            if (ev.toStatus != null) {
                status = ev.toStatus
                if (status != St.EN_COURS) paused = false
            }
            if (ev.type == "PAUSE") paused = true else if (ev.type == "RESUME") paused = false
        }
        if (status != St.CLOTURE && status != St.ANNULE) acc(status, paused, cursor, end)

        val stopEnd = iv.stopEndAt ?: if (iv.locked) null else now
        val downtime = if (iv.productionStopped) secs(iv.stopStartAt, stopEnd) else 0L
        val out = LinkedHashMap<String, JsonElement>()
        b.forEach { (k, v) -> out[k] = JsonPrimitive(v) }
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
