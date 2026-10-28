package com.maintiflow.pro.master

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteException
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.maintiflow.server.Row
import com.maintiflow.server.Sql
import com.maintiflow.server.SqlConstraintException
import com.maintiflow.server.sqlValue

/**
 * Base SQLite du serveur embarqué, dans le stockage privé de l'application.
 * Une seule connexion, accès sérialisés : le serveur traite de toute façon une requête à la fois.
 */
class AndroidSql(context: Context, val name: String) : Sql, AutoCloseable {
    private val lock = Any()
    private var depth = 0
    private val helper: SupportSQLiteOpenHelper = FrameworkSQLiteOpenHelperFactory().create(
        SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(name)
            .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                override fun onConfigure(db: SupportSQLiteDatabase) = db.setForeignKeyConstraintsEnabled(true)
                override fun onCreate(db: SupportSQLiteDatabase) = Unit  // schéma créé par Schema.migrate
                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
            })
            .build(),
    )
    private val db: SupportSQLiteDatabase get() = helper.writableDatabase

    private fun <T> wrap(block: () -> T): T = try {
        block()
    } catch (e: SQLiteConstraintException) {
        throw SqlConstraintException(e.message ?: "contrainte", e)
    } catch (e: SQLiteException) {
        if (e.message?.contains("MaintiFlow") == true || e.message?.contains("constraint", true) == true) {
            throw SqlConstraintException(e.message ?: "contrainte", e)
        }
        throw e
    }

    override fun exec(sql: String, vararg args: Any?) = synchronized(lock) {
        wrap {
            if (args.isEmpty()) db.execSQL(sql) else db.execSQL(sql, args.map { sqlValue(it) }.toTypedArray())
        }
    }

    override fun query(sql: String, vararg args: Any?): List<Row> = synchronized(lock) {
        wrap {
            db.query(SimpleSQLiteQuery(sql, args.map { sqlValue(it) }.toTypedArray())).use { c ->
                val out = ArrayList<Row>(c.count)
                while (c.moveToNext()) {
                    val row = LinkedHashMap<String, Any?>(c.columnCount)
                    for (i in 0 until c.columnCount) {
                        row[c.getColumnName(i)] = when (c.getType(i)) {
                            Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                            Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                            Cursor.FIELD_TYPE_STRING -> c.getString(i)
                            Cursor.FIELD_TYPE_BLOB -> c.getBlob(i)
                            else -> null
                        }
                    }
                    out += row
                }
                out
            }
        }
    }

    override fun <T> transaction(block: () -> T): T {
        synchronized(lock) {
            if (depth > 0) return block()
            val d = db
            d.beginTransaction()
            depth++
            try {
                val r = block()
                d.setTransactionSuccessful()
                return r
            } finally {
                depth--
                d.endTransaction()
            }
        }
    }

    /** Exécute [block] sans aucune requête concurrente (copie de sauvegarde). */
    fun <T> exclusive(block: () -> T): T = synchronized(lock) { block() }

    /** Force l'écriture du journal WAL dans le fichier principal avant une copie. */
    fun checkpoint() = synchronized(lock) {
        runCatching { db.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() } }
        Unit
    }

    override fun close() = synchronized(lock) { helper.close() }
}
