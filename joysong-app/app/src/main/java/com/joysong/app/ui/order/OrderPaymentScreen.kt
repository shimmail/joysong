package com.joysong.app.ui.order

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.widget.Toast
import com.joysong.app.R
import com.joysong.app.domain.model.Order
import com.joysong.app.domain.repository.OrderRepository
import com.joysong.app.ui.components.JoysongTopBar
import com.joysong.app.ui.components.LoadingIndicator
import com.joysong.app.ui.theme.Background
import com.joysong.app.ui.theme.PrimaryDark
import com.joysong.app.ui.theme.Surface
import com.joysong.app.ui.theme.TextHint
import com.joysong.app.ui.theme.TextOnPrimary
import com.joysong.app.ui.theme.TextPrimary
import com.joysong.app.ui.theme.TextSecondary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// ── ViewModel ────────────────────────────────────────────────────────────────

data class OrderPaymentUiState(
    val order: Order? = null,
    val isLoading: Boolean = true,
    val isPaying: Boolean = false,
    val paySuccess: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class OrderPaymentViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val orderRepository: OrderRepository
) : ViewModel() {

    val orderId: String = savedStateHandle.get<String>("orderId") ?: ""
    val paymentType: String = savedStateHandle.get<String>("paymentType") ?: "consultation"

    private val _uiState = MutableStateFlow(OrderPaymentUiState())
    val uiState: StateFlow<OrderPaymentUiState> = _uiState

    init {
        loadOrder()
    }

    private fun loadOrder() {
        if (orderId.isBlank()) {
            _uiState.value = OrderPaymentUiState(isLoading = false, error = "订单ID无效")
            return
        }
        viewModelScope.launch {
            _uiState.value = OrderPaymentUiState(isLoading = true)
            orderRepository.getOrderById(orderId)
                .onSuccess {
                    _uiState.value = OrderPaymentUiState(order = it, isLoading = false)
                }
                .onFailure {
                    _uiState.value = OrderPaymentUiState(isLoading = false, error = it.message)
                }
        }
    }

    fun confirmPayment() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isPaying = true, error = null)
            val result = when (paymentType) {
                "balance" -> orderRepository.payBalance(orderId)
                else -> orderRepository.payConsultationFee(orderId)
            }
            result
                .onSuccess {
                    _uiState.value = _uiState.value.copy(isPaying = false, paySuccess = true, order = it)
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        isPaying = false,
                        error = it.message ?: "支付失败"
                    )
                }
        }
    }
}

// ── Screen ───────────────────────────────────────────────────────────────────

@Composable
fun OrderPaymentScreen(
    onBackClick: () -> Unit,
    onPaySuccess: (orderId: String) -> Unit,
    viewModel: OrderPaymentViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(uiState.paySuccess) {
        if (uiState.paySuccess) {
            Toast.makeText(context, "支付成功", Toast.LENGTH_SHORT).show()
            onPaySuccess(viewModel.orderId)
        }
    }

    val title = when (viewModel.paymentType) {
        "balance" -> "支付尾款"
        else -> "支付面诊金"
    }

    Scaffold(
        topBar = {
            JoysongTopBar(title = title, onBackClick = onBackClick)
        }
    ) { innerPadding ->
        when {
            uiState.isLoading -> LoadingIndicator()
            uiState.error != null && uiState.order == null -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(80.dp))
                    Text(text = uiState.error ?: "加载失败", color = TextSecondary, fontSize = 15.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { viewModel.confirmPayment() },
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryDark)
                    ) { Text(stringResource(R.string.retry), color = TextOnPrimary) }
                }
            }
            uiState.order != null -> {
                val order = uiState.order!!
                val amount = when (viewModel.paymentType) {
                    "balance" -> order.remainingAmount
                    else -> order.consultationFee
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .verticalScroll(rememberScrollState())
                        .background(Background)
                ) {
                    // Order info card
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Surface)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = order.institutionName,
                                fontSize = 13.sp,
                                color = TextSecondary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = order.projectName,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    text = when (viewModel.paymentType) {
                                        "balance" -> "尾款金额"
                                        else -> "面诊金"
                                    },
                                    fontSize = 14.sp,
                                    color = TextSecondary
                                )
                                Spacer(modifier = Modifier.weight(1f))
                                Text(
                                    text = String.format("¥%.2f", amount),
                                    fontSize = 22.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = PrimaryDark
                                )
                            }
                        }
                    }

                    // Fee breakdown
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Surface)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
                            ) {
                                Text("面诊金", fontSize = 13.sp, color = TextSecondary)
                                Text(String.format("¥%.2f", order.consultationFee), fontSize = 13.sp, color = TextPrimary)
                            }
                            if (order.remainingAmount > 0) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
                                ) {
                                    Text("尾款", fontSize = 13.sp, color = TextSecondary)
                                    Text(String.format("¥%.2f", order.remainingAmount), fontSize = 13.sp, color = TextPrimary)
                                }
                            }
                            if (order.couponDiscount > 0) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
                                ) {
                                    Text("优惠", fontSize = 13.sp, color = PrimaryDark)
                                    Text("-¥${order.couponDiscount.toInt()}", fontSize = 13.sp, color = PrimaryDark)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Confirm button
                    Button(
                        onClick = { viewModel.confirmPayment() },
                        enabled = !uiState.isPaying,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .height(50.dp),
                        shape = RoundedCornerShape(25.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryDark)
                    ) {
                        if (uiState.isPaying) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .width(20.dp)
                                    .height(20.dp),
                                color = TextOnPrimary,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.payment_processing),
                                color = TextOnPrimary,
                                fontWeight = FontWeight.SemiBold
                            )
                        } else {
                            Text(
                                text = "确认支付 ${String.format("¥%.2f", amount)}",
                                color = TextOnPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp
                            )
                        }
                    }

                    if (uiState.error != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = uiState.error ?: "",
                            color = com.joysong.app.ui.theme.Error,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}
