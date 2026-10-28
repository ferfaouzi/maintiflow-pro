package com.maintiflow.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Administration : comptes (matricule, nom, prénom, lignes), configuration de l'usine, paramètres, sauvegarde. */
class AdminTest {
    private var now = 1_800_000_000_000L
    private val backupData = "PK-fausse-sauvegarde".toByteArray()
    private val env = TestEnv(clock = { now }, backup = { "maintiflow-sauvegarde.zip" to backupData })
    private val u get() = env.users
    private val admin get() = u["admin"]!!

    @After fun tearDown() = env.close()

    private fun login(matricule: String, password: String) =
        env.raw("POST", "/api/v1/auth/login", jsonOf("username" to matricule, "password" to password))

    @Test fun userWithMatriculeNamesAndLines() {
        val l2 = env.ref("lines", "L2")
        val r = admin.post("/admin/users", "matricule" to "M-1042", "first_name" to "Sami", "last_name" to "Ben Ali",
            "role" to "CHEF_LIGNE", "password" to "Initial2026x", "line_ids" to listOf(l2))
        assertEquals(r.text, 201, r.status)
        val o = r.obj
        assertEquals("m-1042", o.s("matricule"))
        assertEquals("Sami", o.s("first_name")); assertEquals("Ben Ali", o.s("last_name"))
        assertEquals("Chef de ligne", o.s("role_label"))
        assertEquals(listOf(l2), o["line_ids"]!!.jsonArray.map { it.jsonPrimitive.long })
        assertEquals("true", o.s("must_change_password"))
        // Mot de passe stocké haché, jamais en clair.
        val hash = env.sql.one("SELECT password_hash FROM users WHERE username='m-1042'")!!.str("password_hash")
        assertTrue(!hash.contains("Initial2026x") && Security.verifyPassword("Initial2026x", hash))
        assertEquals(422, admin.post("/admin/users", "matricule" to "a b", "first_name" to "x", "last_name" to "y",
            "role" to "OPERATEUR", "password" to "Initial2026x").status)
        assertEquals(422, admin.post("/admin/users", "matricule" to "m2", "first_name" to "x", "last_name" to "y",
            "role" to "INCONNU", "password" to "Initial2026x").status)
        // Ligne d'un autre site refusée.
        assertEquals(422, admin.post("/admin/users", "matricule" to "m3", "first_name" to "x", "last_name" to "y",
            "role" to "OPERATEUR", "password" to "Initial2026x", "line_ids" to listOf(env.sql.one("SELECT id FROM lines WHERE code='X1'")!!.long("id"))).status)

        val id = o.l("id")
        val p = admin.patch("/admin/users/$id", "role" to "OPERATEUR", "line_ids" to emptyList<Long>(), "first_name" to "Samir")
        assertEquals(p.text, 200, p.status)
        assertEquals("OPERATEUR", p.obj.s("role")); assertEquals("Samir Ben Ali", p.obj.s("full_name"))
        assertTrue(p.obj["line_ids"]!!.jsonArray.isEmpty())
        val audit = admin.get("/admin/audit?action=USER_UPDATED").json.jsonArray
        assertTrue(audit.isNotEmpty())
    }

    @Test fun deactivationInsteadOfDeletion() {
        // Compte jamais utilisé : suppression permise.
        val fresh = admin.post("/admin/users", "matricule" to "neuf", "first_name" to "a", "last_name" to "b",
            "role" to "QUALITE", "password" to "Initial2026x").obj
        assertEquals(204, admin.delete("/admin/users/${fresh.l("id")}").status)
        // Compte présent dans l'historique : suppression refusée, désactivation possible.
        env.declare(u["op"]!!)
        val op = env.userId("op")
        assertEquals(409, admin.delete("/admin/users/$op").status)
        assertEquals(200, admin.patch("/admin/users/$op", "active" to false).status)
        assertEquals(401, u["op"]!!.get("/auth/me").status)
        assertEquals(401, login("op", PWD).statusCode())
        assertEquals(200, admin.patch("/admin/users/$op", "active" to true).status)
        assertEquals(200, login("op", PWD).statusCode())
        // Le dernier administrateur actif ne peut être retiré.
        assertEquals(409, admin.patch("/admin/users/${admin.id}", "role" to "CHEF_EQUIPE").status)
        assertEquals(409, admin.delete("/admin/users/${admin.id}").status)
        // Réinitialisation du mot de passe : changement obligatoire à la prochaine connexion.
        assertEquals(200, admin.patch("/admin/users/${env.userId("tech")}", "reset_password" to "Reset2026xy").status)
        val o = Json.parseToJsonElement(String(login("tech", "Reset2026xy").body())).jsonObject
        assertEquals("true", o["user"]!!.jsonObject.s("must_change_password"))
    }

