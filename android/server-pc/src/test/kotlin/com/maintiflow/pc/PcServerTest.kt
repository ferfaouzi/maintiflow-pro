package com.maintiflow.pc

import com.maintiflow.server.Backup
import com.maintiflow.server.HttpServer
import com.maintiflow.server.MaintiFlowServer
import com.maintiflow.server.Seed
import com.maintiflow.server.long
import com.maintiflow.server.ServerConfig
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files

/** Serveur PC réel : base dans un fichier, PDF PDFBox, sauvegarde et restauration. */
class PcServerTest {
    private val dir = Files.createTempDirectory("mf-pc-").toFile()
    private val sql = FileSql(File(dir, Backup.DB_ENTRY))
    private val storage = File(dir, "storage").apply { mkdirs() }

    @After fun tearDown() { sql.close(); dir.deleteRecursively() }

    private val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

    private fun call(base: String, method: String, path: String, token: String?, body: String? = null): HttpResponse<ByteArray> {
        val b = HttpRequest.newBuilder(URI.create(base + path))
        token?.let { b.header("Authorization", "Bearer $it") }
        if (body != null) b.header("Content-Type", "application/json")
        b.method(method, body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody())
        return client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray())
    }

    private fun field(json: String, key: String) = Regex("\"$key\":\"([^\"]+)\"").find(json)!!.groupValues[1]

    @Test fun fullCycleWithRealPdfAndBackup() {
        Seed.initialize(sql, "Demo2026admin", "Atelier fictif", withDemo = true, iterations = 1000, now = System.currentTimeMillis())
        val server = MaintiFlowServer(sql, ServerConfig(storage, PdfBoxRenderer(), passwordIterations = 1000, mode = "pc",
            backup = {
                val snap = File(dir, "snap.db"); sql.snapshot(snap)
                val out = ByteArrayOutputStream(); Backup.write(snap, storage, "test", 0, out); snap.delete()
                "s.zip" to out.toByteArray()
            }))
        val http = HttpServer(0, java.net.InetAddress.getLoopbackAddress(), handler = server::handle).also { it.start() }
        try {
            val base = "http://127.0.0.1:${http.localPort}/api/v1"
            fun login(u: String) = field(String(call(base, "POST", "/auth/login", null,
                """{"username":"$u","password":"Demo2026admin"}""").body()), "access_token")
            val op = login("op.l1"); val tech = login("tech.1"); val chef = login("chef.l1"); val admin = login("admin")
            val refs = String(call(base, "GET", "/referentials", op).body())
            val machine = Regex("\"machines\":\\[\\{\"id\":(\\d+)").find(refs)!!.groupValues[1]
            val category = Regex("\"categories\":\\[\\{\"id\":(\\d+)").find(refs)!!.groupValues[1]
            val created = call(base, "POST", "/interventions", op,
                """{"machine_id":$machine,"category_id":$category,"title":"Bourrage → éjecteur Ø32","description":"Câble coincé à la sortie"}""")
            assertEquals(String(created.body()), 201, created.statusCode())
            val id = field(String(created.body()), "id")
            for ((who, action, body) in listOf(
                Triple(tech, "accept", "{}"), Triple(tech, "start", """{"diagnosis":"Guide desserré"}"""),
                Triple(tech, "finish", """{"root_cause":"Vibration","corrective_action":"Resserrage","test_result":"30 pièces OK","machine_final_state":"OPERATIONNELLE"}"""),
            )) {
                val r = call(base, "POST", "/interventions/$id/actions/$action", who, body)
                assertEquals("$action ${String(r.body())}", 200, r.statusCode())
            }
            val st = String(call(base, "GET", "/interventions/$id", chef).body())
            if (st.contains("\"status\":\"EN_ATTENTE_QUALITE\"")) {
                assertEquals(200, call(base, "POST", "/interventions/$id/actions/quality_validate", login("qualite.1"), "{}").statusCode())
            }
            assertEquals(200, call(base, "POST", "/interventions/$id/actions/close", chef, """{"comment":"Production relancée"}""").statusCode())

            val pdf = call(base, "GET", "/interventions/$id/pdf", chef)
            assertEquals(200, pdf.statusCode())
            val text = Loader.loadPDF(pdf.body()).use { PDFTextStripper().getText(it) }
            assertTrue(text.contains("PAGE ROSE") && text.contains("PAGE JAUNE"))
            assertTrue(text, text.contains("Bourrage → éjecteur Ø32"))
            assertTrue(text.contains("Production relancée"))
            val dash = call(base, "GET", "/dashboard/export.pdf", chef)
            assertTrue(Loader.loadPDF(dash.body()).use { PDFTextStripper().getText(it) }.contains("TABLEAU DE BORD"))

            val zip = call(base, "GET", "/admin/backup", admin)
            assertEquals(200, zip.statusCode())
            val restored = File(dir, "restored")
            Backup.extract(zip.body().inputStream(), restored)
            FileSql(File(restored, Backup.DB_ENTRY)).use { r ->
                assertEquals(1L, r.one("SELECT COUNT(*) AS n FROM interventions WHERE status='CLOTURE'")!!.long("n"))
                assertTrue(Seed.isInitialized(r))
            }
            assertTrue(File(restored, "storage").walkTopDown().any { it.name.endsWith(".pdf") })
        } finally {
            http.stop()
        }
    }
}
