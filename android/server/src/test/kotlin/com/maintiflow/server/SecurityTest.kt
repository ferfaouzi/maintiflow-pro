package com.maintiflow.server

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** Portage de backend/tests/test_security.py et test_pdf.py, plus les règles propres au téléphone maître. */
class SecurityTest {
    private val env = TestEnv()
    private val u get() = env.users

    @After fun tearDown() = env.close()

    private fun newTicket(api: Api, vararg kw: Pair<String, Any?>): JsonObject = env.declare(api, *kw)

    /** Panne complète : pièce consommée pendant l'intervention, fiche jaune remplie, clôture par le chef d'équipe. */
    private fun closed(): JsonObject {
        val t = u["tech"]!!
        var iv = newTicket(u["chef"]!!, "production_stopped" to true)
        iv = t.act(iv, "accept")
        iv = t.act(iv, "start", 200, "diagnosis" to "Vérin HS")
        val r = t.post("/interventions/${iv.s("id")}/parts", "reference" to "VER-32",
            "designation" to "Joint de vérin Ø32", "quantity" to 2)
        assertEquals(r.text, 201, r.status)
        iv = t.act(r.obj, "finish", 200, *env.YELLOW)
        return u["chef"]!!.act(iv, "close", 200, "comment" to "Production relancée, OK", "signature_png_base64" to SIG)
    }

    @Test fun requiresAuthentication() {
        assertEquals(401, env.raw("GET", "/api/v1/interventions").statusCode())
        assertEquals(401, env.raw("GET", "/api/v1/interventions", headers = mapOf("Authorization" to "Bearer abc")).statusCode())
        val health = env.raw("GET", "/health")
        assertEquals(200, health.statusCode())
        assertTrue(String(health.body()).contains("\"status\":\"ok\""))
        assertEquals(404, env.raw("GET", "/api/v1/inconnu").statusCode())
    }

    @Test fun rolePermissions() {
        for (who in listOf("logi", "admin")) {
            assertEquals(403, u[who]!!.post("/interventions", "machine_id" to env.id("machines", 0),
                "category_id" to env.id("categories", 0), "description" to "abc").status)
        }
        var iv = newTicket(u["chef"]!!)
        u["chef"]!!.act(iv, "accept", 403)
        u["admin"]!!.act(iv, "accept", 403)
        u["resp"]!!.act(iv, "accept", 403)
        u["qual"]!!.act(iv, "accept", 403)
        u["logi"]!!.act(iv, "accept", 404)  // la Logistique ne voit que les pannes avec demande de pièces
        iv = u["tech"]!!.act(iv, "accept")
        u["tech2"]!!.act(iv, "start", 403, "diagnosis" to "x")
        u["tech"]!!.act(iv, "assign", 403, "technician_id" to u["tech2"]!!.id)
        for (who in listOf("chef", "tech", "resp")) {
            assertEquals(403, u[who]!!.get("/admin/users").status)
            assertEquals(403, u[who]!!.get("/admin/audit").status)
            assertEquals(403, u[who]!!.put("/admin/settings", "require_manager_validation" to true).status)
        }
        assertEquals(200, u["admin"]!!.get("/admin/users").status)
    }

    @Test fun siteIsolation() {
        val iv = newTicket(u["chef"]!!)
        assertEquals(404, u["chefo"]!!.get("/interventions/${iv.s("id")}").status)
        assertEquals(404, u["chefo"]!!.get("/interventions/${iv.s("id")}/pdf").status)
        assertEquals(0L, u["chefo"]!!.get("/interventions").obj.l("total"))
        val other = u["chefo"]!!.get("/referentials").obj.arr("machines")[0].l("id")
        assertEquals(422, u["chef"]!!.post("/interventions", "machine_id" to other,
            "category_id" to env.id("categories", 0), "description" to "abc").status)
    }

    @Test fun logoutRevokesSession() {
        val api = env.api("tech")
        assertEquals(200, api.get("/auth/me").status)
        assertEquals(204, api.post("/auth/logout").status)
        assertEquals(401, api.get("/auth/me").status)
    }

