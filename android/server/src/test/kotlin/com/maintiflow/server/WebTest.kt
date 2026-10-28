package com.maintiflow.server

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Interface web embarquée : servie sous /app/, sans sortir du dossier des ressources. */
class WebTest {
    private val env = TestEnv()

    @After fun tearDown() = env.close()

    @Test fun servesTheWebApp() {
        val root = env.raw("GET", "/")
        assertEquals(302, root.statusCode())
        assertEquals("/app/", root.headers().firstValue("location").orElse(""))
        val index = env.raw("GET", "/app/")
        assertEquals(200, index.statusCode())
        assertTrue(index.headers().firstValue("content-type").get().startsWith("text/html"))
        assertTrue(index.headers().firstValue("content-security-policy").isPresent)
        assertTrue(String(index.body()).contains("MaintiFlow"))
        val js = env.raw("GET", "/app/app.js")
        assertEquals(200, js.statusCode())
        assertTrue(js.headers().firstValue("content-type").get().startsWith("text/javascript"))
        assertEquals(200, env.raw("GET", "/app/styles.css").statusCode())
        // Application d'une seule page : un chemin sans extension renvoie index.html.
        assertEquals(200, env.raw("GET", "/app/pannes/BI-2026-000001").statusCode())
        assertEquals(404, env.raw("GET", "/app/inconnu.js").statusCode())
        assertEquals(404, env.raw("GET", "/app/..%2f..%2fetc/passwd").statusCode())
        assertEquals(404, env.raw("GET", "/app/../MaintiFlowServer.class").statusCode())
    }
}
