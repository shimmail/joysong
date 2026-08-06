package com.joysong.app.data.local

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "joysong_prefs")

data class SavedAccount(
    val phone: String,
    val nickname: String = "",
    val avatar: String = "",
    val token: String,
    val refreshToken: String = ""
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("phone", phone)
        put("nickname", nickname)
        put("avatar", avatar)
        put("token", token)
        put("refreshToken", refreshToken)
    }

    companion object {
        fun fromJson(json: JSONObject): SavedAccount = SavedAccount(
            phone = json.getString("phone"),
            nickname = json.optString("nickname", ""),
            avatar = json.optString("avatar", ""),
            token = json.getString("token"),
            refreshToken = json.optString("refreshToken", "")
        )
    }
}

@Singleton
class TokenManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // DataStore keys for non-sensitive data
    private val userIdKey = stringPreferencesKey("user_id")
    private val rememberPhoneKey = stringPreferencesKey("remember_phone")
    private val collapsedCommentsKey = stringSetPreferencesKey("collapsed_comments")
    private val hiddenMessageIdsKey = stringSetPreferencesKey("hidden_message_ids")

    // DataStore keys used only during one-time migration (kept for reading old data)
    private object MigrationKeys {
        val JWT_TOKEN = stringPreferencesKey("jwt_token")
        val REMEMBER_PASSWORD = stringPreferencesKey("remember_password")
        val SAVED_ACCOUNTS = stringPreferencesKey("saved_accounts")
    }

    // ── 加密存储 ──────────────────────────────────────────────────────────────
    private var isEncryptedFallback = false

    private val encryptedPrefs: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            isEncryptedFallback = false
            EncryptedSharedPreferences.create(
                context,
                "joysong_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e("TokenManager", "Failed to init EncryptedSharedPreferences", e)
            // 降级到普通 SharedPreferences（安全性降低但不崩溃）
            isEncryptedFallback = true
            context.getSharedPreferences("joysong_secure_prefs_fallback", Context.MODE_PRIVATE)
        }
    }

    // MutableStateFlow 包装（替代 DataStore Flow）
    private val _tokenFlow = MutableStateFlow<String?>(null)
    private val _savedAccountsFlow = MutableStateFlow<List<SavedAccount>>(emptyList())

    val tokenFlow: Flow<String?> = _tokenFlow
    val savedAccountsFlow: Flow<List<SavedAccount>> = _savedAccountsFlow

    private val migrationJob: Job

    init {
        // 初始化 Flow 的当前值
        _tokenFlow.value = encryptedPrefs.getString("jwt_token", null)
        val accountsJson = encryptedPrefs.getString("saved_accounts", null)
        _savedAccountsFlow.value = if (accountsJson.isNullOrBlank()) emptyList() else parseSavedAccounts(accountsJson)

        // 异步执行迁移，不阻塞构造
        migrationJob = CoroutineScope(Dispatchers.IO).launch {
            migrateSensitiveData()
            // 迁移完成后重新读取
            _tokenFlow.value = encryptedPrefs.getString("jwt_token", null)
            val newAccountsJson = encryptedPrefs.getString("saved_accounts", null)
            _savedAccountsFlow.value = if (newAccountsJson.isNullOrBlank()) emptyList() else parseSavedAccounts(newAccountsJson)
        }
    }

    // ── 一次性数据迁移 ──────────────────────────────────────────────────────────
    private suspend fun migrateSensitiveData() {
        try {
            val prefs = encryptedPrefs
            if (prefs.getBoolean("migration_complete", false)) return

            val data = context.dataStore.data.first()

            data[MigrationKeys.JWT_TOKEN]?.let { token ->
                prefs.edit().putString("jwt_token", token).apply()
            }

            data[MigrationKeys.REMEMBER_PASSWORD]?.let { pwd ->
                prefs.edit().putString("remember_password", pwd).apply()
            }

            data[MigrationKeys.SAVED_ACCOUNTS]?.let { accounts ->
                prefs.edit().putString("saved_accounts", accounts).apply()
            }

            if (!isEncryptedFallback) {
                // 只在真正加密存储时才标记完成并清理旧数据
                prefs.edit().putBoolean("migration_complete", true).apply()
                // 从 DataStore 删除已迁移的敏感数据
                context.dataStore.edit {
                    it.remove(MigrationKeys.JWT_TOKEN)
                    it.remove(MigrationKeys.REMEMBER_PASSWORD)
                    it.remove(MigrationKeys.SAVED_ACCOUNTS)
                }
            }
        } catch (e: Exception) {
            Log.e("TokenManager", "Data migration failed", e)
            // 迁移失败不崩溃，用户需重新登录
        }
    }

    // ── Token ────────────────────────────────────────────────────────────────────
    suspend fun saveToken(token: String) {
        try {
            // 旧式单 Token 写入不能沿用另一个账号的 Refresh Token。
            encryptedPrefs.edit()
                .putString("jwt_token", token)
                .remove("refresh_token")
                .apply()
            _tokenFlow.value = token
        } catch (e: Exception) {
            Log.e("TokenManager", "saveToken failed", e)
        }
    }

    /** Access Token 与 Refresh Token 必须作为一组保存，避免轮换时出现半更新状态。 */
    suspend fun saveTokens(accessToken: String, refreshToken: String) {
        try {
            encryptedPrefs.edit()
                .putString("jwt_token", accessToken)
                .putString("refresh_token", refreshToken)
                .apply()
            _tokenFlow.value = accessToken
        } catch (e: Exception) {
            Log.e("TokenManager", "saveTokens failed", e)
        }
    }

    suspend fun getRefreshToken(): String? {
        migrationJob.join()
        return try {
            encryptedPrefs.getString("refresh_token", null)
        } catch (e: Exception) {
            Log.e("TokenManager", "getRefreshToken failed", e)
            null
        }
    }

    /** Refresh Token 轮换后同步当前已保存账号，避免切换回来时使用已撤销的旧令牌。 */
    suspend fun updateSavedAccountTokens(
        oldAccessToken: String,
        newAccessToken: String,
        newRefreshToken: String
    ) {
        if (oldAccessToken.isBlank()) return
        val accounts = getSavedAccounts()
        if (accounts.none { it.token == oldAccessToken }) return
        saveSavedAccounts(accounts.map { account ->
            if (account.token == oldAccessToken) {
                account.copy(token = newAccessToken, refreshToken = newRefreshToken)
            } else {
                account
            }
        })
    }

    suspend fun getToken(): String? {
        migrationJob.join()
        return try {
            encryptedPrefs.getString("jwt_token", null)
        } catch (e: Exception) {
            Log.e("TokenManager", "getToken failed", e)
            null
        }
    }

    suspend fun saveUserId(userId: String) {
        context.dataStore.edit { it[userIdKey] = userId }
    }

    suspend fun getUserId(): String? {
        return context.dataStore.data.map { it[userIdKey] }.first()
    }

    suspend fun clear() {
        // 清理加密存储（保留 saved_accounts）
        try {
            val editor = encryptedPrefs.edit()
            editor.remove("jwt_token")
            editor.remove("refresh_token")
            editor.remove("remember_password")
            // 保留 saved_accounts，不清除
            editor.apply()
        } catch (e: Exception) {
            Log.e("TokenManager", "clear encryptedPrefs failed", e)
        }
        // 清理 DataStore
        context.dataStore.edit {
            it.clear()
        }
        // 更新 Flow
        _tokenFlow.value = null
    }

    suspend fun isLoggedIn(): Boolean {
        return getToken() != null
    }

    // ── 记住密码 ────────────────────────────────────────────────────────────────
    /** 保存记住的账号密码（密码存入加密存储，手机号存入 DataStore） */
    suspend fun saveRememberedCredentials(phone: String, password: String) {
        context.dataStore.edit { it[rememberPhoneKey] = phone }
        try {
            encryptedPrefs.edit().putString("remember_password", password).apply()
        } catch (e: Exception) {
            Log.e("TokenManager", "saveRememberedCredentials failed", e)
        }
    }

    /** 获取记住的账号密码 */
    suspend fun getRememberedCredentials(): Pair<String, String>? {
        migrationJob.join()
        val phone = context.dataStore.data.map { it[rememberPhoneKey] }.first()
        val password = try {
            encryptedPrefs.getString("remember_password", null)
        } catch (e: Exception) {
            Log.e("TokenManager", "getRememberedCredentials failed", e)
            null
        }
        return if (!phone.isNullOrBlank() && !password.isNullOrBlank()) phone to password else null
    }

    /** 清除记住的账号密码 */
    suspend fun clearRememberedCredentials() {
        context.dataStore.edit { it.remove(rememberPhoneKey) }
        try {
            encryptedPrefs.edit().remove("remember_password").apply()
        } catch (e: Exception) {
            Log.e("TokenManager", "clearRememberedCredentials failed", e)
        }
    }

    // ── 评论 & 消息（保留在 DataStore） ────────────────────────────────────────
    /** 保存折叠的评论 ID 集合 */
    suspend fun saveCollapsedComments(ids: Set<String>) {
        context.dataStore.edit { it[collapsedCommentsKey] = ids }
    }

    /** 获取折叠的评论 ID 集合 */
    suspend fun getCollapsedComments(): Set<String> {
        return context.dataStore.data.map { it[collapsedCommentsKey] ?: emptySet() }.first()
    }

    suspend fun getHiddenMessageIds(): Set<String> {
        return context.dataStore.data.map { it[hiddenMessageIdsKey] ?: emptySet() }.first()
    }

    suspend fun hideMessage(messageId: String) {
        context.dataStore.edit {
            val current = it[hiddenMessageIdsKey] ?: emptySet()
            it[hiddenMessageIdsKey] = current + messageId
        }
    }

    val collapsedCommentsFlow: Flow<Set<String>> = context.dataStore.data.map {
        it[collapsedCommentsKey] ?: emptySet()
    }

    // ── 已保存账号管理 ──────────────────────────────────────────────────────────

    /** 保存账号列表 */
    suspend fun saveSavedAccounts(accounts: List<SavedAccount>) {
        val jsonArray = JSONArray()
        accounts.forEach { jsonArray.put(it.toJson()) }
        val json = jsonArray.toString()
        try {
            encryptedPrefs.edit().putString("saved_accounts", json).apply()
            _savedAccountsFlow.value = accounts
        } catch (e: Exception) {
            Log.e("TokenManager", "saveSavedAccounts failed", e)
        }
    }

    /** 获取账号列表 */
    suspend fun getSavedAccounts(): List<SavedAccount> {
        migrationJob.join()
        val json = try {
            encryptedPrefs.getString("saved_accounts", null)
        } catch (e: Exception) {
            Log.e("TokenManager", "getSavedAccounts failed", e)
            null
        } ?: return emptyList()
        return parseSavedAccounts(json)
    }

    /** 添加当前账号到列表（最多5个） */
    suspend fun addSavedAccount(account: SavedAccount) {
        val accounts = getSavedAccounts().toMutableList()
        val existingIndex = accounts.indexOfFirst { it.phone == account.phone }
        if (existingIndex >= 0) {
            accounts[existingIndex] = account
        } else {
            accounts.add(0, account)
        }
        while (accounts.size > 5) {
            accounts.removeAt(accounts.lastIndex)
        }
        saveSavedAccounts(accounts)
    }

    /** 删除账号 */
    suspend fun removeSavedAccount(phone: String) {
        val accounts = getSavedAccounts().filter { it.phone != phone }
        saveSavedAccounts(accounts)
    }

    /** 解析 JSON 字符串为账号列表 */
    private fun parseSavedAccounts(json: String): List<SavedAccount> {
        return try {
            val jsonArray = JSONArray(json)
            (0 until jsonArray.length()).map { i ->
                SavedAccount.fromJson(jsonArray.getJSONObject(i))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
