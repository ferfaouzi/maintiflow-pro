package com.maintiflow.server

import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.ZipInputStream

/** Tableau de bord (MTTR, MTBF, récurrence, répartitions, chronologie) et exports Excel / PDF. */
class DashboardTest {
    private var now = 1_800_000_000_000L
    private val env = TestEnv(clock = { now })
    private val u get() = env.users
    private val min = 60_000L

    @After fun tearDown() = env.close()

    // Les scénarios avancent l'horloge de plusieurs heures : pas de déconnexion automatique ici.
    @Before fun noIdleLogout() {
        assertEquals(200, u["admin"]!!.put("/admin/settings", "session_idle_minutes" to 0).status)
    }

    /** Panne déclarée, réparée en [repairMin] minutes, puis clôturée. */
    private fun repaired(machine: String, repairMin: Long) {
        val t = u["tech"]!!
        var iv = env.declare(u["chef"]!!, "machine_id" to env.ref("machines", machine), "production_stopped" to true)
        iv = t.act(iv, "accept")
        iv = t.act(iv, "start", 200, "diagnosis" to "Capteur HS")
        now += repairMin * min
        iv = t.act(iv, "finish", 200, *env.YELLOW)
        u["chef"]!!.act(iv, "close", 200, "comment" to "Production relancée")
        now += min
    }

    private fun zipEntries(bytes: ByteArray): Map<String, String> {
        val out = mutableMapOf<String, String>()
        ZipInputStream(bytes.inputStream()).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                out[e.name] = z.readBytes().toString(Charsets.UTF_8)
            }
        }
        return out
    }

    @Test fun indicatorsAndBreakdowns() {
        repaired("M1", 30)
        repaired("M1", 90)
        repaired("M3", 60)
        env.declare(u["chef"]!!, "machine_id" to env.ref("machines", "M2"), "category_id" to env.ref("categories", "SECU"))
        val cancelled = env.declare(u["chef"]!!)
        u["chef"]!!.act(cancelled, "cancel", 200, "comment" to "Doublon de saisie")

        val d = u["chef"]!!.get("/dashboard").obj
        val totals = d["totals"]!!.jsonObject
        assertEquals("4", totals.s("declared"))
        assertEquals("3", totals.s("closed"))
        assertEquals("1", totals.s("cancelled"))
        assertEquals("1", totals.s("critical"))
        assertEquals("1", d["live"]!!.jsonObject.s("open"))
        assertEquals("1", d["live"]!!.jsonObject.s("critical_open"))
        // MTTR : (30 + 90 + 60) / 3 = 60 minutes, à quelques secondes près (déclaration → réparation).
        val mttr = d.s("mttr_s")!!.toDouble()
        assertTrue("mttr=$mttr", mttr in 3600.0..3700.0)
        assertNotNull(d.s("mtbf_s"))
        assertEquals("3", d.s("machines_count"))  // M1, M2, M3 (XM est sur l'autre site)
        val byMachine = d.arr("by_machine").associate { it.s("code") to it.s("count") }
        assertEquals("2", byMachine["M1"]); assertEquals("1", byMachine["M3"])
        val byLine = d.arr("by_line").associate { it.s("code") to it.s("count") }
        assertEquals("3", byLine["L1"]); assertEquals("1", byLine["L2"])
        assertEquals("1", d["by_priority"]!!.jsonObject.s("CRITIQUE"))
        assertEquals(4, d.arr("timeline").sumOf { it.s("declared")!!.toInt() })

        // Filtre par ligne.
        val l2 = u["chef"]!!.get("/dashboard?line_id=${env.ref("lines", "L2")}").obj
        assertEquals("1", l2["totals"]!!.jsonObject.s("declared"))
        // Le chef de ligne ne voit que sa ligne.
        assertEquals("3", u["cl"]!!.get("/dashboard").obj["totals"]!!.jsonObject.s("declared"))
        assertEquals(422, u["chef"]!!.get("/dashboard?granularity=year").status)
        assertEquals(422, u["chef"]!!.get("/dashboard?date_from=2030-01-02&date_to=2030-01-01").status)
    }

    @Test fun recurrenceRate() {
        repaired("M1", 10)
        repaired("M1", 10)
        val d = u["chef"]!!.get("/dashboard").obj
        // Deuxième panne de même catégorie sur M1 : récidive (seuil 2 par défaut).
        assertEquals("50.0", d.s("recurrence_rate"))
    }

    @Test fun excelAndPdfExports() {
        repaired("M1", 20)
        env.declare(u["chef"]!!, "description" to "Fuite d'huile vérin")
        val x = u["chef"]!!.get("/dashboard/export.xlsx")
        assertEquals(200, x.status)
        assertTrue(x.header("content-type")!!.contains("spreadsheetml"))
        val entries = zipEntries(x.bytes)
        assertTrue(entries.keys.toString(), "xl/workbook.xml" in entries && "[Content_Types].xml" in entries)
        assertTrue(entries.values.any { it.contains("huile vérin") })

        val list = u["chef"]!!.get("/interventions/export.xlsx?open_only=true")
        assertEquals(200, list.status)
        val sheets = zipEntries(list.bytes).filterKeys { it.startsWith("xl/worksheets/") }.values.joinToString()
        assertTrue(sheets.contains("vérin"))

        val pdf = u["chef"]!!.get("/dashboard/export.pdf")
        assertEquals(200, pdf.status)
        assertEquals("application/pdf", pdf.header("content-type"))
        assertTrue(pdf.text.contains("TABLEAU DE BORD"))
        assertTrue(u["admin"]!!.get("/admin/audit?action=EXPORT_XLSX").text.contains("EXPORT_XLSX"))
    }
}
