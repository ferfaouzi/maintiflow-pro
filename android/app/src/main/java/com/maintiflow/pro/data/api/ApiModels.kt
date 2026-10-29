package com.maintiflow.pro.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// Miroir des schémas du backend (backend/app/schemas.py). Les dates sont des chaînes ISO-8601 UTC.

@Serializable
data class LoginRequest(val username: String, val password: String, val device: String? = null)

@Serializable
data class UserDto(
    val id: Int,
    val username: String,
    @SerialName("full_name") val fullName: String,
    val role: String,
    @SerialName("site_id") val siteId: Int,
    val active: Boolean = true,
    @SerialName("must_change_password") val mustChangePassword: Boolean = false,
)

@Serializable
data class TokenResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("expires_at") val expiresAt: String,
    val user: UserDto,
)

@Serializable
data class ChangePasswordRequest(
    @SerialName("current_password") val currentPassword: String,
    @SerialName("new_password") val newPassword: String,
)

@Serializable
data class LineDto(val id: Int, val code: String, val name: String, val active: Boolean = true)

@Serializable
data class MachineDto(
    val id: Int,
    @SerialName("line_id") val lineId: Int,
    val code: String,
    val name: String,
    @SerialName("qr_token") val qrToken: String = "",
    val active: Boolean = true,
)

@Serializable
data class CategoryDto(
    val id: Int,
    val code: String,
    val name: String,
    @SerialName("default_priority") val defaultPriority: String = "NORMALE",
    val active: Boolean = true,
)

@Serializable
data class Referentials(
    val lines: List<LineDto>,
    val machines: List<MachineDto>,
    val categories: List<CategoryDto>,
    val technicians: List<UserDto>,
)

@Serializable
data class PersonRef(val id: Int, @SerialName("full_name") val fullName: String)

@Serializable
data class TicketSummary(
    val id: String,
    val number: String,
    val status: String,
    val priority: String,
    @SerialName("line_code") val lineCode: String,
    @SerialName("line_name") val lineName: String,
    @SerialName("machine_code") val machineCode: String,
    @SerialName("machine_name") val machineName: String,
    @SerialName("category_name") val categoryName: String,
    val description: String,
    @SerialName("reported_by") val reportedBy: PersonRef,
    @SerialName("assigned_to") val assignedTo: PersonRef? = null,
    @SerialName("declared_at") val declaredAt: String,
    @SerialName("production_stopped") val productionStopped: Boolean,
    val recurring: Boolean,
    val paused: Boolean,
    val late: Boolean,
    val version: Int,
)

@Serializable
data class TicketPage(val total: Int, val items: List<TicketSummary>)

@Serializable
data class PartDto(
    val id: Int,
    val reference: String,
    val designation: String,
    val quantity: String,
    val unit: String,
    @SerialName("added_at") val addedAt: String,
)

@Serializable
data class SignatureDto(
    val id: Int,
    val role: String,
    @SerialName("user_name") val userName: String,
    @SerialName("signed_at") val signedAt: String,
    val revision: Int,
    @SerialName("content_sha256") val contentSha256: String,
    val superseded: Boolean,
    @SerialName("has_image") val hasImage: Boolean,
)

@Serializable
data class AttachmentDto(
    val id: String,
    val kind: String,
    @SerialName("content_type") val contentType: String,
    @SerialName("size_bytes") val sizeBytes: Int,
    @SerialName("uploaded_at") val uploadedAt: String,
)

@Serializable
data class EventDto(
    val id: Int,
    val at: String,
    val type: String,
    @SerialName("from_status") val fromStatus: String? = null,
    @SerialName("to_status") val toStatus: String? = null,
    val comment: String? = null,
    val data: JsonObject? = null,
    @SerialName("user_name") val userName: String? = null,
)

@Serializable
data class Durations(
    @SerialName("waiting_takeover_s") val waitingTakeover: Long = 0,
    @SerialName("active_maintenance_s") val activeMaintenance: Long = 0,
    @SerialName("paused_s") val paused: Long = 0,
    @SerialName("waiting_parts_s") val waitingParts: Long = 0,
    @SerialName("waiting_validation_s") val waitingValidation: Long = 0,
    @SerialName("response_s") val response: Long? = null,
    @SerialName("arrival_s") val arrival: Long? = null,
    @SerialName("repair_window_s") val repairWindow: Long? = null,
    @SerialName("production_downtime_s") val productionDowntime: Long? = null,
    @SerialName("total_s") val total: Long? = null,
    val running: Boolean = true,
)

