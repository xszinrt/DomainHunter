package com.example.domainhunter.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "domains")
data class Domain(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sessionId: Long,
    val domainName: String,
    val registrationDate: String?,
    val expirationDate: String?,
    val isExpiringSoon: Boolean = false,
    val status: DomainStatus = DomainStatus.PENDING
)

enum class DomainStatus {
    PENDING,
    REGISTERED,
    FAILED
}
