package com.maintiflow.server

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Notifications internes : bons destinataires à chaque étape, jamais l'auteur de l'action. */
class NotificationTest {
    private var now = 1_800_000_000_000L
    private val env = TestEnv(clock = { now })
    private val u get() = env.users

    @After fun tearDown() = env.close()

    private fun types(who: String) = u[who]!!.get("/notifications").obj.arr("items").map { it.s("type")!! }

    @Test fun declarationNotifiesChefsAndMaintenance() {
        env.declare(u["op"]!!)
        assertTrue(types("chef").contains("NOUVELLE_PANNE"))
        assertTrue(types("cl").contains("NOUVELLE_PANNE"))
        assertTrue(types("tech").contains("NOUVELLE_PANNE"))
        assertTrue(types("op").isEmpty())          // l'auteur n'est pas notifié
        assertTrue(types("qual").isEmpty())
        assertTrue(types("chefo").isEmpty())       // autre site
        // Panne sur la ligne 2 : le chef de ligne de la ligne 1 n'est pas concerné.
        env.declare(u["chef"]!!, "machine_id" to env.ref("machines", "M3"))
        assertEquals(1, types("cl").size)
    }

    @Test fun criticalFaultAlertsEveryone() {
        env.declare(u["op"]!!, "category_id" to env.ref("categories", "SECU"))
        for (who in listOf("chef", "tech", "tech2", "meth", "qual")) assertTrue(who, types(who).contains("PANNE_CRITIQUE"))
    }

    @Test fun workflowStepsReachTheRightPeople() {
        val t = u["tech"]!!
        var iv = env.declare(u["op"]!!, "category_id" to env.ref("categories", "SECU"))
        iv = u["chef"]!!.act(iv, "assign", 200, "technician_id" to env.userId("tech"))
        assertTrue(types("tech").contains("AFFECTATION"))
        iv = t.act(iv, "accept")
        iv = t.act(iv, "start", 200, "diagnosis" to "Barrière déréglée")
        iv = t.act(iv, "wait_part", 200, "reference" to "BAR-01", "designation" to "Barrière immatérielle")
        assertTrue(types("logi").contains("ATTENTE_PIECE"))
        iv = u["logi"]!!.act(iv, "part_delivered", 200, "comment" to "Livrée au poste")
        assertTrue(types("tech").contains("PIECE"))
        iv = t.act(iv, "finish", 200, *env.YELLOW)
        assertEquals("EN_ATTENTE_QUALITE", iv.s("status"))
        assertTrue(types("qual").contains("ATTENTE_QUALITE"))
        iv = u["qual"]!!.act(iv, "quality_validate", 200, "comment" to "Conforme")
        assertTrue(types("op").contains("A_CLOTURER"))
        u["chef"]!!.act(iv, "close", 200, "comment" to "Production relancée")
        assertTrue(types("op").contains("CLOTURE"))
        assertTrue(types("tech").contains("CLOTURE"))
    }

    @Test fun markReadAndUnreadCount() {
        env.declare(u["op"]!!)
        env.declare(u["op"]!!)
        val chef = u["chef"]!!
        val list = chef.get("/notifications").obj
        assertEquals("2", list.s("unread"))
        val first = list.arr("items")[0].l("id")
        assertEquals(204, chef.post("/notifications/read", "ids" to listOf(first)).status)
        assertEquals("1", chef.get("/notifications").obj.s("unread"))
        assertEquals(1, chef.get("/notifications?unread_only=true").obj.arr("items").size)
        // Impossible de marquer les notifications d'un autre.
        val techFirst = u["tech"]!!.get("/notifications").obj.arr("items")[0].l("id")
        chef.post("/notifications/read", "ids" to listOf(techFirst))
        assertEquals("2", u["tech"]!!.get("/notifications").obj.s("unread"))
        assertEquals(204, chef.post("/notifications/read").status)
        assertEquals("0", chef.get("/notifications").obj.s("unread"))
    }

    @Test fun lateTakeoverIsSignalledOnce() {
        env.declare(u["op"]!!, "category_id" to env.ref("categories", "SECU"))  // critique : 5 minutes
        now += 4 * 60_000L
        assertFalse(types("chef").contains("RETARD"))
        now += 2 * 60_000L
        assertTrue(types("chef").contains("RETARD"))
        now += 10 * 60_000L
        assertEquals(1, types("chef").count { it == "RETARD" })
        assertTrue(types("tech").contains("RETARD"))
    }
}
