package com.maintiflow.server

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID

/** Parcours d'une panne : page rose, page jaune, statuts et transitions autorisées. */
class WorkflowTest {
    private val env = TestEnv()
    private val u get() = env.users

    @After fun tearDown() = env.close()

    @Test fun fullMaintenancePathFromOperatorToClosure() {
        val op = u["op"]!!; val cl = u["cl"]!!; val tech = u["tech"]!!; val logi = u["logi"]!!
        val year = Instant.now().atZone(ZoneOffset.UTC).year

        // Page rose : l'opérateur du poste A déclare.
        var iv = env.declare(op, "production_stopped" to true, "poste_id" to env.ref("postes", "L1-A"),
            "cable_ref_id" to env.ref("cable_refs", "CBL-1"), "title" to "Bourrage sortie presse")
        assertEquals("BI-$year-000001", iv.s("number"))
        assertEquals("NOUVEAU", iv.s("status")); assertEquals("Nouvelle panne", iv.s("status_label"))
        assertEquals("NORMALE", iv.s("priority")); assertEquals("Moyen", iv.s("priority_label"))
        assertEquals("OPERATEUR", iv.s("reporter_role")); assertEquals("op", iv["reported_by"]!!.jsonObject.s("matricule"))
        assertEquals("L1-A", iv["poste"]!!.jsonObject.s("code")); assertEquals("CBL-1", iv.s("cable_ref_code"))
        assertNotNull(iv.s("stop_start_at"))

        // Le chef de ligne affecte.
        iv = cl.act(iv, "assign", 200, "technician_id" to env.userId("tech"), "priority" to "HAUTE")
        assertEquals("EN_ATTENTE", iv.s("status")); assertEquals("HAUTE", iv.s("priority")); assertEquals("MAINTENANCE", iv.s("service"))

        // Page jaune : prise en charge, diagnostic, intervention.
        u["tech2"]!!.act(iv, "accept", 403)
        iv = tech.act(iv, "accept")
        assertEquals("EN_DIAGNOSTIC", iv.s("status")); assertNotNull(iv.s("accepted_at"))
        iv = tech.act(iv, "arrive")
        tech.act(iv, "start", 422)
        iv = tech.act(iv, "start", 200, "diagnosis" to "Capteur inductif HS", "intervention_type_id" to env.ref("intervention_types", "CORR"))
        assertEquals("INTERVENTION_MAINTENANCE", iv.s("status")); assertEquals("Corrective", iv["intervention_type"]!!.jsonObject.s("name"))

        tech.act(iv, "pause", 422)
        iv = tech.act(iv, "pause", 200, "comment" to "Consignation sécurité")
        assertEquals("true", iv.s("paused"))
        iv = tech.act(iv, "resume")
        iv = tech.act(iv, "wait_part", 200, "reference" to "IG5953", "designation" to "Capteur inductif M18", "quantity" to 1)
        assertEquals("EN_ATTENTE_PIECE", iv.s("status"))
        assertEquals("DEMANDEE", iv.arr("part_requests")[0].s("status"))
        tech.act(iv, "part_delivered", 403)
        iv = logi.act(iv, "part_delivered", 200, "comment" to "Sorti du magasin")
        assertEquals("INTERVENTION_MAINTENANCE", iv.s("status")); assertEquals("LIVREE", iv.arr("part_requests")[0].s("status"))

        var r = tech.post("/interventions/${iv.s("id")}/parts", "reference" to "IG5953", "designation" to "Capteur inductif M18", "quantity" to 1)
        assertEquals(r.text, 201, r.status)
        iv = r.obj

        r = tech.post("/interventions/${iv.s("id")}/actions/finish", "corrective_action" to "x")
        assertEquals(422, r.status); assertTrue(r.text, r.text.contains("résultat des tests"))
        iv = tech.act(iv, "finish", 200, *env.YELLOW, "tool_id" to env.ref("tools", "APP-1"), "signature_png_base64" to SIG)
        assertEquals("RESOLUE", iv.s("status")); assertNotNull(iv.s("repaired_at"))

        // Clôture justifiée par un responsable habilité.
        op.act(iv, "close", 403, "comment" to "je clôture")
        tech.act(iv, "close", 403, "comment" to "je clôture")
        cl.act(iv, "close", 422)
        iv = cl.act(iv, "close", 200, "comment" to "Production relancée, 50 pièces OK", "signature_png_base64" to SIG)
        assertEquals("CLOTURE", iv.s("status")); assertEquals("true", iv.s("locked"))
        assertEquals("cl", iv["closed_by"]!!.jsonObject.s("matricule"))
        assertEquals("Production relancée, 50 pièces OK", iv.s("final_comment"))
        assertNotNull(iv.s("stop_end_at")); assertNotNull(iv.s("closed_at"))
        assertEquals(listOf("MAINTENANCE", "PRODUCTION"),
            iv.arr("signatures").filter { it.s("superseded") == "false" }.map { it.s("role")!! }.sorted())
        // Clôturée : plus aucune action, sauf la réouverture en cas de récidive.
        assertEquals(listOf("reopen"), iv["allowed_actions"]!!.jsonArray.map { it.jsonPrimitive.content })

        val d = iv["durations"]!!.jsonObject
        assertEquals("false", d.s("running"))
        assertNotNull(d.s("response_s"))
        val perStatus = iv["status_durations"]!!.jsonObject.keys
        assertTrue(perStatus.toString(), perStatus.all { it in St.ALL })
        val types = iv.arr("events").map { it.s("type") }
        listOf("CREATION", "AFFECTATION", "PRISE_EN_CHARGE", "ARRIVEE", "DEMARRAGE", "PAUSE", "RESUME", "ATTENTE_PIECE",
            "PIECE_LIVREE", "PIECE_AJOUTEE", "INTERVENTION_TERMINEE", "SIGNATURE", "CLOTURE").forEach { assertTrue(it, it in types) }
        // Chaque ligne d'historique dit qui (matricule et rôle) et quand.
        val takeover = iv.arr("events").first { it.s("type") == "PRISE_EN_CHARGE" }
        assertEquals("tech", takeover.s("matricule")); assertEquals("MAINTENANCIER", takeover.s("user_role"))
        assertEquals("EN_ATTENTE", takeover.s("from_status")); assertEquals("EN_DIAGNOSTIC", takeover.s("to_status"))

        assertEquals(409, cl.patch("/interventions/${iv.s("id")}", "version" to iv.l("version"), "description" to "modif").status)
        assertEquals(409, tech.post("/interventions/${iv.s("id")}/parts", "reference" to "A", "designation" to "B", "quantity" to 1).status)
        u["resp"]!!.act(iv, "cancel", 409, "comment" to "tentative d'annulation")
    }