    @Test fun plantConfigurationCrud() {
        val line = admin.post("/admin/lines", "code" to "L7", "name" to "Ligne 7").obj
        val poste = admin.post("/admin/postes", "line_id" to line.l("id"), "code" to "L7-B", "name" to "Poste B")
        assertEquals(poste.text, 201, poste.status)
        val m = admin.post("/admin/machines", "line_id" to line.l("id"), "poste_id" to poste.obj.l("id"), "code" to "SERT-7", "name" to "Sertisseuse 7")
        assertEquals(m.text, 201, m.status)
        // Poste d'une autre ligne refusé pour une machine.
        assertEquals(422, admin.post("/admin/machines", "line_id" to env.ref("lines", "L1"), "poste_id" to poste.obj.l("id"),
            "code" to "M8", "name" to "x").status)
        assertEquals(201, admin.post("/admin/cable-refs", "code" to "CBL-77", "name" to "Faisceau 77").status)
        assertEquals(201, admin.post("/admin/tools", "code" to "APP-7", "name" to "Applicateur 7", "machine_id" to m.obj.l("id")).status)
        val cat = admin.post("/admin/categories", "code" to "ELEC", "name" to "Électrique", "default_priority" to "HAUTE", "requires_quality" to true)
        assertEquals(cat.text, 201, cat.status)
        assertEquals(201, admin.post("/admin/causes", "category_id" to cat.obj.l("id"), "name" to "Court-circuit").status)
        assertEquals(201, admin.post("/admin/intervention-types", "code" to "PREV", "name" to "Préventive").status)

        val refs = u["chef"]!!.get("/referentials").obj
        assertTrue(refs.arr("postes").any { it.s("code") == "L7-B" })
        assertTrue(refs.arr("cable_refs").any { it.s("code") == "CBL-77" })
        assertTrue(refs.arr("tools").any { it.s("code") == "APP-7" })
        assertTrue(refs.arr("causes").any { it.s("name") == "Court-circuit" })
        assertTrue(refs.arr("intervention_types").any { it.s("code") == "PREV" })
        assertEquals("true", refs.arr("categories").first { it.s("code") == "ELEC" }.s("requires_quality"))

        // Modification et désactivation.
        val upd = admin.put("/admin/machines/${m.obj.l("id")}", "line_id" to line.l("id"), "code" to "SERT-7", "name" to "Sertisseuse 7 bis", "active" to false)
        assertEquals(upd.text, 200, upd.status)
        assertTrue(u["chef"]!!.get("/referentials").obj.arr("machines").none { it.s("code") == "SERT-7" })
        // Suppression refusée si la valeur est utilisée par une panne.
        env.declare(u["chef"]!!)
        assertEquals(409, admin.delete("/admin/machines/${env.ref("machines", "M1")}").status)
        assertEquals(409, admin.delete("/admin/lines/${env.ref("lines", "L1")}").status)
        assertEquals(204, admin.delete("/admin/intervention-types/${env.sql.one("SELECT id FROM intervention_types WHERE code='PREV'")!!.long("id")}").status)
        // Isolation entre sites.
        val other = env.sql.one("SELECT id FROM lines WHERE code='X1'")!!.long("id")
        assertEquals(404, admin.put("/admin/lines/$other", "code" to "X1", "name" to "volé").status)
        assertEquals(409, admin.post("/admin/lines", "code" to "L7", "name" to "Doublon").status)
    }

    @Test fun siteAndSettings() {
        val s = admin.put("/admin/site", "code" to "USN", "name" to "Usine pilote", "timezone" to "Africa/Tunis")
        assertEquals(s.text, 200, s.status)
        assertEquals("Usine pilote", s.obj.s("name"))
        assertEquals(422, admin.put("/admin/site", "code" to "USN", "name" to "x", "timezone" to "Mars/Olympus").status)
        assertEquals(422, admin.put("/admin/settings", "session_idle_minutes" to 1000).status)
        assertEquals(200, admin.put("/admin/settings", "session_idle_minutes" to 10).status)
        val a = admin.get("/admin/audit?action=SETTINGS_UPDATED").json.jsonArray[0].jsonObject["detail"]!!.jsonObject
        assertEquals("30", a["old"]!!.jsonObject.s("session_idle_minutes"))
        assertEquals("10", a["new"]!!.jsonObject.s("session_idle_minutes"))
    }

    @Test fun autoLogoutAfterIdleTime() {
        assertEquals(200, admin.put("/admin/settings", "session_idle_minutes" to 10).status)
        val tech = env.api("tech")
        now += 9 * 60_000L
        assertEquals(200, tech.get("/auth/me").status)
        // Le sondage en arrière-plan (notifications) ne prolonge pas la session.
        now += 9 * 60_000L
        val bg = env.raw("GET", "/api/v1/notifications", headers = mapOf("Authorization" to "Bearer ${tech.token}", "X-Background" to "1"))
        assertEquals(200, bg.statusCode())
        now += 2 * 60_000L
        assertEquals(401, tech.get("/auth/me").status)
        // 0 = jamais (nouvelle session admin : la précédente a elle aussi expiré).
        assertEquals(200, env.api("admin").put("/admin/settings", "session_idle_minutes" to 0).status)
        val chef = env.api("chef")
        now += 300 * 60_000L  // bien au-delà de 30 min, en deçà de la durée de vie du jeton
        assertEquals(200, chef.get("/auth/me").status)
    }

    @Test fun backupIsAdminOnlyAndAudited() {
        val r = admin.get("/admin/backup")
        assertEquals(200, r.status)
        assertArrayEquals(backupData, r.bytes)
        assertTrue(r.header("content-disposition")!!.contains("maintiflow-sauvegarde.zip"))
        assertEquals(403, u["chef"]!!.get("/admin/backup").status)
        assertTrue(admin.get("/admin/audit?action=BACKUP_DOWNLOADED").json.jsonArray.isNotEmpty())
        TestEnv().use { e -> assertEquals(404, e.api("admin").get("/admin/backup").status) }
    }

    @Test fun machineHistory() {
        val m1 = env.ref("machines", "M1")
        env.closed()
        env.declare(u["chef"]!!)
        val h = u["chef"]!!.get("/machines/$m1/history")
        assertEquals(h.text, 200, h.status)
        assertTrue(h.text.contains("CLOTURE"))
        assertEquals(404, u["op2"]!!.get("/machines/$m1/history").status)
    }
}
