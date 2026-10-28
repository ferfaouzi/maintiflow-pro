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
    val version: String = "1.0.0-maitre",
    val clock: () -> Long = { System.currentTimeMillis() },
    val log: (String) -> Unit = {},
)

/**
 * Serveur MaintiFlow complet exécuté sur le téléphone maître : même API REST (/api/v1) et mêmes règles
 * que le serveur PC (FastAPI), stockage SQLite. Les requêtes sont traitées une par une (verrou global),
 * ce qui garantit la numérotation sans doublon et le contrôle de version.
 */
class MaintiFlowServer(private val sql: Sql, private val config: ServerConfig) {
    private val settings = SettingsStore(sql)
    private val wf = Workflow(sql, settings)
    private val router = Router()
    private val lock = Any()
    private val dummyHash by lazy { Security.hashPassword("dummy-password-0", config.passwordIterations) }

    init {
        Schema.migrate(sql)
        routes()
    }

    private fun now() = config.clock()

    fun handle(req: Request): Response = synchronized(lock) {
        val path = req.path
        try {
            when {
                path == "/health" || path == "/health/" -> Response.json(buildJsonObject {
                    put("status", "ok"); put("version", config.version); put("mode", "telephone-maitre")
                })
                path == "/api/v1" || path.startsWith("/api/v1/") ->
                    sql.transaction { router.dispatch(req, path.removePrefix("/api/v1")) }
                else -> Response.error(404, "Not Found")
            }
        } catch (e: ApiException) {
            Response.error(e.status, e.detail)
        } catch (e: SqlConstraintException) {
            config.log("Contrainte refusée : ${e.message}")
            Response.error(409, if (e.message?.contains("MaintiFlow") == true) e.message!!.substringAfter("MaintiFlow").trimStart(' ', ':') else "Conflit : donnée déjà existante")
        }
    }

    // ================================================================== authentification
    private fun session(req: Request): Pair<User, String> {
        val unauthorized = ApiException(401, "Session invalide ou expirée")
        val auth = req.header("authorization") ?: throw unauthorized
        if (!auth.startsWith("Bearer ", ignoreCase = true)) throw unauthorized
        val sid = Security.sha256Hex(auth.substring(7).trim())
        val s = sql.one("SELECT * FROM user_sessions WHERE id=?", sid) ?: throw unauthorized
        if (s.longOrNull("revoked_at") != null || s.long("expires_at") <= now()) throw unauthorized
        val user = sql.one("SELECT * FROM users WHERE id=?", s.long("user_id"))?.let { User.of(it) } ?: throw unauthorized
        if (!user.active) throw unauthorized
        return user to sid
    }

    private fun user(req: Request): User {
        val u = session(req).first
        if (u.mustChangePassword) throw ApiException(403, "PASSWORD_CHANGE_REQUIRED")
        return u
    }

    private fun admin(req: Request): User {
        val u = user(req)
        if (u.role != Role.ADMIN) throw ApiException(403, "Action non autorisée pour votre rôle")
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

    private fun login(req: Request): Response {
        val b = Body(req.json())
        val username = b.reqStr("username", 1, 60).trim().lowercase()
        val password = b.reqStr("password", 1, 200)
        val device = b.str("device", max = 120)
        val t = now()
        val generic = Response.error(401, "Identifiant ou mot de passe incorrect")
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
            "INSERT INTO user_sessions(id, user_id, created_at, expires_at, device) VALUES (?,?,?,?,?)",
            sid, user.id, t, expires, (device ?: req.header("user-agent") ?: "").take(120),
        )
        audit("LOGIN", user, req = req, detail = jsonObj { put("session", sid.take(12)) })
        return Response.json(buildJsonObject {
            put("access_token", token); put("token_type", "bearer"); put("expires_at", iso(expires))
            put("user", user.copy(failedLogins = 0, lockedUntil = null).json())
        })
    }

    // ================================================================== bons : sérialisation
    private fun person(id: Long?, name: String?): JsonElement =
        if (id == null) JsonNull else buildJsonObject { put("id", id); put("full_name", name) }

