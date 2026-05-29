package com.example.domainhunter.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "scan_sessions")
data class ScanSession(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val startTime: Long = System.currentTimeMillis(),
    val endTime: Long? = null,
    val totalDomains: Int = 0,
    val scannedDomains: Int = 0,
    val registeredCount: Int = 0,
    val failedCount: Int = 0,
    val lastScannedIndex: Int = 0,
    val status: SessionStatus = SessionStatus.RUNNING
)

enum class SessionStatus {
    RUNNING,
    PAUSED,
    COMPLETED,
    STOPPED
}