    @Test fun qualityValidationWhenCategoryRequiresIt() {
        var iv = env.resolved(kw = arrayOf("category_id" to env.ref("categories", "SECU")))
        assertEquals("EN_ATTENTE_QUALITE", iv.s("status")); assertEquals("true", iv.s("requires_quality"))
        u["chef"]!!.act(iv, "close", 409, "comment" to "trop tôt pour clôturer")
        u["tech"]!!.act(iv, "quality_validate", 403, "comment" to "auto-validation")
        // Le texte des observations Qualité n'est modifiable que par la Qualité, à ce statut.
        assertEquals(403, u["tech"]!!.patch("/interventions/${iv.s("id")}", "version" to iv.l("version"), "quality_observations" to "x").status)
        u["qual"]!!.act(iv, "quality_reject", 422)
        iv = u["qual"]!!.act(iv, "quality_reject", 200, "comment" to "Barrière mal alignée, refaire le test")
        assertEquals("INTERVENTION_MAINTENANCE", iv.s("status"))
        assertTrue(iv.arr("signatures").all { it.s("superseded") == "true" })
        iv = u["tech"]!!.act(iv, "finish", 200, "test_result" to "Test barrière OK 10/10")
        assertEquals("EN_ATTENTE_QUALITE", iv.s("status"))
        iv = u["qual"]!!.act(iv, "quality_validate", 200, "comment" to "Conforme après réalignement", "signature_png_base64" to SIG)
        assertEquals("RESOLUE", iv.s("status")); assertEquals("qual", iv["quality_by"]!!.jsonObject.s("matricule"))
        assertTrue(iv.s("quality_observations")!!.contains("Conforme"))
        iv = u["chef"]!!.act(iv, "close", 200, "comment" to "Sécurité vérifiée, production relancée")
        assertEquals("CLOTURE", iv.s("status"))
        assertTrue(iv.arr("signatures").any { it.s("role") == "QUALITE" && it.s("superseded") == "false" })
    }

