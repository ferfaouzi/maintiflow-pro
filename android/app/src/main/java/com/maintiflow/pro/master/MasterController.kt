package com.maintiflow.pro.master

import android.content.Context
import android.net.Uri
import android.util.Log
import com.maintiflow.pro.BuildConfig
import com.maintiflow.server.Backup
import com.maintiflow.server.HttpServer
import com.maintiflow.server.MaintiFlowServer
import com.maintiflow.server.Seed
import com.maintiflow.server.ServerConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Mode « téléphone maître » : ce téléphone héberge la base et le serveur MaintiFlow ; les autres
 * téléphones du même Wi-Fi s'y connectent avec http://<adresse-du-téléphone>:8000.
 */
class MasterController(private val context: Context) {
    data class State(val running: Boolean = false, val error: String? = null)

    companion object {
        const val PORT = 8000
        const val LOCAL_URL = "http://127.0.0.1:$PORT"
        private const val DB_NAME = "maintiflow_server.db"
        private const val ITERATIONS = 100_000
        private const val TAG = "MaintiFlowMaitre"
    }

    private val prefs = context.getSharedPreferences("master_mode", Context.MODE_PRIVATE)
    private val storageDir get() = File(context.filesDir, "server-storage").apply { mkdirs() }
    private var sqlOrNull: AndroidSql? = null
    private val sql: AndroidSql
        get() = sqlOrNull ?: AndroidSql(context, DB_NAME).also { sqlOrNull = it }
    private var http: HttpServer? = null

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** Relancer le serveur au démarrage du téléphone (coupure de courant, redémarrage). */
    var autoStart: Boolean
        get() = prefs.getBoolean("auto_start", false)
        set(v) = prefs.edit().putBoolean("auto_start", v).apply()

    @Synchronized
    fun isInitialized(): Boolean = Seed.isInitialized(sql)

    @Synchronized
    fun initialize(adminPassword: String, company: String?, withDemo: Boolean) =
        Seed.initialize(sql, adminPassword, company, withDemo, ITERATIONS, System.currentTimeMillis())

    @Synchronized
    fun resetAdminPassword(password: String) =
        Seed.resetAdminPassword(sql, password, ITERATIONS, System.currentTimeMillis())

    /** Appelé par MasterService (service de premier plan). */
    @Synchronized
    fun startServer() {
        if (http?.isRunning == true) return
        try {
            val server = MaintiFlowServer(sql, ServerConfig(
                storageDir = storageDir,
                pdf = AndroidPdfRenderer(),
                passwordIterations = ITERATIONS,
                version = BuildConfig.VERSION_NAME,
                // Bouton « Sauvegarde » de l'administration web : même ZIP que l'écran du téléphone maître.
                backup = { exportBackup().let { it.name to it.readBytes() } },
                log = { Log.i(TAG, it) },
            ))
            http = HttpServer(PORT, null, log = { Log.i(TAG, it) }, handler = server::handle).also { it.start() }
            _state.value = State(running = true)
        } catch (e: Exception) {
            Log.e(TAG, "Démarrage impossible", e)
            http = null
            _state.value = State(running = false, error = if (e is java.net.BindException)
                "Le port $PORT est déjà utilisé sur ce téléphone." else "Démarrage impossible : ${e.message}")
        }
    }

    @Synchronized
    fun stopServer() {
        http?.stop()
        http = null
        _state.value = State(running = false)
    }

    /** Adresses IPv4 du téléphone sur le Wi-Fi ou sur son propre point d'accès. */
    fun addresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("ap") || it.name.startsWith("swlan")) 0 else 1 }
            .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>().map { it.hostAddress ?: "" } }
            .filter { it.isNotEmpty() && (it.startsWith("192.168.") || it.startsWith("10.") || it.matches(Regex("^172\\.(1[6-9]|2\\d|3[01])\\..*"))) }
            .distinct()
            .map { "http://$it:$PORT" }
    }.getOrDefault(emptyList())

    /** Sauvegarde complète (base + photos + PDF archivés) dans un ZIP à partager ou copier. */
    @Synchronized
    fun exportBackup(): File {
        val dir = File(context.cacheDir, "backup").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"))
        val out = File(dir, "maintiflow-sauvegarde-$stamp.zip")
        val db = sql
        db.exclusive {
            db.checkpoint()
            ZipOutputStream(FileOutputStream(out)).use { zip ->
                zip.putNextEntry(ZipEntry(DB_NAME))
                context.getDatabasePath(DB_NAME).inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                val root = storageDir
                root.walkTopDown().filter { it.isFile }.forEach { f ->
                    zip.putNextEntry(ZipEntry("storage/" + f.relativeTo(root).invariantSeparatorsPath))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
        return out
    }

    /** Remplace toutes les données du serveur par une sauvegarde. Le serveur doit être arrêté. */
    @Synchronized
    fun restoreBackup(uri: Uri) {
        check(http?.isRunning != true) { "Arrêtez le serveur avant de restaurer." }
        val tmp = File(context.cacheDir, "restore").apply { deleteRecursively(); mkdirs() }
        var hasDb = false
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Fichier illisible" }
            ZipInputStream(input).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val target = File(tmp, e.name).canonicalFile
                    require(target.path.startsWith(tmp.canonicalPath + File.separator)) { "Sauvegarde invalide" }
                    if (e.isDirectory) continue
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { zip.copyTo(it) }
                    if (e.name == DB_NAME || e.name == Backup.DB_ENTRY) hasDb = true
                }
            }
        }
        require(hasDb) { "Ce fichier n'est pas une sauvegarde MaintiFlow" }
        sqlOrNull?.close()
        sqlOrNull = null
        val dbFile = context.getDatabasePath(DB_NAME)
        listOf("", "-wal", "-shm", "-journal").forEach { File(dbFile.path + it).delete() }
        // Sauvegarde du téléphone (maintiflow_server.db) ou du serveur PC (maintiflow.db).
        (File(tmp, DB_NAME).takeIf { it.exists() } ?: File(tmp, Backup.DB_ENTRY)).copyTo(dbFile, overwrite = true)
        storageDir.deleteRecursively()
        File(tmp, "storage").takeIf { it.exists() }?.copyRecursively(storageDir, overwrite = true)
        tmp.deleteRecursively()
        require(isInitialized()) { "Sauvegarde vide" }
    }
}
