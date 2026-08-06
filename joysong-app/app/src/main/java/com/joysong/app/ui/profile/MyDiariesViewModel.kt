package com.joysong.app.ui.profile

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.joysong.app.domain.model.Diary
import com.joysong.app.domain.repository.DiaryRepository
import com.joysong.app.domain.repository.FileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

data class MyDiariesUiState(
    val diaries: List<Diary> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

sealed class PublishDiaryState {
    data object Idle : PublishDiaryState()
    data object Loading : PublishDiaryState()
    data object Success : PublishDiaryState()
    data class Error(val message: String) : PublishDiaryState()
}

sealed class DeleteState {
    data object Idle : DeleteState()
    data object Loading : DeleteState()
    data object Success : DeleteState()
    data class Error(val message: String) : DeleteState()
}

@HiltViewModel
class MyDiariesViewModel @Inject constructor(
    private val diaryRepository: DiaryRepository,
    private val fileRepository: FileRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MyDiariesUiState())
    val uiState: StateFlow<MyDiariesUiState> = _uiState

    private val _publishState = MutableStateFlow<PublishDiaryState>(PublishDiaryState.Idle)
    val publishState: StateFlow<PublishDiaryState> = _publishState

    private val _deleteState = MutableStateFlow<DeleteState>(DeleteState.Idle)
    val deleteState: StateFlow<DeleteState> = _deleteState

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing

    val uploadProgress = mutableStateOf(0f)
    val isUploading = mutableStateOf(false)

    init {
        loadDiaries()
    }

    fun loadDiaries() {
        viewModelScope.launch { loadDiariesFromApi() }
    }

    fun refreshDiaries() {
        _isRefreshing.value = true
        viewModelScope.launch {
            diaryRepository.getMyDiaries()
                .onSuccess {
                    val sorted = it.sortedWith(
                        compareByDescending<Diary> { it.createdAt }
                            .thenByDescending { it.publishDate }
                    )
                    _uiState.value = MyDiariesUiState(diaries = sorted)
                }
            _isRefreshing.value = false
        }
    }

    private suspend fun loadDiariesFromApi() {
        val isInitialLoad = _uiState.value.diaries.isEmpty()
        if (isInitialLoad) {
            _uiState.value = MyDiariesUiState(isLoading = true)
        }
        diaryRepository.getMyDiaries()
            .onSuccess {
                val sorted = it.sortedWith(
                    compareByDescending<Diary> { it.createdAt }
                        .thenByDescending { it.publishDate }
                )
                _uiState.value = MyDiariesUiState(diaries = sorted)
            }
            .onFailure {
                if (isInitialLoad) {
                    _uiState.value = MyDiariesUiState(error = it.message)
                }
            }
    }

    /**
     * Upload a single image from a Uri. Copies the URI content to a temp file then uploads.
     * Returns the uploaded URL on success, or null on failure.
     */
    suspend fun uploadImage(uri: Uri, context: Context): String? {
        return withContext(Dispatchers.IO) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri) ?: return@withContext null
                val tempFile = File.createTempFile("diary_", ".jpg", context.cacheDir)
                FileOutputStream(tempFile).use { out -> inputStream.copyTo(out) }
                inputStream.close()
                val result = fileRepository.uploadImage(tempFile, "diary")
                tempFile.delete()
                result.getOrNull()
            } catch (e: Exception) {
                null
            }
        }
    }

    suspend fun publishDiary(
        title: String,
        content: String,
        images: List<String>,
        tags: List<String>,
        rating: Int = 0,
        doctorId: String = "",
        projectId: String = "",
        institutionId: String = "",
        institutionProjectId: String = "",
        orderId: String = "",
        beforeImages: List<String> = emptyList(),
        afterImages: List<String> = emptyList(),
        status: String = "published"
    ) {
        _publishState.value = PublishDiaryState.Loading
        diaryRepository.publishDiary(
            title = title,
            content = content,
            images = images,
            tags = tags,
            rating = rating,
            doctorId = doctorId,
            projectId = projectId,
            institutionId = institutionId,
            institutionProjectId = institutionProjectId,
            orderId = orderId,
            beforeImages = beforeImages,
            afterImages = afterImages,
            status = status
        )
            .onSuccess {
                loadDiariesFromApi()
                _publishState.value = PublishDiaryState.Success
            }
            .onFailure { _publishState.value = PublishDiaryState.Error(it.message ?: "发布失败") }
    }

    suspend fun deleteDiary(diaryId: String) {
        _deleteState.value = DeleteState.Loading
        diaryRepository.deleteDiary(diaryId)
            .onSuccess {
                // 显示下拉刷新动画并重新加载列表
                _isRefreshing.value = true
                diaryRepository.getMyDiaries()
                    .onSuccess {
                        val sorted = it.sortedWith(
                            compareByDescending<Diary> { it.createdAt }
                                .thenByDescending { it.publishDate }
                        )
                        _uiState.value = MyDiariesUiState(diaries = sorted)
                    }
                _isRefreshing.value = false
                _deleteState.value = DeleteState.Success
            }
            .onFailure { _deleteState.value = DeleteState.Error(it.message ?: "删除失败") }
    }

    fun resetDeleteState() {
        _deleteState.value = DeleteState.Idle
    }

    suspend fun updateDiary(
        diaryId: String,
        title: String,
        content: String,
        images: List<String>,
        tags: List<String>,
        rating: Int = 0,
        doctorId: String = "",
        projectId: String = "",
        institutionId: String = "",
        institutionProjectId: String = "",
        orderId: String = "",
        beforeImages: List<String> = emptyList(),
        afterImages: List<String> = emptyList(),
        status: String = "published"
    ) {
        _publishState.value = PublishDiaryState.Loading
        diaryRepository.updateDiary(
            id = diaryId,
            title = title,
            content = content,
            images = images.joinToString(","),
            tags = tags.joinToString(","),
            rating = rating,
            doctorId = doctorId,
            projectId = projectId,
            institutionId = institutionId,
            institutionProjectId = institutionProjectId,
            orderId = orderId,
            beforeImages = beforeImages.joinToString(","),
            afterImages = afterImages.joinToString(","),
            status = status
        )
            .onSuccess {
                loadDiariesFromApi()
                _publishState.value = PublishDiaryState.Success
            }
            .onFailure { _publishState.value = PublishDiaryState.Error(it.message ?: "保存失败") }
    }

    fun resetPublishState() {
        _publishState.value = PublishDiaryState.Idle
    }
}