@Serializable
data class TicketDetail(
    val id: String,
    val number: String,
    val status: String,
    val priority: String,
    @SerialName("line_code") val lineCode: String,
    @SerialName("line_name") val lineName: String,
    @SerialName("machine_code") val machineCode: String,
    @SerialName("machine_name") val machineName: String,
    @SerialName("category_name") val categoryName: String,
    val description: String,
    @SerialName("reported_by") val reportedBy: PersonRef,
    @SerialName("assigned_to") val assignedTo: PersonRef? = null,
    @SerialName("declared_at") val declaredAt: String,
    @SerialName("production_stopped") val productionStopped: Boolean,
    val recurring: Boolean,
    val paused: Boolean,
    val late: Boolean,
    val version: Int,
    @SerialName("stop_start_at") val stopStartAt: String? = null,
    @SerialName("stop_end_at") val stopEndAt: String? = null,
    @SerialName("accepted_at") val acceptedAt: String? = null,
    @SerialName("arrived_at") val arrivedAt: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("repaired_at") val repairedAt: String? = null,
    @SerialName("closed_at") val closedAt: String? = null,
    @SerialName("cancel_reason") val cancelReason: String? = null,
    val diagnosis: String? = null,
    @SerialName("root_cause") val rootCause: String? = null,
    @SerialName("corrective_action") val correctiveAction: String? = null,
    val observations: String? = null,
    @SerialName("machine_final_state") val machineFinalState: String? = null,
    @SerialName("production_comment") val productionComment: String? = null,
    val revision: Int,
    val locked: Boolean,
    @SerialName("content_sha256") val contentSha256: String,
    val durations: Durations,
    val parts: List<PartDto>,
    val signatures: List<SignatureDto>,
    val attachments: List<AttachmentDto>,
    val events: List<EventDto>,
    @SerialName("allowed_actions") val allowedActions: List<String>,
    @SerialName("editable_fields") val editableFields: List<String>,
    // Version 2 (absents d'un serveur version 1).
    val title: String? = null,
    val service: String? = null,
    @SerialName("test_result") val testResult: String? = null,
)

@Serializable
data class CreateTicketRequest(
    @SerialName("client_uuid") val clientUuid: String,
    @SerialName("machine_id") val machineId: Int,
    @SerialName("category_id") val categoryId: Int,
    val description: String,
    val priority: String? = null,
    @SerialName("production_stopped") val productionStopped: Boolean,
    @SerialName("stop_start_at") val stopStartAt: String? = null,
    @SerialName("declared_at") val declaredAt: String? = null,
)

/** PATCH : seuls les champs non nuls sont envoyés (explicitNulls = false dans le Json). */
@Serializable
data class PatchTicketRequest(
    val version: Int,
    val diagnosis: String? = null,
    @SerialName("root_cause") val rootCause: String? = null,
    @SerialName("corrective_action") val correctiveAction: String? = null,
    val observations: String? = null,
    @SerialName("machine_final_state") val machineFinalState: String? = null,
    val priority: String? = null,
    val description: String? = null,
)

@Serializable
data class ActionRequest(
    val version: Int? = null,
    val comment: String? = null,
    @SerialName("technician_id") val technicianId: Int? = null,
    val priority: String? = null,
    @SerialName("signature_png_base64") val signaturePngBase64: String? = null,
    @SerialName("stop_end_at") val stopEndAt: String? = null,
    val diagnosis: String? = null,
    @SerialName("root_cause") val rootCause: String? = null,
    @SerialName("corrective_action") val correctiveAction: String? = null,
    @SerialName("machine_final_state") val machineFinalState: String? = null,
    // Version 2
    @SerialName("test_result") val testResult: String? = null,
    val service: String? = null,
)

@Serializable
data class PartRequest(val reference: String, val designation: String, val quantity: Double, val unit: String = "pce")

@Serializable
data class SimilarItem(
    val id: String,
    val number: String,
    val machine: String,
    val category: String,
    @SerialName("same_machine") val sameMachine: Boolean,
    val diagnosis: String? = null,
    @SerialName("root_cause") val rootCause: String? = null,
    @SerialName("corrective_action") val correctiveAction: String? = null,
)

@Serializable
data class SimilarResponse(val disclaimer: String, val items: List<SimilarItem>)

@Serializable
data class SettingsResponse(val values: Map<String, JsonElement>)

@Serializable
data class LineRequest(val code: String, val name: String, val active: Boolean = true)

@Serializable
data class MachineRequest(@SerialName("line_id") val lineId: Int, val code: String, val name: String, val active: Boolean = true)

@Serializable
data class CategoryRequest(
    val code: String,
    val name: String,
    @SerialName("default_priority") val defaultPriority: String = "NORMALE",
    val active: Boolean = true,
)

@Serializable
data class UserCreateRequest(
    val username: String,
    @SerialName("full_name") val fullName: String,
    val role: String,
    val password: String,
)

@Serializable
data class UserUpdateRequest(
    val active: Boolean? = null,
    @SerialName("reset_password") val resetPassword: String? = null,
)

@Serializable
data class ApiError(val detail: JsonElement? = null)
