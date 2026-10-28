package com.maintiflow.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Erreur métier renvoyée au client sous la forme {"detail": "..."} (même format que le serveur PC). */
class ApiException(val status: Int, val detail: String) : RuntimeException(detail)

class Request(
    val method: String,
    val path: String,
    val query: Map<String, List<String>>,
    /** En-têtes, noms en minuscules. */
    val headers: Map<String, String>,
    val body: ByteArray,
    val remoteIp: String?,
) {
    fun q(name: String): String? = query[name]?.lastOrNull()
    fun qAll(name: String): List<String> = query[name].orEmpty()
    fun header(name: String): String? = headers[name.lowercase()]

    fun json(): JsonObject {
        if (body.isEmpty()) return JsonObject(emptyMap())
        val el = runCatching { Json.parseToJsonElement(body.toString(Charsets.UTF_8)) }
            .getOrElse { throw ApiException(422, "Corps de requête JSON invalide") }
        return el as? JsonObject ?: throw ApiException(422, "Objet JSON attendu")
    }
}

class Response(
    val status: Int,
    val body: ByteArray = ByteArray(0),
    val contentType: String? = null,
    val headers: Map<String, String> = emptyMap(),
) {
    companion object {
        private val json = Json { encodeDefaults = true }

        fun json(value: JsonElement, status: Int = 200) =
            Response(status, json.encodeToString(JsonElement.serializer(), value).toByteArray(), "application/json")

        fun error(status: Int, detail: String) = json(buildJsonObject { put("detail", detail) }, status)

        fun noContent() = Response(204)
    }
}

/** Petit routeur : chemins du type "interventions/{id}/actions/{name}". */
class Router {
    private class Route(val method: String, val parts: List<String>, val handler: (Request, Map<String, String>) -> Response)

    private val routes = mutableListOf<Route>()

    fun add(method: String, pattern: String, handler: (Request, Map<String, String>) -> Response) {
        routes += Route(method, pattern.trim('/').split('/'), handler)
    }

    fun get(p: String, h: (Request, Map<String, String>) -> Response) = add("GET", p, h)
    fun post(p: String, h: (Request, Map<String, String>) -> Response) = add("POST", p, h)
    fun put(p: String, h: (Request, Map<String, String>) -> Response) = add("PUT", p, h)
    fun patch(p: String, h: (Request, Map<String, String>) -> Response) = add("PATCH", p, h)
    fun delete(p: String, h: (Request, Map<String, String>) -> Response) = add("DELETE", p, h)

    fun dispatch(req: Request, path: String): Response {
        val segs = path.trim('/').split('/').map { decode(it) }
        var pathMatched = false
        for (r in routes) {
            if (r.parts.size != segs.size) continue
            val params = HashMap<String, String>()
            val ok = r.parts.indices.all { i ->
                val p = r.parts[i]
                if (p.startsWith("{")) { params[p.trim('{', '}')] = segs[i]; true } else p == segs[i]
            }
            if (!ok) continue
            pathMatched = true
            if (r.method == req.method) return r.handler(req, params)
        }
        return if (pathMatched) Response.error(405, "Méthode non autorisée") else Response.error(404, "Not Found")
    }

    private fun decode(s: String) = URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
}

/**
 * Serveur HTTP/1.1 minimal (une requête par connexion, « Connection: close ») : suffisant pour
 * quelques dizaines de téléphones sur un réseau local, sans dépendance externe.
 */
