package com.joysong.app.ui.order

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.joysong.app.R
import com.joysong.app.domain.model.Order
import com.joysong.app.domain.model.Review
import com.joysong.app.domain.model.Settlement
import com.joysong.app.domain.repository.OrderRepository
import com.joysong.app.domain.repository.PaymentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OrderDetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val orderRepository: OrderRepository,
    private val paymentRepository: PaymentRepository
) : ViewModel() {

    private val _order = MutableStateFlow<Order?>(null)
    val order: StateFlow<Order?> = _order

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _payState = MutableStateFlow<OrderActionState>(OrderActionState.Idle)
    val payState: StateFlow<OrderActionState> = _payState

    private val _actionState = MutableStateFlow<OrderActionState>(OrderActionState.Idle)
    val actionState: StateFlow<OrderActionState> = _actionState

    private val _settlement = MutableStateFlow<Settlement?>(null)
    val settlement: StateFlow<Settlement?> = _settlement

    private val _deleteState = MutableStateFlow<OrderActionState>(OrderActionState.Idle)
    val deleteState: StateFlow<OrderActionState> = _deleteState

    private val _cancelState = MutableStateFlow<OrderActionState>(OrderActionState.Idle)
    val cancelState: StateFlow<OrderActionState> = _cancelState

    private val _cancelRefundState = MutableStateFlow<Result<String>?>(null)
    val cancelRefundState: StateFlow<Result<String>?> = _cancelRefundState

    private val _review = MutableStateFlow<Review?>(null)
    val review: StateFlow<Review?> = _review.asStateFlow()

    private val _reviewLoading = MutableStateFlow(false)
    val reviewLoading: StateFlow<Boolean> = _reviewLoading.asStateFlow()

    private val _deleteReviewMessage = MutableStateFlow<String?>(null)
    val deleteReviewMessage: StateFlow<String?> = _deleteReviewMessage.asStateFlow()

    fun loadOrder(orderId: String) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            orderRepository.getOrderById(orderId)
                .onSuccess {
                    _order.value = it
                    _isLoading.value = false
                    // Auto-load settlement for relevant statuses
                    if (it.status.name == "PENDING_SETTLEMENT" || it.status.name == "SETTLED") {
                        loadSettlement(orderId)
                    }
                    // Auto-load review when order has one
                    if (it.hasReview) {
                        loadReview(orderId)
                    }
                }
                .onFailure {
                    _error.value = it.message
                    _isLoading.value = false
                }
        }
    }

    fun payConsultationFee(orderId: String) {
        viewModelScope.launch {
            _payState.value = OrderActionState.Loading
            orderRepository.payConsultationFee(orderId)
                .onSuccess {
                    _payState.value = OrderActionState.Success
                    _order.value = it
                }
                .onFailure {
                    _payState.value = OrderActionState.Error(it.message ?: context.getString(R.string.payment_failed_msg))
                }
        }
    }

    fun payBalance(orderId: String) {
        viewModelScope.launch {
            _payState.value = OrderActionState.Loading
            orderRepository.payBalance(orderId)
                .onSuccess {
                    _payState.value = OrderActionState.Success
                    _order.value = it
                }
                .onFailure {
                    _payState.value = OrderActionState.Error(it.message ?: context.getString(R.string.payment_failed_msg))
                }
        }
    }

    fun confirmCompletion(orderId: String) {
        viewModelScope.launch {
            _actionState.value = OrderActionState.Loading
            orderRepository.confirmCompletion(orderId)
                .onSuccess {
                    _actionState.value = OrderActionState.Success
                    _order.value = it
                }
                .onFailure {
                    _actionState.value = OrderActionState.Error(it.message ?: "操作失败")
                }
        }
    }

    fun loadSettlement(orderId: String) {
        viewModelScope.launch {
            orderRepository.getSettlement(orderId)
                .onSuccess { _settlement.value = it }
                .onFailure { /* silent */ }
        }
    }

    fun deleteOrder(orderId: String) {
        viewModelScope.launch {
            _deleteState.value = OrderActionState.Loading
            orderRepository.deleteOrder(orderId)
                .onSuccess {
                    _deleteState.value = OrderActionState.Success
                }
                .onFailure {
                    _deleteState.value = OrderActionState.Error(it.message ?: "删除失败")
                }
        }
    }

    fun resetDeleteState() {
        _deleteState.value = OrderActionState.Idle
    }

    fun cancelOrder(orderId: String) {
        viewModelScope.launch {
            _cancelState.value = OrderActionState.Loading
            orderRepository.cancelOrder(orderId)
                .onSuccess {
                    _cancelState.value = OrderActionState.Success
                }
                .onFailure {
                    _cancelState.value = OrderActionState.Error(it.message ?: context.getString(R.string.cancel_failed_msg))
                }
        }
    }

    fun resetCancelState() {
        _cancelState.value = OrderActionState.Idle
    }

    fun resetPayState() {
        _payState.value = OrderActionState.Idle
    }

    fun resetActionState() {
        _actionState.value = OrderActionState.Idle
    }

    fun cancelRefund(orderId: String) {
        viewModelScope.launch {
            val result = paymentRepository.cancelRefund(orderId)
            _cancelRefundState.value = result
            if (result.isSuccess) {
                loadOrder(orderId)
            }
        }
    }

    fun resetCancelRefundState() {
        _cancelRefundState.value = null
    }

    private fun loadReview(orderId: String) {
        viewModelScope.launch {
            _reviewLoading.value = true
            orderRepository.getReviewByOrderId(orderId)
                .onSuccess { _review.value = it }
            _reviewLoading.value = false
        }
    }

    fun deleteReview(reviewId: String) {
        viewModelScope.launch {
            orderRepository.deleteReview(reviewId)
                .onSuccess {
                    _review.value = null
                    _deleteReviewMessage.value = "评价已删除"
                    _order.value?.let { loadOrder(it.id) }
                }
        }
    }

    fun clearDeleteReviewMessage() {
        _deleteReviewMessage.value = null
    }
}
