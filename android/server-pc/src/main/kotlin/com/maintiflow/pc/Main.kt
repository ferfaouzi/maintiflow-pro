package com.maintiflow.pc

import com.maintiflow.server.ApiException
import com.maintiflow.server.Backup
import com.maintiflow.server.HttpServer
import com.maintiflow.server.MaintiFlowServer
import com.maintiflow.server.Seed
import com.maintiflow.server.ServerConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.NetworkInterface
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

const val VERSION = "2.0.0"

private const val HELP = """MaintiFlow Pro – serveur PC / Raspberry Pi $VERSION

Utilisation : java -jar maintiflow-server.jar [commande] [options]

Commandes :
  (aucune)            démarre le serveur (initialise la base au premier lancement)
  init                initialise la base sans démarrer le serveur
  reset-admin         choisit un nouveau mot de passe pour le compte « admin »
  backup FICHIER.zip  sauvegarde complète (base, photos, PDF archivés)
  restore FICHIER.zip restaure une sauvegarde (serveur arrêté) ; l'ancien dossier est conservé
  help                cette aide

Options :
  --data DOSSIER      dossier des données (défaut : ./maintiflow-data)
  --port N            port HTTP (défaut : 8080)
  --host ADRESSE      adresse d'écoute (défaut : 0.0.0.0, tout le réseau local)
  --demo              à l'initialisation, ajoute des données fictives de démonstration
  --company NOM       nom affiché sur les fiches (défaut : « Usine de démonstration »)

Mot de passe administrateur : demandé dans la console (jamais affiché), ou lu dans la variable
d'environnement MAINTIFLOW_ADMIN_PASSWORD pour une installation sans écran. Il n'existe aucun
mot de passe par défaut."""

class Options(args: Array<String>) {
    var command = "serve"
    val positional = mutableListOf<String>()
    var data = File("maintiflow-data")
    var port = 8080
    var host = "0.0.0.0"
    var demo = false
    var company: String? = null

    init {
        var i = 0
        fun value(): String = args.getOrNull(++i) ?: fail("Valeur manquante après ${args[i - 1]}")
        while (i < args.size) {
            when (val a = args[i]) {
                "--data" -> data = File(value())
                "--port" -> port = value().toIntOrNull()?.takeIf { it in 1..65535 } ?: fail("Port invalide")
                "--host" -> host = value()
                "--demo" -> demo = true
                "--company" -> company = value()
                "-h", "--help" -> command = "help"
                else -> if (a.startsWith("--")) fail("Option inconnue : $a") else if (command == "serve" && positional.isEmpty() &&
                    a in setOf("serve", "init", "reset-admin", "backup", "restore", "help")) command = a else positional += a
            }
            i++
        }
    }
}

fun fail(msg: String): Nothing {
    System.err.println("Erreur : $msg")
    exitProcess(2)
}

/** Mot de passe choisi par la personne qui installe : console masquée, ou variable d'environnement. */
fun askPassword(what: String): String {
    System.getenv("MAINTIFLOW_ADMIN_PASSWORD")?.takeIf { it.isNotEmpty() }?.let { return it }
    val console = System.console() ?: fail("Pas de console : définissez MAINTIFLOW_ADMIN_PASSWORD pour $what")
    while (true) {
        val p1 = String(console.readPassword("%s (8 caractères minimum, lettres et chiffres) : ", what) ?: fail("Annulé"))
        val p2 = String(console.readPassword("Confirmez : ") ?: fail("Annulé"))
        if (p1 != p2) { println("Les deux saisies diffèrent, recommencez."); continue }
        return p1
    }
}

fun dbFile(o: Options) = File(o.data, Backup.DB_ENTRY)
fun storageDir(o: Options) = File(o.data, "storage")

fun openDb(o: Options): FileSql {
    o.data.mkdirs()
    return FileSql(dbFile(o))
}

fun initialize(o: Options, sql: FileSql) {
    if (Seed.isInitialized(sql)) return
    println("Première mise en service : création du compte administrateur « admin ».")
    while (true) {
        try {
            Seed.initialize(sql, askPassword("Mot de passe administrateur"), o.company, withDemo = o.demo,
                iterations = 120_000, now = System.currentTimeMillis())
            println("Base initialisée" + if (o.demo) " avec des données fictives de démonstration." else ".")
            return
        } catch (e: ApiException) {
            println(e.message)
            if (System.getenv("MAINTIFLOW_ADMIN_PASSWORD") != null) fail("MAINTIFLOW_ADMIN_PASSWORD refusé : ${e.message}")
        }
    }
}

