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

/**
 * Rôles. L'Admin configure (utilisateurs, usine, paramètres) mais n'agit pas sur les pannes.
 * RESPONSABLE (Responsable maintenance) est le rôle de la version 1, conservé tel quel.
 */
object Role {
    const val OPERATEUR = "OPERATEUR"
    const val CHEF_LIGNE = "CHEF_LIGNE"
    const val CHEF_EQUIPE = "CHEF_EQUIPE"
    const val MAINTENANCIER = "MAINTENANCIER"
    const val METHODE = "METHODE"
    const val QUALITE = "QUALITE"
    const val LOGISTIQUE = "LOGISTIQUE"
    const val RESPONSABLE = "RESPONSABLE"
    const val ADMIN = "ADMIN"

    /** Code de la version 1, converti en MAINTENANCIER par la migration du schéma. */
    const val TECHNICIEN_V1 = "TECHNICIEN"

    val ALL = listOf(OPERATEUR, CHEF_LIGNE, CHEF_EQUIPE, MAINTENANCIER, METHODE, QUALITE, LOGISTIQUE, RESPONSABLE, ADMIN)

    val LABELS = mapOf(
        OPERATEUR to "Opérateur", CHEF_LIGNE to "Chef de ligne", CHEF_EQUIPE to "Chef d'équipe",
        MAINTENANCIER to "Maintenancier", METHODE to "Méthode", QUALITE to "Qualité", LOGISTIQUE to "Logistique",
        RESPONSABLE to "Responsable maintenance", ADMIN to "Administrateur",
    )

    val CHEFS = setOf(CHEF_LIGNE, CHEF_EQUIPE)
    /** Ceux qui prennent en charge et remplissent la page jaune. */
    val INTERVENANTS = setOf(MAINTENANCIER, METHODE)
    /** Ceux qui peuvent remplir une page rose. */
    val DECLARANTS = setOf(OPERATEUR, CHEF_LIGNE, CHEF_EQUIPE, MAINTENANCIER, METHODE, QUALITE, RESPONSABLE)
    /** Rôles limités aux lignes que l'Admin leur a attribuées (toutes si aucune). */
    val LINE_SCOPED = setOf(OPERATEUR, CHEF_LIGNE)
    /** Rôles qui voient le tableau de bord. */
    val DASHBOARD = ALL.toSet() - OPERATEUR

    fun service(role: String): String = if (role == METHODE) Service.METHODE else Service.MAINTENANCE
}

object Service {
    const val MAINTENANCE = "MAINTENANCE"
    const val METHODE = "METHODE"
    val ALL = listOf(MAINTENANCE, METHODE)
    val LABELS = mapOf(MAINTENANCE to "Maintenance", METHODE to "Méthode")
    fun roleOf(service: String) = if (service == METHODE) Role.METHODE else Role.MAINTENANCIER
}

/** Les 10 statuts du cahier des charges, plus ANNULE (doublon ou erreur, motif obligatoire). */
object St {
    const val NOUVEAU = "NOUVEAU"
    const val EN_ATTENTE = "EN_ATTENTE"
    const val EN_DIAGNOSTIC = "EN_DIAGNOSTIC"
    const val INTERVENTION_MAINTENANCE = "INTERVENTION_MAINTENANCE"
    const val INTERVENTION_METHODE = "INTERVENTION_METHODE"
    const val EN_ATTENTE_PIECE = "EN_ATTENTE_PIECE"
    const val EN_ATTENTE_QUALITE = "EN_ATTENTE_QUALITE"
    const val RESOLUE = "RESOLUE"
    const val CLOTURE = "CLOTURE"
    const val REOUVERTE = "REOUVERTE"
    const val ANNULE = "ANNULE"