class HttpServer(
    private val port: Int,
    private val bindAddress: InetAddress? = null,
    private val maxBodyBytes: Int = 16 * 1024 * 1024,
    private val log: (String) -> Unit = {},
    private val handler: (Request) -> Response,
) {
    @Volatile private var socket: ServerSocket? = null
    private var pool: ExecutorService? = null

    val isRunning: Boolean get() = socket?.isClosed == false
    val localPort: Int get() = socket?.localPort ?: -1

    fun start() {
        if (isRunning) return
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(bindAddress, port))
        socket = ss
        val workers = Executors.newFixedThreadPool(8)
        pool = workers
        Thread({
            while (!ss.isClosed) {
                val client = try { ss.accept() } catch (_: SocketException) { break }
                workers.execute { serve(client) }
            }
        }, "maintiflow-http-accept").apply { isDaemon = true }.start()
        log("Serveur HTTP démarré sur le port ${ss.localPort}")
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
        pool?.shutdown()
        pool?.awaitTermination(3, TimeUnit.SECONDS)
        pool = null
    }

    private fun serve(s: Socket) {
        s.use { sock ->
            sock.soTimeout = 30_000
            val out = sock.getOutputStream()
            val resp = try {
                val req = readRequest(BufferedInputStream(sock.getInputStream()), out, sock.inetAddress?.hostAddress)
                    ?: return
                try {
                    handler(req)
                } catch (e: ApiException) {
                    Response.error(e.status, e.detail)
                } catch (e: Throwable) {
                    log("Erreur interne : ${req.method} ${req.path} : $e")
                    Response.error(500, "Erreur interne du serveur")
                }
            } catch (e: ApiException) {
                Response.error(e.status, e.detail)
            } catch (_: java.io.IOException) {
                return
            }
            runCatching { write(out, resp) }
        }
    }

    private fun readLine(input: InputStream, limit: Int = 16 * 1024): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return if (buf.size() == 0) null else buf.toString("ISO-8859-1")
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
            if (buf.size() > limit) throw ApiException(431, "En-têtes trop longs")
        }
        return buf.toString("ISO-8859-1")
    }

    private fun readRequest(input: InputStream, out: OutputStream, ip: String?): Request? {
        val first = readLine(input) ?: return null
        val parts = first.split(' ')
        if (parts.size < 3) throw ApiException(400, "Requête invalide")
        val method = parts[0].uppercase()
        val target = parts[1]
        val headers = LinkedHashMap<String, String>()
        var count = 0
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            if (++count > 100) throw ApiException(431, "Trop d'en-têtes")
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        if (headers["expect"]?.equals("100-continue", ignoreCase = true) == true) {
            out.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            out.flush()
        }
        val body = when {
            headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true -> readChunked(input)
            headers["content-length"] != null -> {
                val len = headers["content-length"]!!.toLongOrNull() ?: throw ApiException(400, "Content-Length invalide")
                if (len > maxBodyBytes) throw ApiException(413, "Requête trop volumineuse")
                readExactly(input, len.toInt())
            }
            else -> ByteArray(0)
        }
        val q = target.indexOf('?')
        val path = if (q >= 0) target.substring(0, q) else target
        val query = LinkedHashMap<String, MutableList<String>>()
        if (q >= 0) {
            target.substring(q + 1).split('&').filter { it.isNotEmpty() }.forEach { kv ->
                val e = kv.indexOf('=')
                val k = URLDecoder.decode(if (e >= 0) kv.substring(0, e) else kv, "UTF-8")
                val v = if (e >= 0) URLDecoder.decode(kv.substring(e + 1), "UTF-8") else ""
                query.getOrPut(k) { mutableListOf() }.add(v)
            }
        }
        return Request(method, path, query, headers, body, ip)
    }

    private fun readExactly(input: InputStream, len: Int): ByteArray {
        val data = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(data, off, len - off)
            if (n < 0) throw java.io.EOFException("Corps incomplet")
            off += n
        }
        return data
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input) ?: throw java.io.EOFException()
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: throw ApiException(400, "Bloc invalide")
            if (size == 0) {
                while (!readLine(input).isNullOrEmpty()) { /* en-têtes de fin ignorés */ }
                break
            }
            if (out.size() + size > maxBodyBytes) throw ApiException(413, "Requête trop volumineuse")
            out.write(readExactly(input, size))
            readLine(input)
        }
        return out.toByteArray()
    }

    private fun write(out: OutputStream, r: Response) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(r.status).append(' ').append(reason(r.status)).append("\r\n")
        if (r.status != 204) {
            r.contentType?.let { sb.append("Content-Type: ").append(it).append("\r\n") }
            sb.append("Content-Length: ").append(r.body.size).append("\r\n")
        }
        sb.append("Connection: close\r\n")
        sb.append("X-Content-Type-Options: nosniff\r\n")
        r.headers.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
        if (r.status != 204) out.write(r.body)
        out.flush()
    }

    private fun reason(code: Int) = when (code) {
        200 -> "OK"; 201 -> "Created"; 204 -> "No Content"; 302 -> "Found"; 400 -> "Bad Request"; 401 -> "Unauthorized"
        403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; 409 -> "Conflict"
        413 -> "Payload Too Large"; 415 -> "Unsupported Media Type"; 422 -> "Unprocessable Entity"
        423 -> "Locked"; 431 -> "Request Header Fields Too Large"; 500 -> "Internal Server Error"
        else -> "Status"
    }
}

