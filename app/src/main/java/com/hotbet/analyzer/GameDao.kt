package com.example.hotbetanalyzer

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface GameDao {

    // Create a new game session
    @Insert
    suspend fun createSession(
        session: GameSession
    ): Long

    // Save a new multiplier
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertResult(
        result: GameResult
    ): Long

    // Observe all saved sessions
    @Query(
        "SELECT * FROM game_sessions ORDER BY startedAt DESC"
    )
    fun observeSessions(): Flow<List<GameSession>>

    // Get results of one session
    @Query(
        "SELECT * FROM game_results " +
        "WHERE sessionId = :sessionId " +
        "ORDER BY sequenceNumber ASC"
    )
    suspend fun getSessionResults(
        sessionId: Long
    ): List<GameResult>

    // Get all saved results
    @Query(
        "SELECT * FROM game_results ORDER BY recordedAt DESC"
    )
    suspend fun getAllResults(): List<GameResult>

    // Get all multipliers in chronological order
    @Query(
        "SELECT multiplier FROM game_results " +
        "ORDER BY recordedAt ASC, id ASC"
    )
    suspend fun getAllMultipliers(): List<Double>

    // Find active session
    @Query(
        "SELECT * FROM game_sessions WHERE isActive = 1 LIMIT 1"
    )
    suspend fun getActiveSession(): GameSession?

    // Finish all previously active sessions
    @Query(
        "UPDATE game_sessions SET isActive = 0, endedAt = :endedAt " +
        "WHERE isActive = 1"
    )
    suspend fun finishAllActiveSessions(
        endedAt: Long
    )

    // Finish one session
    @Query(
        "UPDATE game_sessions SET isActive = 0, endedAt = :endedAt " +
        "WHERE id = :sessionId"
    )
    suspend fun finishSession(
        sessionId: Long,
        endedAt: Long = System.currentTimeMillis()
    )

    // Update total results count
    @Query(
        "UPDATE game_sessions SET totalResults = " +
        "(SELECT COUNT(*) FROM game_results WHERE sessionId = :sessionId) " +
        "WHERE id = :sessionId"
    )
    suspend fun refreshSessionCount(
        sessionId: Long
    )

    // Delete a session if explicitly requested
    @Query(
        "DELETE FROM game_sessions WHERE id = :sessionId"
    )
    suspend fun deleteSession(
        sessionId: Long
    )
}