    val ALL = listOf(NOUVEAU, EN_ATTENTE, EN_DIAGNOSTIC, INTERVENTION_MAINTENANCE, INTERVENTION_METHODE,
        EN_ATTENTE_PIECE, EN_ATTENTE_QUALITE, RESOLUE, CLOTURE, REOUVERTE, ANNULE)
    val OPEN = ALL.toSet() - CLOTURE - ANNULE
    val WORK = setOf(INTERVENTION_MAINTENANCE, INTERVENTION_METHODE)
    /** En attente d'une prise en charge (délai de réaction). */
    val WAITING_TAKEOVER = setOf(NOUVEAU, EN_ATTENTE, REOUVERTE)

    val LABELS = mapOf(
        NOUVEAU to "Nouvelle panne", EN_ATTENTE to "En attente de prise en charge",
        EN_DIAGNOSTIC to "En cours de diagnostic", INTERVENTION_MAINTENANCE to "Intervention Maintenance en cours",
        INTERVENTION_METHODE to "Intervention Méthode en cours", EN_ATTENTE_PIECE to "En attente de pièces",
        EN_ATTENTE_QUALITE to "En attente de validation Qualité", RESOLUE to "Résolue, en attente de clôture",
        CLOTURE to "Clôturée", REOUVERTE to "Réouverte après récidive", ANNULE to "Annulée",
    )

    /** Statuts de la version 1 encore présents dans l'historique (non modifiable) des anciens bons. */
    val LEGACY = mapOf(
        "ACCEPTE" to EN_DIAGNOSTIC, "EN_COURS" to INTERVENTION_MAINTENANCE,
        "REPARE" to RESOLUE, "VALIDATION_PRODUCTION" to RESOLUE,
    )

    fun normalize(s: String?): String? = s?.let { LEGACY[it] ?: it }
    fun work(service: String?) = if (service == Service.METHODE) INTERVENTION_METHODE else INTERVENTION_MAINTENANCE
}

/** Codes inchangés depuis la version 1 ; libellés du cahier des charges. */
object Priority {
    val ALL = listOf("BASSE", "NORMALE", "HAUTE", "CRITIQUE")
    val ORDER = mapOf("CRITIQUE" to 0, "HAUTE" to 1, "NORMALE" to 2, "BASSE" to 3)
    val LABELS = mapOf("BASSE" to "Faible", "NORMALE" to "Moyen", "HAUTE" to "Élevé", "CRITIQUE" to "Critique")
}

object MachineState {
    val ALL = listOf("OPERATIONNELLE", "DEGRADEE", "ARRETEE")
    val LABELS = mapOf("OPERATIONNELLE" to "Opérationnelle", "DEGRADEE" to "Dégradée", "ARRETEE" to "Arrêtée")
}

object SigRole {
    const val MAINTENANCE = "MAINTENANCE"
    const val RESPONSABLE = "RESPONSABLE"
    const val QUALITE = "QUALITE"
    const val PRODUCTION = "PRODUCTION"
}

/** Paramètres par défaut d'un site, modifiables par l'Admin. */
val DEFAULT_SETTINGS: Map<String, JsonElement> = mapOf(
    "require_manager_validation" to JsonPrimitive(false),
    "recurrence_window_days" to JsonPrimitive(30),
    "recurrence_threshold" to JsonPrimitive(2),
    "late_acceptance_minutes" to buildJsonObject {
        put("CRITIQUE", 5); put("HAUTE", 15); put("NORMALE", 60); put("BASSE", 240)
    },
    "company_display_name" to JsonPrimitive("Usine de démonstration"),
    // Déconnexion automatique après ce nombre de minutes sans action (0 = jamais).
    "session_idle_minutes" to JsonPrimitive(30),
)

fun iso(ms: Long?): String? = ms?.let { Instant.ofEpochMilli(it).toString() }

