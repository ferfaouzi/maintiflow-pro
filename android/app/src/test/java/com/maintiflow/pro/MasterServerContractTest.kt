package com.maintiflow.pro

import com.maintiflow.pro.data.api.*
import com.maintiflow.server.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.File
import java.time.Instant
import java.util.UUID

/** Le client Retrofit de l'application (mêmes fichiers) contre le serveur du téléphone maître. */
class MasterServerContractTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true; coerceInputValues = true }

    private fun api(base: String, token: () -> String?): MaintiFlowApi {
        val http = OkHttpClient.Builder().addInterceptor { c ->
            val t = token()
            c.proceed(if (t != null) c.request().newBuilder().header("Authorization", "Bearer $t").build() else c.request())
        }.build()
        return Retrofit.Builder().baseUrl("$base/api/v1/").client(http)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType())).build()
            .create(MaintiFlowApi::class.java)
    }

    private val pdf = PdfRenderer { ("%PDF-1.4\n" + PdfBuilder.toText(it)).toByteArray() }

    @Test fun androidClientAgainstMasterServer() = runBlocking {
        val sql = JdbcSql()
        Seed.initialize(sql, "Demo2026x", null, withDemo = true, iterations = 1000, now = System.currentTimeMillis())
        val dir = kotlin.io.path.createTempDirectory().toFile()
        val server = MaintiFlowServer(sql, ServerConfig(dir, pdf, passwordIterations = 1000))
        val http = HttpServer(0, null, handler = server::handle).also { it.start() }
        val base = "http://127.0.0.1:${http.localPort}"
        try {
            val tokens = HashMap<String, String>()
            fun as_(u: String) = api(base) { tokens[u] }
            for (u in listOf("chef.l1", "tech.1", "resp.maint", "admin")) {
                val res = as_(u).login(LoginRequest(u, "Demo2026x", "Pixel test"))
                tokens[u] = res.accessToken
                assertEquals(u, res.user.username)
                Instant.parse(res.expiresAt)
            }
            val chef = as_("chef.l1"); val tech = as_("tech.1"); val resp = as_("resp.maint"); val admin = as_("admin")
            try { api(base) { null }.login(LoginRequest("chef.l1", "faux")); fail() } catch (e: HttpException) { assertEquals(401, e.code()) }

            val refs = chef.referentials()
            assertEquals(3, refs.lines.size); assertEquals(10, refs.machines.size); assertEquals(8, refs.categories.size)
            assertEquals(3, refs.technicians.size)  // 2 maintenanciers + 1 Méthode (fictifs)
            assertEquals("chef.l1", chef.me().username)

            val now = Instant.now().toString()
            var t = chef.create(CreateTicketRequest(UUID.randomUUID().toString(), refs.machines[0].id, refs.categories[0].id,
                "Bourrage en sortie de presse", null, true, now, now))
            assertEquals("NOUVEAU", t.status); assertTrue(t.number.startsWith("BI-"))
            assertTrue("cancel" in t.allowedActions)
            assertEquals(1, chef.search(openOnly = true, mine = true, limit = 100).total)
            assertEquals(1, tech.search(q = t.number, status = listOf("NOUVEAU", "EN_ATTENTE"), limit = 100).total)

            // Photo exactement comme Repository.uploadPhoto
            val photo = File(dir, "photo.jpg").apply { writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 1, 2)) }
            val part = MultipartBody.Part.createFormData("file", photo.name, photo.asRequestBody("image/jpeg".toMediaType()))
            t = chef.upload(t.id, "PROBLEME".toRequestBody("text/plain".toMediaType()), part)
            assertEquals(1, t.attachments.size)

            t = chef.patch(t.id, PatchTicketRequest(version = t.version, description = "Bourrage + bruit anormal"))
            assertEquals("Bourrage + bruit anormal", t.description)

            t = resp.action(t.id, "assign", ActionRequest(version = t.version, technicianId = refs.technicians.first { it.username == "tech.1" }.id, priority = "HAUTE"))
            t = tech.action(t.id, "accept", ActionRequest(version = t.version))
            assertEquals("EN_DIAGNOSTIC", t.status)
            // Version 2 : diagnostic obligatoire pour démarrer (dialogue « DÉMARRER » de l'application).
            try { tech.action(t.id, "start", ActionRequest(version = t.version)); fail() } catch (e: HttpException) { assertEquals(422, e.code()) }
            t = tech.action(t.id, "start", ActionRequest(version = t.version, diagnosis = "Capteur HS"))
            assertEquals("INTERVENTION_MAINTENANCE", t.status)
            t = tech.patch(t.id, PatchTicketRequest(version = t.version, diagnosis = "Capteur HS", observations = "RAS"))
            t = tech.addPart(t.id, PartRequest("IG5953", "Capteur inductif", 2.0))
            assertEquals("2.00", t.parts[0].quantity)
            t = tech.addPart(t.id, PartRequest("X", "Erreur", 1.0))
            t = tech.deletePart(t.id, t.parts[1].id)
            assertEquals(1, t.parts.size)
            // « TERMINER » sans la page jaune complète : refusé.
            try { tech.action(t.id, "finish", ActionRequest(version = t.version)); fail() } catch (e: HttpException) { assertEquals(422, e.code()) }
            t = tech.patch(t.id, PatchTicketRequest(version = t.version, rootCause = "Câble écrasé", correctiveAction = "Remplacement",
                machineFinalState = "OPERATIONNELLE"))
            // Comme le dialogue « TERMINER » : rapport enregistré repris, résultat des tests saisi.
            t = tech.action(t.id, "finish", ActionRequest(version = t.version, testResult = "20 pièces conformes", diagnosis = t.diagnosis,
                rootCause = t.rootCause, correctiveAction = t.correctiveAction, machineFinalState = t.machineFinalState))
            assertTrue(t.status, t.status == "RESOLUE" || t.status == "EN_ATTENTE_QUALITE")
            assertEquals("20 pièces conformes", t.testResult)
            if (t.status == "EN_ATTENTE_QUALITE") {
                tokens["qualite.1"] = as_("qualite.1").login(LoginRequest("qualite.1", "Demo2026x")).accessToken
                t = as_("qualite.1").action(t.id, "quality_validate", ActionRequest(version = t.version))
            }
            assertTrue(chef.pdf(t.id).bytes().toString(Charsets.UTF_8).contains("PROVISOIRE"))
            // Clôture : justification obligatoire.
            try { chef.action(t.id, "close", ActionRequest(version = t.version)); fail() } catch (e: HttpException) { assertEquals(422, e.code()) }
            val sig = java.util.Base64.getEncoder().encodeToString(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2))
            t = chef.action(t.id, "close", ActionRequest(version = t.version, signaturePngBase64 = sig, comment = "Production relancée OK"))
            assertEquals("CLOTURE", t.status); assertTrue(t.locked)
            assertTrue(t.durations.response != null && !t.durations.running)
            assertTrue(t.events.any { it.type == "SIGNATURE" && it.data != null })
            val closedPdf = chef.pdf(t.id).bytes().toString(Charsets.UTF_8)
            assertFalse(closedPdf.contains("PROVISOIRE")); assertTrue(closedPdf.contains("Clôturée"))
            assertTrue(closedPdf.contains("PAGE ROSE") && closedPdf.contains("PAGE JAUNE"))
            assertTrue(tech.similar(t.id).disclaimer.isNotEmpty())
            try { chef.action(t.id, "cancel", ActionRequest(version = t.version, comment = "trop tard")); fail() } catch (e: HttpException) { assertEquals(409, e.code()) }

            // Administration
            assertEquals(false, (admin.settings().values["require_manager_validation"] as JsonPrimitive).content.toBoolean())
            admin.putSettings(mapOf("require_manager_validation" to JsonPrimitive(true)))
            val line = admin.createLine(LineRequest("L4", "Ligne 4"))
            admin.createMachine(MachineRequest(line.id, "L4-M1", "Robot"))
            admin.createCategory(CategoryRequest("HYD", "Hydraulique", "HAUTE"))
            assertEquals(4, admin.lines().size); assertEquals(11, admin.machines().size); assertEquals(9, admin.categories().size)
            val nu = admin.createUser(UserCreateRequest("chef.l3", "Chef L3 (fictif)", "CHEF_EQUIPE", "Initial123"))
            // Rôle de la version 1 envoyé par une ancienne application : compris comme Maintenancier.
            assertEquals("MAINTENANCIER", admin.createUser(UserCreateRequest("tech.v1", "Tech v1 (fictif)", "TECHNICIEN", "Initial123")).role)
            assertTrue(nu.mustChangePassword)
            admin.updateUser(nu.id, UserUpdateRequest(resetPassword = "Reset2026x"))
            assertEquals(14, admin.users().size)

            val newChef = api(base) { tokens["chef.l3"] }
            tokens["chef.l3"] = newChef.login(LoginRequest("chef.l3", "Reset2026x")).accessToken
            try { newChef.referentials(); fail() } catch (e: HttpException) { assertEquals(403, e.code()) }
            assertTrue(newChef.changePassword(ChangePasswordRequest("Reset2026x", "Final2026x")).isSuccessful)
            assertFalse(newChef.me().mustChangePassword)
            assertTrue(newChef.logout().isSuccessful)
            try { newChef.me(); fail() } catch (e: HttpException) { assertEquals(401, e.code()) }

            // Image de pièce jointe (chargée par Coil avec le même jeton)
            val ok = OkHttpClient().newCall(okhttp3.Request.Builder().url("$base/api/v1/attachments/${t.attachments[0].id}")
                .header("Authorization", "Bearer ${tokens["tech.1"]}").build()).execute()
            assertEquals(200, ok.code); assertEquals("image/jpeg", ok.header("Content-Type"))
            val health = OkHttpClient().newCall(okhttp3.Request.Builder().url("$base/health").build()).execute()
            assertTrue(health.body!!.string().contains("telephone-maitre"))
        } finally {
            http.stop()
        }
    }
}
