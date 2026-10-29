package com.maintiflow.server

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Sauvegarde complète au format ZIP : la base SQLite (copie cohérente), les photos et les PDF archivés,
 * et un manifeste. Le même format sert au téléphone maître et au serveur PC : une sauvegarde de l'un
 * se restaure sur l'autre.
 */
object Backup {
    const val DB_ENTRY = "maintiflow.db"
    /** Nom de la base dans les sauvegardes faites par le téléphone maître. */
    const val PHONE_DB_ENTRY = "maintiflow_server.db"
    private const val STORAGE_PREFIX = "storage/"
    private const val MANIFEST = "manifest.txt"

    fun fileName(now: Long) = "maintiflow-sauvegarde-" +
        Instant.ofEpochMilli(now).toString().take(19).replace(':', '-').replace('T', '_') + ".zip"

    /** Écrit la sauvegarde ; [dbSnapshot] est une copie de la base faite par l'hôte (VACUUM INTO). */
    fun write(dbSnapshot: File, storageDir: File, version: String, now: Long, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write("MaintiFlow Pro $version\nschema=${Schema.VERSION}\ndate=${Instant.ofEpochMilli(now)}\n".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(DB_ENTRY))
            dbSnapshot.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
            if (storageDir.isDirectory) {
                storageDir.walkTopDown().filter { it.isFile }.sortedBy { it.path }.forEach { f ->
                    zip.putNextEntry(ZipEntry(STORAGE_PREFIX + f.relativeTo(storageDir).invariantSeparatorsPath))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
        }
    }

    /**
     * Extrait une sauvegarde dans [targetDir] (vide) : [DB_ENTRY] et le dossier storage/. Refuse les archives
     * sans base ou contenant des chemins qui sortiraient du dossier. Le serveur doit être arrêté.
     */
    fun extract(input: InputStream, targetDir: File) {
        targetDir.mkdirs()
        val root = targetDir.canonicalFile
        var hasDb = false
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory || e.name == MANIFEST) continue
                if (e.name != DB_ENTRY && e.name != PHONE_DB_ENTRY && !e.name.startsWith(STORAGE_PREFIX)) continue
                val dest = File(root, if (e.name == PHONE_DB_ENTRY) DB_ENTRY else e.name).canonicalFile
                if (!dest.path.startsWith(root.path + File.separator)) throw ApiException(422, "Sauvegarde invalide : chemin ${e.name}")
                dest.parentFile.mkdirs()
                dest.outputStream().use { zip.copyTo(it) }
                if (e.name == DB_ENTRY || e.name == PHONE_DB_ENTRY) hasDb = true
            }
        }
        if (!hasDb) throw ApiException(422, "Sauvegarde invalide : base de données absente")
        val head = File(root, DB_ENTRY).inputStream().use { it.readNBytes(16) }
        if (!String(head, Charsets.US_ASCII).startsWith("SQLite format 3")) throw ApiException(422, "Sauvegarde invalide : base illisible")
    }
}
