package com.example.domainhunter.data.db

import androidx.lifecycle.LiveData
import androidx.room.*
import com.example.domainhunter.data.model.Domain
import com.example.domainhunter.data.model.DomainStatus

@Dao
interface DomainDao {
    @Insert
    suspend fun insert(domain: Domain): Long

    @Query("SELECT * FROM domains WHERE sessionId = :sessionId ORDER BY id ASC")
    fun getBySessionDefault(sessionId: Long): LiveData<List<Domain>>

    @Query("SELECT * FROM domains WHERE sessionId = :sessionId ORDER BY expirationDate ASC")
    fun getBySessionExpirySoonest(sessionId: Long): LiveData<List<Domain>>

    @Query("SELECT * FROM domains WHERE sessionId = :sessionId ORDER BY expirationDate DESC")
    fun getBySessionExpiryLatest(sessionId: Long): LiveData<List<Domain>>

    @Query("SELECT * FROM domains WHERE sessionId = :sessionId AND status = :status ORDER BY id ASC")
    fun getByStatus(sessionId: Long, status: DomainStatus): LiveData<List<Domain>>

    @Query("DELETE FROM domains WHERE sessionId = :sessionId")
    suspend fun deleteBySession(sessionId: Long)
}