    @Test fun methodePathAndTransfers() {
        var iv = env.declare(u["chef"]!!)
        iv = u["chef"]!!.act(iv, "assign", 200, "service" to "METHODE")
        assertEquals("METHODE", iv.s("service")); assertNull(iv.s("assigned_to"))
        val refused = u["tech"]!!.post("/interventions/${iv.s("id")}/actions/accept")
        assertEquals(403, refused.status); assertTrue(refused.text.contains("Méthode"))
        iv = u["meth"]!!.act(iv, "accept")
        iv = u["meth"]!!.act(iv, "start", 200, "diagnosis" to "Réglage machine à revoir")
        assertEquals("INTERVENTION_METHODE", iv.s("status"))
        u["meth"]!!.act(iv, "transfer", 422, "service" to "MAINTENANCE")
        u["tech"]!!.act(iv, "transfer", 403, "service" to "MAINTENANCE", "comment" to "pas à moi")
        u["meth"]!!.act(iv, "transfer", 422, "service" to "MAINTENANCE", "technician_id" to env.userId("meth"), "comment" to "mauvais service")
        iv = u["meth"]!!.act(iv, "transfer", 200, "service" to "MAINTENANCE", "technician_id" to env.userId("tech"),
            "comment" to "Pièce mécanique cassée, besoin Maintenance")
        assertEquals("EN_ATTENTE", iv.s("status")); assertEquals("MAINTENANCE", iv.s("service"))
        assertEquals("tech", iv["assigned_to"]!!.jsonObject.s("matricule"))
        u["tech2"]!!.act(iv, "accept", 403)
        iv = u["tech"]!!.act(iv, "accept")
        iv = u["tech"]!!.act(iv, "start")  // diagnostic déjà saisi par Méthode
        assertEquals("INTERVENTION_MAINTENANCE", iv.s("status"))
        // Le chef d'équipe peut aussi renvoyer vers Méthode, avec motif.
        iv = u["chef"]!!.act(iv, "transfer", 200, "service" to "METHODE", "comment" to "Analyse Méthode demandée")
        assertEquals("EN_ATTENTE", iv.s("status")); assertNull(iv.s("assigned_to"))
        val transfer = iv.arr("events").last { it.s("type") == "TRANSFERT" }
        assertEquals("MAINTENANCE", transfer["data"]!!.jsonObject.s("from")); assertEquals("METHODE", transfer["data"]!!.jsonObject.s("to"))
    }

    @Test fun logisticsSeesOnlyPartRequestsAndHandlesThem() {
        var iv = env.declare(u["chef"]!!)
        assertEquals(404, u["logi"]!!.get("/interventions/${iv.s("id")}").status)
        assertEquals(0L, u["logi"]!!.get("/interventions").obj.l("total"))
        iv = u["tech"]!!.act(iv, "accept")
        iv = u["tech"]!!.act(iv, "start", 200, "diagnosis" to "Roulement HS")
        u["tech"]!!.act(iv, "wait_part", 422)
        iv = u["tech"]!!.act(iv, "wait_part", 200, "reference" to "6204-2RS", "quantity" to 2)
        assertEquals(1L, u["logi"]!!.get("/interventions").obj.l("total"))
        u["logi"]!!.act(iv, "accept", 403)
        u["logi"]!!.act(iv, "part_unavailable", 422)
        iv = u["logi"]!!.act(iv, "part_unavailable", 200, "comment" to "Rupture, livraison jeudi")
        assertEquals("EN_ATTENTE_PIECE", iv.s("status")); assertEquals("INDISPONIBLE", iv.arr("part_requests")[0].s("status"))
        assertEquals("2", iv.arr("part_requests")[0].s("quantity"))
        // L'intervenant peut reprendre avec une pièce trouvée ailleurs.
        iv = u["tech"]!!.act(iv, "resume", 200, "comment" to "Pièce récupérée sur machine arrêtée")
        assertEquals("INTERVENTION_MAINTENANCE", iv.s("status"))
        assertEquals(403, u["logi"]!!.post("/interventions", "machine_id" to env.ref("machines", "M1"),
            "category_id" to env.ref("categories", "MECA"), "description" to "abc").status)
    }