    private fun summaryJson(iv: Iv, r: Row, now: Long, limits: JsonObject?) = buildJsonObject {
        put("id", iv.id); put("number", iv.number); put("status", iv.status); put("priority", iv.priority)
        put("line_code", r.str("l_code")); put("line_name", r.str("l_name"))
        put("machine_code", r.str("m_code")); put("machine_name", r.str("m_name"))
        put("category_name", r.str("c_name")); put("description", iv.description)
        put("reported_by", person(iv.reportedById, r.str("r_name")))
        put("assigned_to", person(iv.assignedToId, r.strOrNull("a_name")))
        put("declared_at", iso(iv.declaredAt)); put("production_stopped", iv.productionStopped)
        put("recurring", iv.recurring); put("paused", iv.paused); put("late", wf.isLate(iv, now, limits))
        put("version", iv.version)
    }

    private val SELECT_IV = """SELECT i.*, l.code AS l_code, l.name AS l_name, m.code AS m_code, m.name AS m_name,
        c.name AS c_name, r.full_name AS r_name, a.full_name AS a_name
        FROM interventions i JOIN lines l ON l.id = i.line_id JOIN machines m ON m.id = i.machine_id
        JOIN failure_categories c ON c.id = i.category_id JOIN users r ON r.id = i.reported_by_id
        LEFT JOIN users a ON a.id = i.assigned_to_id"""

    private fun loadIv(user: User, id: String): Iv {
        val iv = sql.one("SELECT * FROM interventions WHERE id=?", id)?.let { Iv(it) }
        if (iv == null || !wf.canView(user, iv)) throw ApiException(404, "Bon introuvable")
        return iv
    }

    private fun detail(user: User, id: String): JsonObject {
        val iv = loadIv(user, id)
        val f = IvFull(iv, sql)
        val t = now()
        val row = sql.one("$SELECT_IV WHERE i.id=?", iv.id)!!
        val base = summaryJson(iv, row, t, null)
        return buildJsonObject {
            base.forEach { (k, v) -> put(k, v) }
            put("line_id", iv.lineId); put("machine_id", iv.machineId); put("category_id", iv.categoryId)
            put("client_uuid", iv.clientUuid)
            put("stop_start_at", iso(iv.stopStartAt)); put("stop_end_at", iso(iv.stopEndAt))
            put("accepted_at", iso(iv.acceptedAt)); put("arrived_at", iso(iv.arrivedAt))
            put("started_at", iso(iv.startedAt)); put("repaired_at", iso(iv.repairedAt))
            put("maintenance_validated_at", iso(iv.maintenanceValidatedAt))
            put("production_confirmed_at", iso(iv.productionConfirmedAt))
            put("closed_at", iso(iv.closedAt)); put("cancelled_at", iso(iv.cancelledAt))
            put("cancel_reason", iv.cancelReason); put("diagnosis", iv.diagnosis); put("root_cause", iv.rootCause)
            put("corrective_action", iv.correctiveAction); put("observations", iv.observations)
            put("machine_final_state", iv.machineFinalState); put("production_comment", iv.productionComment)
            put("reopen_count", iv.reopenCount); put("revision", iv.revision); put("locked", iv.locked)
            put("content_sha256", wf.contentSha256(iv))
            put("durations", JsonObject(Timing.compute(iv, f.events, t)))
            put("parts", buildJsonArray {
                f.parts.forEach { p ->
                    add(buildJsonObject {
                        put("id", p.id); put("reference", p.reference); put("designation", p.designation)
                        put("quantity", p.quantity); put("unit", p.unit); put("added_at", iso(p.addedAt))
                    })
                }
            })
            put("signatures", buildJsonArray {
                f.signatures.forEach { s ->
                    add(buildJsonObject {
                        put("id", s.id); put("role", s.role); put("user_name", s.userName)
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
                        put("from_status", e.fromStatus); put("to_status", e.toStatus); put("comment", e.comment)
                        put("data", e.data ?: JsonNull); put("user_name", e.userName)
                    })
                }
            })
            put("allowed_actions", JsonArray(wf.allowedActions(user, iv).map { JsonPrimitive(it) }))
            put("editable_fields", JsonArray(wf.editableFields(user, iv).sorted().map { JsonPrimitive(it) }))
        }
    }

