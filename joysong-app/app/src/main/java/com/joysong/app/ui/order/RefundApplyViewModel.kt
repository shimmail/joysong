package com.joysong.app.ui.order

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.joysong.app.R
import com.joysong.app.domain.model.Order
import com.joysong.app.domain.repository.FileRepository
import com.joysong.app.domain.repository.OrderRepository
import com.joysong.app.domain.repository.PaymentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

sealed class RefundSubmitState {
    data object Idle : RefundSubmitState()
    data object Loading : RefundSubmitState()
    data object Success : RefundSubmitState()
    data class Error(val message: String) : RefundSubmitState()
}

@HiltViewModel
class RefundApplyViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val orderRepository: OrderRepository,
    private val paymentRepository: PaymentRepository,
    private val fileRepository: FileRepository
) : ViewModel() {

    private val _order = MutableStateFlow<Order?>(null)
    val order: StateFlow<Order?> = _order

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _submitState = MutableStateFlow<RefundSubmitState>(RefundSubmitState.Idle)
    val submitState: StateFlow<RefundSubmitState> = _submitState

    fun loadOrder(orderId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            orderRepository.getOrderById(orderId)
                .onSuccess {
                    _order.value = it
                    _isLoading.value = false
                }
                .onFailure {
                    _error.value = it.message
                    _isLoading.value = false
                }
        }
    }

    suspend fun uploadImage(uri: Uri, context: Context): String? {
        return withContext(Dispatchers.IO) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri) ?: return@withContext null
                val tempFile = File.createTempFile("refund_", ".jpg", context.cacheDir)
                FileOutputStream(tempFile).use { out -> inputStream.copyTo(out) }
                inputStream.close()
                val result = fileRepository.uploadImage(tempFile, "refund")
                tempFile.delete()
                result.getOrNull()
            } catch (e: Exception) {
                null
            }
        }
    }

    fun applyRefund(orderId: String, reason: String, description: String, evidenceUrls: List<String>) {
        viewModelScope.launch {
            _submitState.value = RefundSubmitState.Loading
            val evidenceUrl = evidenceUrls.joinToString(",")
            paymentRepository.refundOrder(orderId, reason, description, evidenceUrl)
                .onSuccess { _submitState.value = RefundSubmitState.Success }
                .onFailure { _submitState.value = RefundSubmitState.Error(it.message ?: appContext.getString(R.string.submit_failed_msg)) }
        }
    }

    fun resetSubmitState() {
        _submitState.value = RefundSubmitState.Idle
    }
}