    @Test fun managerValidationWhenConfigured() {
        assertEquals(200, u["admin"]!!.put("/admin/settings", "require_manager_validation" to true).status)
        var iv = env.resolved()
        u["chef"]!!.act(iv, "close", 409, "comment" to "clôture sans validation")
        iv = u["resp"]!!.act(iv, "validate_maintenance", 200, "signature_png_base64" to SIG)
        u["resp"]!!.act(iv, "validate_maintenance", 409)
        iv = u["chef"]!!.act(iv, "close", 200, "comment" to "Validé et relancé")
        assertEquals("CLOTURE", iv.s("status"))
        assertEquals(listOf("MAINTENANCE", "PRODUCTION", "RESPONSABLE"), iv.arr("signatures").map { it.s("role")!! }.sorted())
    }

    @Test fun unresolvedFaultIsNeverClosed() {
        var iv = env.declare(u["chef"]!!)
        u["chef"]!!.act(iv, "close", 409, "comment" to "clôture directe")
        iv = u["tech"]!!.act(iv, "accept")
        u["chef"]!!.act(iv, "close", 409, "comment" to "clôture pendant le diagnostic")
        iv = u["tech"]!!.act(iv, "start", 200, "diagnosis" to "Vérin")
        u["chef"]!!.act(iv, "close", 409, "comment" to "clôture pendant l'intervention")
        iv = u["tech"]!!.act(iv, "finish", 200, *env.YELLOW)
        u["chef"]!!.act(iv, "reject_resolution", 422)
        iv = u["chef"]!!.act(iv, "reject_resolution", 200, "comment" to "Le défaut réapparaît après 10 pièces")
        assertEquals("INTERVENTION_MAINTENANCE", iv.s("status")); assertNull(iv.s("repaired_at"))
        assertTrue(iv.arr("signatures").all { it.s("superseded") == "true" })
        iv = u["tech"]!!.act(iv, "finish")
        assertEquals("RESOLUE", iv.s("status"))
    }

    @Test fun cancelRules() {
        var iv = env.declare(u["op"]!!)
        u["op"]!!.act(iv, "cancel", 403, "comment" to "opérateur")
        u["chef"]!!.act(iv, "cancel", 422)
        iv = u["cl"]!!.act(iv, "cancel", 200, "comment" to "Déclaré par erreur sur la mauvaise machine")
        assertEquals("ANNULE", iv.s("status")); assertNotNull(iv.s("cancel_reason"))
        u["resp"]!!.act(iv, "reopen", 409, "comment" to "une panne annulée ne se réouvre pas")
    }

    @Test fun reopenAfterRecurrenceKeepsHistory() {
        var iv = env.closed()
        val eventsBefore = iv.arr("events").size
        u["tech"]!!.act(iv, "reopen", 403, "comment" to "non autorisé")
        u["op"]!!.act(iv, "reopen", 403, "comment" to "non autorisé")
        u["qual"]!!.act(iv, "reopen", 422, "comment" to "")
        iv = u["qual"]!!.act(iv, "reopen", 200, "comment" to "Même défaut le lendemain, analyse insuffisante")
        assertEquals("REOUVERTE", iv.s("status")); assertEquals(2L, iv.l("revision")); assertEquals(1L, iv.l("reopen_count"))
        assertEquals("true", iv.s("recurring")); assertNull(iv.s("assigned_to")); assertNull(iv.s("final_comment"))
        assertTrue(iv.arr("signatures").all { it.s("superseded") == "true" })
        assertTrue(iv.arr("events").size > eventsBefore)  // rien n'est effacé
        val last = iv.arr("events").last()
        assertEquals("REOUVERTURE", last.s("type")); assertEquals(1L, last["data"]!!.jsonObject.l("revision"))
        iv = u["tech2"]!!.act(iv, "accept")
        assertEquals("EN_DIAGNOSTIC", iv.s("status"))
        iv = u["tech2"]!!.act(iv, "start")
        iv = u["tech2"]!!.act(iv, "finish", 200, "corrective_action" to "Remplacement du câble complet")
        iv = u["chef"]!!.act(iv, "close", 200, "comment" to "Câble remplacé, OK")
        assertEquals("CLOTURE", iv.s("status")); assertEquals(2L, iv.l("revision"))
    }

