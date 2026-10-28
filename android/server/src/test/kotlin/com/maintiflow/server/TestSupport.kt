package com.maintiflow.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.Deflater

const val PWD = "Test1234pass"

/** Rendu PDF factice pour la JVM : le texte du document, précédé d'un en-tête PDF. */
val TEXT_PDF = PdfRenderer { doc -> ("%PDF-1.4\n" + PdfBuilder.toText(doc)).toByteArray() }

/**
 * Serveur de test : même jeu de données que backend/tests/conftest.py (deux sites, 7 comptes),
 * servi en HTTP réel sur un port local libre.
 */
class TestEnv(
    val clock: () -> Long = { System.currentTimeMillis() },
    pdf: PdfRenderer = TEXT_PDF,
) : AutoCloseable {
    val sql = JdbcSql()
    val storage = Files.createTempDirectory("mf-test-").toFile()
    val server = MaintiFlowServer(sql, ServerConfig(storage, pdf, passwordIterations = 1000, clock = clock))
    private val http = HttpServer(0, java.net.InetAddress.getLoopbackAddress(), handler = server::handle).also { it.start() }
    val base = "http://127.0.0.1:${http.localPort}"
    val client: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

    init {
        val hash = Security.hashPassword(PWD, 1000)
        sql.exec("INSERT INTO sites(code, name) VALUES ('T', 'Site test'), ('O', 'Autre site')")
        sql.exec("INSERT INTO lines(site_id, code, name) VALUES (1, 'L1', 'Ligne 1'), (2, 'X1', 'Ligne autre site')")
        sql.exec(
            "INSERT INTO machines(line_id, code, name, qr_token) VALUES (1, 'M1', 'Presse 1', 'q1'), " +
                "(1, 'M2', 'Convoyeur', 'q2'), (2, 'XM', 'Machine autre site', 'q3')",
        )
        sql.exec(
            "INSERT INTO failure_categories(site_id, code, name, default_priority) VALUES " +
                "(1, 'MECA', 'Mécanique', 'NORMALE'), (1, 'SECU', 'Sécurité', 'CRITIQUE')",
        )
        listOf("chef" to Role.CHEF_EQUIPE, "chef2" to Role.CHEF_EQUIPE, "tech" to Role.TECHNICIEN,
            "tech2" to Role.TECHNICIEN, "resp" to Role.RESPONSABLE, "admin" to Role.ADMIN, "chefo" to Role.CHEF_EQUIPE,
        ).forEach { (u, r) ->
            sql.exec(
                "INSERT INTO users(site_id, username, full_name, role, password_hash, must_change_password, created_at) " +
                    "VALUES (?,?,?,?,?,0,0)", if (u == "chefo") 2 else 1, u, u.uppercase(), r, hash,
            )
        }
    }

    fun raw(method: String, path: String, body: String? = null, headers: Map<String, String> = emptyMap(),
            bytes: ByteArray? = null): HttpResponse<ByteArray> {
        val b = HttpRequest.newBuilder(URI.create(base + path))
        headers.forEach { (k, v) -> b.header(k, v) }
        val pub = when {
            bytes != null -> HttpRequest.BodyPublishers.ofByteArray(bytes)
            body != null -> { b.header("Content-Type", "application/json"); HttpRequest.BodyPublishers.ofString(body) }
            else -> HttpRequest.BodyPublishers.noBody()
        }
        b.method(method, pub)
        return client.send(b.build(), HttpResponse.BodyHandlers.ofByteArray())
    }

    fun api(username: String) = Api(this, username)

    val users by lazy { listOf("chef", "chef2", "tech", "tech2", "resp", "admin", "chefo").associateWith { api(it) } }
    val refs by lazy { users["chef"]!!.get("/referentials").json.jsonObject }

    fun id(list: String, i: Int) = refs[list]!!.jsonArray[i].jsonObject["id"]!!.jsonPrimitive.long

    override fun close() {
        http.stop()
        storage.deleteRecursively()
    }
}

class Res(val status: Int, val bytes: ByteArray, val headers: Map<String, List<String>>) {
    val text get() = bytes.toString(Charsets.UTF_8)
    val json: JsonElement get() = Json.parseToJsonElement(text)
    val obj: JsonObject get() = json.jsonObject
    fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
}

