package com.joysong.app.ui.order

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.joysong.app.R
import com.joysong.app.domain.model.Order
import com.joysong.app.domain.repository.OrderRepository
import com.joysong.app.domain.repository.PaymentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OrderListUiState(
    val orders: List<Order> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val selectedTab: Int = 0
)

sealed class OrderActionState {
    data object Idle : OrderActionState()
    data object Loading : OrderActionState()
    data object Success : OrderActionState()
    data class Error(val message: String) : OrderActionState()
}

@HiltViewModel
class OrderListViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val orderRepository: OrderRepository,
    private val paymentRepository: PaymentRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(OrderListUiState())
    val uiState: StateFlow<OrderListUiState> = _uiState

    private val _payState = MutableStateFlow<OrderActionState>(OrderActionState.Idle)
    val payState: StateFlow<OrderActionState> = _payState

    private val _cancelState = MutableStateFlow<OrderActionState>(OrderActionState.Idle)
    val cancelState: StateFlow<OrderActionState> = _cancelState

    private val _deleteState = MutableStateFlow<OrderActionState>(OrderActionState.Idle)
    val deleteState: StateFlow<OrderActionState> = _deleteState

    private val _confirmCompletionState = MutableStateFlow<OrderActionState>(OrderActionState.Idle)
    val confirmCompletionState: StateFlow<OrderActionState> = _confirmCompletionState

    private val _cancelRefundState = MutableStateFlow<OrderActionState>(OrderActionState.Idle)
    val cancelRefundState: StateFlow<OrderActionState> = _cancelRefundState

    init {
        loadOrders()
    }

    fun loadOrders() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            orderRepository.getOrders()
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        orders = it,
                        isLoading = false,
                        error = null
                    )
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = it.message
                    )
                }
        }
    }

    fun refreshOrders() {
        _uiState.value = _uiState.value.copy(orders = emptyList())
        loadOrders()
    }

    fun selectTab(index: Int) {
        _uiState.value = _uiState.value.copy(selectedTab = index)
    }

    fun payOrder(orderId: String, method: String = "balance") {
        viewModelScope.launch {
            _payState.value = OrderActionState.Loading
            paymentRepository.payOrder(orderId, method)
                .onSuccess {
                    _payState.value = OrderActionState.Success
                    loadOrders()
                }
                .onFailure { _payState.value = OrderActionState.Error(it.message ?: context.getString(R.string.payment_failed_msg)) }
        }
    }

    fun cancelOrder(orderId: String) {
        viewModelScope.launch {
            _cancelState.value = OrderActionState.Loading
            orderRepository.cancelOrder(orderId)
                .onSuccess {
                    _cancelState.value = OrderActionState.Success
                    loadOrders()
                }
                .onFailure { _cancelState.value = OrderActionState.Error(it.message ?: context.getString(R.string.cancel_failed_msg)) }
        }
    }

    fun deleteOrder(orderId: String) {
        viewModelScope.launch {
            _deleteState.value = OrderActionState.Loading
            orderRepository.deleteOrder(orderId)
                .onSuccess {
                    _deleteState.value = OrderActionState.Success
                    loadOrders()
                }
                .onFailure { _deleteState.value = OrderActionState.Error(it.message ?: "删除失败") }
        }
    }

    fun confirmCompletion(orderId: String) {
        viewModelScope.launch {
            _confirmCompletionState.value = OrderActionState.Loading
            orderRepository.confirmCompletion(orderId)
                .onSuccess {
                    _confirmCompletionState.value = OrderActionState.Success
                    loadOrders()
                }
                .onFailure { _confirmCompletionState.value = OrderActionState.Error(it.message ?: "确认完成失败") }
        }
    }

    fun cancelRefund(orderId: String) {
        viewModelScope.launch {
            _cancelRefundState.value = OrderActionState.Loading
            paymentRepository.cancelRefund(orderId)
                .onSuccess {
                    _cancelRefundState.value = OrderActionState.Success
                    loadOrders()
                }
                .onFailure { _cancelRefundState.value = OrderActionState.Error(it.message ?: context.getString(R.string.cancel_failed_msg)) }
        }
    }

    fun resetActionStates() {
        _payState.value = OrderActionState.Idle
        _cancelState.value = OrderActionState.Idle
        _deleteState.value = OrderActionState.Idle
        _confirmCompletionState.value = OrderActionState.Idle
        _cancelRefundState.value = OrderActionState.Idle
    }
}