data class User(
    val id: Long,
    val siteId: Long,
    /** Matricule : identifiant de connexion unique. */
    val username: String,
    val firstName: String,
    val lastName: String,
    val fullName: String,
    val role: String,
    val passwordHash: String,
    val active: Boolean,
    val mustChangePassword: Boolean,
    val failedLogins: Long,
    val lockedUntil: Long?,
) {
    fun json(lineIds: List<Long>? = null): JsonObject = buildJsonObject {
        put("id", id); put("username", username); put("matricule", username)
        put("first_name", firstName); put("last_name", lastName); put("full_name", fullName)
        put("role", role); put("role_label", Role.LABELS[role] ?: role)
        put("site_id", siteId); put("active", active); put("must_change_password", mustChangePassword)
        if (lineIds != null) put("line_ids", kotlinx.serialization.json.JsonArray(lineIds.map { JsonPrimitive(it) }))
    }

    companion object {
        fun of(r: Row) = User(
            r.long("id"), r.long("site_id"), r.str("username"), r.strOrNull("first_name").orEmpty(),
            r.strOrNull("last_name").orEmpty(), r.str("full_name"), r.str("role"),
            r.str("password_hash"), r.bool("active"), r.bool("must_change_password"), r.long("failed_logins"),
            r.longOrNull("locked_until"),
        )

        fun displayName(first: String, last: String) = listOf(first.trim(), last.trim()).filter { it.isNotEmpty() }.joinToString(" ")
    }
}

/**
 * Une panne : page rose (déclaration) et page jaune (intervention, clôture) sous le même numéro.
 * Chargée en mémoire puis sauvegardée en une requête avec contrôle de version.
 */
class Iv(r: Row) {
    val id: String = r.str("id")
    val number: String = r.str("number")
    val clientUuid: String? = r.strOrNull("client_uuid")
    val siteId: Long = r.long("site_id")
    val lineId: Long = r.long("line_id")
    val machineId: Long = r.long("machine_id")
    var posteId: Long? = r.longOrNull("poste_id")
    var cableRefId: Long? = r.longOrNull("cable_ref_id")
    var title: String? = r.strOrNull("title")
    var categoryId: Long = r.long("category_id")
    var priority: String = r.str("priority")
    var status: String = r.str("status")
    var description: String = r.str("description")
    val reportedById: Long = r.long("reported_by_id")
    val reporterRole: String? = r.strOrNull("reporter_role")
    var assignedToId: Long? = r.longOrNull("assigned_to_id")
    /** Service qui a la panne en charge (ou à qui elle est adressée) : MAINTENANCE ou METHODE. */
    var service: String? = r.strOrNull("service")
    var productionStopped: Boolean = r.bool("production_stopped")
    var stopStartAt: Long? = r.longOrNull("stop_start_at")
    var stopEndAt: Long? = r.longOrNull("stop_end_at")
    val declaredAt: Long = r.long("declared_at")
    var acceptedAt: Long? = r.longOrNull("accepted_at")
    var arrivedAt: Long? = r.longOrNull("arrived_at")
    var startedAt: Long? = r.longOrNull("started_at")
    var repairedAt: Long? = r.longOrNull("repaired_at")
    var maintenanceValidatedAt: Long? = r.longOrNull("maintenance_validated_at")
    var qualityValidatedAt: Long? = r.longOrNull("quality_validated_at")
    var qualityById: Long? = r.longOrNull("quality_by_id")
    var productionConfirmedAt: Long? = r.longOrNull("production_confirmed_at")
    var closedAt: Long? = r.longOrNull("closed_at")
    var closedById: Long? = r.longOrNull("closed_by_id")
    var cancelledAt: Long? = r.longOrNull("cancelled_at")
    var cancelReason: String? = r.strOrNull("cancel_reason")
    var paused: Boolean = r.bool("paused")
    var interventionTypeId: Long? = r.longOrNull("intervention_type_id")
    var diagnosis: String? = r.strOrNull("diagnosis")
    var causeId: Long? = r.longOrNull("cause_id")
    var rootCause: String? = r.strOrNull("root_cause")
    var toolId: Long? = r.longOrNull("tool_id")
    var correctiveAction: String? = r.strOrNull("corrective_action")
    var testResult: String? = r.strOrNull("test_result")
    var observations: String? = r.strOrNull("observations")
    var qualityObservations: String? = r.strOrNull("quality_observations")
    var machineFinalState: String? = r.strOrNull("machine_final_state")
    var productionComment: String? = r.strOrNull("production_comment")
    var finalComment: String? = r.strOrNull("final_comment")
    var recurring: Boolean = r.bool("recurring")
    var reopenCount: Long = r.long("reopen_count")
    var revision: Long = r.long("revision")
    var lateNotifiedAt: Long? = r.longOrNull("late_notified_at")
    val version: Long = r.long("version")
    var archivedPdfSha256: String? = r.strOrNull("archived_pdf_sha256")
    var archivedPdfPath: String? = r.strOrNull("archived_pdf_path")

