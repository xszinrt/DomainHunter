package com.example.domainhunter.data.db

import androidx.lifecycle.LiveData
import androidx.room.*
import com.example.domainhunter.data.model.ScanSession
import com.example.domainhunter.data.model.SessionStatus

@Dao
interface SessionDao {
    @Insert
    suspend fun insert(session: ScanSession): Long

    @Update
    suspend fun update(session: ScanSession)

    @Query("SELECT * FROM scan_sessions ORDER BY startTime DESC")
    fun getAll(): LiveData<List<ScanSession>>

    @Query("SELECT * FROM scan_sessions WHERE id = :id")
    suspend fun getById(id: Long): ScanSession?

    @Query("SELECT * FROM scan_sessions WHERE status = :status LIMIT 1")
    suspend fun getByStatus(status: SessionStatus): ScanSession?

    @Query("DELETE FROM scan_sessions WHERE id = :id")
    suspend fun delete(id: Long)
}