    @Test fun bruteforceLockout() {
        val admin = u["admin"]!!  // sessions ouvertes avant le verrouillage, comme les fixtures pytest
        repeat(5) {
            assertEquals(401, env.raw("POST", "/api/v1/auth/login", jsonOf("username" to "tech2", "password" to "mauvais1")).statusCode())
        }
        assertEquals(423, env.raw("POST", "/api/v1/auth/login", jsonOf("username" to "tech2", "password" to PWD)).statusCode())
        assertEquals(401, env.raw("POST", "/api/v1/auth/login", jsonOf("username" to "inconnu", "password" to "x")).statusCode())
        val audit = admin.get("/admin/audit?action=LOGIN_FAILED").json.jsonArray
        assertTrue(audit.size >= 6)
    }

    @Test fun adminUserLifecycleAndForcedPasswordChange() {
        val admin = u["admin"]!!
        val r = admin.post("/admin/users", "username" to "Nouveau.Tech", "full_name" to "Nouveau", "role" to "MAINTENANCIER", "password" to "Initial123")
        assertEquals(r.text, 201, r.status); assertEquals("nouveau.tech", r.obj.s("username"))
        assertEquals(409, admin.post("/admin/users", "username" to "nouveau.tech", "full_name" to "x", "role" to "MAINTENANCIER", "password" to "Initial123").status)
        assertEquals(422, admin.post("/admin/users", "username" to "faible", "full_name" to "x", "role" to "MAINTENANCIER", "password" to "12345678").status)
        val login = env.raw("POST", "/api/v1/auth/login", jsonOf("username" to "nouveau.tech", "password" to "Initial123"))
        val o = kotlinx.serialization.json.Json.parseToJsonElement(String(login.body())).jsonObject
        val h = mapOf("Authorization" to "Bearer ${o.s("access_token")}")
        assertEquals("true", o["user"]!!.jsonObject.s("must_change_password"))
        assertEquals(403, env.raw("GET", "/api/v1/interventions", headers = h).statusCode())
        assertEquals(204, env.raw("POST", "/api/v1/auth/change-password",
            jsonOf("current_password" to "Initial123", "new_password" to "Nouveau456"), h).statusCode())
        assertEquals(200, env.raw("GET", "/api/v1/interventions", headers = h).statusCode())
        val uid = o["user"]!!.jsonObject.l("id")
        assertEquals(200, admin.patch("/admin/users/$uid", "active" to false).status)
        assertEquals(401, env.raw("GET", "/api/v1/auth/me", headers = h).statusCode())
        assertEquals(409, admin.patch("/admin/users/${admin.id}", "active" to false).status)
    }

    @Test fun adminReferentials() {
        val admin = u["admin"]!!
        val line = admin.post("/admin/lines", "code" to "L9", "name" to "Ligne 9")
        assertEquals(201, line.status)
        assertEquals(409, admin.post("/admin/lines", "code" to "L9", "name" to "Doublon").status)
        val m = admin.post("/admin/machines", "line_id" to line.obj.l("id"), "code" to "M9", "name" to "Robot")
        assertEquals(m.text, 201, m.status)
        assertEquals(36, m.obj.s("qr_token")!!.length)
        assertEquals(422, admin.post("/admin/machines", "line_id" to 2, "code" to "M9", "name" to "Autre site").status)
        val c = admin.post("/admin/categories", "code" to "HYD", "name" to "Hydraulique", "default_priority" to "HAUTE")
        assertEquals(201, c.status)
        assertEquals(422, admin.post("/admin/categories", "code" to "X", "name" to "x", "default_priority" to "URGENT").status)
        assertTrue(u["chef"]!!.get("/referentials").obj.arr("machines").any { it.s("code") == "M9" })
        assertEquals(422, admin.put("/admin/settings", "inconnu" to 1).status)
        assertEquals(422, admin.put("/admin/settings", "require_manager_validation" to "oui").status)
        val s = admin.put("/admin/settings", "company_display_name" to "Atelier pilote")
        assertEquals("Atelier pilote", s.obj["values"]!!.jsonObject.s("company_display_name"))
        assertEquals(200, u["chef"]!!.get("/admin/settings").status)
    }

    @Test fun auditTrailRecordsActions() {
        val iv = newTicket(u["chef"]!!)
        u["tech"]!!.act(iv, "accept")
        u["chef"]!!.get("/interventions/${iv.s("id")}/pdf")
        val actions = u["admin"]!!.get("/admin/audit?entity_id=${iv.s("number")}").json.jsonArray.map { it.jsonObject.s("action") }
        assertTrue(actions.toString(), actions.containsAll(listOf("INTERVENTION_CREATED", "ACTION_ACCEPT", "PDF_EXPORTED")))
    }

