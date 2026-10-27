package com.maintiflow.pro.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.maintiflow.pro.data.api.ActionRequest
import com.maintiflow.pro.data.api.ApiClient
import com.maintiflow.pro.data.api.ChangePasswordRequest
import com.maintiflow.pro.data.api.CreateTicketRequest
import com.maintiflow.pro.data.api.LoginRequest
import com.maintiflow.pro.data.api.PartRequest
import com.maintiflow.pro.data.api.PatchTicketRequest
import com.maintiflow.pro.data.api.Referentials
import com.maintiflow.pro.data.api.SimilarResponse
import com.maintiflow.pro.data.api.TicketDetail
import com.maintiflow.pro.data.api.TicketPage
import com.maintiflow.pro.data.api.UserDto
import com.maintiflow.pro.data.api.isNetwork
import com.maintiflow.pro.data.local.CacheEntry
import com.maintiflow.pro.data.local.LocalDb
import com.maintiflow.pro.data.local.PendingTicket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.util.UUID

/** Données servies avec leur origine : le réseau, ou le cache local quand le serveur est injoignable. */
data class Loaded<T>(val value: T, val fromCache: Boolean)

sealed interface CreateResult {
    data class Sent(val ticket: TicketDetail) : CreateResult
    data object SavedOffline : CreateResult
}

class Repository(
    private val context: Context,
    val session: SessionStore,
    val client: ApiClient,
    private val db: LocalDb,
) {
    private val json get() = client.json
    private val api get() = client.api

    // ------------------------------------------------------------ session
    suspend fun login(server: String, username: String, password: String): UserDto {
        session.serverUrl = server
        val res = api.login(LoginRequest(username.trim().lowercase(), password, android.os.Build.MODEL))
        session.saveSession(res.accessToken, res.user)
        return res.user
    }

    suspend fun changePassword(current: String, new: String) {
        val r = api.changePassword(ChangePasswordRequest(current, new))
        if (!r.isSuccessful) throw HttpException(r)
        session.updateUser(api.me())
    }

    suspend fun logout() {
        runCatching { api.logout() }
        session.clearSession()
        db.cache().clear()
    }

    // ------------------------------------------------------------ lecture avec repli sur le cache
    private suspend inline fun <reified T> cached(key: String, crossinline fetch: suspend () -> T): Loaded<T> {
        return try {
            val v = fetch()
            db.cache().put(CacheEntry(key, json.encodeToString(v)))
            Loaded(v, false)
        } catch (t: Throwable) {
            if (!t.isNetwork()) throw t
            val entry = db.cache().get(key) ?: throw t
            Loaded(json.decodeFromString<T>(entry.json), true)
        }
    }

    suspend fun referentials(): Loaded<Referentials> = cached("referentials") { api.referentials() }

    suspend fun home(mine: Boolean): Loaded<TicketPage> =
        cached("home:$mine") { api.search(openOnly = true, mine = if (mine) true else null, limit = 100) }

    suspend fun search(q: String?, status: List<String>?, openOnly: Boolean): TicketPage =
        api.search(q = q?.takeIf { it.isNotBlank() }, status = status, openOnly = if (openOnly) true else null, limit = 100)

    suspend fun ticket(id: String): Loaded<TicketDetail> = cached("ticket:$id") { api.get(id) }

    suspend fun similar(id: String): SimilarResponse = api.similar(id)

    // ------------------------------------------------------------ écritures
    private suspend fun store(t: TicketDetail): TicketDetail {
        db.cache().put(CacheEntry("ticket:${t.id}", json.encodeToString(t)))
        return t
    }

    suspend fun action(t: TicketDetail, name: String, req: ActionRequest): TicketDetail =
        store(api.action(t.id, name, req.copy(version = t.version)))

    suspend fun patch(t: TicketDetail, req: PatchTicketRequest): TicketDetail = store(api.patch(t.id, req))

    suspend fun addPart(t: TicketDetail, req: PartRequest): TicketDetail = store(api.addPart(t.id, req))

    suspend fun deletePart(t: TicketDetail, partId: Int): TicketDetail = store(api.deletePart(t.id, partId))

    suspend fun uploadPhoto(ticketId: String, file: File, kind: String): TicketDetail {
        val part = MultipartBody.Part.createFormData("file", file.name, file.asRequestBody("image/jpeg".toMediaType()))
        return store(api.upload(ticketId, kind.toRequestBody("text/plain".toMediaType()), part))
    }

    /** Télécharge le PDF (2 exemplaires) dans le cache privé pour affichage, impression ou partage. */
    suspend fun downloadPdf(t: TicketDetail): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "pdf").apply { mkdirs() }
        val out = File(dir, "${t.number}_rev${t.revision}.pdf")
        api.pdf(t.id).byteStream().use { input -> FileOutputStream(out).use { input.copyTo(it) } }
        out
    }

    // ------------------------------------------------------------ création (en ligne ou hors ligne)
    fun newClientUuid(): String = UUID.randomUUID().toString()

    suspend fun createTicket(
        machineId: Int, categoryId: Int, description: String, productionStopped: Boolean,
        priority: String?, photos: List<File>, summary: String,
    ): CreateResult {
        val now = Instant.now().toString()
        val req = CreateTicketRequest(
            clientUuid = newClientUuid(), machineId = machineId, categoryId = categoryId,
            description = description.trim(), priority = priority, productionStopped = productionStopped,
            stopStartAt = if (productionStopped) now else null, declaredAt = now,
        )
        return try {
            val created = api.create(req)
            var last = created
            photos.forEach { last = uploadPhoto(created.id, it, "PROBLEME") }
            CreateResult.Sent(store(last))
        } catch (t: Throwable) {
            if (!t.isNetwork()) throw t
            db.pending().put(PendingTicket(req.clientUuid, json.encodeToString(req),
                photos.joinToString("|") { it.absolutePath }, summary))
            CreateResult.SavedOffline
        }
    }

    fun observePending(): Flow<List<PendingTicket>> = db.pending().observeAll()

    /** Envoie les bons créés hors ligne. Idempotent grâce au client_uuid. Retourne le nombre restant. */
    suspend fun syncPending(): Int {
        for (p in db.pending().all()) {
            try {
                val req = json.decodeFromString<CreateTicketRequest>(p.payloadJson)
                val created = api.create(req)
                val remaining = p.photoPaths.split("|").filter { it.isNotBlank() }.toMutableList()
                while (remaining.isNotEmpty()) {
                    val f = File(remaining.first())
                    if (f.exists()) uploadPhoto(created.id, f, "PROBLEME")
                    remaining.removeAt(0)
                    db.pending().put(p.copy(photoPaths = remaining.joinToString("|")))
                }
                db.pending().delete(p.clientUuid)
            } catch (t: Throwable) {
                db.pending().markFailed(p.clientUuid, client.messageOf(t))
                if (t.isNetwork()) break
            }
        }
        return db.pending().all().size
    }

    // ------------------------------------------------------------ photos
    /** Réduit la photo (1600 px max, JPEG 80 %) pour un envoi rapide sur le Wi-Fi d'atelier. */
    suspend fun compressPhoto(src: File): File = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.absolutePath, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 1600) sample *= 2
        val bmp = BitmapFactory.decodeFile(src.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return@withContext src
        val out = File(src.parentFile, "c_" + src.name)
        FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        bmp.recycle()
        src.delete()
        out
    }

    fun newPhotoFile(): File {
        val dir = File(context.filesDir, "photos").apply { mkdirs() }
        return File(dir, "photo_${System.currentTimeMillis()}.jpg")
    }
}
