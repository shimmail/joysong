package com.joysong.app.ui.profile

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.joysong.app.data.remote.dto.IdentityOverviewDto
import com.joysong.app.data.remote.dto.PrivateIdentityFileDto
import com.joysong.app.data.repository.IdentityRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class IdentityVerificationUiState(
    val overview: IdentityOverviewDto = IdentityOverviewDto(),
    val isLoading: Boolean = false,
    val isSubmitting: Boolean = false,
    val uploadingTypes: Set<String> = emptySet(),
    val uploadedFiles: Map<String, PrivateIdentityFileDto> = emptyMap(),
    val errorMessage: String? = null,
    val submitSuccess: Boolean = false
)

@HiltViewModel
class IdentityVerificationViewModel @Inject constructor(
    private val repository: IdentityRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(IdentityVerificationUiState())
    val uiState: StateFlow<IdentityVerificationUiState> = _uiState.asStateFlow()

    init {
        loadOverview()
    }

    fun loadOverview() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, errorMessage = null) }
            repository.getOverview()
                .onSuccess { overview -> _uiState.update { it.copy(overview = overview, isLoading = false) } }
                .onFailure { error -> _uiState.update { it.copy(isLoading = false, errorMessage = error.message ?: "认证状态加载失败") } }
        }
    }

    fun uploadDocument(type: String, uri: Uri) {
        if (type in _uiState.value.uploadingTypes) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(uploadingTypes = it.uploadingTypes + type, errorMessage = null)
            }
            repository.uploadDocument(uri, type)
                .onSuccess { file ->
                    val replacedFile = _uiState.value.uploadedFiles[type]
                    _uiState.update {
                        it.copy(
                            uploadingTypes = it.uploadingTypes - type,
                            uploadedFiles = it.uploadedFiles + (type to file)
                        )
                    }
                    if (replacedFile != null && replacedFile.fileId != file.fileId) {
                        repository.deleteDraftFile(replacedFile.fileId)
                    }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(uploadingTypes = it.uploadingTypes - type, errorMessage = error.message ?: "材料上传失败")
                    }
                }
        }
    }

    fun removeDocument(type: String) {
        val file = _uiState.value.uploadedFiles[type]
        _uiState.update { it.copy(uploadedFiles = it.uploadedFiles - type) }
        if (file != null) {
            viewModelScope.launch {
                repository.deleteDraftFile(file.fileId).onFailure { error ->
                    _uiState.update { it.copy(errorMessage = error.message ?: "材料删除失败") }
                }
            }
        }
    }

    fun submit(roleCode: String, data: Map<String, String>) {
        if (_uiState.value.isSubmitting) return
        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errorMessage = null, submitSuccess = false) }
            repository.submitApplication(roleCode, data, _uiState.value.uploadedFiles)
                .onSuccess {
                    _uiState.update { state -> state.copy(isSubmitting = false, submitSuccess = true) }
                }
                .onFailure { error ->
                    _uiState.update { state -> state.copy(isSubmitting = false, errorMessage = error.message ?: "认证申请提交失败") }
                }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(errorMessage = null) }
    }
}
