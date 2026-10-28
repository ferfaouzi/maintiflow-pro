package com.maintiflow.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant

object Role {
    const val CHEF_EQUIPE = "CHEF_EQUIPE"
    const val TECHNICIEN = "TECHNICIEN"
    const val RESPONSABLE = "RESPONSABLE"
    const val ADMIN = "ADMIN"
    val ALL = listOf(CHEF_EQUIPE, TECHNICIEN, RESPONSABLE, ADMIN)
}

object St {
    const val NOUVEAU = "NOUVEAU"
    const val EN_ATTENTE = "EN_ATTENTE"
    const val ACCEPTE = "ACCEPTE"
    const val EN_COURS = "EN_COURS"
    const val EN_ATTENTE_PIECE = "EN_ATTENTE_PIECE"
    const val REPARE = "REPARE"
    const val VALIDATION_PRODUCTION = "VALIDATION_PRODUCTION"
    const val CLOTURE = "CLOTURE"
    const val ANNULE = "ANNULE"
    val ALL = listOf(NOUVEAU, EN_ATTENTE, ACCEPTE, EN_COURS, EN_ATTENTE_PIECE, REPARE, VALIDATION_PRODUCTION, CLOTURE, ANNULE)
    val OPEN = setOf(NOUVEAU, EN_ATTENTE, ACCEPTE, EN_COURS, EN_ATTENTE_PIECE, REPARE, VALIDATION_PRODUCTION)
}

object Priority {
    val ALL = listOf("BASSE", "NORMALE", "HAUTE", "CRITIQUE")
    val ORDER = mapOf("CRITIQUE" to 0, "HAUTE" to 1, "NORMALE" to 2, "BASSE" to 3)
}

object MachineState {
    val ALL = listOf("OPERATIONNELLE", "DEGRADEE", "ARRETEE")
}

object SigRole {
    const val MAINTENANCE = "MAINTENANCE"
    const val RESPONSABLE = "RESPONSABLE"
    const val PRODUCTION = "PRODUCTION"
}

/** Paramètres par défaut d'un site (identiques à backend/app/models.py DEFAULT_SETTINGS). */
val DEFAULT_SETTINGS: Map<String, JsonElement> = mapOf(
    "require_manager_validation" to JsonPrimitive(false),
    "recurrence_window_days" to JsonPrimitive(30),
    "recurrence_threshold" to JsonPrimitive(2),
    "late_acceptance_minutes" to buildJsonObject {
        put("CRITIQUE", 5); put("HAUTE", 15); put("NORMALE", 60); put("BASSE", 240)
    },
    "company_display_name" to JsonPrimitive("Usine de démonstration"),
)

fun iso(ms: Long?): String? = ms?.let { Instant.ofEpochMilli(it).toString() }

data class User(
    val id: Long,
    val siteId: Long,
    val username: String,
    val fullName: String,
    val role: String,
    val passwordHash: String,
    val active: Boolean,
    val mustChangePassword: Boolean,
    val failedLogins: Long,
    val lockedUntil: Long?,
) {
    fun json(): JsonObject = buildJsonObject {
        put("id", id); put("username", username); put("full_name", fullName); put("role", role)
        put("site_id", siteId); put("active", active); put("must_change_password", mustChangePassword)
    }

    companion object {
        fun of(r: Row) = User(
            r.long("id"), r.long("site_id"), r.str("username"), r.str("full_name"), r.str("role"),
            r.str("password_hash"), r.bool("active"), r.bool("must_change_password"), r.long("failed_logins"),
            r.longOrNull("locked_until"),
        )
    }
}

