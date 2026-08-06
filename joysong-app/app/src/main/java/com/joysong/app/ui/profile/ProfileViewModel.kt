package com.joysong.app.ui.profile

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.joysong.app.data.local.TokenManager
import com.joysong.app.data.remote.ApiService
import com.joysong.app.data.remote.dto.ChangePhoneRequestDto
import com.joysong.app.data.remote.dto.SendCodeRequestDto
import com.joysong.app.data.remote.dto.VerifyCodeRequestDto
import com.joysong.app.domain.model.User
import com.joysong.app.domain.repository.AuthRepository
import com.joysong.app.domain.repository.FileRepository
import com.joysong.app.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

sealed class ProfileUiState {
    data object Idle : ProfileUiState()
    data object Loading : ProfileUiState()
    data class Success(val user: User) : ProfileUiState()
    data class Error(val message: String) : ProfileUiState()
}

sealed class ProfileActionState {
    data object Idle : ProfileActionState()
    data object Loading : ProfileActionState()
    data object Success : ProfileActionState()
    data class Error(val message: String) : ProfileActionState()
}

@HiltViewModel
class ProfileViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val authRepository: AuthRepository,
    private val userRepository: UserRepository,
    private val fileRepository: FileRepository,
    private val tokenManager: TokenManager,
    private val apiService: ApiService
) : ViewModel() {

    private val _user = MutableStateFlow<User?>(null)
    val user: StateFlow<User?> = _user

    private val _uiState = MutableStateFlow<ProfileUiState>(ProfileUiState.Idle)
    val uiState: StateFlow<ProfileUiState> = _uiState

    private val _updateState = MutableStateFlow<ProfileActionState>(ProfileActionState.Idle)
    val updateState: StateFlow<ProfileActionState> = _updateState

    private val _bindPhoneState = MutableStateFlow<ProfileActionState>(ProfileActionState.Idle)
    val bindPhoneState: StateFlow<ProfileActionState> = _bindPhoneState

    private val _bindEmailState = MutableStateFlow<ProfileActionState>(ProfileActionState.Idle)
    val bindEmailState: StateFlow<ProfileActionState> = _bindEmailState

    private val _changePasswordState = MutableStateFlow<ProfileActionState>(ProfileActionState.Idle)
    val changePasswordState: StateFlow<ProfileActionState> = _changePasswordState

    private val _sendCodeState = MutableStateFlow<ProfileActionState>(ProfileActionState.Idle)
    val sendCodeState: StateFlow<ProfileActionState> = _sendCodeState
    private val _phoneChangeState = MutableStateFlow<ProfileActionState>(ProfileActionState.Idle)
    val phoneChangeState: StateFlow<ProfileActionState> = _phoneChangeState
    private val _maskedCurrentPhone = MutableStateFlow("")
    val maskedCurrentPhone: StateFlow<String> = _maskedCurrentPhone

    private val _avatarUploadState = MutableStateFlow<AvatarUploadState>(AvatarUploadState.Idle)
    val avatarUploadState: StateFlow<AvatarUploadState> = _avatarUploadState

    init {
        loadUser()
        // 监听 token 变化，账号切换后自动刷新用户信息
        viewModelScope.launch {
            tokenManager.tokenFlow
                .drop(1)
                .collect {
                    loadUser()
                }
        }
    }

    fun loadUser() {
        viewModelScope.launch {
            _uiState.value = ProfileUiState.Loading
            userRepository.getUserProfile()
                .onSuccess {
                    _user.value = it
                    _uiState.value = ProfileUiState.Success(it)
                }
                .onFailure { _uiState.value = ProfileUiState.Error(it.message ?: "加载失败") }
        }
    }

    fun updateProfile(nickname: String, avatar: String, city: String, bio: String, gender: String, birthday: String) {
        viewModelScope.launch {
            _updateState.value = ProfileActionState.Loading
            userRepository.updateProfile(nickname, avatar, city, bio, gender, birthday)
                .onSuccess {
                    _user.value = it
                    _updateState.value = ProfileActionState.Success
                }
                .onFailure { _updateState.value = ProfileActionState.Error(it.message ?: "保存失败") }
        }
    }

    fun bindPhone(phone: String, code: String) {
        viewModelScope.launch {
            _bindPhoneState.value = ProfileActionState.Loading
            userRepository.bindPhone(phone, code)
                .onSuccess { _bindPhoneState.value = ProfileActionState.Success }
                .onFailure { _bindPhoneState.value = ProfileActionState.Error(it.message ?: "绑定失败") }
        }
    }

    fun bindEmail(email: String, code: String) {
        viewModelScope.launch {
            _bindEmailState.value = ProfileActionState.Loading
            userRepository.bindEmail(email, code)
                .onSuccess { _bindEmailState.value = ProfileActionState.Success }
                .onFailure { _bindEmailState.value = ProfileActionState.Error(it.message ?: "绑定失败") }
        }
    }

    fun changePassword(oldPassword: String, newPassword: String) {
        viewModelScope.launch {
            _changePasswordState.value = ProfileActionState.Loading
            userRepository.changePassword(oldPassword, newPassword)
                .onSuccess { _changePasswordState.value = ProfileActionState.Success }
                .onFailure { _changePasswordState.value = ProfileActionState.Error(it.message ?: "修改失败") }
        }
    }

    fun setPassword(phone: String, code: String, newPassword: String) {
        viewModelScope.launch {
            _changePasswordState.value = ProfileActionState.Loading
            userRepository.setPassword(phone, code, newPassword)
                .onSuccess { _changePasswordState.value = ProfileActionState.Success }
                .onFailure { _changePasswordState.value = ProfileActionState.Error(it.message ?: "设置失败") }
        }
    }

    fun sendVerificationCode(phone: String) {
        viewModelScope.launch {
            _sendCodeState.value = ProfileActionState.Loading
            authRepository.sendVerificationCode(phone)
                .onSuccess { _sendCodeState.value = ProfileActionState.Success }
                .onFailure { _sendCodeState.value = ProfileActionState.Error(it.message ?: "发送失败") }
        }
    }

    fun sendCurrentPhoneChangeCode() {
        viewModelScope.launch {
            _phoneChangeState.value = ProfileActionState.Loading
            try {
                val response = apiService.sendCurrentPhoneChangeCode()
                if (response.code == 200 && response.data != null) {
                    _maskedCurrentPhone.value = response.data["maskedPhone"].orEmpty()
                    _phoneChangeState.value = ProfileActionState.Success
                } else _phoneChangeState.value = ProfileActionState.Error(response.message)
            } catch (error: Exception) {
                _phoneChangeState.value = ProfileActionState.Error(error.message ?: "发送失败")
            }
        }
    }

    fun verifyCurrentPhoneChangeCode(code: String) {
        viewModelScope.launch {
            _phoneChangeState.value = ProfileActionState.Loading
            try {
                val response = apiService.verifyCurrentPhoneChangeCode(VerifyCodeRequestDto(code))
                _phoneChangeState.value = if (response.code == 200) ProfileActionState.Success else ProfileActionState.Error(response.message)
            } catch (error: Exception) {
                _phoneChangeState.value = ProfileActionState.Error(error.message ?: "验证失败")
            }
        }
    }

    fun sendNewPhoneChangeCode(phone: String) {
        viewModelScope.launch {
            _sendCodeState.value = ProfileActionState.Loading
            try {
                val response = apiService.sendNewPhoneChangeCode(SendCodeRequestDto(phone))
                _sendCodeState.value = if (response.code == 200) ProfileActionState.Success else ProfileActionState.Error(response.message)
            } catch (error: Exception) {
                _sendCodeState.value = ProfileActionState.Error(error.message ?: "发送失败")
            }
        }
    }

    fun changePhone(phone: String, code: String) {
        viewModelScope.launch {
            _bindPhoneState.value = ProfileActionState.Loading
            try {
                val response = apiService.changePhone(ChangePhoneRequestDto(phone, code))
                _bindPhoneState.value = if (response.code == 200) ProfileActionState.Success else ProfileActionState.Error(response.message)
            } catch (error: Exception) {
                _bindPhoneState.value = ProfileActionState.Error(error.message ?: "修改失败")
            }
        }
    }

    fun resetActionStates() {
        _updateState.value = ProfileActionState.Idle
        _bindPhoneState.value = ProfileActionState.Idle
        _bindEmailState.value = ProfileActionState.Idle
        _changePasswordState.value = ProfileActionState.Idle
        _sendCodeState.value = ProfileActionState.Idle
    }

    fun uploadAvatar(file: File) {
        viewModelScope.launch {
            _avatarUploadState.value = AvatarUploadState.Uploading
            val userId = _user.value?.id
            val customFileName = if (userId != null) "avatar_$userId" else null
            fileRepository.uploadImage(file, "avatar", customFileName)
                .onSuccess { url -> _avatarUploadState.value = AvatarUploadState.Success(url) }
                .onFailure { _avatarUploadState.value = AvatarUploadState.Error(it.message ?: "上传失败") }
        }
    }

    fun resetAvatarUploadState() {
        _avatarUploadState.value = AvatarUploadState.Idle
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
        }
    }
}

/**
 * 头像上传状态
 */
sealed class AvatarUploadState {
    data object Idle : AvatarUploadState()
    data object Uploading : AvatarUploadState()
    data class Success(val url: String) : AvatarUploadState()
    data class Error(val message: String) : AvatarUploadState()
}


