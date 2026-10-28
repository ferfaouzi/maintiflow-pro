package com.maintiflow.server

/**
 * Interface web (PC, tablette) : fichiers statiques embarqués dans le serveur (ressources « web/ »),
 * servis sous /app/. Aucune installation sur les postes : un navigateur suffit.
 */
object Web {
    private val TYPES = mapOf(
        "html" to "text/html; charset=utf-8", "js" to "text/javascript; charset=utf-8",
        "css" to "text/css; charset=utf-8", "svg" to "image/svg+xml", "png" to "image/png",
        "ico" to "image/x-icon", "json" to "application/json",
    )

    private val SECURITY = mapOf(
        "Content-Security-Policy" to "default-src 'self'; img-src 'self' data: blob:; style-src 'self' 'unsafe-inline'; " +
            "frame-src 'self' blob:; object-src 'self' blob:; frame-ancestors 'self'",
        "Referrer-Policy" to "no-referrer",
        "Cache-Control" to "no-cache",
    )

    fun serve(path: String): Response {
        var rel = path.removePrefix("/app").trimStart('/')
        if (rel.isEmpty()) rel = "index.html"
        if (rel.contains("..") || rel.contains('\\') || !Regex("^[a-zA-Z0-9._/-]+$").matches(rel)) {
            return Response.error(404, "Not Found")
        }
        val stream = Web::class.java.getResourceAsStream("/web/$rel")
            // Application d'une seule page : tout chemin inconnu sans extension renvoie index.html.
            ?: if (!rel.substringAfterLast('/').contains('.')) Web::class.java.getResourceAsStream("/web/index.html") else null
            ?: return Response.error(404, "Not Found")
        val data = stream.use { it.readBytes() }
        val ext = rel.substringAfterLast('/').substringAfterLast('.', "html")
        return Response(200, data, TYPES[ext] ?: "application/octet-stream", SECURITY)
    }
}
