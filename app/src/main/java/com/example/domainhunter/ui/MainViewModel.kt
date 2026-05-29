package com.example.domainhunter.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.switchMap
import com.example.domainhunter.data.db.AppDatabase
import com.example.domainhunter.data.model.Domain

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getInstance(app)
    private val _sessionId = MutableLiveData<Long>()
    private val _searchQuery = MutableLiveData<String>("")

    val domains: LiveData<List<Domain>> = _sessionId.switchMap { id ->
        _searchQuery.switchMap { query ->
            if (query.isBlank()) db.domainDao().getBySession(id)
            else db.domainDao().search(id, query)
        }
    }

    val sessions = db.sessionDao().getAll()

    fun setSession(id: Long) { _sessionId.value = id }
    fun setSearch(query: String) { _searchQuery.value = query }
}