fun backupBytes(o: Options, sql: FileSql): Pair<String, ByteArray> {
    val snap = File.createTempFile("mf-snap-", ".db", o.data)
    try {
        sql.snapshot(snap)
        val out = ByteArrayOutputStream()
        val now = System.currentTimeMillis()
        Backup.write(snap, storageDir(o), VERSION, now, out)
        return Backup.fileName(now) to out.toByteArray()
    } finally {
        snap.delete()
    }
}

fun lanAddresses(): List<String> = runCatching {
    NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }.filter { it is java.net.Inet4Address }.map { it.hostAddress }
}.getOrDefault(emptyList())

fun main(args: Array<String>) {
    // Accents corrects dans la console Windows (page de code de la console) comme sous Linux (UTF-8).
    val cs = System.console()?.charset() ?: Charsets.UTF_8
    System.setOut(java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), true, cs))
    System.setErr(java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.err), true, cs))
    val o = Options(args)
    when (o.command) {
        "help" -> println(HELP)
        "init" -> openDb(o).use { sql ->
            if (Seed.isInitialized(sql)) println("La base ${dbFile(o)} est déjà initialisée.") else initialize(o, sql)
        }
        "reset-admin" -> openDb(o).use { sql ->
            if (!Seed.isInitialized(sql)) fail("Base non initialisée")
            try {
                Seed.resetAdminPassword(sql, askPassword("Nouveau mot de passe administrateur"), 120_000, System.currentTimeMillis())
                println("Mot de passe du compte « admin » modifié ; le compte est réactivé et déverrouillé.")
            } catch (e: ApiException) { fail(e.message ?: "refusé") }
        }
        "backup" -> {
            val target = File(o.positional.firstOrNull() ?: fail("Indiquez le fichier .zip à créer"))
            if (!dbFile(o).exists()) fail("Aucune base dans ${o.data}")
            openDb(o).use { sql -> target.writeBytes(backupBytes(o, sql).second) }
            println("Sauvegarde écrite : ${target.absolutePath}")
        }
        "restore" -> {
            val zip = File(o.positional.firstOrNull() ?: fail("Indiquez le fichier .zip à restaurer"))
            if (!zip.isFile) fail("Fichier introuvable : $zip")
            val staging = File(o.data.absoluteFile.parentFile, o.data.name + ".restauration")
            staging.deleteRecursively()
            try {
                zip.inputStream().use { Backup.extract(it, staging) }
                FileSql(File(staging, Backup.DB_ENTRY)).use { sql ->
                    if (!Seed.isInitialized(sql)) fail("La sauvegarde ne contient pas de base MaintiFlow initialisée")
                }
            } catch (e: ApiException) { staging.deleteRecursively(); fail(e.message ?: "sauvegarde invalide") }
            if (o.data.exists()) {
                val old = File(o.data.absoluteFile.parentFile,
                    o.data.name + ".avant-restauration-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")))
                if (!o.data.renameTo(old)) fail("Impossible de déplacer ${o.data} (serveur encore en marche ?)")
                println("Anciennes données conservées dans ${old.absolutePath}")
            }
            if (!staging.renameTo(o.data)) fail("Impossible de mettre en place la sauvegarde")
            File(o.data, "storage").mkdirs()
            println("Sauvegarde restaurée dans ${o.data.absolutePath}. Vous pouvez démarrer le serveur.")
        }
        else -> serve(o)
    }
}

fun serve(o: Options) {
    val sql = openDb(o)
    initialize(o, sql)
    val storage = storageDir(o).also { it.mkdirs() }
    val server = MaintiFlowServer(sql, ServerConfig(
        storageDir = storage, pdf = PdfBoxRenderer(), version = VERSION, mode = "pc",
        backup = { backupBytes(o, sql) },
        log = { System.err.println(it) },
    ))
    val http = HttpServer(o.port, InetAddress.getByName(o.host), log = { System.err.println(it) }, handler = server::handle)
    http.start()
    Runtime.getRuntime().addShutdownHook(Thread { http.stop(); sql.close() })
    println("MaintiFlow Pro $VERSION en marche. Données : ${o.data.absolutePath}")
    val hosts = if (o.host == "0.0.0.0") lanAddresses().ifEmpty { listOf("localhost") } else listOf(o.host)
    hosts.forEach { println("  Interface web : http://$it:${http.localPort}/app/") }
    println("Ctrl+C pour arrêter.")
    Thread.currentThread().join()
}
