package com.joysong.app.ui.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.joysong.app.data.local.SavedAccount
import com.joysong.app.data.local.TokenManager
import com.joysong.app.domain.model.User
import com.joysong.app.data.repository.MessageCenter
import com.joysong.app.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class AuthUiState {
    data object Idle : AuthUiState()
    data object Loading : AuthUiState()
    data class Success(val user: User) : AuthUiState()
    data class Error(val message: String) : AuthUiState()
}

/** 冷启动认证状态：Loading=正在检查 Token，Authenticated=已登录，Unauthenticated=未登录 */
sealed class AuthState {
    data object Loading : AuthState()
    data object Authenticated : AuthState()
    data object Unauthenticated : AuthState()
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val tokenManager: TokenManager,
    private val messageCenter: MessageCenter
) : ViewModel() {

    /** 冷启动认证状态：观察 token 变化，DataStore 加载完成后自动切换 */
    val authState: StateFlow<AuthState> = tokenManager.tokenFlow
        .map { if (it != null) AuthState.Authenticated else AuthState.Unauthenticated }
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, AuthState.Loading)

    private val _loginState = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val loginState: StateFlow<AuthUiState> = _loginState

    private val _registerState = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val registerState: StateFlow<AuthUiState> = _registerState

    private val _resetPasswordState = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val resetPasswordState: StateFlow<AuthUiState> = _resetPasswordState

    private val _codeState = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val codeState: StateFlow<AuthUiState> = _codeState

    private val _switchState = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val switchState: StateFlow<AuthUiState> = _switchState

    /** 已保存的账号列表 */
    val savedAccounts: StateFlow<List<SavedAccount>> = tokenManager.savedAccountsFlow
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptyList())

    fun login(phone: String, password: String) {
        viewModelScope.launch {
            _loginState.value = AuthUiState.Loading
            authRepository.login(phone, password)
                .onSuccess { user ->
                    _loginState.value = AuthUiState.Success(user)
                    user.token?.let { token ->
                        tokenManager.addSavedAccount(SavedAccount(
                            phone = user.phone ?: phone,
                            nickname = user.nickname,
                            avatar = user.avatar,
                            token = token,
                            refreshToken = tokenManager.getRefreshToken().orEmpty()
                        ))
                    }
                }
                .onFailure { _loginState.value = AuthUiState.Error(it.message ?: "登录失败") }
        }
    }

    fun loginWithCode(phone: String, code: String) {
        viewModelScope.launch {
            _loginState.value = AuthUiState.Loading
            authRepository.loginWithCode(phone, code)
                .onSuccess { user ->
                    _loginState.value = AuthUiState.Success(user)
                    user.token?.let { token ->
                        tokenManager.addSavedAccount(SavedAccount(
                            phone = user.phone ?: phone,
                            nickname = user.nickname,
                            avatar = user.avatar,
                            token = token,
                            refreshToken = tokenManager.getRefreshToken().orEmpty()
                        ))
                    }
                }
                .onFailure { _loginState.value = AuthUiState.Error(it.message ?: "登录失败") }
        }
    }

    fun loginWithGoogle(idToken: String) {
        viewModelScope.launch {
            _loginState.value = AuthUiState.Loading
            authRepository.loginWithGoogle(idToken)
                .onSuccess { user ->
                    _loginState.value = AuthUiState.Success(user)
                    user.token?.let { token ->
                        tokenManager.addSavedAccount(SavedAccount(
                            phone = user.phone ?: "google_${user.id}",
                            nickname = user.nickname,
                            avatar = user.avatar,
                            token = token,
                            refreshToken = tokenManager.getRefreshToken().orEmpty()
                        ))
                    }
                }
                .onFailure { _loginState.value = AuthUiState.Error(it.message ?: "Google 登录失败") }
        }
    }

    fun register(phone: String, code: String, password: String) {
        viewModelScope.launch {
            _registerState.value = AuthUiState.Loading
            authRepository.register(phone, code, password)
                .onSuccess { _registerState.value = AuthUiState.Success(it) }
                .onFailure { _registerState.value = AuthUiState.Error(it.message ?: "注册失败") }
        }
    }

    fun resetPassword(phone: String, code: String, newPassword: String) {
        viewModelScope.launch {
            _resetPasswordState.value = AuthUiState.Loading
            authRepository.resetPassword(phone, code, newPassword)
                .onSuccess { _resetPasswordState.value = AuthUiState.Success(User(id = "")) }
                .onFailure { _resetPasswordState.value = AuthUiState.Error(it.message ?: "重置失败") }
        }
    }

    fun sendCode(phone: String) {
        viewModelScope.launch {
            _codeState.value = AuthUiState.Loading
            authRepository.sendVerificationCode(phone)
                .onSuccess { _codeState.value = AuthUiState.Success(User(id = "")) }
                .onFailure { _codeState.value = AuthUiState.Error(it.message ?: "发送失败") }
        }
    }

    /** 重置密码场景发送验证码：先检查手机号是否已注册，已注册才发送并回调 onSent */
    fun sendCodeForResetPassword(phone: String, onNotRegistered: () -> Unit, onSent: () -> Unit) {
        viewModelScope.launch {
            _codeState.value = AuthUiState.Loading
            authRepository.checkPhoneRegistered(phone)
                .onSuccess { registered ->
                    if (!registered) {
                        _codeState.value = AuthUiState.Idle
                        onNotRegistered()
                        return@launch
                    }
                    authRepository.sendVerificationCode(phone)
                        .onSuccess {
                            _codeState.value = AuthUiState.Success(User(id = ""))
                            onSent()
                        }
                        .onFailure { _codeState.value = AuthUiState.Error(it.message ?: "发送失败") }
                }
                .onFailure { _codeState.value = AuthUiState.Error(it.message ?: "检查失败") }
        }
    }

    fun resetStates() {
        _loginState.value = AuthUiState.Idle
        _registerState.value = AuthUiState.Idle
        _resetPasswordState.value = AuthUiState.Idle
        _codeState.value = AuthUiState.Idle
        _switchState.value = AuthUiState.Idle
    }

    fun logout() {
        viewModelScope.launch {
            messageCenter.clearAll()
            authRepository.logout()
        }
    }

    /** 切换账号：直接替换 token，失败时回滚 */
    fun switchAccount(account: SavedAccount) {
        viewModelScope.launch {
            _switchState.value = AuthUiState.Loading
            val previousToken = tokenManager.getToken()
            val previousRefreshToken = tokenManager.getRefreshToken()
            val previousUserId = tokenManager.getUserId()
            // 直接替换 token，不经过 clear()，避免 authState 中间态
            if (account.refreshToken.isNotBlank()) {
                tokenManager.saveTokens(account.token, account.refreshToken)
            } else {
                tokenManager.saveToken(account.token)
            }
            authRepository.getUserProfile()
                .onSuccess { user ->
                    tokenManager.saveUserId(user.id)
                    messageCenter.clearAll()
                    // 更新已保存账号的最新信息
                    tokenManager.addSavedAccount(SavedAccount(
                        phone = user.phone ?: account.phone,
                        nickname = user.nickname,
                        avatar = user.avatar,
                        token = account.token,
                        refreshToken = account.refreshToken
                    ))
                    _switchState.value = AuthUiState.Success(user)
                }
                .onFailure {
                    // 回滚到之前的 token
                    if (previousToken != null) {
                        if (!previousRefreshToken.isNullOrBlank()) {
                            tokenManager.saveTokens(previousToken, previousRefreshToken)
                        } else {
                            tokenManager.saveToken(previousToken)
                        }
                        previousUserId?.let { tokenManager.saveUserId(it) }
                    } else {
                        tokenManager.clear()
                    }
                    _switchState.value = AuthUiState.Error(it.message ?: "切换失败")
                }
        }
    }

    /** 删除已保存的账号 */
    fun removeSavedAccount(phone: String) {
        viewModelScope.launch {
            tokenManager.removeSavedAccount(phone)
        }
    }

    /** 注销账号（永久删除） */
    private val _deleteState = MutableStateFlow<AuthUiState>(AuthUiState.Idle)
    val deleteState: StateFlow<AuthUiState> = _deleteState

    fun deleteAccount() {
        viewModelScope.launch {
            _deleteState.value = AuthUiState.Loading
            authRepository.deleteAccount()
                .onSuccess {
                    _deleteState.value = AuthUiState.Success(User(id = ""))
                }
                .onFailure { _deleteState.value = AuthUiState.Error(it.message ?: "注销失败") }
        }
    }

    /** 加载记住的账号密码 */
    suspend fun getRememberedCredentials(): Pair<String, String>? {
        return tokenManager.getRememberedCredentials()
    }

    /** 保存/清除记住的账号密码 */
    fun saveRememberedCredentials(phone: String, password: String, remember: Boolean) {
        viewModelScope.launch {
            if (remember) {
                tokenManager.saveRememberedCredentials(phone, password)
            } else {
                tokenManager.clearRememberedCredentials()
            }
        }
    }
}
