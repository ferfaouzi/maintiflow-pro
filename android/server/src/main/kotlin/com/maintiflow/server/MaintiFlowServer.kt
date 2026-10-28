package com.maintiflow.server

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

class ServerConfig(
    /** Dossier privé des photos et des PDF archivés. */
    val storageDir: File,
    val pdf: PdfRenderer,
    val tokenHours: Long = 12,
    val maxUploadMb: Int = 8,
    val passwordIterations: Int = 120_000,
    val loginMaxAttempts: Int = 5,
    val loginLockMinutes: Long = 15,
    val version: String = "2.0.0",
    /** « telephone-maitre » sur Android, « pc » pour le serveur Java. */
    val mode: String = "telephone-maitre",
    /** Sauvegarde complète (nom du fichier, contenu ZIP), fournie par l'hôte ; null si indisponible. */
    val backup: (() -> Pair<String, ByteArray>)? = null,
    val clock: () -> Long = { System.currentTimeMillis() },
    val log: (String) -> Unit = {},
)

/**
 * Serveur MaintiFlow : API REST /api/v1 et interface web (/app/), base SQLite. Le même code tourne sur le
 * téléphone maître et sur un PC (Java). Les requêtes sont traitées une par une (verrou global), ce qui
 * garantit la numérotation sans doublon et le contrôle de version.
 */
class MaintiFlowServer(private val sql: Sql, private val config: ServerConfig) {
    private val settings = SettingsStore(sql)
    private val wf = Workflow(sql, settings)
    private val notifier = Notifier(sql, wf)
    private val dashboard = Dashboard(sql, wf, settings)
    private val router = Router()
    private val lock = Any()
    private val dummyHash by lazy { Security.hashPassword("dummy-password-0", config.passwordIterations) }

    private fun now() = config.clock()

    fun handle(req: Request): Response = synchronized(lock) {
        val path = req.path
        try {
            when {
                path == "/health" || path == "/health/" -> Response.json(buildJsonObject {
                    put("status", "ok"); put("version", config.version); put("mode", config.mode)
                    put("schema", Schema.VERSION)
                })
                path == "/" -> Response(302, headers = mapOf("Location" to "/app/"))
                path == "/app" || path.startsWith("/app/") -> Web.serve(path)
                // La sauvegarde lit le fichier de la base : hors transaction.
                path == "/api/v1/admin/backup" -> backup(req)
                path == "/api/v1" || path.startsWith("/api/v1/") ->
                    sql.transaction { router.dispatch(req, path.removePrefix("/api/v1")) }
                else -> Response.error(404, "Not Found")
            }
        } catch (e: ApiException) {
            Response.error(e.status, e.detail)
        } catch (e: SqlConstraintException) {
            config.log("Contrainte refusée : ${e.message}")
            Response.error(409, if (e.message?.contains("MaintiFlow") == true) e.message!!.substringAfter("MaintiFlow").trimStart(' ', ':') else "Conflit : donnée déjà existante ou encore utilisée")
        }
    }

    // ================================================================== authentification
    private fun session(req: Request): Pair<User, String> {
        val unauthorized = ApiException(401, "Session invalide ou expirée")
        val auth = req.header("authorization") ?: throw unauthorized
        if (!auth.startsWith("Bearer ", ignoreCase = true)) throw unauthorized
        val sid = Security.sha256Hex(auth.substring(7).trim())
        val s = sql.one("SELECT * FROM user_sessions WHERE id=?", sid) ?: throw unauthorized
        val t = now()
        if (s.longOrNull("revoked_at") != null || s.long("expires_at") <= t) throw unauthorized
        val user = sql.one("SELECT * FROM users WHERE id=?", s.long("user_id"))?.let { User.of(it) } ?: throw unauthorized
        if (!user.active) throw unauthorized
        // Déconnexion automatique après inactivité ; les interrogations automatiques (notifications)
        // envoient « X-Background: 1 » et ne comptent pas comme une activité.
        val idle = settings.int(user.siteId, "session_idle_minutes")
        val lastSeen = s.longOrNull("last_seen_at") ?: s.long("created_at")
        if (idle > 0 && t - lastSeen > idle * 60_000L) {
            throw ApiException(401, "Session expirée après $idle minutes d'inactivité : reconnectez-vous")
        }
        if (req.header("x-background") != "1") sql.exec("UPDATE user_sessions SET last_seen_at=? WHERE id=?", t, sid)
        return user to sid
    }

    private fun user(req: Request): User {
        val u = session(req).first
        if (u.mustChangePassword) throw ApiException(403, "PASSWORD_CHANGE_REQUIRED")
        return u
    }

    private fun admin(req: Request): User {
        val u = user(req)
        if (u.role != Role.ADMIN) throw ApiException(403, "Réservé à l'administrateur")
        return u
    }

    private fun audit(
        action: String, user: User? = null, entity: String? = null, entityId: Any? = null,
        detail: JsonElement? = null, req: Request? = null, username: String? = null,
    ) {
        sql.exec(
            "INSERT INTO audit_log(at, user_id, username, action, entity, entity_id, ip, detail) VALUES (?,?,?,?,?,?,?,?)",
            now(), user?.id, user?.username ?: username, action, entity, entityId?.toString(), req?.remoteIp,
            detail?.takeIf { it !is JsonNull }?.toString(),
        )
    }

    private fun userJson(u: User) = u.json(wf.userLines(u.id))

    private fun login(req: Request): Response {
        val b = Body(req.json())
        val username = b.reqStr("username", 1, 60).trim().lowercase()
        val password = b.reqStr("password", 1, 200)
        val device = b.str("device", max = 120)
        val t = now()
        val generic = Response.error(401, "Matricule ou mot de passe incorrect")
        val user = sql.one("SELECT * FROM users WHERE username=?", username)?.let { User.of(it) }
        if (user == null) {
            Security.verifyPassword(password, dummyHash)
            audit("LOGIN_FAILED", username = username.take(60), req = req, detail = jsonObj { put("reason", "unknown") })
            return generic
        }
        if (user.lockedUntil != null && user.lockedUntil > t) {
            audit("LOGIN_LOCKED", user, req = req)
            return Response.error(423, "Compte temporairement verrouillé après plusieurs échecs")
        }
        if (!Security.verifyPassword(password, user.passwordHash) || !user.active) {
            var failed = user.failedLogins + 1
            var lockedUntil: Long? = user.lockedUntil
            if (failed >= config.loginMaxAttempts) {
                lockedUntil = t + config.loginLockMinutes * 60_000
                failed = 0
            }
            sql.exec("UPDATE users SET failed_logins=?, locked_until=? WHERE id=?", failed, lockedUntil, user.id)
            audit("LOGIN_FAILED", user, req = req,
                detail = jsonObj { put("reason", if (!user.active) "inactive" else "password") })
            return generic
        }
        sql.exec("UPDATE users SET failed_logins=0, locked_until=NULL WHERE id=?", user.id)
        val token = Security.newToken()
        val sid = Security.sha256Hex(token)
        val expires = t + config.tokenHours * 3_600_000
        sql.exec(
            "INSERT INTO user_sessions(id, user_id, created_at, expires_at, device, last_seen_at) VALUES (?,?,?,?,?,?)",
            sid, user.id, t, expires, (device ?: req.header("user-agent") ?: "").take(120), t,
        )
        audit("LOGIN", user, req = req, detail = jsonObj { put("session", sid.take(12)) })
        return Response.json(buildJsonObject {
            put("access_token", token); put("token_type", "bearer"); put("expires_at", iso(expires))
            put("idle_minutes", settings.int(user.siteId, "session_idle_minutes"))
            put("user", userJson(user.copy(failedLogins = 0, lockedUntil = null)))
        })
    }

    // ================================================================== pannes : sérialisation
    private fun person(id: Long?, name: String?, matricule: String?): JsonElement =
        if (id == null) JsonNull else buildJsonObject { put("id", id); put("full_name", name); put("matricule", matricule) }

    private fun summaryJson(iv: Iv, r: Row, now: Long, limits: JsonObject?) = buildJsonObject {
        put("id", iv.id); put("number", iv.number); put("status", iv.status); put("status_label", St.LABELS[iv.status])
        put("priority", iv.priority); put("priority_label", Priority.LABELS[iv.priority])
        put("line_code", r.str("l_code")); put("line_name", r.str("l_name"))
        put("poste_code", r.strOrNull("p_code")); put("poste_name", r.strOrNull("p_name"))
        put("machine_code", r.str("m_code")); put("machine_name", r.str("m_name"))
        put("cable_ref_code", r.strOrNull("cr_code"))
        put("category_name", r.str("c_name")); put("title", iv.title); put("description", iv.description)
        put("reported_by", person(iv.reportedById, r.str("r_name"), r.str("r_mat")))
        put("assigned_to", person(iv.assignedToId, r.strOrNull("a_name"), r.strOrNull("a_mat")))
        put("service", iv.service)
        put("declared_at", iso(iv.declaredAt)); put("production_stopped", iv.productionStopped)
        put("recurring", iv.recurring); put("paused", iv.paused); put("late", wf.isLate(iv, now, limits))
        put("version", iv.version)
    }

