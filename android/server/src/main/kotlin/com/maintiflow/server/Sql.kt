package com.maintiflow.server

/** Une ligne de résultat : nom de colonne -> Long, Double, String, ByteArray ou null. */
typealias Row = Map<String, Any?>

/**
 * Accès SQLite minimal, fourni par l'hôte : Android (SupportSQLiteDatabase) dans l'application,
 * JDBC dans les tests. Toutes les dates sont stockées en millisecondes UTC (INTEGER).
 */
interface Sql {
    fun exec(sql: String, vararg args: Any?)
    fun query(sql: String, vararg args: Any?): List<Row>
    fun <T> transaction(block: () -> T): T

    fun lastInsertId(): Long = query("SELECT last_insert_rowid() AS id").first().long("id")
    fun changes(): Long = query("SELECT changes() AS n").first().long("n")
    fun one(sql: String, vararg args: Any?): Row? = query(sql, *args).firstOrNull()
}

/** Violation d'une contrainte (unicité, clé étrangère, déclencheur de traçabilité). */
class SqlConstraintException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Convertit un argument Kotlin en valeur SQLite (Boolean -> 0/1, Int -> Long). */
fun sqlValue(v: Any?): Any? = when (v) {
    null -> null
    is Boolean -> if (v) 1L else 0L
    is Int -> v.toLong()
    is Short -> v.toLong()
    is Byte -> v.toLong()
    is Long, is Double, is String, is ByteArray -> v
    is Float -> v.toDouble()
    is Enum<*> -> v.name
    else -> v.toString()
}

fun Row.long(k: String): Long = (this[k] as Number).toLong()
fun Row.longOrNull(k: String): Long? = (this[k] as Number?)?.toLong()
fun Row.int(k: String): Int = long(k).toInt()
fun Row.str(k: String): String = this[k] as String
fun Row.strOrNull(k: String): String? = this[k] as String?
fun Row.bool(k: String): Boolean = long(k) != 0L
fun Row.bytes(k: String): ByteArray? = this[k] as ByteArray?
