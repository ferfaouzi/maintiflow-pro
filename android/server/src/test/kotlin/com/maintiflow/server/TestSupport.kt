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
 * Serveur de test servi en HTTP réel sur un port local libre. Site 1 : lignes L1 (machines M1, M2, poste L1-A)
 * et L2 (machine M3) ; site 2 : ligne X1. Un compte par rôle, plus quelques doublons pour les règles d'affectation.
 */
class TestEnv(
    val clock: () -> Long = { System.currentTimeMillis() },
    pdf: PdfRenderer = TEXT_PDF,
    backup: (() -> Pair<String, ByteArray>)? = null,
    val sql: JdbcSql = JdbcSql(),
    fixtures: Boolean = true,
) : AutoCloseable {
    val storage = Files.createTempDirectory("mf-test-").toFile()
    val server = MaintiFlowServer(sql, ServerConfig(storage, pdf, passwordIterations = 1000, clock = clock, backup = backup))
    private val http = HttpServer(0, java.net.InetAddress.getLoopbackAddress(), handler = server::handle).also { it.start() }
    val base = "http://127.0.0.1:${http.localPort}"
    val client: HttpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()

    init {
        if (fixtures) fixtures()
    }

    private fun fixtures() {
        val hash = Security.hashPassword(PWD, 1000)
        sql.exec("INSERT INTO sites(code, name) VALUES ('T', 'Site test'), ('O', 'Autre site')")
        sql.exec("INSERT INTO lines(site_id, code, name) VALUES (1, 'L1', 'Ligne 1'), (2, 'X1', 'Ligne autre site'), (1, 'L2', 'Ligne 2')")
        sql.exec("INSERT INTO postes(line_id, code, name) VALUES (1, 'L1-A', 'Poste A'), (3, 'L2-A', 'Poste A ligne 2')")
        sql.exec(
            "INSERT INTO machines(line_id, poste_id, code, name, qr_token) VALUES (1, 1, 'M1', 'Presse 1', 'q1'), " +
                "(1, NULL, 'M2', 'Convoyeur', 'q2'), (2, NULL, 'XM', 'Machine autre site', 'q3'), (3, 2, 'M3', 'Sertisseuse L2', 'q4')",
        )
        sql.exec(
            "INSERT INTO failure_categories(site_id, code, name, default_priority, requires_quality) VALUES " +
                "(1, 'MECA', 'Mécanique', 'NORMALE', 0), (1, 'SECU', 'Sécurité', 'CRITIQUE', 1)",
        )
        sql.exec("INSERT INTO failure_causes(category_id, name) VALUES (1, 'Usure'), (2, 'Barrière immatérielle')")
        sql.exec("INSERT INTO cable_refs(site_id, code, name) VALUES (1, 'CBL-1', 'Câble test')")
        sql.exec("INSERT INTO intervention_types(site_id, code, name) VALUES (1, 'CORR', 'Corrective')")
        sql.exec("INSERT INTO tools(site_id, machine_id, code, name) VALUES (1, 1, 'APP-1', 'Applicateur')")
        USERS.forEach { (u, r) ->
            sql.exec(
                "INSERT INTO users(site_id, username, first_name, last_name, full_name, role, password_hash, must_change_password, created_at) " +
                    "VALUES (?,?,?,?,?,?,?,0,0)", if (u == "chefo") 2 else 1, u, u, u.uppercase(), u.uppercase(), r, hash,
            )
        }
        // Opérateurs et chef de ligne limités à une ligne.
        sql.exec("INSERT INTO user_lines(user_id, line_id) SELECT id, 1 FROM users WHERE username IN ('op', 'cl')")
        sql.exec("INSERT INTO user_lines(user_id, line_id) SELECT id, 3 FROM users WHERE username = 'op2'")
    }

    companion object {
        val USERS = listOf(
            "op" to Role.OPERATEUR, "op2" to Role.OPERATEUR, "cl" to Role.CHEF_LIGNE, "chef" to Role.CHEF_EQUIPE,
            "chef2" to Role.CHEF_EQUIPE, "tech" to Role.MAINTENANCIER, "tech2" to Role.MAINTENANCIER, "meth" to Role.METHODE,
            "qual" to Role.QUALITE, "logi" to Role.LOGISTIQUE, "resp" to Role.RESPONSABLE, "admin" to Role.ADMIN,
            "chefo" to Role.CHEF_EQUIPE,
        )
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

    val users by lazy { USERS.map { it.first }.associateWith { api(it) } }
    val refs by lazy { users["chef"]!!.get("/referentials").json.jsonObject }

    fun id(list: String, i: Int) = refs[list]!!.jsonArray[i].jsonObject["id"]!!.jsonPrimitive.long

    /** Identifiant d'un élément de référentiel par son code (ou son nom). */
    fun ref(list: String, code: String) = refs[list]!!.jsonArray.map { it.jsonObject }
        .first { it.s("code") == code || it.s("name") == code }.l("id")

    fun userId(username: String) = sql.one("SELECT id FROM users WHERE username=?", username)!!.long("id")

    /** Déclare une panne (machine M1, catégorie Mécanique par défaut). */
    fun declare(api: Api, vararg kw: Pair<String, Any?>): JsonObject {
        val body = mutableMapOf<String, Any?>(
            "machine_id" to ref("machines", "M1"), "category_id" to ref("categories", "MECA"),
            "description" to "Bourrage récurrent en sortie de presse",
        )
        kw.forEach { body[it.first] = it.second }
        val r = api.post("/interventions", *body.toList().toTypedArray())
        assertEquals(r.text, true, r.status == 200 || r.status == 201)
        return r.obj
    }

    /** Fiche jaune complète, acceptée par « finish ». */
    val YELLOW = arrayOf<Pair<String, Any?>>(
        "diagnosis" to "Capteur inductif HS", "root_cause" to "Câble écrasé par le carter",
        "corrective_action" to "Remplacement du capteur, protection du câble", "test_result" to "20 pièces conformes",
        "machine_final_state" to "OPERATIONNELLE",
    )

    /** Déclaration par [by] puis prise en charge, démarrage et fin par tech : statut RESOLUE (ou attente Qualité). */
    fun resolved(by: String = "chef", vararg kw: Pair<String, Any?>): JsonObject {
        val t = users["tech"]!!
        var iv = declare(users[by]!!, "production_stopped" to true, *kw)
        iv = t.act(iv, "accept")
        iv = t.act(iv, "start", 200, "diagnosis" to "Capteur inductif HS")
        return t.act(iv, "finish", 200, *YELLOW)
    }

    fun closed(by: String = "chef"): JsonObject =
        users["chef"]!!.act(resolved(by), "close", 200, "comment" to "Production relancée, OK", "signature_png_base64" to SIG)

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
    val token: String
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