class Api(private val env: TestEnv, username: String) {
    private val token: String
    val user: JsonObject

    init {
        val r = env.raw("POST", "/api/v1/auth/login", jsonOf("username" to username, "password" to PWD))
        assertEquals(String(r.body()), 200, r.statusCode())
        val o = Json.parseToJsonElement(String(r.body())).jsonObject
        token = o["access_token"]!!.jsonPrimitive.content
        user = o["user"]!!.jsonObject
    }

    val id get() = user["id"]!!.jsonPrimitive.long

    private fun call(method: String, url: String, body: String? = null, bytes: ByteArray? = null, ct: String? = null): Res {
        val h = mutableMapOf("Authorization" to "Bearer $token")
        ct?.let { h["Content-Type"] = it }
        val r = env.raw(method, "/api/v1$url", body, h, bytes)
        return Res(r.statusCode(), r.body(), r.headers().map())
    }

    fun get(url: String) = call("GET", url)
    fun post(url: String, vararg kv: Pair<String, Any?>) = call("POST", url, jsonOf(*kv))
    fun patch(url: String, vararg kv: Pair<String, Any?>) = call("PATCH", url, jsonOf(*kv))
    fun put(url: String, vararg kv: Pair<String, Any?>) = call("PUT", url, jsonOf(*kv))
    fun delete(url: String) = call("DELETE", url)
    fun multipart(url: String, bytes: ByteArray, ct: String) = call("POST", url, bytes = bytes, ct = ct)

    fun act(iv: JsonObject, name: String, expect: Int = 200, vararg body: Pair<String, Any?>): JsonObject {
        val r = post("/interventions/${iv.s("id")}/actions/$name", *body)
        assertEquals("$name: ${r.text}", expect, r.status)
        return r.obj
    }
}

fun toJson(v: Any?): JsonElement = when (v) {
    null -> JsonNull
    is JsonElement -> v
    is String -> JsonPrimitive(v)
    is Number -> JsonPrimitive(v)
    is Boolean -> JsonPrimitive(v)
    is List<*> -> JsonArray(v.map { toJson(it) })
    else -> error("type $v")
}

fun jsonOf(vararg kv: Pair<String, Any?>): String = JsonObject(kv.associate { it.first to toJson(it.second) }).toString()

fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
fun JsonObject.l(k: String): Long = this[k]!!.jsonPrimitive.long
fun JsonObject.arr(k: String) = this[k]!!.jsonArray.map { it.jsonObject }

fun tinyPng(w: Int = 4, h: Int = 2): ByteArray {
    fun chunk(type: String, data: ByteArray): ByteArray {
        val t = type.toByteArray()
        val crc = CRC32().apply { update(t); update(data) }.value
        return int32(data.size) + t + data + int32(crc.toInt())
    }
    val raw = java.io.ByteArrayOutputStream()
    repeat(h) { raw.write(0); raw.write(ByteArray(w * 3)) }
    val def = Deflater().apply { setInput(raw.toByteArray()); finish() }
    val buf = ByteArray(4096)
    val comp = buf.copyOf(def.deflate(buf))
    val ihdr = int32(w) + int32(h) + byteArrayOf(8, 2, 0, 0, 0)
    return byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10) +
        chunk("IHDR", ihdr) + chunk("IDAT", comp) + chunk("IEND", ByteArray(0))
}

private fun int32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

val SIG: String = Base64.getEncoder().encodeToString(tinyPng(60, 20))

fun multipartBody(fields: Map<String, String>, file: Triple<String, String, ByteArray>?): Pair<ByteArray, String> {
    val boundary = "----mfboundary42"
    val out = java.io.ByteArrayOutputStream()
    fields.forEach { (k, v) ->
        out.write("--$boundary\r\nContent-Disposition: form-data; name=\"$k\"\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n$v\r\n".toByteArray())
    }
    file?.let { (name, ct, data) ->
        out.write("--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$name\"\r\nContent-Type: $ct\r\n\r\n".toByteArray())
        out.write(data)
        out.write("\r\n".toByteArray())
    }
    out.write("--$boundary--\r\n".toByteArray())
    return out.toByteArray() to "multipart/form-data; boundary=$boundary"
}
