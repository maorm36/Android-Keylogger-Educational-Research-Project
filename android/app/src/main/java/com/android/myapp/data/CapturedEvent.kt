package com.android.myapp.data

import android.graphics.Rect
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "captured_events")
data class CapturedEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val timestamp: Long,
    val packageName: String,
    val className: String?,
    val eventType: String,

    // Text content (will be encrypted in storage)
    val text: String?,
    val contentDescription: String? = null,

    // Field information
    val viewId: String? = null,
    val isPassword: Boolean = false,

    // Sensitivity classification (from ML)
    val isSensitive: Boolean = false,
    val sensitivityType: String? = null, // "password", "credit_card", "pii", etc.
    val confidence: Float? = null,

    // Session grouping
    val sessionId: Long? = null
)

@Entity(tableName = "sessions")
data class Session(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    val startTime: Long,
    val endTime: Long? = null,
    val packageName: String,
    val totalKeystrokes: Int = 0,
    val sensitiveDataCount: Int = 0
)

data class SensitivityClassification(
    val isSensitive: Boolean,
    val type: String?,
    val confidence: Float
)

/**
 * Visual tree representation (for Android 14+ where screenshots are limited)
 */
data class VisualTree(
    val bounds: Rect,
    val text: String?,
    val contentDescription: String?,
    val viewId: String?,
    val className: String?,
    val isPassword: Boolean,
    val children: List<VisualTree> = emptyList()
)