    val locked: Boolean get() = status == St.CLOTURE || status == St.ANNULE

    /** Valeurs des champs modifiables par PATCH, pour l'historique (ancienne / nouvelle valeur). */
    fun field(name: String): Any? = when (name) {
        "title" -> title
        "description" -> description
        "category_id" -> categoryId
        "poste_id" -> posteId
        "cable_ref_id" -> cableRefId
        "priority" -> priority
        "production_stopped" -> productionStopped
        "stop_start_at" -> stopStartAt
        "intervention_type_id" -> interventionTypeId
        "diagnosis" -> diagnosis
        "cause_id" -> causeId
        "root_cause" -> rootCause
        "tool_id" -> toolId
        "corrective_action" -> correctiveAction
        "test_result" -> testResult
        "observations" -> observations
        "quality_observations" -> qualityObservations
        "machine_final_state" -> machineFinalState
        else -> error(name)
    }

    fun setField(name: String, v: Any?) {
        when (name) {
            "title" -> title = v as String?
            "description" -> description = v as String
            "category_id" -> categoryId = v as Long
            "poste_id" -> posteId = v as Long?
            "cable_ref_id" -> cableRefId = v as Long?
            "priority" -> priority = v as String
            "production_stopped" -> productionStopped = v as Boolean
            "stop_start_at" -> stopStartAt = v as Long?
            "intervention_type_id" -> interventionTypeId = v as Long?
            "diagnosis" -> diagnosis = v as String?
            "cause_id" -> causeId = v as Long?
            "root_cause" -> rootCause = v as String?
            "tool_id" -> toolId = v as Long?
            "corrective_action" -> correctiveAction = v as String?
            "test_result" -> testResult = v as String?
            "observations" -> observations = v as String?
            "quality_observations" -> qualityObservations = v as String?
            "machine_final_state" -> machineFinalState = v as String?
            else -> error(name)
        }
    }

