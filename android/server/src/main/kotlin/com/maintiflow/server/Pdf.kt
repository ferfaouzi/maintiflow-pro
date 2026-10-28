package com.maintiflow.server

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Contenu du PDF « autocopiant », indépendant du moteur de dessin : le serveur prépare les textes,
 * l'application Android les dessine (android.graphics.pdf.PdfDocument). Même structure que backend/app/pdf.py.
 */
class PdfDoc(
    val number: String,
    val revision: Long,
    val statusLabel: String,
    val company: String,
    val footer: String,
    /** Filigrane (« PROVISOIRE – NON CLÔTURÉ », « ANNULÉ ») ou null pour un bon clôturé. */
    val watermark: String?,
    val blocks: List<PdfBlock>,
    val copies: List<String> = listOf("PRODUCTION", "MAINTENANCE"),
)

class PdfCell(val text: String, val bold: Boolean = false, val small: Boolean = false, val shaded: Boolean = false)

sealed interface PdfBlock {
    class Section(val title: String) : PdfBlock
    /** Tableau : largeurs relatives des colonnes, lignes de cellules. */
    class Grid(val widths: List<Float>, val rows: List<List<PdfCell>>) : PdfBlock
    class Signatures(val cells: List<SignatureCell>) : PdfBlock
    class Photos(val photos: List<Pair<ByteArray, String>>) : PdfBlock
    class Note(val text: String) : PdfBlock
}

class SignatureCell(val label: String, val lines: List<String>, val image: ByteArray?, val footer: String?)

/** Dessin du PDF, fourni par l'hôte (Android). */
fun interface PdfRenderer {
    fun render(doc: PdfDoc): ByteArray
}

object PdfBuilder {
    val STATUS_FR = mapOf(
        St.NOUVEAU to "Nouveau", St.EN_ATTENTE to "En attente", St.ACCEPTE to "Accepté", St.EN_COURS to "En cours",
        St.EN_ATTENTE_PIECE to "En attente de pièce", St.REPARE to "Réparé",
        St.VALIDATION_PRODUCTION to "Validation production", St.CLOTURE to "Clôturé", St.ANNULE to "Annulé",
    )

    val EVENT_FR = mapOf(
        "CREATION" to "Déclaration", "AFFECTATION" to "Affectation", "PRISE_EN_CHARGE" to "Prise en charge",
        "ARRIVEE" to "Arrivée sur machine", "DEMARRAGE" to "Début d'intervention", "PAUSE" to "Suspension",
        "RESUME" to "Reprise", "REPRISE" to "Reprise", "ATTENTE_PIECE" to "Attente de pièce",
        "REPARATION_TERMINEE" to "Réparation terminée", "SIGNATURE" to "Signature", "MODIFICATION" to "Modification",
        "VALIDATION_MAINTENANCE" to "Validation maintenance", "RENVOI_TECHNICIEN" to "Renvoi au technicien",
        "CONFIRMATION_PRODUCTION" to "Reprise production confirmée", "REFUS_PRODUCTION" to "Refus production",
        "ANNULATION" to "Annulation", "REOUVERTURE" to "Réouverture", "PANNE_RECURRENTE" to "Panne récurrente",
        "PIECE_AJOUTEE" to "Pièce ajoutée", "PIECE_RETIREE" to "Pièce retirée", "PHOTO_AJOUTEE" to "Photo ajoutée",
    )

