package com.maintiflow.server

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Portage de backend/tests/test_workflow.py sur le serveur du téléphone maître. */
class WorkflowTest {
    private val env = TestEnv()
    private val u get() = env.users

    @After fun tearDown() = env.close()

    private fun newTicket(api: Api, vararg kw: Pair<String, Any?>): JsonObject {
        val body = mutableMapOf<String, Any?>(
            "machine_id" to env.id("machines", 0), "category_id" to env.id("categories", 0),
            "description" to "Bourrage récurrent en sortie de presse",
        )
        kw.forEach { body[it.first] = it.second }
        val r = api.post("/interventions", *body.toList().toTypedArray())
        assertTrue(r.text, r.status == 200 || r.status == 201)
        return r.obj
    }

    private fun runToRepaired(vararg kw: Pair<String, Any?>): JsonObject {
        var iv = newTicket(u["chef"]!!, "production_stopped" to true, *kw)
        iv = u["tech"]!!.act(iv, "accept")
        iv = u["tech"]!!.act(iv, "start")
        return u["tech"]!!.act(iv, "repair", 200, "diagnosis" to "Capteur inductif HS", "root_cause" to "Câble écrasé",
            "corrective_action" to "Remplacement capteur + recâblage", "machine_final_state" to "OPERATIONNELLE")
    }

    @Test fun fullWorkflowFromReportToClosure() {
        val chef = u["chef"]!!; val tech = u["tech"]!!; val resp = u["resp"]!!
        val year = Instant.now().atZone(ZoneOffset.UTC).year

        var iv = newTicket(chef, "production_stopped" to true)
        assertEquals("BI-$year-000001", iv.s("number"))
        assertEquals("NOUVEAU", iv.s("status"))
        assertEquals("NORMALE", iv.s("priority"))
        assertNotNull(iv.s("stop_start_at"))
        assertTrue("cancel" in iv.arr2("allowed_actions"))

        val techId = env.refs["technicians"]!!.jsonArray.map { it.jsonObject }.first { it.s("full_name") == "TECH" }.l("id")
        iv = resp.act(iv, "assign", 200, "technician_id" to techId, "priority" to "HAUTE")
        assertEquals("EN_ATTENTE", iv.s("status")); assertEquals("HAUTE", iv.s("priority"))
        assertEquals(techId, iv["assigned_to"]!!.jsonObject.l("id"))

        u["tech2"]!!.act(iv, "accept", 403)
        iv = tech.act(iv, "accept")
        assertEquals("ACCEPTE", iv.s("status")); assertNotNull(iv.s("accepted_at"))
        iv = tech.act(iv, "arrive")
        iv = tech.act(iv, "start")
        assertEquals("EN_COURS", iv.s("status"))

        tech.act(iv, "pause", 422)
        iv = tech.act(iv, "pause", 200, "comment" to "Pause sécurité – consignation")
        assertEquals("true", iv.s("paused"))
        iv = tech.act(iv, "resume")
        iv = tech.act(iv, "wait_part", 200, "comment" to "Capteur IFM IG5953 en commande")
        assertEquals("EN_ATTENTE_PIECE", iv.s("status"))
        iv = tech.act(iv, "resume")
        assertEquals("EN_COURS", iv.s("status"))

        var r = tech.post("/interventions/${iv.s("id")}/parts", "reference" to "IG5953", "designation" to "Capteur inductif M18", "quantity" to 1)
        assertEquals(r.text, 201, r.status)
        iv = r.obj
        assertEquals(1, iv.arr("parts").size)
        assertEquals("1.00", iv.arr("parts")[0].s("quantity"))

        r = tech.post("/interventions/${iv.s("id")}/actions/repair", "diagnosis" to "x")
        assertEquals(422, r.status); assertTrue(r.text.contains("actions correctives"))
        iv = tech.act(iv, "repair", 200, "diagnosis" to "Capteur inductif HS", "root_cause" to "Câble écrasé par le carter",
            "corrective_action" to "Remplacement du capteur, protection du câble", "machine_final_state" to "OPERATIONNELLE")
        assertEquals("REPARE", iv.s("status"))

        chef.act(iv, "confirm_production", 409)
        iv = tech.act(iv, "sign_maintenance", 200, "signature_png_base64" to SIG)
        assertEquals("VALIDATION_PRODUCTION", iv.s("status"))
        assertNotNull(iv.s("maintenance_validated_at")); assertEquals(null, iv.s("production_confirmed_at"))
        tech.act(iv, "confirm_production", 403)

        iv = chef.act(iv, "confirm_production", 200, "signature_png_base64" to SIG, "comment" to "Production relancée OK")
        assertEquals("CLOTURE", iv.s("status")); assertEquals("true", iv.s("locked"))
        assertNotNull(iv.s("stop_end_at")); assertNotNull(iv.s("closed_at"))
        assertEquals(listOf("MAINTENANCE", "PRODUCTION"),
            iv.arr("signatures").filter { it.s("superseded") == "false" }.map { it.s("role")!! }.sorted())
        assertTrue(iv.arr("allowed_actions").isEmpty())

        val d = iv["durations"]!!.jsonObject
        assertEquals("false", d.s("running"))
        assertTrue(d.l("production_downtime_s") >= 0); assertNotNull(d.s("response_s"))
        val types = iv.arr("events").map { it.s("type") }
        listOf("CREATION", "AFFECTATION", "PRISE_EN_CHARGE", "ARRIVEE", "DEMARRAGE", "PAUSE", "RESUME", "ATTENTE_PIECE",
            "REPRISE", "PIECE_AJOUTEE", "REPARATION_TERMINEE", "SIGNATURE", "VALIDATION_MAINTENANCE",
            "CONFIRMATION_PRODUCTION").forEach { assertTrue(it, it in types) }

        assertEquals(409, chef.patch("/interventions/${iv.s("id")}", "version" to iv.l("version"), "description" to "modif").status)
        assertEquals(409, tech.post("/interventions/${iv.s("id")}/parts", "reference" to "A", "designation" to "B", "quantity" to 1).status)
        resp.act(iv, "cancel", 409, "comment" to "tentative d'annulation")
    }

