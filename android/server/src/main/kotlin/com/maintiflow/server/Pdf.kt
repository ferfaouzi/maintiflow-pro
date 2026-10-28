package com.maintiflow.server

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Contenu d'un PDF, indépendant du moteur de dessin : le serveur prépare les textes, l'hôte les dessine
 * (android.graphics.pdf sur le téléphone maître, PDFBox sur PC).
 *
 * Fiche de panne « autocopiante » : une page rose (déclaration, exemplaire Production) et une page jaune
 * (intervention et clôture, exemplaire Maintenance), sous le même numéro.
 */
class PdfDoc(
    val number: String,
    val revision: Long,
    val statusLabel: String,
    val company: String,
    val footer: String,
    /** Filigrane (« PROVISOIRE – NON CLÔTURÉE », « ANNULÉE ») ou null pour une panne clôturée. */
    val watermark: String?,
    val parts: List<PdfPart>,
    val title: String = "FICHE DE PANNE",
)

/** Une partie commence sur une nouvelle page : bandeau [label] de couleur [color], fond de page [paper]. */
class PdfPart(val label: String, val color: Int, val paper: Int?, val blocks: List<PdfBlock>)

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

/** Dessin du PDF, fourni par l'hôte. */
fun interface PdfRenderer {
    fun render(doc: PdfDoc): ByteArray
}

object PdfColors {
    const val PINK = 0xC2185B
    const val PINK_PAPER = 0xFDEEF4
    const val YELLOW = 0xB28704
    const val YELLOW_PAPER = 0xFFF9DB
    const val NAVY = 0x0B3D5C
}

object PdfBuilder {
    val STATUS_FR get() = St.LABELS

    val EVENT_FR = mapOf(
        "CREATION" to "Déclaration", "AFFECTATION" to "Affectation", "PRISE_EN_CHARGE" to "Prise en charge",
        "ARRIVEE" to "Arrivée sur machine", "DEMARRAGE" to "Début d'intervention", "PAUSE" to "Suspension",
        "RESUME" to "Reprise", "REPRISE" to "Reprise", "ATTENTE_PIECE" to "Demande de pièce",
        "PIECE_LIVREE" to "Pièce livrée", "PIECE_INDISPONIBLE" to "Pièce indisponible", "TRANSFERT" to "Transfert",
        "INTERVENTION_TERMINEE" to "Intervention terminée", "REPARATION_TERMINEE" to "Réparation terminée",
        "VALIDATION_QUALITE" to "Validation Qualité", "REFUS_QUALITE" to "Refus Qualité",
        "SIGNATURE" to "Signature", "MODIFICATION" to "Modification",
        "VALIDATION_MAINTENANCE" to "Validation maintenance", "RENVOI_TECHNICIEN" to "Renvoi au technicien",
        "CLOTURE" to "Clôture", "CONFIRMATION_PRODUCTION" to "Reprise production confirmée",
        "REFUS_CLOTURE" to "Clôture refusée", "REFUS_PRODUCTION" to "Refus production",
        "ANNULATION" to "Annulation", "REOUVERTURE" to "Réouverture", "PANNE_RECURRENTE" to "Panne récurrente",
        "PIECE_AJOUTEE" to "Pièce ajoutée", "PIECE_RETIREE" to "Pièce retirée", "PHOTO_AJOUTEE" to "Photo ajoutée",
    )

    fun formatter(timezone: String): DateTimeFormatter {
        val tz = runCatching { ZoneId.of(timezone) }.getOrDefault(ZoneId.of("UTC"))
        return DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(tz)
    }

