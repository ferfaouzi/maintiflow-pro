package com.maintiflow.server

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Droits d'accès : chaque rôle contre chaque action, à chaque étape du workflow, vérifiés côté serveur. */
class PermissionTest {
    private val env = TestEnv()
    private val u get() = env.users

    @After fun tearDown() = env.close()

    private fun allowed(who: String, iv: JsonObject): Set<String> =
        u[who]!!.get("/interventions/${iv.s("id")}").obj["allowed_actions"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()

    /** Tente l'action avec un corps valide ; renvoie le code HTTP sans rien modifier si refusé. */
    private fun tryAct(who: String, iv: JsonObject, action: String): Int {
        val body = when (action) {
            "start" -> arrayOf<Pair<String, Any?>>("diagnosis" to "Diagnostic test")
            "finish" -> env.YELLOW
            "assign" -> arrayOf("technician_id" to env.userId("tech"))
            "transfer" -> arrayOf("service" to "METHODE", "comment" to "Réglage process")
            "wait_part" -> arrayOf("reference" to "REF-1")
            else -> arrayOf("comment" to "Motif de test suffisant")
        }
        return u[who]!!.post("/interventions/${iv.s("id")}/actions/$action", *body).status
    }

    @Test fun declarationByRole() {
        for (who in listOf("op", "cl", "chef", "tech", "meth", "qual", "resp")) {
            env.declare(u[who]!!)
        }
        for (who in listOf("logi", "admin")) {
            val r = u[who]!!.post("/interventions", "machine_id" to env.ref("machines", "M1"),
                "category_id" to env.ref("categories", "MECA"), "description" to "Essai interdit")
            assertEquals(who, 403, r.status)
        }
        // Opérateur de la ligne 1 : machine de la ligne 2 refusée.
        val r = u["op"]!!.post("/interventions", "machine_id" to env.ref("machines", "M3"),
            "category_id" to env.ref("categories", "MECA"), "description" to "Hors de ma ligne")
        assertEquals(r.text, 403, r.status)
        env.declare(u["op2"]!!, "machine_id" to env.ref("machines", "M3"))
    }

    @Test fun onlyAllowedRolesActAtEachStep() {
        val roles = listOf("op", "cl", "chef", "tech", "tech2", "meth", "qual", "logi", "resp", "admin")
        var iv = env.declare(u["op"]!!)
        // Nouvelle panne : seuls les intervenants prennent en charge, seuls chefs/responsable affectent.
        for (who in roles) {
            val a = runCatching { allowed(who, iv) }.getOrDefault(emptySet())
            assertEquals(who, who in setOf("tech", "tech2", "meth"), "accept" in a)
            assertEquals(who, who in setOf("cl", "chef", "resp"), "assign" in a)
            assertFalse(who, "close" in a || "finish" in a)
        }
        for (who in listOf("op", "chef", "qual", "resp")) assertEquals(who, 403, tryAct(who, iv, "accept"))
        assertEquals(403, tryAct("admin", iv, "accept"))
        assertEquals(403, tryAct("op", iv, "assign"))
        assertEquals(403, tryAct("tech", iv, "assign"))
        assertEquals(403, tryAct("op", iv, "close"))

        iv = u["tech"]!!.act(iv, "accept")
        assertEquals("EN_DIAGNOSTIC", iv.s("status"))
        // Seul le maintenancier affecté poursuit.
        assertEquals(403, tryAct("tech2", iv, "start"))
        assertEquals(403, tryAct("meth", iv, "start"))
        assertEquals(403, tryAct("chef", iv, "start"))
        iv = u["tech"]!!.act(iv, "start", 200, "diagnosis" to "Capteur HS")
        assertEquals("INTERVENTION_MAINTENANCE", iv.s("status"))
        for (who in listOf("op", "cl", "chef", "tech2", "qual", "resp")) assertEquals(who, 403, tryAct(who, iv, "finish"))
        // Clôture impossible tant que la panne n'est pas résolue, même pour un chef.
        assertEquals(409, tryAct("chef", iv, "close"))

        iv = u["tech"]!!.act(iv, "finish", 200, *env.YELLOW)
        assertEquals("RESOLUE", iv.s("status"))
        for (who in listOf("op", "tech", "tech2", "meth", "qual", "logi")) assertEquals(who, if (who == "logi") 404 else 403, tryAct(who, iv, "close"))
        assertTrue("close" in allowed("chef", iv))
        assertTrue("close" in allowed("cl", iv))
        u["cl"]!!.act(iv, "close", 200, "comment" to "Production relancée")
    }

    @Test fun lineScopeLimitsVisibility() {
        val l1 = env.declare(u["chef"]!!)
        val l2 = env.declare(u["chef"]!!, "machine_id" to env.ref("machines", "M3"))
        fun numbers(who: String) = u[who]!!.get("/interventions").obj.arr("items").map { it.s("number") }.toSet()
        assertEquals(setOf(l1.s("number")), numbers("op"))
        assertEquals(setOf(l1.s("number")), numbers("cl"))
        assertEquals(setOf(l2.s("number")), numbers("op2"))
        assertEquals(setOf(l1.s("number"), l2.s("number")), numbers("chef"))
        assertEquals(404, u["op"]!!.get("/interventions/${l2.s("id")}").status)
        assertEquals(404, u["op2"]!!.get("/interventions/${l1.s("id")}/pdf").status)
        // Référentiels limités aux lignes de l'utilisateur.
        assertEquals(listOf("L1"), u["op"]!!.get("/referentials").obj.arr("lines").map { it.s("code") })
        // Logistique : rien tant qu'aucune pièce n'est demandée.
        assertTrue(numbers("logi").isEmpty())
    }

    @Test fun adminConfiguresButNeverActsOnFaults() {
        val iv = env.declare(u["chef"]!!)
        assertTrue(allowed("admin", iv).isEmpty())
        for (a in listOf("accept", "assign", "cancel", "close")) {
            assertEquals(a, 403, tryAct("admin", iv, a))
        }
        assertEquals(403, u["admin"]!!.patch("/interventions/${iv.s("id")}", "version" to iv.l("version"), "title" to "x").status)
    }

    @Test fun adminEndpointsAreAdminOnly() {
        val paths = listOf("users", "lines", "postes", "machines", "cable-refs", "tools", "categories", "causes",
            "intervention-types", "site", "audit")
        for (who in listOf("op", "cl", "chef", "tech", "meth", "qual", "logi", "resp")) {
            for (p in paths) assertEquals("$who $p", 403, u[who]!!.get("/admin/$p").status)
            assertEquals(403, u[who]!!.post("/admin/lines", "code" to "LX", "name" to "x").status)
            assertEquals(403, u[who]!!.post("/admin/users", "matricule" to "x1", "first_name" to "a", "last_name" to "b",
                "role" to "ADMIN", "password" to "Admin2026x").status)
            assertEquals(403, u[who]!!.put("/admin/settings", "session_idle_minutes" to 0).status)
            assertEquals(403, u[who]!!.get("/admin/backup").status)
        }
        for (p in paths) assertEquals(p, 200, u["admin"]!!.get("/admin/$p").status)
    }

    @Test fun dashboardForbiddenToOperators() {
        assertEquals(403, u["op"]!!.get("/dashboard").status)
        assertEquals(403, u["op"]!!.get("/dashboard/export.xlsx").status)
        for (who in listOf("cl", "chef", "tech", "meth", "qual", "logi", "resp", "admin")) {
            assertEquals(who, 200, u[who]!!.get("/dashboard").status)
        }
    }
}