/** Corps multipart/form-data : champs texte et fichiers. */
class Multipart(val fields: Map<String, String>, val files: Map<String, FilePart>) {
    class FilePart(val filename: String?, val contentType: String?, val data: ByteArray)

    companion object {
        fun parse(req: Request): Multipart {
            val ct = req.header("content-type") ?: throw ApiException(422, "Formulaire multipart attendu")
            if (!ct.lowercase().startsWith("multipart/form-data")) throw ApiException(422, "Formulaire multipart attendu")
            val boundary = ct.split(';').map { it.trim() }.firstOrNull { it.lowercase().startsWith("boundary=") }
                ?.substringAfter('=')?.trim('"') ?: throw ApiException(422, "Délimiteur multipart manquant")
            val body = req.body
            val delim = "--$boundary".toByteArray(Charsets.ISO_8859_1)
            val fields = HashMap<String, String>()
            val files = HashMap<String, FilePart>()
            var pos = indexOf(body, delim, 0)
            if (pos < 0) throw ApiException(422, "Formulaire multipart invalide")
            while (true) {
                pos += delim.size
                if (pos + 1 < body.size && body[pos] == '-'.code.toByte() && body[pos + 1] == '-'.code.toByte()) break
                pos = skipCrlf(body, pos)
                val headerEnd = indexOf(body, "\r\n\r\n".toByteArray(), pos)
                if (headerEnd < 0) throw ApiException(422, "Formulaire multipart invalide")
                val headers = String(body, pos, headerEnd - pos, Charsets.UTF_8).split("\r\n").associate {
                    it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim()
                }
                val start = headerEnd + 4
                val next = indexOf(body, "\r\n--$boundary".toByteArray(Charsets.ISO_8859_1), start)
                if (next < 0) throw ApiException(422, "Formulaire multipart invalide")
                val data = body.copyOfRange(start, next)
                val disp = headers["content-disposition"].orEmpty()
                val name = param(disp, "name")
                val filename = param(disp, "filename")
                if (name != null) {
                    if (filename != null || headers["content-type"]?.startsWith("text/") == false) {
                        files[name] = FilePart(filename, headers["content-type"], data)
                    } else {
                        fields[name] = String(data, Charsets.UTF_8)
                    }
                }
                pos = next + 2
            }
            return Multipart(fields, files)
        }

        private fun param(disp: String, key: String): String? = disp.split(';').map { it.trim() }
            .firstOrNull { it.startsWith("$key=") }?.substringAfter('=')?.trim('"')

        private fun skipCrlf(b: ByteArray, p: Int): Int =
            if (p + 1 < b.size && b[p] == '\r'.code.toByte() && b[p + 1] == '\n'.code.toByte()) p + 2 else p

        fun indexOf(hay: ByteArray, needle: ByteArray, from: Int): Int {
            outer@ for (i in from..hay.size - needle.size) {
                for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
                return i
            }
            return -1
        }
    }
}

fun jsonStr(v: String?): JsonElement = JsonPrimitive(v)
