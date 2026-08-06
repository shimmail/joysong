package com.joysong.app.ui.report

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.joysong.app.domain.repository.ReportRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ReportTarget(
    val targetType: String,
    val targetId: String
)

@HiltViewModel
class ReportViewModel @Inject constructor(
    private val reportRepository: ReportRepository
) : ViewModel() {

    val reportTarget = mutableStateOf<ReportTarget?>(null)
    val isSubmitting = mutableStateOf(false)
    val hasReported = mutableStateOf(false)

    fun showReport(targetType: String, targetId: String) {
        reportTarget.value = ReportTarget(targetType, targetId)
        hasReported.value = false
        // 检查是否已举报
        viewModelScope.launch {
            reportRepository.checkReported(targetType, targetId).onSuccess {
                hasReported.value = it
            }
        }
    }

    fun hideReport() {
        reportTarget.value = null
    }

    fun submitReport(reason: String, description: String?, onSuccess: () -> Unit, onError: (String) -> Unit) {
        val target = reportTarget.value ?: return
        isSubmitting.value = true
        viewModelScope.launch {
            reportRepository.submitReport(
                targetType = target.targetType,
                targetId = target.targetId,
                reason = reason,
                description = description
            ).onSuccess {
                hasReported.value = true
                isSubmitting.value = false
                onSuccess()
            }.onFailure { e ->
                isSubmitting.value = false
                onError(e.message ?: "举报失败")
            }
        }
    }
}
