package com.example.domainhunter.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.switchMap
import com.example.domainhunter.data.db.AppDatabase
import com.example.domainhunter.data.model.Domain

enum class SortOrder { DEFAULT, EXPIRY_SOONEST, EXPIRY_LATEST }

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getInstance(app)
    private val _sessionId = MutableLiveData<Long>()
    private val _sortOrder = MutableLiveData(SortOrder.DEFAULT)

    val domains: LiveData<List<Domain>> = _sessionId.switchMap { id ->
        _sortOrder.switchMap { sort ->
            when (sort) {
                SortOrder.DEFAULT -> db.domainDao().getBySessionDefault(id)
                SortOrder.EXPIRY_SOONEST -> db.domainDao().getBySessionExpirySoonest(id)
                SortOrder.EXPIRY_LATEST -> db.domainDao().getBySessionExpiryLatest(id)
            }
        }
    }

    val sessions = db.sessionDao().getAll()

    fun setSession(id: Long) { _sessionId.value = id }
    fun setSort(order: SortOrder) { _sortOrder.value = order }
}
