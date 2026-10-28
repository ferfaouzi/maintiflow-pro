package com.maintiflow.server

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Tableau de bord : compteurs en direct (pannes ouvertes, machines arrêtées…) et indicateurs sur une
 * période (MTTR, MTBF, taux de récidive, répartitions, historique), avec exports Excel et PDF.
 *
 * Définitions (rappelées dans la documentation) :
 * - MTTR = moyenne de (fin d'intervention − déclaration) des pannes déclarées sur la période et réparées ;
 * - MTBF = (nombre de machines × durée de la période − temps d'arrêt machine) ÷ nombre de pannes ;
 * - taux de récidive = pannes marquées récurrentes ÷ pannes de la période (hors annulées).
 */
class Dashboard(private val sql: Sql, private val wf: Workflow, private val settings: SettingsStore) {

    class Filter(
        val from: Long, val to: Long, val lineId: Long?, val machineId: Long?, val posteId: Long?,
        val categoryId: Long?, val granularity: String,
    )

    fun filter(req: Request, now: Long): Filter {
        fun id(k: String) = req.q(k)?.let { it.toLongOrNull() ?: throw ApiException(422, "Paramètre $k invalide") }
        fun date(k: String) = req.q(k)?.let {
            (Body.parseInstant(it) ?: runCatching { LocalDate.parse(it).atStartOfDay(ZoneId.of("UTC")).toInstant() }.getOrNull()
                ?: throw ApiException(422, "$k invalide")).toEpochMilli()
        }
        val to = date("date_to") ?: now
        val from = date("date_from") ?: (to - 30 * 86_400_000L)
        if (from >= to) throw ApiException(422, "La date de début doit précéder la date de fin")
        val days = (to - from) / 86_400_000L
        val g = req.q("granularity") ?: when {
            days <= 31 -> "day"
            days <= 180 -> "week"
            else -> "month"
        }
        if (g !in setOf("day", "week", "month")) throw ApiException(422, "granularity : day, week ou month")
        return Filter(from, to, id("line_id"), id("machine_id"), id("poste_id"), id("category_id"), g)
    }

    private val SELECT = """SELECT i.*, l.code AS l_code, l.name AS l_name, m.code AS m_code, m.name AS m_name,
        c.name AS c_name, p.code AS p_code, p.name AS p_name, r.full_name AS r_name, r.username AS r_mat,
        a.full_name AS a_name, a.username AS a_mat
        FROM interventions i JOIN lines l ON l.id = i.line_id JOIN machines m ON m.id = i.machine_id
        JOIN failure_categories c ON c.id = i.category_id LEFT JOIN postes p ON p.id = i.poste_id
        JOIN users r ON r.id = i.reported_by_id LEFT JOIN users a ON a.id = i.assigned_to_id"""

    private fun scope(user: User, f: Filter): Pair<MutableList<String>, MutableList<Any?>> {
        if (user.role !in Role.DASHBOARD) throw ApiException(403, "Tableau de bord non disponible pour votre rôle")
        val (vis, args) = wf.visibilitySql(user)
        val cond = mutableListOf(vis)
        val a = args.toMutableList()
        f.lineId?.let { cond += "i.line_id = ?"; a += it }
        f.machineId?.let { cond += "i.machine_id = ?"; a += it }
        f.posteId?.let { cond += "i.poste_id = ?"; a += it }
        f.categoryId?.let { cond += "i.category_id = ?"; a += it }
        return cond to a
    }

    /** Pannes déclarées sur la période (filtres appliqués). */
    fun periodRows(user: User, f: Filter): List<Row> {
        val (cond, args) = scope(user, f)
        cond += "i.declared_at >= ?"; args += f.from
        cond += "i.declared_at < ?"; args += f.to
        return sql.query("$SELECT WHERE ${cond.joinToString(" AND ")} ORDER BY i.declared_at DESC", *args.toTypedArray())
    }

    private fun openRows(user: User, f: Filter): List<Row> {
        val (cond, args) = scope(user, f)
        cond += "i.status NOT IN ('CLOTURE','ANNULE')"
        return sql.query("$SELECT WHERE ${cond.joinToString(" AND ")}", *args.toTypedArray())
    }

    private fun downtimeMs(iv: Iv, now: Long, f: Filter): Long {
        if (!iv.productionStopped || iv.stopStartAt == null) return 0
        val start = maxOf(iv.stopStartAt!!, f.from)
        val end = minOf(iv.stopEndAt ?: if (iv.locked) iv.closedAt ?: now else now, f.to)
        return maxOf(0L, end - start)
    }

    private fun zone(user: User): ZoneId {
        val tz = sql.one("SELECT timezone FROM sites WHERE id=?", user.siteId)?.str("timezone") ?: "UTC"
        return runCatching { ZoneId.of(tz) }.getOrDefault(ZoneId.of("UTC"))
    }

    private fun bucket(ms: Long, g: String, zone: ZoneId): String {
        val d = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        return when (g) {
            "week" -> d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString()
            "month" -> d.withDayOfMonth(1).toString().substring(0, 7)
            else -> d.toString()
        }
    }

    class Stats(val json: JsonObject, val rows: List<Row>)

    fun compute(user: User, f: Filter, now: Long): Stats {
        val rows = periodRows(user, f)
        val valid = rows.filter { it.str("status") != St.ANNULE }
        val ivs = valid.map { Iv(it) }
        val open = openRows(user, f).map { Iv(it) }
        val limits = settings.get(user.siteId, "late_acceptance_minutes") as? JsonObject

        // MTTR : déclaration -> fin d'intervention.
        val repaired = ivs.filter { it.repairedAt != null }
        val mttr = if (repaired.isEmpty()) null else repaired.map { (it.repairedAt!! - it.declaredAt) / 1000 }.average().toLong()
        val responses = ivs.filter { it.acceptedAt != null }
        val response = if (responses.isEmpty()) null else responses.map { (it.acceptedAt!! - it.declaredAt) / 1000 }.average().toLong()
        val downtime = ivs.sumOf { downtimeMs(it, now, f) }

        // MTBF : temps de fonctionnement des machines concernées ÷ nombre de pannes.
        val (mc, ma) = scopeMachines(user, f)
        val machines = sql.one("SELECT COUNT(*) AS n FROM machines m JOIN lines l ON l.id = m.line_id WHERE $mc", *ma.toTypedArray())!!.long("n")
        val mtbf = if (ivs.isEmpty() || machines == 0L) null
            else ((machines * (f.to - f.from) - downtime) / ivs.size / 1000).coerceAtLeast(0)

        fun groupBy(key: (Row) -> Long?, code: (Row) -> String?, name: (Row) -> String?) = buildJsonArray {
            valid.filter { key(it) != null }.groupBy { key(it)!! }.entries
                .sortedByDescending { it.value.size }
                .forEach { (id, list) ->
                    add(buildJsonObject {
                        put("id", id); put("code", code(list.first())); put("name", name(list.first()))
                        put("count", list.size)
                        put("downtime_s", list.sumOf { downtimeMs(Iv(it), now, f) } / 1000)
                        put("recurring", list.count { it.bool("recurring") })
                    })
                }
        }

        val zone = zone(user)
        val timeline = sortedMapOf<String, IntArray>()
        var cursor = f.from
        while (cursor < f.to && timeline.size < 400) {
            timeline.getOrPut(bucket(cursor, f.granularity, zone)) { IntArray(2) }
            cursor += 86_400_000L
        }
        valid.forEach { timeline.getOrPut(bucket(it.long("declared_at"), f.granularity, zone)) { IntArray(2) }[0]++ }
        val (cc, ca) = scope(user, f)
        cc += "i.closed_at >= ?"; ca += f.from
        cc += "i.closed_at < ?"; ca += f.to
        val closedInPeriod = sql.query("SELECT i.closed_at FROM interventions i WHERE ${cc.joinToString(" AND ")}", *ca.toTypedArray())
        closedInPeriod.forEach { timeline.getOrPut(bucket(it.long("closed_at"), f.granularity, zone)) { IntArray(2) }[1]++ }

        val json = buildJsonObject {
            put("period", buildJsonObject {
                put("from", iso(f.from)); put("to", iso(f.to)); put("granularity", f.granularity)
            })
            put("live", buildJsonObject {
                put("open", open.size)
                put("critical_open", open.count { it.priority == "CRITIQUE" })
                put("machines_stopped", open.filter { it.productionStopped && it.stopEndAt == null }.map { it.machineId }.distinct().size)
                put("waiting_takeover", open.count { it.status in St.WAITING_TAKEOVER })
                put("in_progress", open.count { it.status == St.EN_DIAGNOSTIC || it.status in St.WORK })
                put("waiting_parts", open.count { it.status == St.EN_ATTENTE_PIECE })
                put("waiting_quality", open.count { it.status == St.EN_ATTENTE_QUALITE })
                put("to_close", open.count { it.status == St.RESOLUE })
                put("late", open.count { wf.isLate(it, now, limits) })
                put("by_status", buildJsonObject { St.ALL.forEach { s -> put(s, open.count { it.status == s }) } })
            })
            put("totals", buildJsonObject {
                put("declared", valid.size)
                put("closed", closedInPeriod.size)
                put("cancelled", rows.size - valid.size)
                put("critical", ivs.count { it.priority == "CRITIQUE" })
                put("recurring", ivs.count { it.recurring })
                put("still_open", ivs.count { !it.locked })
            })
            put("mttr_s", mttr?.let { JsonPrimitive(it) } ?: JsonNull)
            put("mtbf_s", mtbf?.let { JsonPrimitive(it) } ?: JsonNull)
            put("response_avg_s", response?.let { JsonPrimitive(it) } ?: JsonNull)
            put("recurrence_rate", if (ivs.isEmpty()) JsonNull else JsonPrimitive(Math.round(ivs.count { it.recurring } * 1000.0 / ivs.size) / 10.0))
            put("downtime_s", downtime / 1000)
            put("machines_count", machines)
            put("by_line", groupBy({ it.long("line_id") }, { it.str("l_code") }, { it.str("l_name") }))
            put("by_machine", groupBy({ it.long("machine_id") }, { it.str("m_code") }, { it.str("m_name") }))
            put("by_poste", groupBy({ it.longOrNull("poste_id") }, { it.strOrNull("p_code") }, { it.strOrNull("p_name") }))
            put("by_category", groupBy({ it.long("category_id") }, { null }, { it.str("c_name") }))
            put("by_priority", buildJsonObject { Priority.ALL.forEach { p -> put(p, ivs.count { it.priority == p }) } })
            put("timeline", JsonArray(timeline.map { (k, v) ->
                buildJsonObject { put("period", k); put("declared", v[0]); put("closed", v[1]) }
            }))
        }
        return Stats(json, rows)
    }

    private fun scopeMachines(user: User, f: Filter): Pair<String, List<Any?>> {
        val cond = mutableListOf("l.site_id = ?", "m.active = 1")
        val args = mutableListOf<Any?>(user.siteId)
        if (user.role in Role.LINE_SCOPED) {
            val lines = wf.userLines(user.id)
            if (lines.isNotEmpty()) { cond += "m.line_id IN (${lines.joinToString(",") { "?" }})"; args.addAll(lines) }
        }
        f.lineId?.let { cond += "m.line_id = ?"; args += it }
        f.machineId?.let { cond += "m.id = ?"; args += it }
        f.posteId?.let { cond += "m.poste_id = ?"; args += it }
        return cond.joinToString(" AND ") to args
    }

    // ------------------------------------------------------------------ exports
    fun listSheetRows(rows: List<Row>, zone: ZoneId): List<List<Any?>> {
        val fmt = PdfBuilder.formatter(zone.id)
        fun dt(ms: Long?) = ms?.let { fmt.format(Instant.ofEpochMilli(it)) }
        fun mins(a: Long?, b: Long?) = if (a == null || b == null) null else (b - a) / 60_000
        val header = listOf("N°", "Statut", "Priorité", "Déclarée le", "Déclarant (matricule)", "Ligne", "Poste", "Machine",
            "Catégorie", "Nom de la panne", "Description", "Machine arrêtée", "Intervenant (matricule)", "Service",
            "Prise en charge", "Délai prise en charge (min)", "Fin d'intervention", "Durée jusqu'à réparation (min)",
            "Clôturée le", "Diagnostic", "Cause", "Actions réalisées", "Résultat des tests", "Récurrente")
        return listOf(header) + rows.map { r ->
            val iv = Iv(r)
            listOf(iv.number, St.LABELS[iv.status], Priority.LABELS[iv.priority], dt(iv.declaredAt),
                "${r.str("r_name")} (${r.str("r_mat")})", r.str("l_code"), r.strOrNull("p_code"), r.str("m_code"),
                r.str("c_name"), iv.title, iv.description, if (iv.productionStopped) "Oui" else "Non",
                r.strOrNull("a_mat")?.let { "${r.strOrNull("a_name")} ($it)" }, Service.LABELS[iv.service],
                dt(iv.acceptedAt), mins(iv.declaredAt, iv.acceptedAt), dt(iv.repairedAt), mins(iv.declaredAt, iv.repairedAt),
                dt(iv.closedAt), iv.diagnosis, iv.rootCause, iv.correctiveAction, iv.testResult, if (iv.recurring) "Oui" else "Non")
        }
    }

    private fun hours(s: Long?) = s?.let { Math.round(it / 36.0) / 100.0 }

    fun summaryRows(j: JsonObject): List<List<Any?>> {
        fun n(o: JsonObject, k: String): Any? = (o[k] as? JsonPrimitive)?.let { p -> p.content.toLongOrNull() ?: p.content.toDoubleOrNull() }
        val live = j["live"] as JsonObject
        val totals = j["totals"] as JsonObject
        val period = j["period"] as JsonObject
        return listOf(
            listOf("Indicateur", "Valeur"),
            listOf("Période du", (period["from"] as JsonPrimitive).content),
            listOf("au", (period["to"] as JsonPrimitive).content),
            listOf("Pannes déclarées", n(totals, "declared")),
            listOf("Pannes clôturées", n(totals, "closed")),
            listOf("Pannes critiques", n(totals, "critical")),
            listOf("Pannes récurrentes", n(totals, "recurring")),
            listOf("Taux de récidive (%)", n(j, "recurrence_rate")),
            listOf("MTTR (heures)", hours(n(j, "mttr_s") as Long?)),
            listOf("MTBF (heures)", hours(n(j, "mtbf_s") as Long?)),
            listOf("Délai moyen de prise en charge (minutes)", (n(j, "response_avg_s") as Long?)?.let { it / 60 }),
            listOf("Temps d'arrêt machine (heures)", hours(n(j, "downtime_s") as Long?)),
            listOf("En direct : pannes ouvertes", n(live, "open")),
            listOf("En direct : critiques ouvertes", n(live, "critical_open")),
            listOf("En direct : machines arrêtées", n(live, "machines_stopped")),
            listOf("En direct : en attente de prise en charge", n(live, "waiting_takeover")),
            listOf("En direct : en cours", n(live, "in_progress")),
            listOf("En direct : en attente de pièces", n(live, "waiting_parts")),
            listOf("En direct : en attente Qualité", n(live, "waiting_quality")),
            listOf("En direct : à clôturer", n(live, "to_close")),
            listOf("En direct : en retard", n(live, "late")),
        )
    }

    private fun groupRows(j: JsonObject, key: String, label: String): List<List<Any?>> =
        listOf(listOf(label, "Pannes", "Récurrentes", "Arrêt machine (h)")) + (j[key] as JsonArray).map {
            val o = it as JsonObject
            val code = (o["code"] as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content
            val name = (o["name"] as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content
            listOf(listOfNotNull(code, name).joinToString(" – "), n(o, "count"), n(o, "recurring"), hours(n(o, "downtime_s")))
        }

    private fun n(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.content?.toLongOrNull()

    fun xlsx(user: User, f: Filter, now: Long): ByteArray {
        val s = compute(user, f, now)
        val zone = zone(user)
        return Xlsx()
            .sheet("Synthèse", summaryRows(s.json), listOf(45, 22))
            .sheet("Par ligne", groupRows(s.json, "by_line", "Ligne"), listOf(40, 10, 12, 18))
            .sheet("Par machine", groupRows(s.json, "by_machine", "Machine"), listOf(40, 10, 12, 18))
            .sheet("Par poste", groupRows(s.json, "by_poste", "Poste"), listOf(40, 10, 12, 18))
            .sheet("Par catégorie", groupRows(s.json, "by_category", "Catégorie"), listOf(40, 10, 12, 18))
            .sheet("Historique", listOf(listOf("Période", "Déclarées", "Clôturées")) + (s.json["timeline"] as JsonArray).map {
                val o = it as JsonObject
                listOf((o["period"] as JsonPrimitive).content, n(o, "declared"), n(o, "closed"))
            }, listOf(14, 12, 12))
            .sheet("Pannes", listSheetRows(s.rows, zone), listOf(16, 26, 10, 16, 26, 8, 10, 14, 18, 22, 40))
            .bytes()
    }

    fun pdf(user: User, f: Filter, now: Long, company: String): PdfDoc {
        val s = compute(user, f, now)
        fun grid(rows: List<List<Any?>>, widths: List<Float>) = PdfBlock.Grid(widths, rows.mapIndexed { i, r ->
            r.map { v -> PdfCell(v?.toString() ?: "—", bold = i == 0, shaded = i == 0) }
        })
        val blocks = mutableListOf<PdfBlock>(
            PdfBlock.Section("Synthèse"), grid(summaryRows(s.json), listOf(120f, 66f)),
            PdfBlock.Section("Par ligne"), grid(groupRows(s.json, "by_line", "Ligne"), listOf(96f, 30f, 30f, 30f)),
            PdfBlock.Section("Par machine"), grid(groupRows(s.json, "by_machine", "Machine").take(16), listOf(96f, 30f, 30f, 30f)),
            PdfBlock.Section("Par poste"), grid(groupRows(s.json, "by_poste", "Poste"), listOf(96f, 30f, 30f, 30f)),
            PdfBlock.Section("Par catégorie"), grid(groupRows(s.json, "by_category", "Catégorie"), listOf(96f, 30f, 30f, 30f)),
            PdfBlock.Section("Historique"),
            grid(listOf(listOf("Période", "Déclarées", "Clôturées")) + (s.json["timeline"] as JsonArray).map {
                val o = it as JsonObject
                listOf((o["period"] as JsonPrimitive).content, n(o, "declared"), n(o, "closed"))
            }, listOf(62f, 62f, 62f)),
            PdfBlock.Note("MTTR = moyenne (fin d'intervention − déclaration). MTBF = (machines × durée de la période − arrêts) ÷ pannes. " +
                "Taux de récidive = pannes récurrentes ÷ pannes déclarées (hors annulées)."),
        )
        val fmt = PdfBuilder.formatter(zone(user).id)
        val period = "${fmt.format(Instant.ofEpochMilli(f.from))} → ${fmt.format(Instant.ofEpochMilli(f.to))}"
        return PdfDoc(
            number = "Indicateurs", revision = 0, statusLabel = period, company = company,
            footer = "MaintiFlow · tableau de bord · période $period · généré le ${fmt.format(Instant.ofEpochMilli(now))}",
            watermark = null, title = "TABLEAU DE BORD MAINTENANCE",
            parts = listOf(PdfPart("INDICATEURS DE LA PÉRIODE", PdfColors.NAVY, null, blocks)),
        )
    }
}
