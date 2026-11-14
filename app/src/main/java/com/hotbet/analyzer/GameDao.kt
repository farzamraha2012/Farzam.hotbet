
package com.example.hotbetanalyzer

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface GameDao {

    // Create a new analysis session.
    @Insert
    suspend fun createSession(
        session: GameSession
    ): Long

    // Save a multiplier.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertResult(
        result: GameResult
    ): Long

    // Observe all sessions.
    @Query(
        "SELECT * FROM game_sessions ORDER BY startedAt DESC"
    )
    fun observeSessions(): Flow<List<GameSession>>

    // Retrieve results of one session.
    @Query(
        "SELECT * FROM game_results " +
        "WHERE sessionId = :sessionId " +
        "ORDER BY sequenceNumber ASC"
    )
    suspend fun getSessionResults(
        sessionId: Long
    ): List<GameResult>

    // Retrieve complete history, newest first.
    @Query(
        "SELECT * FROM game_results " +
        "ORDER BY recordedAt DESC, id DESC"
    )
    suspend fun getAllResults(): List<GameResult>

    // Retrieve active session.
    @Query(
        "SELECT * FROM game_sessions " +
        "WHERE isActive = 1 LIMIT 1"
    )
    suspend fun getActiveSession(): GameSession?

    // Finish all active sessions.
    @Query(
        "UPDATE game_sessions " +
        "SET isActive = 0, endedAt = :endedAt " +
        "WHERE isActive = 1"
    )
    suspend fun finishAllActiveSessions(
        endedAt: Long = System.currentTimeMillis()
    )

    // Finish a specific session.
    @Query(
        "UPDATE game_sessions " +
        "SET isActive = 0, endedAt = :endedAt " +
        "WHERE id = :sessionId"
    )
    suspend fun finishSession(
        sessionId: Long,
        endedAt: Long = System.currentTimeMillis()
    )

    // Update session result count.
    @Query(
        "UPDATE game_sessions SET totalResults = " +
        "(SELECT COUNT(*) FROM game_results " +
        "WHERE sessionId = :sessionId) " +
        "WHERE id = :sessionId"
    )
    suspend fun refreshSessionCount(
        sessionId: Long
    )

    // Delete a session and its dependent results.
    @Query(
        "DELETE FROM game_sessions WHERE id = :sessionId"
    )
    suspend fun deleteSession(
        sessionId: Long
    )
}