    @Test fun managerValidationWhenConfigured() {
        assertEquals(200, u["admin"]!!.put("/admin/settings", "require_manager_validation" to true).status)
        var iv = runToRepaired()
        u["resp"]!!.act(iv, "validate_maintenance", 409)
        iv = u["tech"]!!.act(iv, "sign_maintenance", 200, "signature_png_base64" to SIG)
        assertEquals("REPARE", iv.s("status"))
        u["chef"]!!.act(iv, "confirm_production", 409)
        iv = u["resp"]!!.act(iv, "validate_maintenance", 200, "signature_png_base64" to SIG)
        assertEquals("VALIDATION_PRODUCTION", iv.s("status"))
        iv = u["chef"]!!.act(iv, "confirm_production")
        assertEquals("CLOTURE", iv.s("status"))
        assertEquals(listOf("MAINTENANCE", "PRODUCTION", "RESPONSABLE"), iv.arr("signatures").map { it.s("role")!! }.sorted())
    }

    @Test fun productionRefusesRestart() {
        var iv = runToRepaired()
        iv = u["tech"]!!.act(iv, "sign_maintenance")
        u["chef"]!!.act(iv, "reject_production", 422)
        iv = u["chef"]!!.act(iv, "reject_production", 200, "comment" to "Le défaut réapparaît après 10 pièces")
        assertEquals("EN_COURS", iv.s("status"))
        assertTrue(iv.arr("signatures").all { it.s("superseded") == "true" })
        iv = u["tech"]!!.act(iv, "repair")
        iv = u["tech"]!!.act(iv, "sign_maintenance")
        assertEquals("VALIDATION_PRODUCTION", iv.s("status"))
    }