    @Test fun databaseForbidsSilentDeletionAndClosedEdits() {
        val iv = closed()
        val id = iv.s("id")
        for (stmt in listOf(
            "DELETE FROM interventions WHERE id = '$id'",
            "UPDATE interventions SET description = 'falsifié' WHERE id = '$id'",
            "DELETE FROM intervention_events WHERE intervention_id = '$id'",
            "DELETE FROM audit_log", "UPDATE audit_log SET action = 'X'",
            "DELETE FROM parts_used WHERE intervention_id = '$id'",
            "UPDATE interventions SET archived_pdf_path = 'autre.pdf' WHERE id = '$id'",
            "DELETE FROM signatures",
        )) {
            try {
                env.sql.exec(stmt)
                fail("accepté : $stmt")
            } catch (_: SqlConstraintException) {
            }
        }
        assertNotEquals("falsifié", env.sql.one("SELECT description FROM interventions WHERE id=?", id)!!.str("description"))
        val cancelled = u["chef"]!!.act(newTicket(u["chef"]!!), "cancel", 200, "comment" to "Erreur de saisie")
        try {
            env.sql.exec("UPDATE interventions SET status='NOUVEAU' WHERE id=?", cancelled.s("id"))
            fail("réactivation acceptée")
        } catch (_: SqlConstraintException) {
        }
    }

