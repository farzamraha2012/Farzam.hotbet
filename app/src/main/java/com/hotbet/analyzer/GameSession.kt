 package com.example.hotbetanalyzer

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "game_sessions")
data class GameSession(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val startedAt: Long = System.currentTimeMillis(),

    val endedAt: Long? = null,

    val totalResults: Int = 0,

    val isActive: Boolean = true
)