    // ================================================================== bons : routes
    private fun search(req: Request): Response {
        val user = user(req)
        val cond = mutableListOf("i.site_id = ?")
        val args = mutableListOf<Any?>(user.siteId)
        req.q("q")?.trim()?.takeIf { it.isNotEmpty() }?.let { q ->
            cond += "(i.number LIKE ? OR i.description LIKE ? OR m.code LIKE ? OR m.name LIKE ?)"
            repeat(4) { args += "%$q%" }
        }
        val statuses = req.qAll("status")
        if (statuses.isNotEmpty()) {
            statuses.forEach { if (it !in St.ALL) throw ApiException(422, "Statut invalide : $it") }
            cond += "i.status IN (${statuses.joinToString(",") { "?" }})"
            args.addAll(statuses)
        }
        val openOnly = req.q("open_only")?.toBooleanStrictOrNull() ?: false
        if (openOnly) cond += "i.status NOT IN ('CLOTURE','ANNULE')"
        if (req.q("mine")?.toBooleanStrictOrNull() == true) {
            cond += if (user.role == Role.TECHNICIEN) "i.assigned_to_id = ?" else "i.reported_by_id = ?"
            args += user.id
        }
        listOf("line_id" to "i.line_id", "machine_id" to "i.machine_id", "category_id" to "i.category_id",
            "technician_id" to "i.assigned_to_id").forEach { (p, col) ->
            req.q(p)?.let { v ->
                cond += "$col = ?"
                args += v.toLongOrNull() ?: throw ApiException(422, "Paramètre $p invalide")
            }
        }
        req.q("priority")?.let {
            if (it !in Priority.ALL) throw ApiException(422, "Priorité invalide")
            cond += "i.priority = ?"; args += it
        }
        req.q("date_from")?.let {
            cond += "i.declared_at >= ?"
            args += (Body.parseInstant(it) ?: throw ApiException(422, "date_from invalide")).toEpochMilli()
        }
        req.q("date_to")?.let {
            cond += "i.declared_at < ?"
            args += (Body.parseInstant(it) ?: throw ApiException(422, "date_to invalide")).toEpochMilli()
        }
        val limit = req.q("limit")?.toIntOrNull()?.takeIf { it in 1..200 } ?: if (req.q("limit") == null) 50
            else throw ApiException(422, "limit doit être entre 1 et 200")
        val offset = req.q("offset")?.toIntOrNull()?.takeIf { it >= 0 } ?: 0
        val where = cond.joinToString(" AND ")
        val total = sql.one(
            "SELECT COUNT(*) AS n FROM interventions i JOIN machines m ON m.id = i.machine_id WHERE $where", *args.toTypedArray(),
        )!!.long("n")
        val order = if (openOnly) {
            "CASE i.priority WHEN 'CRITIQUE' THEN 0 WHEN 'HAUTE' THEN 1 WHEN 'NORMALE' THEN 2 ELSE 3 END, i.declared_at ASC"
        } else "i.declared_at DESC"
        val t = now()
        val limits = settings.get(user.siteId, "late_acceptance_minutes") as? JsonObject
        val items = sql.query("$SELECT_IV WHERE $where ORDER BY $order LIMIT ? OFFSET ?", *(args + limit + offset).toTypedArray())
            .map { summaryJson(Iv(it), it, t, limits) }
        return Response.json(buildJsonObject { put("total", total); put("items", JsonArray(items)) })
    }

    private fun nextNumber(siteId: Long, year: Int): String {
        sql.exec("INSERT OR IGNORE INTO number_sequences(site_id, year, last_value) VALUES (?,?,0)", siteId, year)
        sql.exec("UPDATE number_sequences SET last_value = last_value + 1 WHERE site_id=? AND year=?", siteId, year)
        val n = sql.one("SELECT last_value FROM number_sequences WHERE site_id=? AND year=?", siteId, year)!!.long("last_value")
        return "BI-$year-${n.toString().padStart(6, '0')}"
    }