    @Test fun photoUploadRulesOverRealMultipart() {
        val iv = newTicket(u["chef"]!!)
        val url = "/interventions/${iv.s("id")}/attachments"
        val png = tinyPng()
        var (body, ct) = multipartBody(mapOf("kind" to "PROBLEME"), Triple("a.png", "image/png", png))
        val r = u["chef"]!!.multipart(url, body, ct)
        assertEquals(r.text, 201, r.status)
        val att = r.obj.arr("attachments")[0]
        multipartBody(emptyMap(), Triple("a.exe", "application/octet-stream", "MZ...".toByteArray())).let { (b, c) ->
            assertEquals(415, u["chef"]!!.multipart(url, b, c).status)
        }
        multipartBody(emptyMap(), Triple("a.png", "image/png", "pas une image".toByteArray())).let { (b, c) ->
            assertEquals(415, u["chef"]!!.multipart(url, b, c).status)
        }
        multipartBody(mapOf("kind" to "APRES"), Triple("a.png", "image/png", png)).let { (b, c) ->
            assertEquals(403, u["chef"]!!.multipart(url, b, c).status)
        }
        multipartBody(mapOf("kind" to "AVANT"), Triple("a.png", "image/png", png)).let { (b, c) ->
            assertEquals(403, u["tech"]!!.multipart(url, b, c).status)
        }
        val dl = u["tech"]!!.get("/attachments/${att.s("id")}")
        assertEquals(200, dl.status); assertArrayEquals(png, dl.bytes)
        assertEquals(404, u["chefo"]!!.get("/attachments/${att.s("id")}").status)
        // Comme l'application : photo JPEG avec un champ texte « kind »
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)
        multipartBody(mapOf("kind" to "PROBLEME"), Triple("photo.jpg", "image/jpeg", jpeg)).let { (b, c) ->
            assertEquals(201, u["chef"]!!.multipart(url, b, c).status)
        }
    }

    @Test fun provisionalPdfHasTwoIdenticalCopies() {
        val iv = newTicket(u["chef"]!!, "description" to "Fuite d'air sur vérin – éjecteur bloqué")
        val r = u["chef"]!!.get("/interventions/${iv.s("id")}/pdf")
        assertEquals(200, r.status)
        assertEquals("application/pdf", r.header("content-type"))
        assertEquals("provisoire", r.header("x-document-kind"))
        val text = r.text
        assertTrue(text.contains("PAGE ROSE") && text.contains("PAGE JAUNE"))
        assertTrue(text.split(iv.s("number")!!).size - 1 >= 2)
        assertTrue(text.contains("PROVISOIRE"))
        assertTrue(text.contains("éjecteur bloqué"))
        assertTrue(text.split(iv.s("content_sha256")!!).size - 1 >= 2)
    }

    @Test fun closedPdfIsArchivedAndImmutable() {
        val iv = closed()
        val r1 = u["chef"]!!.get("/interventions/${iv.s("id")}/pdf")
        val r2 = u["resp"]!!.get("/interventions/${iv.s("id")}/pdf")
        assertEquals("archive", r1.header("x-document-kind"))
        assertArrayEquals(r1.bytes, r2.bytes)
        assertFalse(r1.text.contains("PROVISOIRE"))
        assertTrue(r1.text.contains("Joint de vérin") && r1.text.contains("Clôturée"))
        val path = env.sql.one("SELECT archived_pdf_path FROM interventions WHERE id=?", iv.s("id"))!!.str("archived_pdf_path")
        File(env.storage, path).appendBytes("tampered".toByteArray())
        assertEquals(500, u["chef"]!!.get("/interventions/${iv.s("id")}/pdf").status)
    }

    @Test fun pdfRenderFailureDoesNotBlockClosure() {
        TestEnv(pdf = { error("rendu impossible") }).use { e ->
            val chef = e.api("chef"); val tech = e.api("tech")
            var iv = chef.post("/interventions", "machine_id" to e.id("machines", 0), "category_id" to e.id("categories", 0),
                "description" to "Capteur de porte HS").obj
            iv = tech.act(iv, "accept"); iv = tech.act(iv, "start", 200, "diagnosis" to "Capteur HS")
            iv = tech.act(iv, "finish", 200, *e.YELLOW)
            iv = chef.act(iv, "close", 200, "comment" to "Machine relancée")
            assertEquals("CLOTURE", iv.s("status"))
            assertEquals(500, chef.get("/interventions/${iv.s("id")}/pdf").status)
        }
    }

    @Test fun seedCreatesFictionalDemoAndAdminPasswordReset() {
        val sql = JdbcSql()
        assertFalse(Seed.isInitialized(sql))
        try {
            Seed.initialize(sql, "12345678", null, withDemo = true, iterations = 1000, now = 0)
            fail("mot de passe faible accepté")
        } catch (e: ApiException) {
            assertEquals(422, e.status)
        }
        Seed.initialize(sql, "Demo2026x", null, withDemo = true, iterations = 1000, now = 0)
        assertTrue(Seed.isInitialized(sql))
        assertEquals(12L, sql.one("SELECT COUNT(*) AS n FROM users")!!.long("n"))
        assertEquals(10L, sql.one("SELECT COUNT(*) AS n FROM machines")!!.long("n"))
        assertTrue(sql.query("SELECT full_name FROM users WHERE role<>'ADMIN'").all { it.str("full_name").contains("fictif") })
        try {
            Seed.initialize(sql, "Demo2026x", null, withDemo = false, iterations = 1000, now = 0)
            fail("double initialisation")
        } catch (e: ApiException) {
            assertEquals(409, e.status)
        }
        Seed.resetAdminPassword(sql, "Nouveau2026", 1000, 0)
        val hash = sql.one("SELECT password_hash FROM users WHERE username='admin'")!!.str("password_hash")
        assertTrue(Security.verifyPassword("Nouveau2026", hash))
        assertFalse(Security.verifyPassword("Demo2026x", hash))

        val empty = JdbcSql()
        Seed.initialize(empty, "Admin2026x", "Atelier Faouzi", withDemo = false, iterations = 1000, now = 0)
        assertEquals(1L, empty.one("SELECT COUNT(*) AS n FROM users")!!.long("n"))
        assertEquals("Atelier Faouzi", SettingsStore(empty).get(1, "company_display_name").let {
            (it as kotlinx.serialization.json.JsonPrimitive).content
        })
    }

    @Test fun httpParserHandlesChunkedBodyAndBadRequests() {
        val sock = java.net.Socket("127.0.0.1", env.base.substringAfterLast(':').toInt())
        sock.use { s ->
            val body = jsonOf("username" to "chef", "password" to PWD)
            val chunked = "${Integer.toHexString(body.toByteArray().size)}\r\n$body\r\n0\r\n\r\n"
            s.getOutputStream().write(("POST /api/v1/auth/login HTTP/1.1\r\nHost: x\r\nContent-Type: application/json\r\n" +
                "Transfer-Encoding: chunked\r\n\r\n$chunked").toByteArray())
            val resp = s.getInputStream().readBytes().toString(Charsets.UTF_8)
            assertTrue(resp, resp.startsWith("HTTP/1.1 200"))
            assertTrue(resp.contains("access_token"))
        }
        val bad = env.raw("POST", "/api/v1/auth/login", "{pas du json")
        assertEquals(422, bad.statusCode())
        assertTrue(String(bad.body()).contains("detail"))
    }
}
