package com.maintiflow.server

import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/** Accès SQLite par JDBC pour les tests sur la JVM (l'application utilise SQLite d'Android). */
class JdbcSql(url: String = "jdbc:sqlite::memory:") : Sql {
    private val conn: Connection = DriverManager.getConnection(url).also {
        it.createStatement().use { s -> s.execute("PRAGMA foreign_keys = ON") }
    }
    private var depth = 0

    private fun <T> wrap(block: () -> T): T = try {
        block()
    } catch (e: SQLException) {
        if (e.message?.contains("CONSTRAINT", ignoreCase = true) == true) throw SqlConstraintException(e.message ?: "", e)
        throw e
    }

    override fun exec(sql: String, vararg args: Any?) = wrap {
        conn.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a -> st.setObject(i + 1, sqlValue(a)) }
            st.execute()
        }
        Unit
    }

    override fun query(sql: String, vararg args: Any?): List<Row> = wrap {
        conn.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a -> st.setObject(i + 1, sqlValue(a)) }
            st.executeQuery().use { rs ->
                val md = rs.metaData
                val out = mutableListOf<Row>()
                while (rs.next()) {
                    val row = LinkedHashMap<String, Any?>()
                    for (c in 1..md.columnCount) {
                        row[md.getColumnLabel(c)] = when (val v = rs.getObject(c)) {
                            is Int -> v.toLong()
                            else -> v
                        }
                    }
                    out += row
                }
                out
            }
        }
    }

    override fun <T> transaction(block: () -> T): T {
        if (depth > 0) return block()
        conn.autoCommit = false
        depth++
        try {
            val r = block()
            conn.commit()
            return r
        } catch (e: Throwable) {
            conn.rollback()
            throw e
        } finally {
            depth--
            conn.autoCommit = true
        }
    }
}