    fun build(
        f: IvFull, sha: String, company: String, timezone: String, durations: Map<String, JsonElement>,
        storage: File, now: Long,
    ): PdfDoc {
        val iv = f.iv
        val fmt = formatter(timezone)
        fun dt(ms: Long?) = ms?.let { fmt.format(Instant.ofEpochMilli(it)) } ?: "—"
        fun v(s: String?) = if (s.isNullOrEmpty()) "—" else s
        fun l(s: String) = PdfCell(s, bold = true, shaded = true)
        fun c(s: String?) = PdfCell(v(s))
        fun d(k: String) = Timing.format(Timing.long(durations, k))
        fun who(u: User?) = u?.let { "${it.fullName} (${it.username})" }
        val c4 = listOf(30f, 63f, 30f, 63f)

        // ------------------------------------------------------------ page rose
        val pink = mutableListOf<PdfBlock>()
        pink += PdfBlock.Section("Déclaration de la panne")
        pink += PdfBlock.Grid(c4, listOf(
            listOf(l("N° de panne"), PdfCell(iv.number, bold = true), l("Date et heure"), c(dt(iv.declaredAt))),
            listOf(l("Déclarant"), c(who(f.reportedBy)), l("Rôle"), c(Role.LABELS[iv.reporterRole ?: f.reportedBy.role])),
            listOf(l("Société / site"), c(f.site.name), l("Ligne"), c(f.line.label)),
            listOf(l("Poste"), c(f.poste?.label), l("Machine"), c(f.machine.label)),
            listOf(l("Référence câble"), c(f.cableRef?.label), l("Priorité"), c(Priority.LABELS[iv.priority] ?: iv.priority)),
            listOf(l("Catégorie"), c(f.category.name), l("Nom de la panne"), c(iv.title ?: f.category.name)),
            listOf(l("Machine arrêtée"), c(if (iv.productionStopped) "Oui" else "Non"), l("Début / fin d'arrêt"),
                c(if (iv.productionStopped) "${dt(iv.stopStartAt)} → ${dt(iv.stopEndAt)}" else null)),
            listOf(l("Affectée à"), c(who(f.assignedTo) ?: iv.service?.let { "Service ${Service.LABELS[it]}" }),
                l("Panne récurrente"), c(if (iv.recurring) "Oui" else "Non")),
        ))
        pink += PdfBlock.Grid(listOf(30f, 156f), listOf(listOf(l("Description détaillée"), c(iv.description))))
        val problemPhotos = f.attachments.filter { it.kind == "PROBLEME" }.take(2).mapNotNull { a ->
            runCatching { File(storage, a.storagePath).readBytes() to a.kind }.getOrNull()
        }
        if (problemPhotos.isNotEmpty()) {
            pink += PdfBlock.Section("Photos de la déclaration")
            pink += PdfBlock.Photos(problemPhotos)
        }
        pink += PdfBlock.Section("Suivi")
        pink += PdfBlock.Grid(c4, listOf(
            listOf(l("Statut actuel"), c(St.LABELS[iv.status]), l("Prise en charge"), c(dt(iv.acceptedAt))),
            listOf(l("Intervention terminée"), c(dt(iv.repairedAt)), l("Clôture"), c(dt(iv.closedAt))),
        ))
        pink += PdfBlock.Note("La page jaune (intervention et clôture) porte le même numéro ${iv.number}.")

        // ------------------------------------------------------------ page jaune
        val yellow = mutableListOf<PdfBlock>()
        yellow += PdfBlock.Section("Intervention")
        yellow += PdfBlock.Grid(c4, listOf(
            listOf(l("N° de panne"), PdfCell(iv.number, bold = true), l("Service"), c(Service.LABELS[iv.service])),
            listOf(l("Intervenant"), c(who(f.assignedTo)), l("Prise en charge"), c(dt(iv.acceptedAt))),
            listOf(l("Arrivée"), c(dt(iv.arrivedAt)), l("Début intervention"), c(dt(iv.startedAt))),
            listOf(l("Fin d'intervention"), c(dt(iv.repairedAt)), l("Type d'intervention"), c(f.interventionType?.name)),
            listOf(l("Outil / posage"), c(f.tool?.label), l("État final machine"), c(MachineState.LABELS[iv.machineFinalState])),
        ))
        yellow += PdfBlock.Grid(listOf(30f, 156f), listOf(
            listOf(l("Diagnostic"), c(iv.diagnosis)),
            listOf(l("Cause"), c(listOfNotNull(f.cause?.name, iv.rootCause).joinToString(" – ").ifEmpty { null })),
            listOf(l("Actions réalisées"), c(iv.correctiveAction)),
            listOf(l("Résultat des tests"), c(iv.testResult)),
            listOf(l("Observations"), c(iv.observations)),
        ))
        yellow += PdfBlock.Section("Pièces de rechange")
        val parts = mutableListOf(listOf(l("Référence"), l("Désignation"), l("Quantité")))
        f.parts.forEach { parts += listOf(c(it.reference), c(it.designation), c("${it.quantity} ${it.unit}")) }
        f.partRequests.forEach {
            parts += listOf(c(it.reference), c("${it.designation ?: ""} (demande ${it.status.lowercase()})"), c(it.quantity))
        }
        if (parts.size == 1) parts += listOf(c("Aucune pièce"), c(""), c(""))
        yellow += PdfBlock.Grid(listOf(40f, 116f, 30f), parts)

        yellow += PdfBlock.Section("Qualité et clôture")
        yellow += PdfBlock.Grid(c4, listOf(
            listOf(l("Validation Qualité"), c(f.qualityBy?.let { "${who(it)} le ${dt(iv.qualityValidatedAt)}" }),
                l("Clôturée par"), c(who(f.closedBy))),
        ))
        yellow += PdfBlock.Grid(listOf(30f, 156f), listOfNotNull(
            listOf(l("Observations Qualité"), c(iv.qualityObservations)),
            listOf(l("Commentaire final"), c(iv.finalComment)),
            if (iv.status == St.ANNULE) listOf(l("Motif d'annulation"), c(iv.cancelReason)) else null,
        ))

        yellow += PdfBlock.Section("Temps (calculés à partir de l'historique)")
        val waitingParts = (Timing.long(durations, "waiting_parts_s") ?: 0) + (Timing.long(durations, "paused_s") ?: 0)
        yellow += PdfBlock.Grid(listOf(45f, 48f, 45f, 48f), listOf(
            listOf(l("Délai de prise en charge"), c(d("response_s")), l("Temps d'intervention effectif"), c(d("active_maintenance_s"))),
            listOf(l("Diagnostic"), c(d("diagnosis_s")), l("Attente pièces + suspensions"), c(Timing.format(waitingParts))),
            listOf(l("Arrêt machine"), c(d("production_downtime_s")), l("Durée totale"), c(d("total_s"))),
        ))

        yellow += PdfBlock.Section("Validations et signatures")
        yellow += PdfBlock.Signatures(listOf(
            SigRole.MAINTENANCE to "Intervenant",
            SigRole.QUALITE to "Qualité",
            SigRole.PRODUCTION to "Clôture (responsable)",
        ).map { (role, label) ->
            val s = f.activeSignature(role)
            if (s != null) {
                SignatureCell(label, listOf("${s.userName} (${s.userMatricule ?: ""})", dt(s.signedAt)), s.imagePng,
                    "empreinte ${s.contentSha256.take(16)}…")
            } else {
                val na = role == SigRole.QUALITE && iv.status in setOf(St.RESOLUE, St.CLOTURE) && iv.qualityValidatedAt == null
                SignatureCell(label, listOf(if (na) "Non requise" else "En attente"), null, null)
            }
        })

        val workPhotos = f.attachments.filter { it.kind != "PROBLEME" }.take(4).mapNotNull { a ->
            runCatching { File(storage, a.storagePath).readBytes() to a.kind }.getOrNull()
        }
        if (workPhotos.isNotEmpty()) {
            yellow += PdfBlock.Section("Photos de l'intervention")
            yellow += PdfBlock.Photos(workPhotos)
        }

        yellow += PdfBlock.Section("Historique horodaté")
        val hist = mutableListOf(listOf(l("Date"), l("Événement"), l("Par"), l("Commentaire")))
        f.events.forEach { e ->
            var label = EVENT_FR[e.type] ?: e.type
            if (e.type == "SIGNATURE" && e.data != null) {
                label += " (${(e.data["role"] as? JsonPrimitive)?.contentOrNull.orEmpty().lowercase()})"
            } else if ((e.type == "PIECE_AJOUTEE" || e.type == "PIECE_RETIREE") && e.data != null) {
                label += " ${(e.data["reference"] as? JsonPrimitive)?.contentOrNull.orEmpty()}"
            }
            St.normalize(e.toStatus)?.let { label += " → ${St.LABELS[it] ?: it}" }
            hist += listOf(
                PdfCell(dt(e.at), small = true), PdfCell(label, small = true),
                PdfCell(e.userName?.let { "$it (${e.userMatricule})" } ?: "système", small = true),
                PdfCell(e.comment.orEmpty(), small = true),
            )
        }
        yellow += PdfBlock.Grid(listOf(28f, 52f, 36f, 70f), hist)
        yellow += PdfBlock.Note(
            "Page rose et page jaune portent le même numéro et la même empreinte SHA-256. " +
                "Toute modification après clôture nécessite une réouverture tracée et produit une nouvelle révision.",
        )

        val generated = fmt.format(Instant.ofEpochMilli(now))
        return PdfDoc(
            number = iv.number,
            revision = iv.revision,
            statusLabel = "Révision ${iv.revision} · ${St.LABELS[iv.status] ?: iv.status}",
            company = company,
            footer = "${iv.number} · rév. ${iv.revision} · empreinte SHA-256 $sha · généré le $generated",
            watermark = when (iv.status) {
                St.CLOTURE -> null
                St.ANNULE -> "ANNULÉE"
                else -> "PROVISOIRE – NON CLÔTURÉE"
            },
            parts = listOf(
                PdfPart("PAGE ROSE – DÉCLARATION (exemplaire Production)", PdfColors.PINK, PdfColors.PINK_PAPER, pink),
                PdfPart("PAGE JAUNE – INTERVENTION ET CLÔTURE (exemplaire Maintenance)", PdfColors.YELLOW, PdfColors.YELLOW_PAPER, yellow),
            ),
        )
    }

    /** Version texte (tests et secours si le rendu graphique échoue). */
    fun toText(doc: PdfDoc): String = buildString {
        for (part in doc.parts) {
            appendLine("MaintiFlow Pro · ${doc.company}")
            appendLine("${doc.title} ${doc.number} · ${doc.statusLabel}")
            appendLine(part.label)
            doc.watermark?.let { appendLine("[$it]") }
            for (b in part.blocks) when (b) {
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