    private val SELECT_IV = """SELECT i.*, l.code AS l_code, l.name AS l_name, m.code AS m_code, m.name AS m_name,
        c.name AS c_name, p.code AS p_code, p.name AS p_name, cr.code AS cr_code,
        r.full_name AS r_name, r.username AS r_mat, a.full_name AS a_name, a.username AS a_mat
        FROM interventions i JOIN lines l ON l.id = i.line_id JOIN machines m ON m.id = i.machine_id
        JOIN failure_categories c ON c.id = i.category_id JOIN users r ON r.id = i.reported_by_id
        LEFT JOIN users a ON a.id = i.assigned_to_id LEFT JOIN postes p ON p.id = i.poste_id
        LEFT JOIN cable_refs cr ON cr.id = i.cable_ref_id"""

    private fun loadIv(user: User, id: String): Iv {
        val iv = sql.one("SELECT * FROM interventions WHERE id=?", id)?.let { Iv(it) }
        if (iv == null || !wf.canView(user, iv)) throw ApiException(404, "Panne introuvable")
        return iv
    }

    private fun refJson(r: Ref?): JsonElement = r?.let {
        buildJsonObject { put("id", it.id); put("code", it.code); put("name", it.name) }
    } ?: JsonNull

    private fun detail(user: User, id: String): JsonObject {
        val iv = loadIv(user, id)
        val f = IvFull(iv, sql)
        val t = now()
        val row = sql.one("$SELECT_IV WHERE i.id=?", iv.id)!!
        val base = summaryJson(iv, row, t, null)
        return buildJsonObject {
            base.forEach { (k, v) -> put(k, v) }
            put("site_name", f.site.name)
            put("line_id", iv.lineId); put("machine_id", iv.machineId); put("category_id", iv.categoryId)
            put("poste_id", iv.posteId); put("cable_ref_id", iv.cableRefId)
            put("poste", refJson(f.poste)); put("cable_ref", refJson(f.cableRef))
            put("reporter_role", iv.reporterRole); put("reporter_role_label", Role.LABELS[iv.reporterRole])
            put("client_uuid", iv.clientUuid)
            put("stop_start_at", iso(iv.stopStartAt)); put("stop_end_at", iso(iv.stopEndAt))
            put("accepted_at", iso(iv.acceptedAt)); put("arrived_at", iso(iv.arrivedAt))
            put("started_at", iso(iv.startedAt)); put("repaired_at", iso(iv.repairedAt))
            put("maintenance_validated_at", iso(iv.maintenanceValidatedAt))
            put("quality_validated_at", iso(iv.qualityValidatedAt))
            put("quality_by", f.qualityBy?.let { person(it.id, it.fullName, it.username) } ?: JsonNull)
            put("production_confirmed_at", iso(iv.productionConfirmedAt))
            put("closed_at", iso(iv.closedAt))
            put("closed_by", f.closedBy?.let { person(it.id, it.fullName, it.username) } ?: JsonNull)
            put("cancelled_at", iso(iv.cancelledAt)); put("cancel_reason", iv.cancelReason)
            put("intervention_type_id", iv.interventionTypeId); put("intervention_type", refJson(f.interventionType))
            put("diagnosis", iv.diagnosis); put("cause_id", iv.causeId); put("cause", refJson(f.cause))
            put("root_cause", iv.rootCause); put("tool_id", iv.toolId); put("tool", refJson(f.tool))
            put("corrective_action", iv.correctiveAction); put("test_result", iv.testResult)
            put("observations", iv.observations); put("quality_observations", iv.qualityObservations)
            put("machine_final_state", iv.machineFinalState); put("production_comment", iv.productionComment)
            put("final_comment", iv.finalComment)
            put("requires_quality", sql.one("SELECT requires_quality FROM failure_categories WHERE id=?", iv.categoryId)!!.bool("requires_quality"))
            put("reopen_count", iv.reopenCount); put("revision", iv.revision); put("locked", iv.locked)
            put("content_sha256", wf.contentSha256(iv))
            put("durations", JsonObject(Timing.compute(iv, f.events, t)))
            put("status_durations", buildJsonObject { Timing.perStatus(iv, f.events, t).forEach { (k, v) -> put(k, v) } })
            put("parts", buildJsonArray {
                f.parts.forEach { p ->
                    add(buildJsonObject {
                        put("id", p.id); put("reference", p.reference); put("designation", p.designation)
                        put("quantity", p.quantity); put("unit", p.unit); put("added_at", iso(p.addedAt))
                    })
                }
            })
            put("part_requests", buildJsonArray {
                f.partRequests.forEach { p ->
                    add(buildJsonObject {
                        put("id", p.id); put("reference", p.reference); put("designation", p.designation)
                        put("quantity", p.quantity); put("status", p.status); put("requested_at", iso(p.requestedAt))
                        put("requested_by", p.requestedBy); put("handled_at", iso(p.handledAt)); put("handled_by", p.handledBy)
                        put("comment", p.comment)
                    })
                }
            })
            put("signatures", buildJsonArray {
                f.signatures.forEach { s ->
                    add(buildJsonObject {
                        put("id", s.id); put("role", s.role); put("user_name", s.userName); put("matricule", s.userMatricule)
                        put("signed_at", iso(s.signedAt)); put("revision", s.revision)
                        put("content_sha256", s.contentSha256); put("superseded", s.superseded)
                        put("has_image", s.imagePng != null)
                    })
                }
            })
            put("attachments", buildJsonArray {
                f.attachments.forEach { a ->
                    add(buildJsonObject {
                        put("id", a.id); put("kind", a.kind); put("content_type", a.contentType)
                        put("size_bytes", a.sizeBytes); put("uploaded_at", iso(a.uploadedAt))
                    })
                }
            })
            put("events", buildJsonArray {
                f.events.forEach { e ->
                    add(buildJsonObject {
                        put("id", e.id); put("at", iso(e.at)); put("type", e.type)
                        put("label", PdfBuilder.EVENT_FR[e.type] ?: e.type)
                        put("from_status", St.normalize(e.fromStatus)); put("to_status", St.normalize(e.toStatus))
                        put("comment", e.comment); put("data", e.data ?: JsonNull)
                        put("user_name", e.userName); put("matricule", e.userMatricule)
                        put("user_role", e.userRole); put("user_role_label", Role.LABELS[e.userRole])
                    })
                }
            })
            put("allowed_actions", JsonArray(wf.allowedActions(user, iv).map { JsonPrimitive(it) }))
            put("editable_fields", JsonArray(wf.editableFields(user, iv).sorted().map { JsonPrimitive(it) }))
        }
    }

    // ================================================================== pannes : recherche
    private fun searchWhere(user: User, req: Request): Pair<String, List<Any?>> {
        val (vis, visArgs) = wf.visibilitySql(user)
        val cond = mutableListOf(vis)
        val args = visArgs.toMutableList()
        req.q("q")?.trim()?.takeIf { it.isNotEmpty() }?.let { q ->
            cond += "(i.number LIKE ? OR i.description LIKE ? OR i.title LIKE ? OR m.code LIKE ? OR m.name LIKE ? " +
                "OR r.username LIKE ? OR a.username LIKE ? OR cr.code LIKE ?)"
            repeat(8) { args += "%$q%" }
        }
        req.q("number")?.trim()?.takeIf { it.isNotEmpty() }?.let { cond += "i.number = ?"; args += it.uppercase() }
        val statuses = req.qAll("status").flatMap { it.split(',') }.filter { it.isNotBlank() }
        if (statuses.isNotEmpty()) {
            statuses.forEach { if (it !in St.ALL) throw ApiException(422, "Statut invalide : $it") }
            cond += "i.status IN (${statuses.joinToString(",") { "?" }})"
            args.addAll(statuses)
        }
        if (req.q("open_only")?.toBooleanStrictOrNull() == true) cond += "i.status NOT IN ('CLOTURE','ANNULE')"
        if (req.q("recurring")?.toBooleanStrictOrNull() == true) cond += "i.recurring = 1"
        if (req.q("mine")?.toBooleanStrictOrNull() == true) {
            cond += if (user.role in Role.INTERVENANTS) "i.assigned_to_id = ?" else "i.reported_by_id = ?"
            args += user.id
        }
        listOf("line_id" to "i.line_id", "machine_id" to "i.machine_id", "category_id" to "i.category_id",
            "poste_id" to "i.poste_id", "cable_ref_id" to "i.cable_ref_id", "technician_id" to "i.assigned_to_id",
            "reported_by_id" to "i.reported_by_id").forEach { (p, col) ->
            req.q(p)?.takeIf { it.isNotEmpty() }?.let { v ->
                cond += "$col = ?"
                args += v.toLongOrNull() ?: throw ApiException(422, "Paramètre $p invalide")
            }
        }
        req.q("matricule")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let {
            cond += "(r.username = ? OR a.username = ? OR EXISTS (SELECT 1 FROM intervention_events e JOIN users eu ON eu.id = e.user_id " +
                "WHERE e.intervention_id = i.id AND eu.username = ?))"
            repeat(3) { _ -> args += it }
        }
        req.q("reporter")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { cond += "r.username = ?"; args += it }
        req.q("intervenant")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.let { cond += "a.username = ?"; args += it }
        req.q("service")?.takeIf { it.isNotEmpty() }?.let {
            if (it !in Service.ALL) throw ApiException(422, "Service invalide")
            cond += "i.service = ?"; args += it
        }
        req.q("priority")?.takeIf { it.isNotEmpty() }?.let {
            if (it !in Priority.ALL) throw ApiException(422, "Priorité invalide")
            cond += "i.priority = ?"; args += it
        }
        fun date(k: String): Long? = req.q(k)?.takeIf { it.isNotEmpty() }?.let {
            (Body.parseInstant(it) ?: runCatching { java.time.LocalDate.parse(it).atStartOfDay(siteZone(user)).toInstant() }.getOrNull()
                ?: throw ApiException(422, "$k invalide")).toEpochMilli()
        }
        date("date_from")?.let { cond += "i.declared_at >= ?"; args += it }
        date("date_to")?.let { cond += "i.declared_at < ?"; args += it }
        return cond.joinToString(" AND ") to args
    }

