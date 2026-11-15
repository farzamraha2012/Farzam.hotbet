package com.example.hotbetanalyzer

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

import kotlinx.coroutines.flow.Flow

@Dao
interface GameDao {

    @Insert
    suspend fun createSession(
        session: GameSession
    ): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertResult(
        result: GameResult
    ): Long

    @Query(
        "SELECT * FROM game_sessions ORDER BY startedAt DESC"
    )
    fun observeSessions(): Flow<List<GameSession>>

    @Query(
        "SELECT * FROM game_results " +
        "WHERE sessionId = :sessionId " +
        "ORDER BY sequenceNumber ASC"
    )
    suspend fun getSessionResults(
        sessionId: Long
    ): List<GameResult>

    @Query(
        "SELECT * FROM game_results " +
        "ORDER BY recordedAt DESC, id DESC"
    )
    suspend fun getAllResults(): List<GameResult>

    // Load all historical multipliers in chronological order.
    // Oldest first, newest last.

    @Query(
        "SELECT multiplier FROM game_results " +
        "ORDER BY recordedAt ASC, id ASC"
    )
    suspend fun getAllMultipliers(): List<Double>

    @Query(
        "SELECT * FROM game_sessions " +
        "WHERE isActive = 1 LIMIT 1"
    )
    suspend fun getActiveSession(): GameSession?

    @Query(
        "UPDATE game_sessions " +
        "SET isActive = 0, endedAt = :endedAt " +
        "WHERE id = :sessionId"
    )
    suspend fun finishSession(
        sessionId: Long,
        endedAt: Long = System.currentTimeMillis()
    )

    // Close any session left active after an unexpected shutdown.

    @Query(
        "UPDATE game_sessions " +
        "SET isActive = 0, endedAt = :endedAt " +
        "WHERE isActive = 1"
    )
    suspend fun finishAllActiveSessions(
        endedAt: Long = System.currentTimeMillis()
    )

    @Query(
        "UPDATE game_sessions SET totalResults = " +
        "(SELECT COUNT(*) FROM game_results " +
        "WHERE sessionId = :sessionId) " +
        "WHERE id = :sessionId"
    )
    suspend fun refreshSessionCount(
        sessionId: Long
    )

    // Manual deletion only. Never called automatically.

    @Query(
        "DELETE FROM game_sessions WHERE id = :sessionId"
    )
    suspend fun deleteSession(
        sessionId: Long
    )
}
