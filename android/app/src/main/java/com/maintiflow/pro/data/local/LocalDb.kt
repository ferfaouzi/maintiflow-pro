package com.maintiflow.pro.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** Cache de consultation hors ligne (référentiels, listes, fiches de bons). */
@Entity(tableName = "cache")
data class CacheEntry(
    @PrimaryKey val key: String,
    val json: String,
    val updatedAt: Long = System.currentTimeMillis(),
)

/**
 * Bon créé sans réseau. Le client_uuid garantit qu'un renvoi ne crée jamais de doublon côté serveur.
 * Le numéro BI définitif est attribué par le serveur à la synchronisation.
 */
@Entity(tableName = "pending_tickets")
data class PendingTicket(
    @PrimaryKey val clientUuid: String,
    val payloadJson: String,
    val photoPaths: String = "",
    val summary: String,
    val createdAt: Long = System.currentTimeMillis(),
    val attempts: Int = 0,
    val lastError: String? = null,
)

@Dao
interface CacheDao {
    @Query("SELECT * FROM cache WHERE `key` = :key")
    suspend fun get(key: String): CacheEntry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: CacheEntry)

    @Query("DELETE FROM cache")
    suspend fun clear()
}

@Dao
interface PendingDao {
    @Query("SELECT * FROM pending_tickets ORDER BY createdAt")
    fun observeAll(): Flow<List<PendingTicket>>

    @Query("SELECT * FROM pending_tickets ORDER BY createdAt")
    suspend fun all(): List<PendingTicket>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(p: PendingTicket)

    @Query("DELETE FROM pending_tickets WHERE clientUuid = :id")
    suspend fun delete(id: String)

    @Query("UPDATE pending_tickets SET attempts = attempts + 1, lastError = :error WHERE clientUuid = :id")
    suspend fun markFailed(id: String, error: String?)
}

@Database(entities = [CacheEntry::class, PendingTicket::class], version = 1, exportSchema = true)
abstract class LocalDb : RoomDatabase() {
    abstract fun cache(): CacheDao
    abstract fun pending(): PendingDao

    companion object {
        fun build(context: Context): LocalDb =
            Room.databaseBuilder(context, LocalDb::class.java, "maintiflow.db").build()
    }
}
