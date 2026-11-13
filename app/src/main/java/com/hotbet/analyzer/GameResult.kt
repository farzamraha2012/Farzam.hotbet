package com.example.hotbetanalyzer

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "game_results",
    foreignKeys = [
        ForeignKey(
            entity = GameSession::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("sessionId"),
        Index(value = ["sessionId", "sequenceNumber"], unique = true)
    ]
)
data class GameResult(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val sessionId: Long,

    val sequenceNumber: Int,

    val multiplier: Double,

    val recordedAt: Long = System.currentTimeMillis(),

    val source: String = "OCR",

    val ocrConfidence: Float? = null
)