    fun save(sql: Sql, now: Long) {
        sql.exec(
            """UPDATE interventions SET poste_id=?, cable_ref_id=?, title=?, category_id=?, priority=?, status=?,
               description=?, assigned_to_id=?, service=?, production_stopped=?, stop_start_at=?, stop_end_at=?,
               accepted_at=?, arrived_at=?, started_at=?, repaired_at=?, maintenance_validated_at=?,
               quality_validated_at=?, quality_by_id=?, production_confirmed_at=?, closed_at=?, closed_by_id=?,
               cancelled_at=?, cancel_reason=?, paused=?, intervention_type_id=?, diagnosis=?, cause_id=?,
               root_cause=?, tool_id=?, corrective_action=?, test_result=?, observations=?, quality_observations=?,
               machine_final_state=?, production_comment=?, final_comment=?, recurring=?, reopen_count=?,
               revision=?, late_notified_at=?, archived_pdf_sha256=?, archived_pdf_path=?, updated_at=?,
               version=version+1
               WHERE id=? AND version=?""",
            posteId, cableRefId, title, categoryId, priority, status, description, assignedToId, service,
            productionStopped, stopStartAt, stopEndAt, acceptedAt, arrivedAt, startedAt, repairedAt,
            maintenanceValidatedAt, qualityValidatedAt, qualityById, productionConfirmedAt, closedAt, closedById,
            cancelledAt, cancelReason, paused, interventionTypeId, diagnosis, causeId, rootCause, toolId,
            correctiveAction, testResult, observations, qualityObservations, machineFinalState, productionComment,
            finalComment, recurring, reopenCount, revision, lateNotifiedAt, archivedPdfSha256, archivedPdfPath,
            now, id, version,
        )
        if (sql.changes() == 0L) {
            throw ApiException(409, "La panne a été modifiée simultanément par un autre utilisateur. Rechargez-la.")
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
    val userMatricule = r.strOrNull("user_matricule")
    val userRole = r.strOrNull("user_role")
}

class Part(r: Row) {
    val id = r.long("id")
    val reference = r.str("reference")
    val designation = r.str("designation")
    val quantity = r.str("quantity")
    val unit = r.str("unit")
    val addedAt = r.long("added_at")
}

class PartRequest(r: Row) {
    val id = r.long("id")
    val reference = r.str("reference")
    val designation = r.strOrNull("designation")
    val quantity = r.str("quantity")
    val status = r.str("status")
    val requestedAt = r.long("requested_at")
    val requestedBy = r.strOrNull("requested_by_name")
    val handledAt = r.longOrNull("handled_at")
    val handledBy = r.strOrNull("handled_by_name")
    val comment = r.strOrNull("comment")
}

class Signature(r: Row) {
    val id = r.long("id")
    val role = r.str("role")
    val userName = r.str("user_name")
    val userMatricule = r.strOrNull("user_matricule")
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

class Ref(val id: Long, val code: String, val name: String) {
    val label get() = if (code.isEmpty() || code == name) name else "$code – $name"
}

/** Une panne avec tout ce qui l'accompagne (relations chargées depuis la base). */
class IvFull(val iv: Iv, sql: Sql) {
    private fun ref(table: String, id: Long?): Ref? = id?.let {
        sql.one("SELECT id, code, name FROM $table WHERE id=?", it)?.let { r -> Ref(r.long("id"), r.str("code"), r.str("name")) }
    }
    private fun user(id: Long?): User? = id?.let { sql.one("SELECT * FROM users WHERE id=?", it)?.let { r -> User.of(r) } }

    val site: Ref = ref("sites", iv.siteId)!!
    val line: Ref = ref("lines", iv.lineId)!!
    val machine: Ref = ref("machines", iv.machineId)!!
    val poste: Ref? = ref("postes", iv.posteId)
    val cableRef: Ref? = ref("cable_refs", iv.cableRefId)
    val category: Ref = ref("failure_categories", iv.categoryId)!!
    val interventionType: Ref? = ref("intervention_types", iv.interventionTypeId)
    val tool: Ref? = ref("tools", iv.toolId)
    val cause: Ref? = iv.causeId?.let { id ->
        sql.one("SELECT id, name FROM failure_causes WHERE id=?", id)?.let { Ref(it.long("id"), "", it.str("name")) }
    }
    val reportedBy: User = user(iv.reportedById)!!
    val assignedTo: User? = user(iv.assignedToId)
    val qualityBy: User? = user(iv.qualityById)
    val closedBy: User? = user(iv.closedById)
    val events: List<Event> = sql.query(
        "SELECT e.*, u.full_name AS user_name, u.username AS user_matricule, u.role AS user_role " +
            "FROM intervention_events e LEFT JOIN users u ON u.id = e.user_id WHERE e.intervention_id=? ORDER BY e.id", iv.id,
    ).map { Event(it) }
    val parts: List<Part> = sql.query("SELECT * FROM parts_used WHERE intervention_id=? ORDER BY id", iv.id).map { Part(it) }
    val partRequests: List<PartRequest> = sql.query(
        "SELECT p.*, a.full_name AS requested_by_name, b.full_name AS handled_by_name FROM part_requests p " +
            "JOIN users a ON a.id = p.requested_by_id LEFT JOIN users b ON b.id = p.handled_by_id " +
            "WHERE p.intervention_id=? ORDER BY p.id", iv.id,
    ).map { PartRequest(it) }
    val signatures: List<Signature> = sql.query(
        "SELECT s.*, u.full_name AS user_name, u.username AS user_matricule FROM signatures s JOIN users u ON u.id = s.user_id " +
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