    @Test fun editAfterSignatureRequiresNewSignature() {
        var iv = runToRepaired()
        u["admin"]!!.put("/admin/settings", "require_manager_validation" to true)
        iv = u["tech"]!!.act(iv, "sign_maintenance")
        val r = u["tech"]!!.patch("/interventions/${iv.s("id")}", "version" to iv.l("version"), "observations" to "Prévoir graissage")
        assertEquals(r.text, 200, r.status)
        iv = r.obj
        assertEquals("REPARE", iv.s("status"))
        assertTrue(iv.arr("signatures").all { it.s("superseded") == "true" })
        assertTrue("sign_maintenance" in iv.arr2("allowed_actions"))
        val last = iv.arr("events").last()
        assertEquals("MODIFICATION", last.s("type"))
        val diff = last["data"]!!.jsonObject["observations"]!!.jsonArray
        assertEquals(JsonNull, diff[0]); assertEquals("Prévoir graissage", diff[1].jsonPrimitive.content)
    }

    @Test fun cancelRules() {
        val chef = u["chef"]!!; val resp = u["resp"]!!
        var iv = newTicket(chef)
        chef.act(iv, "cancel", 422)
        u["chef2"]!!.act(iv, "cancel", 403, "comment" to "pas mon bon")
        val iv2 = chef.act(iv, "cancel", 200, "comment" to "Déclaré par erreur sur la mauvaise machine")
        assertEquals("ANNULE", iv2.s("status")); assertNotNull(iv2.s("cancel_reason"))

        iv = newTicket(chef)
        iv = u["tech"]!!.act(iv, "accept")
        chef.act(iv, "cancel", 403, "comment" to "trop tard pour le chef")
        iv = resp.act(iv, "cancel", 200, "comment" to "Doublon du bon précédent")
        assertEquals("ANNULE", iv.s("status"))
        resp.act(iv, "reopen", 409, "comment" to "un bon annulé ne se réouvre pas")
    }

    @Test fun reopenCreatesNewRevision() {
        var iv = runToRepaired()
        iv = u["tech"]!!.act(iv, "sign_maintenance")
        iv = u["chef"]!!.act(iv, "confirm_production")
        u["tech"]!!.act(iv, "reopen", 403, "comment" to "non autorisé")
        u["resp"]!!.act(iv, "reopen", 422, "comment" to "")
        iv = u["resp"]!!.act(iv, "reopen", 200, "comment" to "Même panne le lendemain, analyse insuffisante")
        assertEquals("EN_ATTENTE", iv.s("status")); assertEquals(2L, iv.l("revision")); assertEquals(1L, iv.l("reopen_count"))
        assertTrue(iv.arr("signatures").all { it.s("superseded") == "true" })
        val last = iv.arr("events").last()
        assertEquals("REOUVERTURE", last.s("type")); assertEquals(1L, last["data"]!!.jsonObject.l("revision"))
        iv = u["tech"]!!.act(iv, "accept")
        iv = u["tech"]!!.act(iv, "start")
        iv = u["tech"]!!.act(iv, "repair", 200, "corrective_action" to "Remplacement du câble complet")
        iv = u["tech"]!!.act(iv, "sign_maintenance")
        iv = u["chef"]!!.act(iv, "confirm_production")
        assertEquals("CLOTURE", iv.s("status")); assertEquals(2L, iv.l("revision"))
    }

    @Test fun numberingIsUniqueUnderConcurrency() {
        val year = Instant.now().atZone(ZoneOffset.UTC).year
        val numbers = java.util.Collections.synchronizedList(mutableListOf<String>())
        val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val threads = (1..4).map {
            Thread {
                try {
                    val api = env.api("chef")
                    repeat(5) { numbers += newTicket(api).s("number")!! }
                } catch (e: Throwable) { errors += e }
            }
        }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        assertTrue(errors.toString(), errors.isEmpty())
        assertEquals((1..20).map { "BI-$year-${it.toString().padStart(6, '0')}" }, numbers.sorted())
    }

    @Test fun clientUuidMakesCreationIdempotent() {
        val cu = UUID.randomUUID().toString()
        val a = newTicket(u["chef"]!!, "client_uuid" to cu)
        val b = newTicket(u["chef"]!!, "client_uuid" to cu)
        assertEquals(a.s("id"), b.s("id")); assertEquals(a.s("number"), b.s("number"))
        assertEquals(1L, u["chef"]!!.get("/interventions").obj.l("total"))
        val r = u["chef2"]!!.post("/interventions", "machine_id" to env.id("machines", 0),
            "category_id" to env.id("categories", 0), "description" to "abc", "client_uuid" to cu)
        assertEquals(409, r.status)
    }