    private fun siteZone(user: User): ZoneId =
        runCatching { ZoneId.of(sql.one("SELECT timezone FROM sites WHERE id=?", user.siteId)!!.str("timezone")) }.getOrDefault(ZoneOffset.UTC)

    private fun search(req: Request): Response {
        val user = user(req)
        val (where, args) = searchWhere(user, req)
        val limit = req.q("limit")?.toIntOrNull()?.takeIf { it in 1..500 } ?: if (req.q("limit") == null) 50
            else throw ApiException(422, "limit doit être entre 1 et 500")
        val offset = req.q("offset")?.toIntOrNull()?.takeIf { it >= 0 } ?: 0
        val from = SELECT_IV.substringAfter("FROM ")
        val total = sql.one("SELECT COUNT(*) AS n FROM $from WHERE $where", *args.toTypedArray())!!.long("n")
        val order = if (req.q("open_only")?.toBooleanStrictOrNull() == true) {
            "CASE i.priority WHEN 'CRITIQUE' THEN 0 WHEN 'HAUTE' THEN 1 WHEN 'NORMALE' THEN 2 ELSE 3 END, i.declared_at ASC"
        } else "i.declared_at DESC"
        val t = now()
        val limits = settings.get(user.siteId, "late_acceptance_minutes") as? JsonObject
        val items = sql.query("$SELECT_IV WHERE $where ORDER BY $order LIMIT ? OFFSET ?", *(args + limit + offset).toTypedArray())
            .map { summaryJson(Iv(it), it, t, limits) }
        return Response.json(buildJsonObject { put("total", total); put("items", JsonArray(items)) })
    }

    private fun exportList(req: Request): Response {
        val user = user(req)
        val (where, args) = searchWhere(user, req)
        val rows = sql.query("$SELECT_IV WHERE $where ORDER BY i.declared_at DESC LIMIT 5000", *args.toTypedArray())
        val data = Xlsx().sheet("Pannes", dashboard.listSheetRows(rows, siteZone(user)), listOf(16, 26, 10, 16, 26, 8, 10, 14, 18, 22, 40)).bytes()
        audit("EXPORT_XLSX", user, "interventions", null, jsonObj { put("rows", rows.size) }, req)
        return file(data, XLSX, "pannes-${java.time.LocalDate.now(siteZone(user))}.xlsx")
    }

    private fun file(data: ByteArray, type: String, name: String, inline: Boolean = false) = Response(200, data, type, mapOf(
        "Content-Disposition" to "${if (inline) "inline" else "attachment"}; filename=\"$name\"", "Cache-Control" to "no-store",
    ))

    // ================================================================== pannes : déclaration et modification
    private fun nextNumber(siteId: Long, year: Int): String {
        sql.exec("INSERT OR IGNORE INTO number_sequences(site_id, year, last_value) VALUES (?,?,0)", siteId, year)
        sql.exec("UPDATE number_sequences SET last_value = last_value + 1 WHERE site_id=? AND year=?", siteId, year)
        val n = sql.one("SELECT last_value FROM number_sequences WHERE site_id=? AND year=?", siteId, year)!!.long("last_value")
        return "BI-$year-${n.toString().padStart(6, '0')}"
    }

    private fun checkPoste(posteId: Long?, lineId: Long, siteId: Long) {
        val p = wf.checkRef("postes", posteId, siteId, "Poste") ?: return
        if (p.long("line_id") != lineId) throw ApiException(422, "Ce poste n'appartient pas à la ligne de la machine")
    }