    @Test fun assignmentAtDeclaration() {
        val iv = env.declare(u["op"]!!, "service" to "METHODE")
        assertEquals("EN_ATTENTE", iv.s("status")); assertEquals("METHODE", iv.s("service"))
        val iv2 = env.declare(u["chef"]!!, "assigned_to_id" to env.userId("tech2"))
        assertEquals("tech2", iv2["assigned_to"]!!.jsonObject.s("matricule"))
        assertEquals(422, u["chef"]!!.post("/interventions", "machine_id" to env.ref("machines", "M1"),
            "category_id" to env.ref("categories", "MECA"), "description" to "abc", "assigned_to_id" to env.userId("qual")).status)
        // Poste d'une autre ligne refusé ; poste de la machine repris par défaut.
        assertEquals(422, u["chef"]!!.post("/interventions", "machine_id" to env.ref("machines", "M1"),
            "category_id" to env.ref("categories", "MECA"), "description" to "abc", "poste_id" to env.ref("postes", "L2-A")).status)
        assertEquals("L1-A", env.declare(u["chef"]!!)["poste"]!!.jsonObject.s("code"))
    }

    @Test fun numberingIsUniqueUnderConcurrency() {
        val year = Instant.now().atZone(ZoneOffset.UTC).year
        val numbers = java.util.Collections.synchronizedList(mutableListOf<String>())
        val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val threads = (1..4).map {
            Thread {
                try {
                    val api = env.api("chef")
                    repeat(5) { numbers += env.declare(api).s("number")!! }
                } catch (e: Throwable) { errors += e }
            }
        }
        threads.forEach { it.start() }; threads.forEach { it.join() }
        assertTrue(errors.toString(), errors.isEmpty())
        assertEquals((1..20).map { "BI-$year-${it.toString().padStart(6, '0')}" }, numbers.sorted())
    }

    @Test fun clientUuidMakesCreationIdempotent() {
        val cu = UUID.randomUUID().toString()
        val a = env.declare(u["chef"]!!, "client_uuid" to cu)
        val b = env.declare(u["chef"]!!, "client_uuid" to cu)
        assertEquals(a.s("id"), b.s("id")); assertEquals(a.s("number"), b.s("number"))
        assertEquals(1L, u["chef"]!!.get("/interventions").obj.l("total"))
        val r = u["chef2"]!!.post("/interventions", "machine_id" to env.ref("machines", "M1"),
            "category_id" to env.ref("categories", "MECA"), "description" to "abc", "client_uuid" to cu)
        assertEquals(409, r.status)
    }