    fun build(
        f: IvFull, sha: String, company: String, timezone: String, durations: Map<String, JsonElement>,
        storage: File, now: Long,
    ): PdfDoc {
        val iv = f.iv
        val tz = runCatching { ZoneId.of(timezone) }.getOrDefault(ZoneId.of("UTC"))
        val fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(tz)
        fun dt(ms: Long?) = ms?.let { fmt.format(Instant.ofEpochMilli(it)) } ?: "—"
        fun v(s: String?) = if (s.isNullOrEmpty()) "—" else s
        fun l(s: String) = PdfCell(s, bold = true, shaded = true)
        fun c(s: String?) = PdfCell(v(s))
        fun d(k: String) = Timing.format(Timing.long(durations, k))

        val blocks = mutableListOf<PdfBlock>()
        val c4 = listOf(30f, 63f, 30f, 63f)
        blocks += PdfBlock.Section("1. Déclaration (Production)")
        blocks += PdfBlock.Grid(c4, listOf(
            listOf(l("Déclaré le"), c(dt(iv.declaredAt)), l("Chef d'équipe"), c(f.reportedBy.fullName)),
            listOf(l("Ligne"), c("${f.line.code} – ${f.line.name}"), l("Machine"), c("${f.machine.code} – ${f.machine.name}")),
            listOf(l("Catégorie"), c(f.category.name), l("Priorité"), c(iv.priority)),
            listOf(l("Arrêt production"), c(if (iv.productionStopped) "Oui" else "Non"), l("Début / fin d'arrêt"),
                c(if (iv.productionStopped) "${dt(iv.stopStartAt)} → ${dt(iv.stopEndAt)}" else null)),
            listOf(l("Description"), c(iv.description), l("Panne récurrente"), c(if (iv.recurring) "Oui" else "Non")),
        ))
        blocks += PdfBlock.Section("2. Intervention (Maintenance)")
        blocks += PdfBlock.Grid(c4, listOf(
            listOf(l("Technicien"), c(f.assignedTo?.fullName), l("Prise en charge"), c(dt(iv.acceptedAt))),
            listOf(l("Arrivée"), c(dt(iv.arrivedAt)), l("Début intervention"), c(dt(iv.startedAt))),
            listOf(l("Fin (réparé)"), c(dt(iv.repairedAt)), l("État final machine"), c(iv.machineFinalState)),
        ))
        blocks += PdfBlock.Grid(listOf(30f, 156f), listOf(
            listOf(l("Diagnostic"), c(iv.diagnosis)),
            listOf(l("Cause identifiée"), c(iv.rootCause)),
            listOf(l("Actions correctives"), c(iv.correctiveAction)),
            listOf(l("Observations"), c(iv.observations)),
        ))
        blocks += PdfBlock.Section("3. Pièces de rechange utilisées")
        val parts = mutableListOf(listOf(l("Référence"), l("Désignation"), l("Quantité")))
        f.parts.forEach { parts += listOf(c(it.reference), c(it.designation), c("${it.quantity} ${it.unit}")) }
        if (f.parts.isEmpty()) parts += listOf(c("Aucune pièce"), c(""), c(""))
        blocks += PdfBlock.Grid(listOf(40f, 116f, 30f), parts)

        blocks += PdfBlock.Section("4. Temps (calculés à partir des horodatages)")
        val waitingParts = (Timing.long(durations, "waiting_parts_s") ?: 0) + (Timing.long(durations, "paused_s") ?: 0)
        blocks += PdfBlock.Grid(listOf(45f, 48f, 45f, 48f), listOf(
            listOf(l("Délai de prise en charge"), c(d("response_s")), l("Temps d'intervention effectif"), c(d("active_maintenance_s"))),
            listOf(l("Attente avant intervention"), c(d("waiting_takeover_s")), l("Attente pièces + suspensions"), c(Timing.format(waitingParts))),
            listOf(l("Arrêt de production"), c(d("production_downtime_s")), l("Durée totale du bon"), c(d("total_s"))),
        ))
        if (iv.status == St.ANNULE) {
            blocks += PdfBlock.Grid(listOf(40f, 146f), listOf(listOf(l("Motif d'annulation"), c(iv.cancelReason))))
        }
        iv.productionComment?.let {
            blocks += PdfBlock.Grid(listOf(40f, 146f), listOf(listOf(l("Commentaire production"), c(it))))
        }

        blocks += PdfBlock.Section("5. Validations et signatures")
        blocks += PdfBlock.Signatures(listOf(
            SigRole.MAINTENANCE to "Technicien maintenance",
            SigRole.RESPONSABLE to "Responsable maintenance",
            SigRole.PRODUCTION to "Chef d'équipe – reprise production",
        ).map { (role, label) ->
            val s = f.activeSignature(role)
            if (s != null) {
                SignatureCell(label, listOf(s.userName, dt(s.signedAt)), s.imagePng, "empreinte ${s.contentSha256.take(16)}…")
            } else {
                val na = role == SigRole.RESPONSABLE && iv.maintenanceValidatedAt != null
                SignatureCell(label, listOf(if (na) "Non requise" else "En attente"), null, null)
            }
        })

        val photos = f.attachments.take(4).mapNotNull { a ->
            runCatching { File(storage, a.storagePath).readBytes() to a.kind }.getOrNull()
        }
        if (photos.isNotEmpty()) {
            blocks += PdfBlock.Section("6. Photos (${f.attachments.size})")
            blocks += PdfBlock.Photos(photos)
        }

        blocks += PdfBlock.Section("7. Historique horodaté")
        val hist = mutableListOf(listOf(l("Date"), l("Événement"), l("Par"), l("Commentaire")))
        f.events.forEach { e ->
            var label = EVENT_FR[e.type] ?: e.type
            if (e.type == "SIGNATURE" && e.data != null) {
                label += " (${(e.data["role"] as? JsonPrimitive)?.contentOrNull.orEmpty().lowercase()})"
            } else if ((e.type == "PIECE_AJOUTEE" || e.type == "PIECE_RETIREE") && e.data != null) {
                label += " ${(e.data["reference"] as? JsonPrimitive)?.contentOrNull.orEmpty()}"
            }
            hist += listOf(
                PdfCell(dt(e.at), small = true), PdfCell(label, small = true),
                PdfCell(e.userName ?: "système", small = true), PdfCell(e.comment.orEmpty(), small = true),
            )
        }
        blocks += PdfBlock.Grid(listOf(28f, 52f, 36f, 70f), hist)
        blocks += PdfBlock.Note(
            "Les deux exemplaires de ce document sont identiques et portent la même empreinte SHA-256. " +
                "Toute modification après clôture nécessite une réouverture tracée et produit une nouvelle révision.",
        )

        val generated = fmt.format(Instant.ofEpochMilli(now))
        return PdfDoc(
            number = iv.number,
            revision = iv.revision,
            statusLabel = "Révision ${iv.revision} · ${STATUS_FR[iv.status] ?: iv.status}",
            company = company,
            footer = "${iv.number} · rév. ${iv.revision} · empreinte SHA-256 $sha · généré le $generated",
            watermark = when (iv.status) {
                St.CLOTURE -> null
                St.ANNULE -> "ANNULÉ"
                else -> "PROVISOIRE – NON CLÔTURÉ"
            },
            blocks = blocks,
        )
    }

    /** Version texte (tests et secours si le rendu graphique échoue). */
    fun toText(doc: PdfDoc): String = buildString {
        for (copy in doc.copies) {
            appendLine("MaintiFlow Pro · ${doc.company}")
            appendLine("BON D'INTERVENTION MAINTENANCE ${doc.number} · ${doc.statusLabel}")
            appendLine("EXEMPLAIRE $copy")
            doc.watermark?.let { appendLine("[$it]") }
            for (b in doc.blocks) when (b) {
                is PdfBlock.Section -> appendLine("== ${b.title}")
                is PdfBlock.Grid -> b.rows.forEach { r -> appendLine(r.joinToString(" | ") { it.text }) }
                is PdfBlock.Signatures -> b.cells.forEach { appendLine("${it.label} : ${it.lines.joinToString(" ")}") }
                is PdfBlock.Photos -> appendLine("${b.photos.size} photo(s)")
                is PdfBlock.Note -> appendLine(b.text)
            }
            appendLine(doc.footer)
        }
    }
}