    @Test fun offlineDeclaredAtIsKeptAndBounded() {
        val t = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS)
        val iv = newTicket(u["chef"]!!, "declared_at" to t.toString())
        assertEquals(t, Instant.parse(iv.s("declared_at")))
        val r = u["chef"]!!.post("/interventions", "machine_id" to env.id("machines", 0),
            "category_id" to env.id("categories", 0), "description" to "abc",
            "declared_at" to t.plus(1, ChronoUnit.DAYS).toString())
        assertEquals(422, r.status)
        val noTz = u["chef"]!!.post("/interventions", "machine_id" to env.id("machines", 0),
            "category_id" to env.id("categories", 0), "description" to "abc", "declared_at" to "2026-01-01T10:00:00")
        assertEquals(422, noTz.status)
    }

    @Test fun recurrenceDetectionAndSimilar() {
        var first = runToRepaired()
        first = u["tech"]!!.act(first, "sign_maintenance")
        u["chef"]!!.act(first, "confirm_production")
        val iv = newTicket(u["chef"]!!)
        assertEquals("true", iv.s("recurring"))
        assertTrue(iv.arr("events").any { it.s("type") == "PANNE_RECURRENTE" })
        val sim = u["tech"]!!.get("/interventions/${iv.s("id")}/similar").obj
        assertTrue(sim.s("disclaimer")!!.contains("indicatif"))
        val item = sim.arr("items")[0]
        assertEquals(first.s("number"), item.s("number")); assertEquals("true", item.s("same_machine"))
        var other = newTicket(u["chef"]!!, "machine_id" to env.id("machines", 1))
        assertEquals("false", other.s("recurring"))
        other = u["tech"]!!.act(other, "flag_recurring", 200, "comment" to "Déjà vu la semaine dernière")
        assertEquals("true", other.s("recurring"))
    }

    @Test fun optimisticLocking() {
        val iv = newTicket(u["chef"]!!)
        val r1 = u["chef"]!!.patch("/interventions/${iv.s("id")}", "version" to iv.l("version"), "description" to "Bourrage + bruit")
        assertEquals(r1.text, 200, r1.status)
        val r2 = u["chef"]!!.patch("/interventions/${iv.s("id")}", "version" to iv.l("version"), "description" to "autre")
        assertEquals(409, r2.status)
        val r = u["tech"]!!.post("/interventions/${iv.s("id")}/actions/accept", "version" to iv.l("version"))
        assertEquals(409, r.status)
    }

    @Test fun fieldPermissions() {
        var iv = newTicket(u["chef"]!!)
        assertEquals(403, u["chef"]!!.patch("/interventions/${iv.s("id")}", "version" to iv.l("version"), "diagnosis" to "x").status)
        iv = u["tech"]!!.act(iv, "accept")
        assertEquals(403, u["chef"]!!.patch("/interventions/${iv.s("id")}", "version" to iv.l("version"), "description" to "changement").status)
        assertEquals(403, u["tech"]!!.patch("/interventions/${iv.s("id")}", "version" to iv.l("version"), "description" to "texte technicien").status)
        assertEquals(200, u["tech"]!!.patch("/interventions/${iv.s("id")}", "version" to iv.l("version"), "diagnosis" to "Vérin bloqué").status)
    }

    @Test fun searchFilters() {
        val a = newTicket(u["chef"]!!)
        val b = newTicket(u["chef2"]!!, "machine_id" to env.id("machines", 1), "category_id" to env.id("categories", 1))
        u["tech"]!!.act(b, "accept")
        val chef = u["chef"]!!
        assertEquals(a.s("id"), chef.get("/interventions?q=${a.s("number")}").obj.arr("items")[0].s("id"))
        assertEquals(1L, chef.get("/interventions?q=M2").obj.l("total"))
        assertEquals(1L, chef.get("/interventions?mine=true").obj.l("total"))
        assertEquals(b.s("id"), chef.get("/interventions?status=ACCEPTE").obj.arr("items")[0].s("id"))
        assertEquals(2L, chef.get("/interventions?status=ACCEPTE&status=NOUVEAU").obj.l("total"))
        assertEquals(1L, u["resp"]!!.get("/interventions?technician_id=${u["tech"]!!.id}").obj.l("total"))
        val home = u["tech"]!!.get("/interventions?open_only=true").obj.arr("items")
        assertEquals("CRITIQUE", home[0].s("priority"))
        assertEquals(200, chef.get("/interventions/by-number/${a.s("number")}").status)
        assertEquals(422, chef.get("/interventions?status=FAUX").status)
    }

    @Test fun durationsFromTimestamps() {
        val base = 1_700_000_000_000L
        fun ev(id: Long, at: Long, type: String, to: String?) = Event(mapOf(
            "id" to id, "at" to base + at * 1000, "user_id" to null, "type" to type, "from_status" to null,
            "to_status" to to, "comment" to null, "data" to null, "user_name" to null,
        ))
        val row = HashMap<String, Any?>(mapOf(
            "id" to "x", "number" to "BI-1", "client_uuid" to null, "site_id" to 1L, "line_id" to 1L, "machine_id" to 1L,
            "category_id" to 1L, "priority" to "NORMALE", "status" to "REPARE", "description" to "d", "reported_by_id" to 1L,
            "assigned_to_id" to 2L, "production_stopped" to 1L, "stop_start_at" to base, "stop_end_at" to null,
            "declared_at" to base, "accepted_at" to base + 300_000, "arrived_at" to null, "started_at" to base + 900_000,
            "repaired_at" to base + 2_700_000, "maintenance_validated_at" to null, "production_confirmed_at" to null,
            "closed_at" to null, "cancelled_at" to null, "cancel_reason" to null, "paused" to 0L, "diagnosis" to null,
            "root_cause" to null, "corrective_action" to null, "observations" to null, "machine_final_state" to null,
            "production_comment" to null, "recurring" to 0L, "reopen_count" to 0L, "revision" to 1L, "version" to 1L,
            "archived_pdf_sha256" to null, "archived_pdf_path" to null,
        ))
        val iv = Iv(row)
        val d = Timing.compute(iv, listOf(
            ev(1, 0, "CREATION", "NOUVEAU"), ev(2, 300, "PRISE_EN_CHARGE", "ACCEPTE"),
            ev(3, 900, "DEMARRAGE", "EN_COURS"), ev(4, 2700, "REPARATION_TERMINEE", "REPARE"),
        ), base + 3_000_000)
        assertEquals(300L, Timing.long(d, "response_s"))
        assertEquals(900L, Timing.long(d, "waiting_takeover_s"))
        assertEquals(1800L, Timing.long(d, "active_maintenance_s"))
        assertEquals(300L, Timing.long(d, "waiting_validation_s"))
        assertEquals(3000L, Timing.long(d, "production_downtime_s"))
        assertEquals("1 h 05 min", Timing.format(3900))
        assertEquals("—", Timing.format(null))
    }

    @Test fun lateFlagFollowsPrioritySettings() {
        var now = Instant.now().toEpochMilli()
        TestEnv(clock = { now }).use { e ->
            val chef = e.api("chef")
            val r = chef.post("/interventions", "machine_id" to e.id("machines", 0), "category_id" to e.id("categories", 1),
                "description" to "Carter de sécurité arraché")
            assertEquals("false", r.obj.s("late"))
            now += 6 * 60_000  // CRITIQUE : 5 minutes
            assertEquals("true", chef.get("/interventions/${r.obj.s("id")}").obj.s("late"))
        }
    }

    private fun JsonObject.arr2(k: String) = this[k]!!.jsonArray.map { it.jsonPrimitive.content }
}

@Suppress("unused")
private fun JsonObject.long(k: String) = this[k]!!.jsonPrimitive.long