/** Bon d'intervention chargé en mémoire ; sauvegardé en une seule requête avec contrôle de version. */
class Iv(r: Row) {
    val id: String = r.str("id")
    val number: String = r.str("number")
    val clientUuid: String? = r.strOrNull("client_uuid")
    val siteId: Long = r.long("site_id")
    val lineId: Long = r.long("line_id")
    val machineId: Long = r.long("machine_id")
    var categoryId: Long = r.long("category_id")
    var priority: String = r.str("priority")
    var status: String = r.str("status")
    var description: String = r.str("description")
    val reportedById: Long = r.long("reported_by_id")
    var assignedToId: Long? = r.longOrNull("assigned_to_id")
    var productionStopped: Boolean = r.bool("production_stopped")
    var stopStartAt: Long? = r.longOrNull("stop_start_at")
    var stopEndAt: Long? = r.longOrNull("stop_end_at")
    val declaredAt: Long = r.long("declared_at")
    var acceptedAt: Long? = r.longOrNull("accepted_at")
    var arrivedAt: Long? = r.longOrNull("arrived_at")
    var startedAt: Long? = r.longOrNull("started_at")
    var repairedAt: Long? = r.longOrNull("repaired_at")
    var maintenanceValidatedAt: Long? = r.longOrNull("maintenance_validated_at")
    var productionConfirmedAt: Long? = r.longOrNull("production_confirmed_at")
    var closedAt: Long? = r.longOrNull("closed_at")
    var cancelledAt: Long? = r.longOrNull("cancelled_at")
    var cancelReason: String? = r.strOrNull("cancel_reason")
    var paused: Boolean = r.bool("paused")
    var diagnosis: String? = r.strOrNull("diagnosis")
    var rootCause: String? = r.strOrNull("root_cause")
    var correctiveAction: String? = r.strOrNull("corrective_action")
    var observations: String? = r.strOrNull("observations")
    var machineFinalState: String? = r.strOrNull("machine_final_state")
    var productionComment: String? = r.strOrNull("production_comment")
    var recurring: Boolean = r.bool("recurring")
    var reopenCount: Long = r.long("reopen_count")
    var revision: Long = r.long("revision")
    val version: Long = r.long("version")
    var archivedPdfSha256: String? = r.strOrNull("archived_pdf_sha256")
    var archivedPdfPath: String? = r.strOrNull("archived_pdf_path")

    val locked: Boolean get() = status == St.CLOTURE || status == St.ANNULE

    /** Valeurs des champs modifiables par PATCH, pour l'historique des modifications. */
    fun field(name: String): Any? = when (name) {
        "description" -> description
        "category_id" -> categoryId
        "priority" -> priority
        "production_stopped" -> productionStopped
        "stop_start_at" -> stopStartAt
        "diagnosis" -> diagnosis
        "root_cause" -> rootCause
        "corrective_action" -> correctiveAction
        "observations" -> observations
        "machine_final_state" -> machineFinalState
        else -> error(name)
    }

    fun setField(name: String, v: Any?) {
        when (name) {
            "description" -> description = v as String
            "category_id" -> categoryId = v as Long
            "priority" -> priority = v as String
            "production_stopped" -> productionStopped = v as Boolean
            "stop_start_at" -> stopStartAt = v as Long?
            "diagnosis" -> diagnosis = v as String?
            "root_cause" -> rootCause = v as String?
            "corrective_action" -> correctiveAction = v as String?
            "observations" -> observations = v as String?
            "machine_final_state" -> machineFinalState = v as String?
            else -> error(name)
        }
    }

    fun save(sql: Sql, now: Long) {
        sql.exec(
            """UPDATE interventions SET category_id=?, priority=?, status=?, description=?, assigned_to_id=?,
               production_stopped=?, stop_start_at=?, stop_end_at=?, accepted_at=?, arrived_at=?, started_at=?,
               repaired_at=?, maintenance_validated_at=?, production_confirmed_at=?, closed_at=?, cancelled_at=?,
               cancel_reason=?, paused=?, diagnosis=?, root_cause=?, corrective_action=?, observations=?,
               machine_final_state=?, production_comment=?, recurring=?, reopen_count=?, revision=?,
               archived_pdf_sha256=?, archived_pdf_path=?, updated_at=?, version=version+1
               WHERE id=? AND version=?""",
            categoryId, priority, status, description, assignedToId, productionStopped, stopStartAt, stopEndAt,
            acceptedAt, arrivedAt, startedAt, repairedAt, maintenanceValidatedAt, productionConfirmedAt, closedAt,
            cancelledAt, cancelReason, paused, diagnosis, rootCause, correctiveAction, observations,
            machineFinalState, productionComment, recurring, reopenCount, revision, archivedPdfSha256,
            archivedPdfPath, now, id, version,
        )
        if (sql.changes() == 0L) {
            throw ApiException(409, "Le bon a été modifié simultanément par un autre utilisateur. Rechargez-le.")
        }
    }
}