    private fun create(req: Request): Response {
        val user = user(req)
        if (user.role !in Role.DECLARANTS) {
            throw ApiException(403, "Le rôle ${Role.LABELS[user.role]} ne peut pas déclarer de panne")
        }
        val b = Body(req.json())
        val clientUuid = b.str("client_uuid", max = 36)
        val machineId = b.reqLong("machine_id")
        val categoryId = b.reqLong("category_id")
        val description = b.reqStr("description", 3, 4000)
        val title = b.str("title", max = 120)?.trim()?.ifEmpty { null }
        val priority = b.enum("priority", Priority.ALL)
        val stopped = b.bool("production_stopped") ?: false
        val stopStartIn = b.instant("stop_start_at")
        val declaredIn = b.instant("declared_at")
        val posteId = b.long("poste_id")
        val cableRefId = b.long("cable_ref_id")
        val assignTo = b.long("assigned_to_id")
        val serviceIn = b.enum("service", Service.ALL)

        if (clientUuid != null) {
            sql.one("SELECT id, reported_by_id FROM interventions WHERE client_uuid=?", clientUuid)?.let {
                if (it.long("reported_by_id") != user.id) throw ApiException(409, "Identifiant client déjà utilisé")
                return Response.json(detail(user, it.str("id")), 200)  // ré-envoi : pas de doublon
            }
        }
        val machine = sql.one(
            "SELECT m.id, m.line_id, m.code, m.active, m.poste_id, l.site_id, l.active AS line_active FROM machines m " +
                "JOIN lines l ON l.id = m.line_id WHERE m.id=?", machineId,
        )
        if (machine == null || !machine.bool("active") || machine.long("site_id") != user.siteId || !machine.bool("line_active")) {
            throw ApiException(422, "Machine invalide")
        }
        val lineId = machine.long("line_id")
        wf.declarableLines(user)?.let { if (lineId !in it) throw ApiException(403, "Vous ne pouvez déclarer que sur vos lignes") }
        val cat = sql.one("SELECT * FROM failure_categories WHERE id=?", categoryId)
        if (cat == null || !cat.bool("active") || cat.long("site_id") != user.siteId) throw ApiException(422, "Catégorie invalide")
        val poste = posteId ?: machine.longOrNull("poste_id")
        checkPoste(poste, lineId, user.siteId)
        wf.checkRef("cable_refs", cableRefId, user.siteId, "Référence câble")
        var assignee: User? = null
        if (assignTo != null) {
            assignee = sql.one("SELECT * FROM users WHERE id=?", assignTo)?.let { User.of(it) }
            if (assignee == null || assignee.role !in Role.INTERVENANTS || !assignee.active || assignee.siteId != user.siteId) {
                throw ApiException(422, "Intervenant invalide")
            }
        }
        val service = assignee?.let { Role.service(it.role) } ?: serviceIn

        val t = now()
        val declared = declaredIn?.toEpochMilli() ?: t
        if (declared > t + 5 * 60_000 || declared < t - 7 * 86_400_000L) throw ApiException(422, "Date de déclaration incohérente")
        var stopStart: Long? = null
        if (stopped) {
            stopStart = stopStartIn?.toEpochMilli() ?: declared
            if (stopStart > t + 5 * 60_000) throw ApiException(422, "Heure d'arrêt de production incohérente")
        }
        val year = Instant.ofEpochMilli(declared).atZone(ZoneOffset.UTC).year
        val id = UUID.randomUUID().toString()
        sql.exec(
            """INSERT INTO interventions(id, number, client_uuid, site_id, line_id, machine_id, poste_id, cable_ref_id,
               category_id, title, priority, status, description, reported_by_id, reporter_role, production_stopped,
               stop_start_at, declared_at, created_at, updated_at)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            id, nextNumber(user.siteId, year), clientUuid, user.siteId, lineId, machineId, poste, cableRefId,
            categoryId, title, priority ?: cat.str("default_priority"), St.NOUVEAU, description.trim(), user.id, user.role,
            stopped, stopStart, declared, t, t,
        )
        var iv = Iv(sql.one("SELECT * FROM interventions WHERE id=?", id)!!)
        wf.addEvent(iv, user, "CREATION", declared, to = St.NOUVEAU, fromStatus = null,
            data = jsonObj { put("offline", declaredIn != null) })
        if (service != null) {
            iv.assignedToId = assignee?.id
            iv.service = service
            wf.addEvent(iv, user, "AFFECTATION", declared, to = St.EN_ATTENTE, data = jsonObj {
                put("service", service); assignee?.let { put("technician", it.username) }
            })
        }
        val previous = wf.detectRecurrence(iv)
        if (previous > 0) {
            iv.recurring = true
            wf.addEvent(iv, null, "PANNE_RECURRENTE", declared,
                comment = "$previous panne(s) similaire(s) sur cette machine pendant la période de référence",
                data = jsonObj { put("source", "auto"); put("previous", previous) })
        }
        iv.save(sql, t)
        iv = Iv(sql.one("SELECT * FROM interventions WHERE id=?", id)!!)
        notifier.created(iv, user, t)
        audit("INTERVENTION_CREATED", user, "intervention", iv.number, jsonObj { put("machine", machine.str("code")) }, req)
        return Response.json(detail(user, id), 201)
    }

    private fun jsonValue(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        else -> JsonPrimitive(v.toString())
    }

    private fun patch(req: Request, id: String): Response {
        val user = user(req)
        val iv = loadIv(user, id)
        wf.ensureNotLocked(iv)
        val b = Body(req.json())
        wf.checkVersion(iv, b.long("version") ?: throw ApiException(422, "Champ version obligatoire"))
        val allowed = wf.editableFields(user, iv)
        val changes = Workflow.ALL_FIELDS.filter { b.has(it) }
        val refused = changes.filter { it !in allowed }
        if (refused.isNotEmpty()) {
            throw ApiException(403, "Champs non modifiables par vous à ce statut : " + refused.joinToString(", "))
        }
        val diff = LinkedHashMap<String, JsonElement>()
        for (k in changes) {
            var v: Any? = when (k) {
                "description" -> b.str(k, 3, 4000)
                "category_id" -> b.long(k)?.also { cid ->
                    val cat = sql.one("SELECT site_id, active FROM failure_categories WHERE id=?", cid)
                    if (cat == null || cat.long("site_id") != iv.siteId || !cat.bool("active")) throw ApiException(422, "Catégorie invalide")
                }
                "poste_id" -> b.long(k)?.also { checkPoste(it, iv.lineId, iv.siteId) }
                "cable_ref_id" -> b.long(k)?.also { wf.checkRef("cable_refs", it, iv.siteId, "Référence câble") }
                "intervention_type_id" -> b.long(k)?.also { wf.checkRef("intervention_types", it, iv.siteId, "Type d'intervention") }
                "tool_id" -> b.long(k)?.also { wf.checkRef("tools", it, iv.siteId, "Outil") }
                "cause_id" -> b.long(k)
                "priority" -> b.enum(k, Priority.ALL)
                "production_stopped" -> b.bool(k)
                "stop_start_at" -> b.instant(k)?.toEpochMilli()
                "machine_final_state" -> b.enum(k, MachineState.ALL)
                "title" -> b.str(k, max = 120)
                else -> b.str(k, max = 4000)
            }
            if (v is String) v = v.trim().ifEmpty { null }
            if (v == null && k in setOf("description", "category_id", "priority", "production_stopped")) {
                continue  // colonnes obligatoires : une valeur vide n'efface rien
            }
            val old = iv.field(k)
            if (old != v) {
                diff[k] = JsonArray(listOf(jsonFieldValue(k, old), jsonFieldValue(k, v)))
                iv.setField(k, v)
            }
        }
        // La cause doit appartenir à la catégorie (éventuellement modifiée dans la même requête).
        iv.causeId?.let { if ("cause_id" in diff || "category_id" in diff) wf.checkCause(iv, it) }
        val t = now()
        if (iv.productionStopped && iv.stopStartAt == null) {
            iv.stopStartAt = t
            diff["stop_start_at"] = JsonArray(listOf(JsonNull, JsonPrimitive(iso(t))))
        }
        if (!iv.productionStopped) iv.stopStartAt = null
        if (diff.isEmpty()) return Response.json(detail(user, id))
        val d = JsonObject(diff)
        wf.addEvent(iv, user, "MODIFICATION", t, data = d)
        audit("INTERVENTION_EDITED", user, "intervention", iv.number, d, req)
        iv.save(sql, t)
        return Response.json(detail(user, id))
    }

    private fun jsonFieldValue(k: String, v: Any?): JsonElement =
        if (k == "stop_start_at") JsonPrimitive(iso(v as Long?)) else jsonValue(v)

    private fun action(req: Request, id: String, name: String): Response {
        if (name !in Workflow.ACTIONS) throw ApiException(404, "Action inconnue")
        val user = user(req)
        val body = Body(req.json())
        val iv = loadIv(user, id)
        val before = iv.status
        val t = now()
        wf.perform(user, iv, name, body, t)
        iv.save(sql, t)
        val saved = Iv(sql.one("SELECT * FROM interventions WHERE id=?", id)!!)
        if (name == "close") archivePdf(user, id, t)
        notifier.afterAction(saved, user, name, before, body.str("comment")?.trim(), t)
        audit("ACTION_${name.uppercase()}", user, "intervention", iv.number, jsonObj {
            put("from", before); put("to", iv.status); put("comment", body.str("comment"))
        }, req)
        return Response.json(detail(user, id))
    }

    /** À la clôture, le PDF est figé : stocké une fois avec son empreinte, puis servi tel quel. */
    private fun archivePdf(user: User, id: String, t: Long) {
        val iv = loadIv(user, id)
        val data = try {
            renderPdf(iv, t)
        } catch (e: Exception) {
            config.log("PDF d'archive non généré pour ${iv.number} : $e")
            return
        }
        val year = Instant.ofEpochMilli(iv.declaredAt).atZone(ZoneOffset.UTC).year
        val (rel, sha) = saveFile("pdf/$year", data, ".pdf")
        iv.archivedPdfPath = rel
        iv.archivedPdfSha256 = sha
        iv.save(sql, t)
    }

    private fun company(siteId: Long) = (settings.get(siteId, "company_display_name") as? JsonPrimitive)?.content.orEmpty()

    private fun renderPdf(iv: Iv, t: Long): ByteArray {
        val f = IvFull(iv, sql)
        val site = sql.one("SELECT timezone FROM sites WHERE id=?", iv.siteId)!!
        val doc = PdfBuilder.build(f, wf.contentSha256(iv), company(iv.siteId), site.str("timezone"),
            Timing.compute(iv, f.events, t), config.storageDir, t)
        return config.pdf.render(doc)
    }

    private fun saveFile(subdir: String, data: ByteArray, ext: String): Pair<String, String> {
        val rel = "$subdir/${UUID.randomUUID()}$ext"
        val full = File(config.storageDir, rel)
        full.parentFile?.mkdirs()
        val tmp = File(full.path + ".tmp")
        tmp.outputStream().use { it.write(data); it.fd.sync() }
        if (!tmp.renameTo(full)) throw IllegalStateException("Écriture impossible : $rel")
        return rel to Security.sha256Hex(data)
    }

    private fun resolve(rel: String): File {
        val root = config.storageDir.canonicalFile
        val f = File(root, rel).canonicalFile
        if (!f.path.startsWith(root.path + File.separator)) throw ApiException(404, "Fichier introuvable")
        return f
    }

    private fun addPart(req: Request, id: String): Response {
        val user = user(req)
        val iv = loadIv(user, id)
        wf.ensureNotLocked(iv)
        if ("corrective_action" !in wf.editableFields(user, iv)) {
            throw ApiException(403, "Seul l'intervenant qui a la panne en charge (ou le Responsable) peut ajouter des pièces")
        }
        val b = Body(req.json())
        val reference = b.reqStr("reference", 1, 60)
        val designation = b.reqStr("designation", 1, 160)
        val qty = b.decimal("quantity") ?: throw ApiException(422, "Champ quantity obligatoire")
        if (qty <= BigDecimal.ZERO || qty.stripTrailingZeros().scale() > 2 || qty >= BigDecimal("100000000")) {
            throw ApiException(422, "Quantité invalide")
        }
        val quantity = qty.setScale(2, RoundingMode.UNNECESSARY).toPlainString()
        val unit = b.str("unit", max = 10) ?: "pce"
        val t = now()
        sql.exec(
            "INSERT INTO parts_used(intervention_id, reference, designation, quantity, unit, added_by_id, added_at) VALUES (?,?,?,?,?,?,?)",
            iv.id, reference, designation, quantity, unit, user.id, t,
        )
        wf.addEvent(iv, user, "PIECE_AJOUTEE", t, data = jsonObj { put("reference", reference); put("quantity", quantity) })
        audit("PART_ADDED", user, "intervention", iv.number, jsonObj { put("reference", reference) }, req)
        iv.save(sql, t)
        return Response.json(detail(user, id), 201)
    }

    private fun deletePart(req: Request, id: String, partId: String): Response {
        val user = user(req)
        val iv = loadIv(user, id)
        wf.ensureNotLocked(iv)
        if ("corrective_action" !in wf.editableFields(user, iv)) throw ApiException(403, "Non autorisé")
        val p = sql.one("SELECT * FROM parts_used WHERE id=? AND intervention_id=?", partId.toLongOrNull(), iv.id)
            ?: throw ApiException(404, "Pièce introuvable")
        val t = now()
        wf.addEvent(iv, user, "PIECE_RETIREE", t, data = jsonObj {
            put("reference", p.str("reference")); put("quantity", p.str("quantity"))
        })
        sql.exec("DELETE FROM parts_used WHERE id=?", p.long("id"))
        audit("PART_REMOVED", user, "intervention", iv.number, jsonObj { put("reference", p.str("reference")) }, req)
        iv.save(sql, t)
        return Response.json(detail(user, id))
    }

    private fun similar(req: Request, id: String): Response {
        val user = user(req)
        val iv = loadIv(user, id)
        val rows = sql.query(
            """SELECT i.id, i.number, i.machine_id, i.closed_at, i.diagnosis, i.root_cause, i.corrective_action,
               m.code AS m_code, c.name AS c_name FROM interventions i JOIN machines m ON m.id = i.machine_id
               JOIN failure_categories c ON c.id = i.category_id
               WHERE i.site_id=? AND i.id<>? AND i.status='CLOTURE' AND (i.machine_id=? OR i.category_id=?)
               ORDER BY (i.machine_id=?) DESC, (i.category_id=?) DESC, i.closed_at DESC LIMIT 5""",
            iv.siteId, iv.id, iv.machineId, iv.categoryId, iv.machineId, iv.categoryId,
        )
        return Response.json(buildJsonObject {
            put("disclaimer", "Pannes passées comparables, à titre indicatif uniquement : " +
                "elles ne remplacent pas le diagnostic de l'intervenant.")
            put("items", buildJsonArray {
                rows.forEach { r ->
                    add(buildJsonObject {
                        put("id", r.str("id")); put("number", r.str("number")); put("machine", r.str("m_code"))
                        put("category", r.str("c_name")); put("closed_at", iso(r.longOrNull("closed_at")))
                        put("same_machine", r.long("machine_id") == iv.machineId)
                        put("diagnosis", r.strOrNull("diagnosis")); put("root_cause", r.strOrNull("root_cause"))
                        put("corrective_action", r.strOrNull("corrective_action"))
                    })
                }
            })
        })
    }

    private fun pdf(req: Request, id: String): Response {
        val user = user(req)
        val iv = loadIv(user, id)
        val data: ByteArray
        val kind: String
        if (iv.status == St.CLOTURE && iv.archivedPdfPath != null) {
            data = resolve(iv.archivedPdfPath!!).readBytes()
            if (Security.sha256Hex(data) != iv.archivedPdfSha256) {
                audit("PDF_INTEGRITY_ERROR", user, "intervention", iv.number, null, req)
                return Response.error(500, "Intégrité du PDF archivé non vérifiée : contactez l'administrateur")
            }
            kind = "archive"
        } else {
            data = renderPdf(iv, now())
            kind = "provisoire"
        }
        audit("PDF_EXPORTED", user, "intervention", iv.number, jsonObj { put("kind", kind) }, req)
        return Response(200, data, "application/pdf", mapOf(
            "Content-Disposition" to "inline; filename=\"${iv.number}_rev${iv.revision}.pdf\"",
            "X-Document-Kind" to kind, "Cache-Control" to "no-store",
        ))
    }

    private val imageTypes = mapOf("image/jpeg" to ".jpg", "image/png" to ".png", "image/webp" to ".webp")
    private val magic = mapOf(
        "image/jpeg" to byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()),
        "image/png" to byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()),
        "image/webp" to "RIFF".toByteArray(),
    )

    private fun upload(req: Request, id: String): Response {
        val user = user(req)
        val form = Multipart.parse(req)
        val kind = (form.fields["kind"] ?: "PROBLEME").trim().uppercase()
        if (kind !in setOf("PROBLEME", "AVANT", "APRES")) throw ApiException(422, "Type de photo invalide")
        val iv = loadIv(user, id)
        wf.ensureNotLocked(iv)
        val editable = wf.editableFields(user, iv)
        val may = if (kind == "PROBLEME") "production_stopped" in editable || iv.reportedById == user.id
            else "diagnosis" in editable
        if (!may) throw ApiException(403, "Ajout de photo non autorisé")
        val file = form.files["file"] ?: throw ApiException(422, "Fichier manquant")
        val ctype = file.contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        val ext = imageTypes[ctype] ?: throw ApiException(415, "Formats acceptés : JPEG, PNG, WEBP")
        val max = config.maxUploadMb * 1024 * 1024
        if (file.data.size > max) throw ApiException(413, "Photo trop volumineuse (max ${config.maxUploadMb} Mo)")
        val m = magic[ctype]!!
        if (file.data.size < m.size || !file.data.copyOfRange(0, m.size).contentEquals(m)) {
            throw ApiException(415, "Le contenu du fichier ne correspond pas à son type")
        }
        val year = Instant.ofEpochMilli(iv.declaredAt).atZone(ZoneOffset.UTC).year
        val (rel, sha) = saveFile("photos/$year/${iv.number}", file.data, ext)
        val t = now()
        sql.exec(
            "INSERT INTO attachments(id, intervention_id, kind, content_type, size_bytes, sha256, storage_path, uploaded_by_id, uploaded_at) " +
                "VALUES (?,?,?,?,?,?,?,?,?)", UUID.randomUUID().toString(), iv.id, kind, ctype, file.data.size, sha, rel, user.id, t,
        )
        wf.addEvent(iv, user, "PHOTO_AJOUTEE", t, data = jsonObj { put("kind", kind); put("sha256", sha) })
        audit("ATTACHMENT_ADDED", user, "intervention", iv.number, jsonObj { put("kind", kind); put("size", file.data.size) }, req)
        iv.save(sql, t)
        return Response.json(detail(user, id), 201)
    }

    private fun attachment(req: Request, attId: String): Response {
        val user = user(req)
        val a = sql.one("SELECT a.* FROM attachments a WHERE a.id=?", attId) ?: throw ApiException(404, "Fichier introuvable")
        loadIv(user, a.str("intervention_id"))
        val f = resolve(a.str("storage_path"))
        if (!f.exists()) throw ApiException(404, "Fichier introuvable")
        return Response(200, f.readBytes(), a.str("content_type"), mapOf("Cache-Control" to "private"))
    }

    private fun signatureImage(req: Request, sigId: String): Response {
        val user = user(req)
        val s = sql.one("SELECT image_png, intervention_id FROM signatures WHERE id=?", sigId.toLongOrNull())
        val img = s?.bytes("image_png") ?: throw ApiException(404, "Signature introuvable")
        loadIv(user, s.str("intervention_id"))
        return Response(200, img, "image/png", mapOf("Cache-Control" to "private"))
    }

    /** Historique d'une machine : ses pannes, leur fréquence et le temps d'arrêt cumulé. */
    private fun machineHistory(req: Request, machineId: Long): Response {
        val user = user(req)
        val m = sql.one("SELECT m.*, l.site_id, l.code AS l_code FROM machines m JOIN lines l ON l.id = m.line_id WHERE m.id=?", machineId)
        if (m == null || m.long("site_id") != user.siteId) throw ApiException(404, "Machine introuvable")
        if (user.role in Role.LINE_SCOPED && wf.userLines(user.id).let { it.isNotEmpty() && m.long("line_id") !in it }) {
            throw ApiException(404, "Machine introuvable")
        }
        val (vis, args) = wf.visibilitySql(user)
        val rows = sql.query("$SELECT_IV WHERE $vis AND i.machine_id = ? ORDER BY i.declared_at DESC LIMIT 200", *(args + machineId).toTypedArray())
        val t = now()
        val ivs = rows.map { Iv(it) }
        val valid = ivs.filter { it.status != St.ANNULE }
        val downtime = valid.filter { it.productionStopped && it.stopStartAt != null }
            .sumOf { ((it.stopEndAt ?: if (it.locked) it.closedAt ?: t else t) - it.stopStartAt!!) / 1000 }
        return Response.json(buildJsonObject {
            put("machine", buildJsonObject {
                put("id", machineId); put("code", m.str("code")); put("name", m.str("name")); put("line_code", m.str("l_code"))
            })
            put("count", valid.size)
            put("open", valid.count { !it.locked })
            put("recurring", valid.count { it.recurring })
            put("downtime_s", downtime)
            put("by_category", buildJsonObject {
                rows.filter { it.str("status") != St.ANNULE }.groupBy { it.str("c_name") }.forEach { (k, v) -> put(k, v.size) }
            })
            put("items", JsonArray(rows.map { summaryJson(Iv(it), it, t, null) }))
        })
    }

    // ================================================================== référentiels
    private fun rowJson(r: Row): JsonObject = buildJsonObject {
        r.forEach { (k, v) ->
            when (v) {
                null -> put(k, JsonNull)
                is Number -> if (k == "active" || k == "requires_quality") put(k, v.toLong() != 0L) else put(k, v.toLong())
                is String -> put(k, v)
                else -> Unit
            }
        }
    }

    private fun referentials(req: Request): Response {
        val user = user(req)
        val s = user.siteId
        val myLines = wf.declarableLines(user)
        val lineFilter = myLines?.let { " AND l.id IN (${it.joinToString(",")})" }.orEmpty()
        return Response.json(buildJsonObject {
            put("site", rowJson(sql.one("SELECT id, code, name, timezone FROM sites WHERE id=?", s)!!))
            put("company_display_name", company(s))
            put("lines", JsonArray(sql.query("SELECT l.id, l.code, l.name, l.active FROM lines l WHERE l.site_id=? AND l.active=1$lineFilter ORDER BY l.code", s).map { rowJson(it) }))
            put("postes", JsonArray(sql.query(
                "SELECT p.id, p.line_id, p.code, p.name, p.active FROM postes p JOIN lines l ON l.id = p.line_id " +
                    "WHERE l.site_id=? AND p.active=1 AND l.active=1$lineFilter ORDER BY l.code, p.code", s,
            ).map { rowJson(it) }))
            put("machines", JsonArray(sql.query(
                "SELECT m.id, m.line_id, m.poste_id, m.code, m.name, m.qr_token, m.active FROM machines m JOIN lines l ON l.id = m.line_id " +
                    "WHERE l.site_id=? AND m.active=1 AND l.active=1$lineFilter ORDER BY m.code", s,
            ).map { rowJson(it) }))
            put("categories", JsonArray(sql.query(
                "SELECT id, code, name, default_priority, requires_quality, active FROM failure_categories WHERE site_id=? AND active=1 ORDER BY name", s,
            ).map { rowJson(it) }))
            put("causes", JsonArray(sql.query(
                "SELECT f.id, f.category_id, f.name, f.active FROM failure_causes f JOIN failure_categories c ON c.id = f.category_id " +
                    "WHERE c.site_id=? AND f.active=1 ORDER BY f.name", s,
            ).map { rowJson(it) }))
            put("cable_refs", JsonArray(sql.query("SELECT id, code, name, active FROM cable_refs WHERE site_id=? AND active=1 ORDER BY code", s).map { rowJson(it) }))
            put("tools", JsonArray(sql.query("SELECT id, machine_id, code, name, active FROM tools WHERE site_id=? AND active=1 ORDER BY code", s).map { rowJson(it) }))
            put("intervention_types", JsonArray(sql.query("SELECT id, code, name, active FROM intervention_types WHERE site_id=? AND active=1 ORDER BY name", s).map { rowJson(it) }))
            put("technicians", JsonArray(sql.query(
                "SELECT * FROM users WHERE site_id=? AND role IN ('MAINTENANCIER','METHODE') AND active=1 ORDER BY full_name", s,
            ).map { User.of(it).json() }))
            put("labels", buildJsonObject {
                put("statuses", JsonObject(St.LABELS.mapValues { JsonPrimitive(it.value) }))
                put("priorities", JsonObject(Priority.LABELS.mapValues { JsonPrimitive(it.value) }))
                put("roles", JsonObject(Role.LABELS.mapValues { JsonPrimitive(it.value) }))
                put("services", JsonObject(Service.LABELS.mapValues { JsonPrimitive(it.value) }))
                put("machine_states", JsonObject(MachineState.LABELS.mapValues { JsonPrimitive(it.value) }))
                put("actions", JsonObject(Workflow.ACTIONS.mapValues { JsonPrimitive(it.value.label) }))
            })
        })
    }

    private inline fun <T> unique(what: String, block: () -> T): T = try {
        block()
    } catch (e: SqlConstraintException) {
        if (e.message?.contains("UNIQUE", ignoreCase = true) == true) throw ApiException(409, "$what : code déjà utilisé")
        throw e
    }

    /**
     * Listes de configuration de l'usine administrées de la même façon : création, modification,
     * désactivation, suppression seulement si la valeur n'a jamais servi.
     */
    private class RefSpec(
        val path: String, val table: String, val what: String,
        /** Colonne parente obligatoire (ligne, catégorie) et sa table, ou null si rattachée au site. */
        val parent: Pair<String, String>?,
        /** Colonnes facultatives qui pointent vers une autre liste. */
        val links: Map<String, String> = emptyMap(),
        val codeMax: Int = 40,
        val codeRequired: Boolean = true,
        /** Tables et colonnes qui utilisent cette valeur (suppression interdite si utilisée). */
        val usedBy: List<Pair<String, String>>,
        val extra: List<String> = emptyList(),
    )

    private val refSpecs = listOf(
        RefSpec("lines", "lines", "Ligne", null, codeMax = 30,
            usedBy = listOf("machines" to "line_id", "postes" to "line_id", "interventions" to "line_id", "user_lines" to "line_id")),
        RefSpec("postes", "postes", "Poste", "line_id" to "lines", codeMax = 30,
            usedBy = listOf("interventions" to "poste_id", "machines" to "poste_id")),
        RefSpec("machines", "machines", "Machine", "line_id" to "lines", links = mapOf("poste_id" to "postes"),
            usedBy = listOf("interventions" to "machine_id", "tools" to "machine_id")),
        RefSpec("cable-refs", "cable_refs", "Référence câble", null, codeMax = 60, usedBy = listOf("interventions" to "cable_ref_id")),
        RefSpec("tools", "tools", "Outil / posage", null, links = mapOf("machine_id" to "machines"), usedBy = listOf("interventions" to "tool_id")),
        RefSpec("categories", "failure_categories", "Catégorie", null, codeMax = 30,
            usedBy = listOf("interventions" to "category_id", "failure_causes" to "category_id"),
            extra = listOf("default_priority", "requires_quality")),
        RefSpec("causes", "failure_causes", "Cause", "category_id" to "failure_categories", codeRequired = false,
            usedBy = listOf("interventions" to "cause_id")),
        RefSpec("intervention-types", "intervention_types", "Type d'intervention", null, codeMax = 30,
            usedBy = listOf("interventions" to "intervention_type_id")),
    )

    /** Site d'une ligne de configuration (via sa ligne ou sa catégorie parente si besoin). */
    private fun siteOf(table: String, id: Long?): Long? {
        if (id == null) return null
        return when (table) {
            "postes", "machines" -> sql.one("SELECT l.site_id FROM $table t JOIN lines l ON l.id = t.line_id WHERE t.id=?", id)
            "failure_causes" -> sql.one("SELECT c.site_id FROM failure_causes t JOIN failure_categories c ON c.id = t.category_id WHERE t.id=?", id)
            else -> sql.one("SELECT site_id FROM $table WHERE id=?", id)
        }?.long("site_id")
    }

    private fun refList(spec: RefSpec, user: User): JsonArray {
        val q = when (spec.table) {
            "postes", "machines" -> "SELECT t.* FROM ${spec.table} t JOIN lines l ON l.id = t.line_id WHERE l.site_id=? ORDER BY t.code"
            "failure_causes" -> "SELECT t.* FROM failure_causes t JOIN failure_categories c ON c.id = t.category_id WHERE c.site_id=? ORDER BY t.name"
            else -> "SELECT t.* FROM ${spec.table} t WHERE t.site_id=? ORDER BY t.code"
        }
        return JsonArray(sql.query(q, user.siteId).map { r ->
            val o = rowJson(r)
            val used = spec.usedBy.sumOf { (t, c) -> sql.one("SELECT COUNT(*) AS n FROM $t WHERE $c=?", r.long("id"))!!.long("n") }
            JsonObject(o + ("used" to JsonPrimitive(used)))
        })
    }

    private fun refSave(spec: RefSpec, user: User, req: Request, id: Long?): Response {
        val b = Body(req.json())
        val existing = id?.let { sql.one("SELECT * FROM ${spec.table} WHERE id=?", it) }
        if (id != null && (existing == null || siteOf(spec.table, id) != user.siteId)) throw ApiException(404, "${spec.what} introuvable")
        val code = if (spec.codeRequired) b.reqStr("code", 1, spec.codeMax).trim() else b.str("code", max = spec.codeMax)?.trim().orEmpty()
        val name = b.reqStr("name", 1, 160).trim()
        val active = b.bool("active") ?: true
        val cols = linkedMapOf<String, Any?>("code" to code, "name" to name, "active" to active)
        spec.parent?.let { (col, table) ->
            val pid = b.reqLong(col)
            if (siteOf(table, pid) != user.siteId) throw ApiException(422, "Parent invalide ($col)")
            cols[col] = pid
        }
        spec.links.forEach { (col, table) ->
            val v = b.long(col)
            if (v != null && siteOf(table, v) != user.siteId) throw ApiException(422, "Valeur invalide ($col)")
            cols[col] = v
        }
        if (spec.table == "machines" && cols["poste_id"] != null) {
            val p = sql.one("SELECT line_id FROM postes WHERE id=?", cols["poste_id"])!!
            if (p.long("line_id") != cols["line_id"]) throw ApiException(422, "Le poste doit appartenir à la ligne de la machine")
        }
        if (spec.table == "failure_categories") {
            cols["default_priority"] = b.enum("default_priority", Priority.ALL) ?: existing?.str("default_priority") ?: "NORMALE"
            cols["requires_quality"] = b.bool("requires_quality") ?: existing?.bool("requires_quality") ?: false
        }
        var newId = 0L
        unique(spec.what) {
            if (existing == null) {
                val extraCols = mutableMapOf<String, Any?>()
                if (spec.parent == null) extraCols["site_id"] = user.siteId
                if (spec.table == "machines") extraCols["qr_token"] = UUID.randomUUID().toString()
                val all = cols + extraCols
                sql.exec("INSERT INTO ${spec.table}(${all.keys.joinToString(",")}) VALUES (${all.keys.joinToString(",") { "?" }})",
                    *all.values.toTypedArray())
                newId = sql.lastInsertId()
            } else {
                sql.exec("UPDATE ${spec.table} SET ${cols.keys.joinToString(",") { "$it=?" }} WHERE id=?", *(cols.values.toList() + id).toTypedArray())
                newId = id
            }
        }
        val before = existing?.let { rowJson(it) }
        audit("${spec.table.uppercase()}_${if (existing == null) "CREATED" else "UPDATED"}", user, spec.table, newId, buildJsonObject {
            put("new", JsonObject(cols.mapValues { jsonValue(it.value) }))
            before?.let { put("old", it) }
        }, req)
        return Response.json(rowJson(sql.one("SELECT * FROM ${spec.table} WHERE id=?", newId)!!), if (existing == null) 201 else 200)
    }

    private fun refDelete(spec: RefSpec, user: User, req: Request, id: Long): Response {
        val r = sql.one("SELECT * FROM ${spec.table} WHERE id=?", id)
        if (r == null || siteOf(spec.table, id) != user.siteId) throw ApiException(404, "${spec.what} introuvable")
        val used = spec.usedBy.sumOf { (t, c) -> sql.one("SELECT COUNT(*) AS n FROM $t WHERE $c=?", id)!!.long("n") }
        if (used > 0) throw ApiException(409, "${spec.what} déjà utilisée ($used fois) : désactivez-la au lieu de la supprimer")
        sql.exec("DELETE FROM ${spec.table} WHERE id=?", id)
        audit("${spec.table.uppercase()}_DELETED", user, spec.table, id, rowJson(r), req)
        return Response.noContent()
    }

    // ================================================================== utilisateurs
    private fun saveUserLines(userId: Long, siteId: Long, lineIds: List<Long>?) {
        if (lineIds == null) return
        lineIds.forEach { if (siteOf("lines", it) != siteId) throw ApiException(422, "Ligne invalide : $it") }
        sql.exec("DELETE FROM user_lines WHERE user_id=?", userId)
        lineIds.distinct().forEach { sql.exec("INSERT INTO user_lines(user_id, line_id) VALUES (?,?)", userId, it) }
    }

    private fun longList(b: Body, k: String): List<Long>? {
        if (!b.has(k) || b.isNull(k)) return null
        val arr = b.obj[k] as? JsonArray ?: throw ApiException(422, "Champ $k : liste attendue")
        return arr.map { (it as? JsonPrimitive)?.longOrNull ?: throw ApiException(422, "Champ $k : identifiants attendus") }
    }

    private val MATRICULE = Regex("^[a-zA-Z0-9._-]{2,60}$")

    private fun activeAdmins(siteId: Long) =
        sql.one("SELECT COUNT(*) AS n FROM users WHERE site_id=? AND role='ADMIN' AND active=1", siteId)!!.long("n")

    private fun createUser(req: Request): Response {
        val u = admin(req)
        val b = Body(req.json())
        val matricule = (b.str("matricule", max = 60) ?: b.reqStr("username", 2, 60)).trim().lowercase()
        if (!MATRICULE.matches(matricule)) throw ApiException(422, "Matricule : 2 à 60 caractères, lettres, chiffres, . _ - uniquement")
        val first = b.str("first_name", max = 60)?.trim().orEmpty()
        val last = b.str("last_name", max = 60)?.trim().orEmpty()
        val fullName = User.displayName(first, last).ifEmpty { b.reqStr("full_name", 1, 120).trim() }
        val role = b.enum("role", Role.ALL) ?: throw ApiException(422, "Champ role obligatoire")
        val password = b.reqStr("password", 8, 200)
        Security.checkPasswordPolicy(password)
        val active = b.bool("active") ?: true
        unique("Utilisateur") {
            sql.exec(
                "INSERT INTO users(site_id, username, first_name, last_name, full_name, role, password_hash, active, must_change_password, created_at) " +
                    "VALUES (?,?,?,?,?,?,?,?,1,?)",
                u.siteId, matricule, first, last.ifEmpty { if (first.isEmpty()) fullName else "" }, fullName, role,
                Security.hashPassword(password, config.passwordIterations), active, now(),
            )
        }
        val id = sql.lastInsertId()
        saveUserLines(id, u.siteId, longList(b, "line_ids"))
        audit("USER_CREATED", u, "user", id, jsonObj { put("matricule", matricule); put("role", role); put("active", active) }, req)
        return Response.json(userJson(User.of(sql.one("SELECT * FROM users WHERE id=?", id)!!)), 201)
    }

    private fun updateUser(req: Request, idParam: String): Response {
        val u = admin(req)
        val target = sql.one("SELECT * FROM users WHERE id=?", idParam.toLongOrNull())?.let { User.of(it) }
        if (target == null || target.siteId != u.siteId) throw ApiException(404, "Utilisateur introuvable")
        val b = Body(req.json())
        val first = b.str("first_name", max = 60)?.trim()
        val last = b.str("last_name", max = 60)?.trim()
        val fullNameIn = b.str("full_name", max = 120)?.trim()
        val role = b.enum("role", Role.ALL)
        val active = b.bool("active")
        val reset = b.str("reset_password", 8, 200)
        val lines = longList(b, "line_ids")
        if (target.id == u.id && (active == false || (role != null && role != Role.ADMIN))) {
            throw ApiException(409, "Un administrateur ne peut pas se désactiver ni retirer son propre rôle")
        }
        if (target.role == Role.ADMIN && target.active && (active == false || (role != null && role != Role.ADMIN)) && activeAdmins(u.siteId) <= 1) {
            throw ApiException(409, "Impossible : c'est le dernier administrateur actif")
        }
        val detail = LinkedHashMap<String, JsonElement>()
        fun change(k: String, old: Any?, new: Any?) { if (old != new) detail[k] = JsonArray(listOf(jsonValue(old), jsonValue(new))) }
        if (first != null || last != null || fullNameIn != null) {
            val f = first ?: target.firstName
            val l = last ?: target.lastName
            val full = if (first != null || last != null) User.displayName(f, l) else fullNameIn!!
            if (full.isEmpty()) throw ApiException(422, "Nom obligatoire")
            change("full_name", target.fullName, full)
            sql.exec("UPDATE users SET first_name=?, last_name=?, full_name=? WHERE id=?", f, l, full, target.id)
        }
        role?.let { change("role", target.role, it); sql.exec("UPDATE users SET role=? WHERE id=?", it, target.id) }
        active?.let {
            change("active", target.active, it)
            sql.exec("UPDATE users SET active=? WHERE id=?", it, target.id)
            if (!it) sql.exec("UPDATE user_sessions SET revoked_at=? WHERE user_id=? AND revoked_at IS NULL", now(), target.id)
        }
        reset?.let {
            Security.checkPasswordPolicy(it)
            sql.exec("UPDATE users SET password_hash=?, must_change_password=1, failed_logins=0, locked_until=NULL WHERE id=?",
                Security.hashPassword(it, config.passwordIterations), target.id)
            sql.exec("UPDATE user_sessions SET revoked_at=? WHERE user_id=? AND revoked_at IS NULL", now(), target.id)
            detail["password_reset"] = JsonPrimitive(true)
        }
        lines?.let {
            change("line_ids", wf.userLines(target.id).joinToString(","), it.distinct().sorted().joinToString(","))
            saveUserLines(target.id, u.siteId, it)
        }
        audit("USER_UPDATED", u, "user", target.id, JsonObject(detail + ("matricule" to JsonPrimitive(target.username))), req)
        return Response.json(userJson(User.of(sql.one("SELECT * FROM users WHERE id=?", target.id)!!)))
    }

    /** Suppression réservée à un compte créé par erreur : s'il apparaît dans l'historique, il faut le désactiver. */
    private fun deleteUser(req: Request, idParam: String): Response {
        val u = admin(req)
        val target = sql.one("SELECT * FROM users WHERE id=?", idParam.toLongOrNull())?.let { User.of(it) }
        if (target == null || target.siteId != u.siteId) throw ApiException(404, "Utilisateur introuvable")
        if (target.id == u.id) throw ApiException(409, "Un administrateur ne peut pas supprimer son propre compte")
        val refs = listOf(
            "interventions" to "reported_by_id", "interventions" to "assigned_to_id", "interventions" to "quality_by_id",
            "interventions" to "closed_by_id", "intervention_events" to "user_id", "signatures" to "user_id",
            "parts_used" to "added_by_id", "part_requests" to "requested_by_id", "part_requests" to "handled_by_id",
            "attachments" to "uploaded_by_id", "audit_log" to "user_id", "user_sessions" to "user_id",
        ).sumOf { (t, c) -> sql.one("SELECT COUNT(*) AS n FROM $t WHERE $c=?", target.id)!!.long("n") }
        if (refs > 0) throw ApiException(409, "Ce compte apparaît dans l'historique : désactivez-le au lieu de le supprimer")
        sql.exec("DELETE FROM user_lines WHERE user_id=?", target.id)
        sql.exec("DELETE FROM notifications WHERE user_id=?", target.id)
        sql.exec("DELETE FROM users WHERE id=?", target.id)
        audit("USER_DELETED", u, "user", target.id, jsonObj { put("matricule", target.username); put("role", target.role) }, req)
        return Response.noContent()
    }

    private fun backup(req: Request): Response {
        val u = sql.transaction { admin(req) }
        val provider = config.backup ?: throw ApiException(404, "Sauvegarde non disponible sur ce serveur")
        val (name, data) = provider()
        sql.transaction { audit("BACKUP_DOWNLOADED", u, "backup", null, jsonObj { put("size", data.size) }, req) }
        return file(data, "application/zip", name)
    }

    // Placé après toutes les propriétés : routes() lit refSpecs pendant l'enregistrement.
    init {
        Schema.migrate(sql)
        routes()
    }

    // ================================================================== routes
    private fun routes() = with(router) {
        post("auth/login") { r, _ -> login(r) }
        post("auth/logout") { r, _ ->
            val (u, sid) = session(r)
            sql.exec("UPDATE user_sessions SET revoked_at=? WHERE id=?", now(), sid)
            audit("LOGOUT", u, req = r)
            Response.noContent()
        }
        get("auth/me") { r, _ -> Response.json(userJson(session(r).first)) }
        post("auth/change-password") { r, _ ->
            val (u, sid) = session(r)
            val b = Body(r.json())
            val current = b.reqStr("current_password", 0)
            val new = b.reqStr("new_password", 8, 200)
            if (!Security.verifyPassword(current, u.passwordHash)) throw ApiException(400, "Mot de passe actuel incorrect")
            if (current == new) throw ApiException(422, "Le nouveau mot de passe doit être différent de l'ancien")
            Security.checkPasswordPolicy(new)
            sql.exec("UPDATE users SET password_hash=?, must_change_password=0 WHERE id=?",
                Security.hashPassword(new, config.passwordIterations), u.id)
            sql.exec("UPDATE user_sessions SET revoked_at=? WHERE user_id=? AND revoked_at IS NULL AND id<>?", now(), u.id, sid)
            audit("PASSWORD_CHANGED", u, req = r)
            Response.noContent()
        }

        get("referentials") { r, _ -> referentials(r) }
        get("machines/by-qr/{token}") { r, p ->
            val u = user(r)
            val m = sql.one("SELECT m.* FROM machines m JOIN lines l ON l.id = m.line_id WHERE m.qr_token=? AND l.site_id=?", p["token"], u.siteId)
                ?: throw ApiException(404, "Machine inconnue")
            Response.json(rowJson(m))
        }
        get("machines/{id}/history") { r, p -> machineHistory(r, p["id"]!!.toLongOrNull() ?: throw ApiException(404, "Machine introuvable")) }

        // Configuration de l'usine (Admin).
        for (spec in refSpecs) {
            get("admin/${spec.path}") { r, _ -> Response.json(refList(spec, admin(r))) }
            post("admin/${spec.path}") { r, _ -> refSave(spec, admin(r), r, null) }
            put("admin/${spec.path}/{id}") { r, p -> refSave(spec, admin(r), r, p["id"]!!.toLongOrNull() ?: -1) }
            delete("admin/${spec.path}/{id}") { r, p -> refDelete(spec, admin(r), r, p["id"]!!.toLongOrNull() ?: -1) }
        }
        get("admin/site") { r, _ -> Response.json(rowJson(sql.one("SELECT * FROM sites WHERE id=?", admin(r).siteId)!!)) }
        put("admin/site") { r, _ ->
            val u = admin(r)
            val b = Body(r.json())
            val name = b.reqStr("name", 1, 120).trim()
            val code = b.reqStr("code", 1, 20).trim()
            val tz = b.str("timezone", max = 64)?.trim() ?: "Africa/Tunis"
            if (runCatching { ZoneId.of(tz) }.isFailure) throw ApiException(422, "Fuseau horaire invalide")
            unique("Site") { sql.exec("UPDATE sites SET name=?, code=?, timezone=? WHERE id=?", name, code, tz, u.siteId) }
            audit("SITE_UPDATED", u, "site", u.siteId, jsonObj { put("name", name); put("code", code); put("timezone", tz) }, r)
            Response.json(rowJson(sql.one("SELECT * FROM sites WHERE id=?", u.siteId)!!))
        }

        get("admin/users") { r, _ ->
            val u = admin(r)
            Response.json(JsonArray(sql.query("SELECT * FROM users WHERE site_id=? ORDER BY username", u.siteId).map { userJson(User.of(it)) }))
        }
        post("admin/users") { r, _ -> createUser(r) }
        patch("admin/users/{id}") { r, p -> updateUser(r, p["id"]!!) }
        delete("admin/users/{id}") { r, p -> deleteUser(r, p["id"]!!) }

        get("admin/settings") { r, _ ->
            val u = user(r)
            Response.json(buildJsonObject { put("values", JsonObject(settings.all(u.siteId))) })
        }
        put("admin/settings") { r, _ ->
            val u = admin(r)
            val body = r.json()
            val unknown = body.keys.filter { it !in DEFAULT_SETTINGS }
            if (unknown.isNotEmpty()) throw ApiException(422, "Paramètres inconnus : " + unknown.joinToString(", "))
            val before = settings.all(u.siteId)
            body.forEach { (k, v) ->
                if (!sameType(v, DEFAULT_SETTINGS[k]!!)) throw ApiException(422, "Type invalide pour $k")
                if (k == "session_idle_minutes" && (v as JsonPrimitive).longOrNull!! !in 0..720) {
                    throw ApiException(422, "Déconnexion automatique : entre 0 (jamais) et 720 minutes")
                }
                settings.set(u.siteId, k, v)
            }
            audit("SETTINGS_UPDATED", u, "settings", null, buildJsonObject {
                put("new", body); put("old", JsonObject(before.filterKeys { it in body.keys }))
            }, r)
            Response.json(buildJsonObject { put("values", JsonObject(settings.all(u.siteId))) })
        }
        get("admin/audit") { r, _ ->
            val u = admin(r)
            val cond = mutableListOf("(user_id IN (SELECT id FROM users WHERE site_id=?) OR user_id IS NULL)")
            val args = mutableListOf<Any?>(u.siteId)
            listOf("action", "username", "entity_id").forEach { k -> r.q(k)?.takeIf { it.isNotEmpty() }?.let { cond += "$k = ?"; args += it } }
            r.q("date_from")?.let { Body.parseInstant(it)?.let { d -> cond += "at >= ?"; args += d.toEpochMilli() } }
            r.q("date_to")?.let { Body.parseInstant(it)?.let { d -> cond += "at < ?"; args += d.toEpochMilli() } }
            val limit = r.q("limit")?.toIntOrNull()?.coerceIn(1, 1000) ?: 100
            val offset = r.q("offset")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
            val rows = sql.query("SELECT * FROM audit_log WHERE ${cond.joinToString(" AND ")} ORDER BY id DESC LIMIT ? OFFSET ?",
                *(args + limit + offset).toTypedArray())
            Response.json(JsonArray(rows.map { a ->
                buildJsonObject {
                    put("id", a.long("id")); put("at", iso(a.long("at"))); put("username", a.strOrNull("username"))
                    put("action", a.str("action")); put("entity", a.strOrNull("entity")); put("entity_id", a.strOrNull("entity_id"))
                    put("ip", a.strOrNull("ip"))
                    put("detail", a.strOrNull("detail")?.let { kotlinx.serialization.json.Json.parseToJsonElement(it) } ?: JsonNull)
                }
            }))
        }

        get("dashboard") { r, _ ->
            val u = user(r)
            Response.json(dashboard.compute(u, dashboard.filter(r, now()), now()).json)
        }
        get("dashboard/export.xlsx") { r, _ ->
            val u = user(r)
            val data = dashboard.xlsx(u, dashboard.filter(r, now()), now())
            audit("EXPORT_XLSX", u, "dashboard", null, null, r)
            file(data, XLSX, "tableau-de-bord-${java.time.LocalDate.now(siteZone(u))}.xlsx")
        }
        get("dashboard/export.pdf") { r, _ ->
            val u = user(r)
            val data = config.pdf.render(dashboard.pdf(u, dashboard.filter(r, now()), now(), company(u.siteId)))
            audit("EXPORT_PDF", u, "dashboard", null, null, r)
            file(data, "application/pdf", "tableau-de-bord-${java.time.LocalDate.now(siteZone(u))}.pdf", inline = true)
        }

        get("notifications") { r, _ ->
            val u = user(r)
            notifier.sweepLate(u.siteId, now())
            Response.json(notifier.list(u, r.q("unread_only")?.toBooleanStrictOrNull() ?: false,
                r.q("limit")?.toIntOrNull()?.coerceIn(1, 200) ?: 50))
        }
        post("notifications/read") { r, _ ->
            val u = user(r)
            notifier.markRead(u, longList(Body(r.json()), "ids"), now())
            Response.noContent()
        }

        get("interventions") { r, _ -> search(r) }
        get("interventions/export.xlsx") { r, _ -> exportList(r) }
        post("interventions") { r, _ -> create(r) }
        get("interventions/by-number/{number}") { r, p ->
            val u = user(r)
            val row = sql.one("SELECT id FROM interventions WHERE number=? AND site_id=?", p["number"]!!.uppercase(), u.siteId)
                ?: throw ApiException(404, "Panne introuvable")
            Response.json(detail(u, row.str("id")))
        }
        get("interventions/{id}") { r, p -> Response.json(detail(user(r), p["id"]!!)) }
        patch("interventions/{id}") { r, p -> patch(r, p["id"]!!) }
        post("interventions/{id}/actions/{name}") { r, p -> action(r, p["id"]!!, p["name"]!!) }
        post("interventions/{id}/parts") { r, p -> addPart(r, p["id"]!!) }
        delete("interventions/{id}/parts/{partId}") { r, p -> deletePart(r, p["id"]!!, p["partId"]!!) }
        get("interventions/{id}/similar") { r, p -> similar(r, p["id"]!!) }
        get("interventions/{id}/pdf") { r, p -> pdf(r, p["id"]!!) }
        post("interventions/{id}/attachments") { r, p -> upload(r, p["id"]!!) }
        get("attachments/{id}") { r, p -> attachment(r, p["id"]!!) }
        get("signatures/{id}/image") { r, p -> signatureImage(r, p["id"]!!) }
    }

    private fun sameType(v: JsonElement, ref: JsonElement): Boolean = when {
        ref is JsonObject -> v is JsonObject
        ref is JsonPrimitive && ref.isString -> v is JsonPrimitive && v.isString
        ref is JsonPrimitive && ref.booleanOrNull != null -> v is JsonPrimitive && !v.isString && v.booleanOrNull != null
        ref is JsonPrimitive && ref.longOrNull != null -> v is JsonPrimitive && !v.isString && v.longOrNull != null
        else -> false
    }

    private companion object {
        const val XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    }
}