    private fun create(req: Request): Response {
        val user = user(req)
        if (user.role != Role.CHEF_EQUIPE) throw ApiException(403, "Seul un chef d'équipe production peut déclarer un bon")
        val b = Body(req.json())
        val clientUuid = b.str("client_uuid", max = 36)
        val machineId = b.reqLong("machine_id")
        val categoryId = b.reqLong("category_id")
        val description = b.reqStr("description", 3, 4000)
        val priority = b.enum("priority", Priority.ALL)
        val stopped = b.bool("production_stopped") ?: false
        val stopStartIn = b.instant("stop_start_at")
        val declaredIn = b.instant("declared_at")

        if (clientUuid != null) {
            sql.one("SELECT id, reported_by_id FROM interventions WHERE client_uuid=?", clientUuid)?.let {
                if (it.long("reported_by_id") != user.id) throw ApiException(409, "Identifiant client déjà utilisé")
                return Response.json(detail(user, it.str("id")), 200)  // ré-envoi : pas de doublon
            }
        }
        val machine = sql.one(
            "SELECT m.id, m.line_id, m.code, m.active, l.site_id, l.active AS line_active FROM machines m " +
                "JOIN lines l ON l.id = m.line_id WHERE m.id=?", machineId,
        )
        if (machine == null || !machine.bool("active") || machine.long("site_id") != user.siteId || !machine.bool("line_active")) {
            throw ApiException(422, "Machine invalide")
        }
        val cat = sql.one("SELECT * FROM failure_categories WHERE id=?", categoryId)
        if (cat == null || !cat.bool("active") || cat.long("site_id") != user.siteId) throw ApiException(422, "Catégorie invalide")

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
            """INSERT INTO interventions(id, number, client_uuid, site_id, line_id, machine_id, category_id, priority,
               status, description, reported_by_id, production_stopped, stop_start_at, declared_at, created_at, updated_at)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            id, nextNumber(user.siteId, year), clientUuid, user.siteId, machine.long("line_id"), machineId, categoryId,
            priority ?: cat.str("default_priority"), St.NOUVEAU, description.trim(), user.id, stopped, stopStart,
            declared, t, t,
        )
        val iv = Iv(sql.one("SELECT * FROM interventions WHERE id=?", id)!!)
        wf.addEvent(iv, user, "CREATION", declared, to = St.NOUVEAU, fromStatus = null,
            data = jsonObj { put("offline", declaredIn != null) })
        val previous = wf.detectRecurrence(iv)
        if (previous > 0) {
            iv.recurring = true
            wf.addEvent(iv, null, "PANNE_RECURRENTE", declared,
                comment = "$previous bon(s) similaire(s) sur cette machine pendant la période de référence",
                data = jsonObj { put("source", "auto"); put("previous", previous) })
            iv.save(sql, t)
        }
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
        val known = listOf("description", "category_id", "priority", "production_stopped", "stop_start_at",
            "diagnosis", "root_cause", "corrective_action", "observations", "machine_final_state")
        val changes = known.filter { b.has(it) }
        val refused = changes.filter { it !in allowed }
        if (refused.isNotEmpty()) {
            throw ApiException(403, "Champs non modifiables par vous à cet état : " + refused.joinToString(", "))
        }
        val diff = LinkedHashMap<String, JsonElement>()
        for (k in changes) {
            var v: Any? = when (k) {
                "description" -> b.str(k, 3, 4000)
                "category_id" -> b.long(k)?.also { cid ->
                    val cat = sql.one("SELECT site_id, active FROM failure_categories WHERE id=?", cid)
                    if (cat == null || cat.long("site_id") != iv.siteId || !cat.bool("active")) throw ApiException(422, "Catégorie invalide")
                }
                "priority" -> b.enum(k, Priority.ALL)
                "production_stopped" -> b.bool(k)
                "stop_start_at" -> b.instant(k)?.toEpochMilli()
                "machine_final_state" -> b.enum(k, MachineState.ALL)
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
        val t = now()
        if (iv.productionStopped && iv.stopStartAt == null) {
            iv.stopStartAt = t
            diff["stop_start_at"] = JsonArray(listOf(JsonNull, JsonPrimitive(iso(t))))
        }
        if (!iv.productionStopped) iv.stopStartAt = null
        if (diff.isEmpty()) return Response.json(detail(user, id))
        if (iv.status == St.REPARE) wf.supersedeSignatures(iv, setOf(SigRole.MAINTENANCE, SigRole.RESPONSABLE))
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
        if (name == "confirm_production") archivePdf(user, id, t)
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

    private fun renderPdf(iv: Iv, t: Long): ByteArray {
        val f = IvFull(iv, sql)
        val site = sql.one("SELECT timezone FROM sites WHERE id=?", iv.siteId)!!
        val company = (settings.get(iv.siteId, "company_display_name") as? JsonPrimitive)?.content.orEmpty()
        val doc = PdfBuilder.build(f, wf.contentSha256(iv), company, site.str("timezone"),
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
            throw ApiException(403, "Seul le technicien affecté (ou le Responsable) peut ajouter des pièces")
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
        if (iv.status == St.REPARE) wf.supersedeSignatures(iv, setOf(SigRole.MAINTENANCE, SigRole.RESPONSABLE))
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
        if (iv.status == St.REPARE) wf.supersedeSignatures(iv, setOf(SigRole.MAINTENANCE, SigRole.RESPONSABLE))
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
            put("disclaimer", "Interventions passées comparables, à titre indicatif uniquement : " +
                "elles ne remplacent pas le diagnostic du technicien.")
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
        val may = (user.role == Role.CHEF_EQUIPE && kind == "PROBLEME") ||
            (user.role in setOf(Role.TECHNICIEN, Role.RESPONSABLE) && "diagnosis" in wf.editableFields(user, iv))
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
        val a = sql.one("SELECT a.*, i.site_id FROM attachments a JOIN interventions i ON i.id = a.intervention_id WHERE a.id=?", attId)
        if (a == null || a.long("site_id") != user.siteId) throw ApiException(404, "Fichier introuvable")
        val f = resolve(a.str("storage_path"))
        if (!f.exists()) throw ApiException(404, "Fichier introuvable")
        return Response(200, f.readBytes(), a.str("content_type"), mapOf("Cache-Control" to "private"))
    }

    private fun signatureImage(req: Request, sigId: String): Response {
        val user = user(req)
        val s = sql.one(
            "SELECT s.image_png, i.site_id FROM signatures s JOIN interventions i ON i.id = s.intervention_id WHERE s.id=?",
            sigId.toLongOrNull(),
        )
        val img = s?.bytes("image_png")
        if (s == null || img == null || s.long("site_id") != user.siteId) throw ApiException(404, "Signature introuvable")
        return Response(200, img, "image/png", mapOf("Cache-Control" to "private"))
    }

    // ================================================================== référentiels et administration
    private fun lineJson(r: Row) = buildJsonObject {
        put("id", r.long("id")); put("code", r.str("code")); put("name", r.str("name")); put("active", r.bool("active"))
    }

    private fun machineJson(r: Row) = buildJsonObject {
        put("id", r.long("id")); put("line_id", r.long("line_id")); put("code", r.str("code")); put("name", r.str("name"))
        put("qr_token", r.str("qr_token")); put("active", r.bool("active"))
    }

    private fun categoryJson(r: Row) = buildJsonObject {
        put("id", r.long("id")); put("code", r.str("code")); put("name", r.str("name"))
        put("default_priority", r.str("default_priority")); put("active", r.bool("active"))
    }

    private fun referentials(req: Request): Response {
        val user = user(req)
        val s = user.siteId
        return Response.json(buildJsonObject {
            put("lines", JsonArray(sql.query("SELECT * FROM lines WHERE site_id=? AND active=1 ORDER BY code", s).map { lineJson(it) }))
            put("machines", JsonArray(sql.query(
                "SELECT m.* FROM machines m JOIN lines l ON l.id = m.line_id WHERE l.site_id=? AND m.active=1 AND l.active=1 ORDER BY m.code", s,
            ).map { machineJson(it) }))
            put("categories", JsonArray(sql.query(
                "SELECT * FROM failure_categories WHERE site_id=? AND active=1 ORDER BY name", s,
            ).map { categoryJson(it) }))
            put("technicians", JsonArray(sql.query(
                "SELECT * FROM users WHERE site_id=? AND role='TECHNICIEN' AND active=1 ORDER BY full_name", s,
            ).map { User.of(it).json() }))
        })
    }

    private inline fun <T> unique(what: String, block: () -> T): T = try {
        block()
    } catch (e: SqlConstraintException) {
        if (e.message?.contains("UNIQUE", ignoreCase = true) == true) throw ApiException(409, "$what : code déjà utilisé")
        throw e
    }

    private fun lineBody(b: Body) = Triple(b.reqStr("code", 1, 30), b.reqStr("name", 1, 120), b.bool("active") ?: true)

    private fun ownLine(user: User, lineId: Long?): Row {
        val l = lineId?.let { sql.one("SELECT * FROM lines WHERE id=?", it) }
        if (l == null || l.long("site_id") != user.siteId) throw ApiException(422, "Ligne invalide")
        return l
    }

    private fun userJson(r: Row) = User.of(r).json()

    private fun routes() = with(router) {
        post("auth/login") { r, _ -> login(r) }
        post("auth/logout") { r, _ ->
            val (u, sid) = session(r)
            sql.exec("UPDATE user_sessions SET revoked_at=? WHERE id=?", now(), sid)
            audit("LOGOUT", u, req = r)
            Response.noContent()
        }
        get("auth/me") { r, _ -> Response.json(session(r).first.json()) }
        post("auth/change-password") { r, _ ->
            val (u, sid) = session(r)
            val b = Body(r.json())
            val current = b.reqStr("current_password", 0)
            val new = b.reqStr("new_password", 8, 200)
            if (!Security.verifyPassword(current, u.passwordHash)) throw ApiException(400, "Mot de passe actuel incorrect")
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
            Response.json(machineJson(m))
        }

        get("admin/lines") { r, _ ->
            val u = admin(r)
            Response.json(JsonArray(sql.query("SELECT * FROM lines WHERE site_id=? ORDER BY code", u.siteId).map { lineJson(it) }))
        }
        post("admin/lines") { r, _ ->
            val u = admin(r)
            val (code, name, active) = lineBody(Body(r.json()))
            unique("Ligne") { sql.exec("INSERT INTO lines(site_id, code, name, active) VALUES (?,?,?,?)", u.siteId, code, name, active) }
            val id = sql.lastInsertId()
            audit("LINE_CREATED", u, "line", id, jsonObj { put("code", code); put("name", name); put("active", active) }, r)
            Response.json(lineJson(sql.one("SELECT * FROM lines WHERE id=?", id)!!), 201)
        }
        put("admin/lines/{id}") { r, p ->
            val u = admin(r)
            val l = sql.one("SELECT * FROM lines WHERE id=?", p["id"]!!.toLongOrNull())
            if (l == null || l.long("site_id") != u.siteId) throw ApiException(404, "Ligne introuvable")
            val (code, name, active) = lineBody(Body(r.json()))
            unique("Ligne") { sql.exec("UPDATE lines SET code=?, name=?, active=? WHERE id=?", code, name, active, l.long("id")) }
            audit("LINE_UPDATED", u, "line", l.long("id"), jsonObj { put("code", code); put("name", name); put("active", active) }, r)
            Response.json(lineJson(sql.one("SELECT * FROM lines WHERE id=?", l.long("id"))!!))
        }

        get("admin/machines") { r, _ ->
            val u = admin(r)
            Response.json(JsonArray(sql.query(
                "SELECT m.* FROM machines m JOIN lines l ON l.id = m.line_id WHERE l.site_id=? ORDER BY m.code", u.siteId,
            ).map { machineJson(it) }))
        }
        post("admin/machines") { r, _ ->
            val u = admin(r)
            val b = Body(r.json())
            val lineId = b.reqLong("line_id")
            val code = b.reqStr("code", 1, 40)
            val name = b.reqStr("name", 1, 120)
            val active = b.bool("active") ?: true
            ownLine(u, lineId)
            unique("Machine") {
                sql.exec("INSERT INTO machines(line_id, code, name, qr_token, active) VALUES (?,?,?,?,?)",
                    lineId, code, name, UUID.randomUUID().toString(), active)
            }
            val id = sql.lastInsertId()
            audit("MACHINE_CREATED", u, "machine", id, jsonObj { put("line_id", lineId); put("code", code); put("name", name) }, r)
            Response.json(machineJson(sql.one("SELECT * FROM machines WHERE id=?", id)!!), 201)
        }
        put("admin/machines/{id}") { r, p ->
            val u = admin(r)
            val m = sql.one("SELECT m.*, l.site_id FROM machines m JOIN lines l ON l.id = m.line_id WHERE m.id=?", p["id"]!!.toLongOrNull())
            if (m == null || m.long("site_id") != u.siteId) throw ApiException(404, "Machine introuvable")
            val b = Body(r.json())
            val lineId = b.reqLong("line_id")
            val code = b.reqStr("code", 1, 40)
            val name = b.reqStr("name", 1, 120)
            val active = b.bool("active") ?: true
            ownLine(u, lineId)
            unique("Machine") {
                sql.exec("UPDATE machines SET line_id=?, code=?, name=?, active=? WHERE id=?", lineId, code, name, active, m.long("id"))
            }
            audit("MACHINE_UPDATED", u, "machine", m.long("id"), jsonObj { put("line_id", lineId); put("code", code); put("name", name) }, r)
            Response.json(machineJson(sql.one("SELECT * FROM machines WHERE id=?", m.long("id"))!!))
        }

        get("admin/categories") { r, _ ->
            val u = admin(r)
            Response.json(JsonArray(sql.query("SELECT * FROM failure_categories WHERE site_id=? ORDER BY code", u.siteId).map { categoryJson(it) }))
        }
        post("admin/categories") { r, _ ->
            val u = admin(r)
            val b = Body(r.json())
            val code = b.reqStr("code", 1, 30)
            val name = b.reqStr("name", 1, 120)
            val prio = b.enum("default_priority", Priority.ALL) ?: "NORMALE"
            val active = b.bool("active") ?: true
            unique("Catégorie") {
                sql.exec("INSERT INTO failure_categories(site_id, code, name, default_priority, active) VALUES (?,?,?,?,?)",
                    u.siteId, code, name, prio, active)
            }
            val id = sql.lastInsertId()
            audit("CATEGORY_CREATED", u, "category", id, jsonObj { put("code", code); put("name", name); put("default_priority", prio) }, r)
            Response.json(categoryJson(sql.one("SELECT * FROM failure_categories WHERE id=?", id)!!), 201)
        }
        put("admin/categories/{id}") { r, p ->
            val u = admin(r)
            val c = sql.one("SELECT * FROM failure_categories WHERE id=?", p["id"]!!.toLongOrNull())
            if (c == null || c.long("site_id") != u.siteId) throw ApiException(404, "Catégorie introuvable")
            val b = Body(r.json())
            val code = b.reqStr("code", 1, 30)
            val name = b.reqStr("name", 1, 120)
            val prio = b.enum("default_priority", Priority.ALL) ?: "NORMALE"
            val active = b.bool("active") ?: true
            unique("Catégorie") {
                sql.exec("UPDATE failure_categories SET code=?, name=?, default_priority=?, active=? WHERE id=?", code, name, prio, active, c.long("id"))
            }
            audit("CATEGORY_UPDATED", u, "category", c.long("id"), jsonObj { put("code", code); put("name", name); put("default_priority", prio) }, r)
            Response.json(categoryJson(sql.one("SELECT * FROM failure_categories WHERE id=?", c.long("id"))!!))
        }

        get("admin/users") { r, _ ->
            val u = admin(r)
            Response.json(JsonArray(sql.query("SELECT * FROM users WHERE site_id=? ORDER BY username", u.siteId).map { userJson(it) }))
        }
        post("admin/users") { r, _ ->
            val u = admin(r)
            val b = Body(r.json())
            val username = b.reqStr("username", 3, 60)
            if (!Regex("^[a-zA-Z0-9._-]+$").matches(username)) throw ApiException(422, "Identifiant : lettres, chiffres, . _ - uniquement")
            val fullName = b.reqStr("full_name", 1, 120)
            val role = b.enum("role", Role.ALL) ?: throw ApiException(422, "Champ role obligatoire")
            val password = b.reqStr("password", 8, 200)
            Security.checkPasswordPolicy(password)
            unique("Utilisateur") {
                sql.exec(
                    "INSERT INTO users(site_id, username, full_name, role, password_hash, must_change_password, created_at) VALUES (?,?,?,?,?,1,?)",
                    u.siteId, username.lowercase(), fullName, role, Security.hashPassword(password, config.passwordIterations), now(),
                )
            }
            val id = sql.lastInsertId()
            audit("USER_CREATED", u, "user", id, jsonObj { put("username", username.lowercase()); put("role", role) }, r)
            Response.json(userJson(sql.one("SELECT * FROM users WHERE id=?", id)!!), 201)
        }
        patch("admin/users/{id}") { r, p ->
            val u = admin(r)
            val target = sql.one("SELECT * FROM users WHERE id=?", p["id"]!!.toLongOrNull())?.let { User.of(it) }
            if (target == null || target.siteId != u.siteId) throw ApiException(404, "Utilisateur introuvable")
            val b = Body(r.json())
            val fullName = b.str("full_name", max = 120)
            val role = b.enum("role", Role.ALL)
            val active = b.bool("active")
            val reset = b.str("reset_password", 8, 200)
            if (target.id == u.id && (active == false || (role != null && role != Role.ADMIN))) {
                throw ApiException(409, "Un administrateur ne peut pas se désactiver ni retirer son propre rôle")
            }
            val detail = LinkedHashMap<String, JsonElement>()
            fullName?.let { sql.exec("UPDATE users SET full_name=? WHERE id=?", it, target.id); detail["full_name"] = JsonPrimitive(it) }
            role?.let {
                detail["role"] = JsonArray(listOf(JsonPrimitive(target.role), JsonPrimitive(it)))
                sql.exec("UPDATE users SET role=? WHERE id=?", it, target.id)
            }
            active?.let { sql.exec("UPDATE users SET active=? WHERE id=?", it, target.id); detail["active"] = JsonPrimitive(it) }
            reset?.let {
                Security.checkPasswordPolicy(it)
                sql.exec("UPDATE users SET password_hash=?, must_change_password=1, failed_logins=0, locked_until=NULL WHERE id=?",
                    Security.hashPassword(it, config.passwordIterations), target.id)
                detail["password_reset"] = JsonPrimitive(true)
            }
            audit("USER_UPDATED", u, "user", target.id, JsonObject(detail), r)
            Response.json(userJson(sql.one("SELECT * FROM users WHERE id=?", target.id)!!))
        }

        get("admin/settings") { r, _ ->
            val u = user(r)
            Response.json(buildJsonObject { put("values", JsonObject(settings.all(u.siteId))) })
        }
        put("admin/settings") { r, _ ->
            val u = admin(r)
            val body = r.json()
            val unknown = body.keys.filter { it !in DEFAULT_SETTINGS }
            if (unknown.isNotEmpty()) throw ApiException(422, "Paramètres inconnus : " + unknown.joinToString(", "))
            body.forEach { (k, v) ->
                if (!sameType(v, DEFAULT_SETTINGS[k]!!)) throw ApiException(422, "Type invalide pour $k")
                settings.set(u.siteId, k, v)
            }
            audit("SETTINGS_UPDATED", u, "settings", null, body, r)
            Response.json(buildJsonObject { put("values", JsonObject(settings.all(u.siteId))) })
        }
        get("admin/audit") { r, _ ->
            val u = admin(r)
            val cond = mutableListOf("(user_id IN (SELECT id FROM users WHERE site_id=?) OR user_id IS NULL)")
            val args = mutableListOf<Any?>(u.siteId)
            listOf("action", "username", "entity_id").forEach { k -> r.q(k)?.let { cond += "$k = ?"; args += it } }
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

        get("interventions") { r, _ -> search(r) }
        post("interventions") { r, _ -> create(r) }
        get("interventions/by-number/{number}") { r, p ->
            val u = user(r)
            val row = sql.one("SELECT id FROM interventions WHERE number=? AND site_id=?", p["number"]!!.uppercase(), u.siteId)
                ?: throw ApiException(404, "Bon introuvable")
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
}