    @Test fun offlineDeclaredAtIsKeptAndBounded() {
        val t = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS)
        val iv = env.declare(u["chef"]!!, "declared_at" to t.toString())
        assertEquals(t, Instant.parse(iv.s("declared_at")))
        val r = u["chef"]!!.post("/interventions", "machine_id" to env.ref("machines", "M1"),
            "category_id" to env.ref("categories", "MECA"), "description" to "abc",
            "declared_at" to t.plus(1, ChronoUnit.DAYS).toString())
        assertEquals(422, r.status)
        val noTz = u["chef"]!!.post("/interventions", "machine_id" to env.ref("machines", "M1"),
            "category_id" to env.ref("categories", "MECA"), "description" to "abc", "declared_at" to "2026-01-01T10:00:00")
        assertEquals(422, noTz.status)
    }

    @Test fun recurrenceDetectionAndSimilar() {
        val first = env.closed()
        val iv = env.declare(u["chef"]!!)
        assertEquals("true", iv.s("recurring"))
        assertTrue(iv.arr("events").any { it.s("type") == "PANNE_RECURRENTE" })
        val sim = u["tech"]!!.get("/interventions/${iv.s("id")}/similar").obj
        assertTrue(sim.s("disclaimer")!!.contains("indicatif"))
        val item = sim.arr("items")[0]
        assertEquals(first.s("number"), item.s("number")); assertEquals("true", item.s("same_machine"))
        var other = env.declare(u["chef"]!!, "machine_id" to env.ref("machines", "M2"))
        assertEquals("false", other.s("recurring"))
        other = u["tech"]!!.act(other, "flag_recurring", 200, "comment" to "Déjà vu la semaine dernière")
        assertEquals("true", other.s("recurring"))
    }

    @Test fun optimisticLocking() {
        val iv = env.declare(u["chef"]!!)
        val r1 = u["chef"]!!.patch("/interventions/${iv.s("id")}", "version" to iv.l("version"), "description" to "Bourrage + bruit")
        assertEquals(r1.text, 200, r1.status)
        val r2 = u["chef"]!!.patch("/interventions/${iv.s("id")}", "version" to iv.l("version"), "description" to "autre")
        assertEquals(409, r2.status)
        val r = u["tech"]!!.post("/interventions/${iv.s("id")}/actions/accept", "version" to iv.l("version"))
        assertEquals(409, r.status)
    }

    @Test fun pinkPageFrozenAfterTakeoverYellowPageForAssignee() {
        var iv = env.declare(u["op"]!!)
        val id = iv.s("id")
        assertEquals(403, u["op"]!!.patch("/interventions/$id", "version" to iv.l("version"), "diagnosis" to "x").status)
        // op2 (ligne 2) ne voit pas les pannes de la ligne 1.
        assertEquals(404, u["op2"]!!.patch("/interventions/$id", "version" to iv.l("version"), "title" to "x").status)
        var r = u["op"]!!.patch("/interventions/$id", "version" to iv.l("version"), "title" to "Bourrage presse",
            "cable_ref_id" to env.ref("cable_refs", "CBL-1"))
        assertEquals(r.text, 200, r.status)
        iv = r.obj
        val diff = iv.arr("events").last()["data"]!!.jsonObject["title"]!!.jsonArray
        assertEquals(JsonNull, diff[0]); assertEquals("Bourrage presse", diff[1].jsonPrimitive.content)
        assertEquals(403, u["op"]!!.patch("/interventions/$id", "version" to iv.l("version"), "priority" to "CRITIQUE").status)
        iv = u["tech"]!!.act(iv, "accept")
        assertEquals(403, u["op"]!!.patch("/interventions/$id", "version" to iv.l("version"), "description" to "changement").status)
        assertEquals(403, u["tech"]!!.patch("/interventions/$id", "version" to iv.l("version"), "description" to "texte technicien").status)
        assertEquals(403, u["tech2"]!!.patch("/interventions/$id", "version" to iv.l("version"), "diagnosis" to "pas affecté").status)
        r = u["tech"]!!.patch("/interventions/$id", "version" to iv.l("version"), "diagnosis" to "Vérin bloqué",
            "cause_id" to env.ref("causes", "Usure"))
        assertEquals(r.text, 200, r.status)
        assertEquals("Usure", r.obj["cause"]!!.jsonObject.s("name"))
        iv = r.obj
        // Cause d'une autre catégorie refusée.
        assertEquals(422, u["tech"]!!.patch("/interventions/$id", "version" to iv.l("version"),
            "cause_id" to env.ref("causes", "Barrière immatérielle")).status)
    }

    @Test fun searchFilters() {
        val a = env.declare(u["op"]!!, "cable_ref_id" to env.ref("cable_refs", "CBL-1"))
        val b = env.declare(u["chef2"]!!, "machine_id" to env.ref("machines", "M2"), "category_id" to env.ref("categories", "SECU"))
        u["tech"]!!.act(b, "accept")
        val chef = u["chef"]!!
        assertEquals(a.s("id"), chef.get("/interventions?q=${a.s("number")}").obj.arr("items")[0].s("id"))
        assertEquals(1L, chef.get("/interventions?number=${a.s("number")}").obj.l("total"))
        assertEquals(1L, chef.get("/interventions?q=M2").obj.l("total"))
        assertEquals(1L, u["op"]!!.get("/interventions?mine=true").obj.l("total"))
        assertEquals(b.s("id"), chef.get("/interventions?status=EN_DIAGNOSTIC").obj.arr("items")[0].s("id"))
        assertEquals(2L, chef.get("/interventions?status=EN_DIAGNOSTIC&status=NOUVEAU").obj.l("total"))
        assertEquals(2L, chef.get("/interventions?status=EN_DIAGNOSTIC,NOUVEAU").obj.l("total"))
        assertEquals(1L, u["resp"]!!.get("/interventions?technician_id=${env.userId("tech")}").obj.l("total"))
        assertEquals(1L, chef.get("/interventions?matricule=tech").obj.l("total"))
        assertEquals(1L, chef.get("/interventions?reporter=op").obj.l("total"))
        assertEquals(1L, chef.get("/interventions?intervenant=tech").obj.l("total"))
        assertEquals(1L, chef.get("/interventions?cable_ref_id=${env.ref("cable_refs", "CBL-1")}").obj.l("total"))
        assertEquals(1L, chef.get("/interventions?poste_id=${env.ref("postes", "L1-A")}&machine_id=${env.ref("machines", "M1")}").obj.l("total"))
        val home = u["tech"]!!.get("/interventions?open_only=true").obj.arr("items")
        assertEquals("CRITIQUE", home[0].s("priority"))
        assertEquals(200, chef.get("/interventions/by-number/${a.s("number")}").status)
        assertEquals(422, chef.get("/interventions?status=FAUX").status)
        val today = java.time.LocalDate.now(ZoneOffset.UTC)
        assertEquals(2L, chef.get("/interventions?date_from=$today&date_to=${today.plusDays(2)}").obj.l("total"))
    }

    @Test fun durationsFromTimestampsIncludingVersion1Statuses() {
        val base = 1_700_000_000_000L
        fun ev(id: Long, at: Long, type: String, to: String?) = Event(mapOf(
            "id" to id, "at" to base + at * 1000, "user_id" to null, "type" to type, "from_status" to null,
            "to_status" to to, "comment" to null, "data" to null, "user_name" to null,
        ))
        val row = HashMap<String, Any?>(mapOf(
            "id" to "x", "number" to "BI-1", "client_uuid" to null, "site_id" to 1L, "line_id" to 1L, "machine_id" to 1L,
            "category_id" to 1L, "priority" to "NORMALE", "status" to "RESOLUE", "description" to "d", "reported_by_id" to 1L,
            "assigned_to_id" to 2L, "production_stopped" to 1L, "stop_start_at" to base, "stop_end_at" to null,
            "declared_at" to base, "accepted_at" to base + 300_000, "arrived_at" to null, "started_at" to base + 900_000,
            "repaired_at" to base + 2_700_000, "paused" to 0L, "recurring" to 0L, "reopen_count" to 0L, "revision" to 1L,
            "version" to 1L,
        ))
        val iv = Iv(row)
        // Historique d'un bon de la version 1 (ACCEPTE, EN_COURS, REPARE) : converti à la lecture.
        val events = listOf(
            ev(1, 0, "CREATION", "NOUVEAU"), ev(2, 300, "PRISE_EN_CHARGE", "ACCEPTE"),
            ev(3, 900, "DEMARRAGE", "EN_COURS"), ev(4, 2700, "REPARATION_TERMINEE", "REPARE"),
        )
        val d = Timing.compute(iv, events, base + 3_000_000)
        assertEquals(300L, Timing.long(d, "response_s"))
        assertEquals(300L, Timing.long(d, "waiting_takeover_s"))
        assertEquals(600L, Timing.long(d, "diagnosis_s"))
        assertEquals(1800L, Timing.long(d, "active_maintenance_s"))
        assertEquals(300L, Timing.long(d, "waiting_closure_s"))
        assertEquals(300L, Timing.long(d, "waiting_validation_s"))
        assertEquals(3000L, Timing.long(d, "production_downtime_s"))
        val per = Timing.perStatus(iv, events, base + 3_000_000)
        assertEquals(mapOf("NOUVEAU" to 300L, "EN_DIAGNOSTIC" to 600L, "INTERVENTION_MAINTENANCE" to 1800L, "RESOLUE" to 300L), per)
        assertEquals("1 h 05 min", Timing.format(3900))
        assertEquals("—", Timing.format(null))
    }

    @Test fun lateFlagFollowsPrioritySettings() {
        var now = Instant.now().toEpochMilli()
        TestEnv(clock = { now }).use { e ->
            val chef = e.api("chef")
            val iv = e.declare(chef, "category_id" to e.ref("categories", "SECU"), "description" to "Carter de sécurité arraché")
            assertEquals("false", iv.s("late"))
            now += 6 * 60_000  // CRITIQUE : 5 minutes
            assertEquals("true", chef.get("/interventions/${iv.s("id")}").obj.s("late"))
        }
    }
}
