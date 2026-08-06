package com.joysong.app.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.searchHistoryDataStore: DataStore<Preferences> by preferencesDataStore(name = "search_history_prefs")

@Singleton
class SearchHistoryManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val historyKey = stringPreferencesKey("search_history")
    private val maxHistorySize = 20

    /** 搜索历史 Flow（实时响应变化） */
    val searchHistoryFlow: Flow<List<String>> = context.searchHistoryDataStore.data.map { prefs ->
        decode(prefs[historyKey])
    }

    suspend fun getSearchHistory(): List<String> {
        return context.searchHistoryDataStore.data.first().let { prefs ->
            decode(prefs[historyKey])
        }
    }

    suspend fun addSearchQuery(query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        context.searchHistoryDataStore.edit { prefs ->
            val current = decode(prefs[historyKey]).toMutableList()
            current.remove(trimmed)
            current.add(0, trimmed)
            val result = current.take(maxHistorySize)
            prefs[historyKey] = encode(result)
        }
    }

    suspend fun removeSearchQuery(query: String) {
        context.searchHistoryDataStore.edit { prefs ->
            val current = decode(prefs[historyKey]).toMutableList()
            current.remove(query)
            prefs[historyKey] = encode(current)
        }
    }

    suspend fun clearSearchHistory() {
        context.searchHistoryDataStore.edit { prefs ->
            prefs.remove(historyKey)
        }
    }

    private fun encode(list: List<String>): String = list.joinToString("\t")
    private fun decode(raw: String?): List<String> =
        raw?.takeIf { it.isNotBlank() }?.split("\t")?.filter { it.isNotBlank() } ?: emptyList()
}