class Event(r: Row) {
    val id = r.long("id")
    val at = r.long("at")
    val userId = r.longOrNull("user_id")
    val type = r.str("type")
    val fromStatus = r.strOrNull("from_status")
    val toStatus = r.strOrNull("to_status")
    val comment = r.strOrNull("comment")
    val data: JsonObject? = r.strOrNull("data")?.let { Json.parseToJsonElement(it) as? JsonObject }
    val userName = r.strOrNull("user_name")
}

class Part(r: Row) {
    val id = r.long("id")
    val reference = r.str("reference")
    val designation = r.str("designation")
    val quantity = r.str("quantity")
    val unit = r.str("unit")
    val addedAt = r.long("added_at")
}

class Signature(r: Row) {
    val id = r.long("id")
    val role = r.str("role")
    val userName = r.str("user_name")
    val signedAt = r.long("signed_at")
    val revision = r.long("revision")
    val contentSha256 = r.str("content_sha256")
    val imagePng: ByteArray? = r.bytes("image_png")
    val superseded = r.bool("superseded")
}

class Attachment(r: Row) {
    val id = r.str("id")
    val kind = r.str("kind")
    val contentType = r.str("content_type")
    val sizeBytes = r.long("size_bytes")
    val storagePath = r.str("storage_path")
    val uploadedAt = r.long("uploaded_at")
}

class Ref(val id: Long, val code: String, val name: String)

/** Un bon avec tout ce qui l'accompagne (relations chargées depuis la base). */
class IvFull(val iv: Iv, sql: Sql) {
    val line: Ref = sql.one("SELECT id, code, name FROM lines WHERE id=?", iv.lineId)!!.let { Ref(it.long("id"), it.str("code"), it.str("name")) }
    val machine: Ref = sql.one("SELECT id, code, name FROM machines WHERE id=?", iv.machineId)!!.let { Ref(it.long("id"), it.str("code"), it.str("name")) }
    val category: Ref = sql.one("SELECT id, code, name FROM failure_categories WHERE id=?", iv.categoryId)!!.let { Ref(it.long("id"), it.str("code"), it.str("name")) }
    val reportedBy: User = User.of(sql.one("SELECT * FROM users WHERE id=?", iv.reportedById)!!)
    val assignedTo: User? = iv.assignedToId?.let { id -> sql.one("SELECT * FROM users WHERE id=?", id)?.let { User.of(it) } }
    val events: List<Event> = sql.query(
        "SELECT e.*, u.full_name AS user_name FROM intervention_events e LEFT JOIN users u ON u.id = e.user_id " +
            "WHERE e.intervention_id=? ORDER BY e.id", iv.id,
    ).map { Event(it) }
    val parts: List<Part> = sql.query("SELECT * FROM parts_used WHERE intervention_id=? ORDER BY id", iv.id).map { Part(it) }
    val signatures: List<Signature> = sql.query(
        "SELECT s.*, u.full_name AS user_name FROM signatures s JOIN users u ON u.id = s.user_id " +
            "WHERE s.intervention_id=? ORDER BY s.id", iv.id,
    ).map { Signature(it) }
    val attachments: List<Attachment> =
        sql.query("SELECT * FROM attachments WHERE intervention_id=? ORDER BY uploaded_at, id", iv.id).map { Attachment(it) }

    fun activeSignature(role: String): Signature? =
        signatures.firstOrNull { it.role == role && !it.superseded && it.revision == iv.revision }
}

fun JsonObject.bool(k: String): Boolean = (this[k] as? JsonPrimitive)?.boolean ?: false

internal fun jsonObj(builder: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject(builder)

internal fun kotlinx.serialization.json.JsonObjectBuilder.obj(k: String, b: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
    putJsonObject(k, b)
