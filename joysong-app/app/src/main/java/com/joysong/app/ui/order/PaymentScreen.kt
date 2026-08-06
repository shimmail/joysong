package com.joysong.app.ui.order

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.joysong.app.R
import com.joysong.app.domain.model.Project
import com.joysong.app.domain.repository.DiscoverRepository
import com.joysong.app.domain.repository.OrderRepository
import com.joysong.app.domain.repository.PaymentRepository
import com.joysong.app.ui.components.JoysongTopBar
import com.joysong.app.ui.components.LoadingIndicator
import com.joysong.app.ui.theme.Background
import com.joysong.app.ui.theme.Error
import com.joysong.app.ui.theme.Primary
import com.joysong.app.ui.theme.PrimaryDark
import com.joysong.app.ui.theme.PrimaryLight
import com.joysong.app.ui.theme.Surface
import com.joysong.app.ui.theme.SurfaceVariant
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

data class PaymentUiState(
    val project: Project? = null,
    val isLoading: Boolean = true,
    val isPaying: Boolean = false,
    val orderId: String? = null,
    val error: String? = null,
    val paySuccess: Boolean = false,
    val selectedCouponId: Long? = null,
    val selectedCouponName: String? = null,
    val selectedCouponDiscount: Double = 0.0
)

@HiltViewModel
class PaymentViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val discoverRepository: DiscoverRepository,
    private val orderRepository: OrderRepository,
    private val paymentRepository: PaymentRepository
) : ViewModel() {

    val projectId: String = savedStateHandle.get<String>("projectId") ?: ""
    val institutionId: String = savedStateHandle.get<String>("institutionId") ?: ""
    val doctorName: String = savedStateHandle.get<String>("doctorName")
        ?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: ""
    val appointmentTime: String = savedStateHandle.get<String>("appointmentTime")
        ?.let { java.net.URLDecoder.decode(it, "UTF-8") } ?: ""

    private val _uiState = MutableStateFlow(PaymentUiState())
    val uiState: StateFlow<PaymentUiState> = _uiState

    private val consultationFee = 25

    private val savedStateHandle = savedStateHandle

    init {
        loadProject()
        observeSelectedCoupon()
    }

    private fun observeSelectedCoupon() {
        viewModelScope.launch {
            savedStateHandle.getStateFlow<String?>("selectedCoupon", null).collect { json ->
                if (json != null) {
                    try {
                        val obj = org.json.JSONObject(json)
                        _uiState.value = _uiState.value.copy(
                            selectedCouponId = obj.getLong("id"),
                            selectedCouponName = obj.getString("couponName"),
                            selectedCouponDiscount = obj.getDouble("discountValue")
                        )
                    } catch (_: Exception) {}
                }
            }
        }
    }

    fun clearCoupon() {
        _uiState.value = _uiState.value.copy(
            selectedCouponId = null,
            selectedCouponName = null,
            selectedCouponDiscount = 0.0
        )
        savedStateHandle.set("selectedCoupon", null)
    }

    private fun loadProject() {
        if (projectId.isBlank()) {
            _uiState.value = PaymentUiState(isLoading = false)
            return
        }
        viewModelScope.launch {
            _uiState.value = PaymentUiState(isLoading = true)
            discoverRepository.getProjectById(projectId)
                .onSuccess { detail ->
                    _uiState.value = PaymentUiState(project = detail.project, isLoading = false)
                }
                .onFailure {
                    _uiState.value = PaymentUiState(isLoading = false, error = it.message)
                }
        }
    }

    fun confirmPayment() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isPaying = true, error = null)
            // Step 1: Create order
            orderRepository.createOrder(projectId)
                .onSuccess { order ->
                    _uiState.value = _uiState.value.copy(orderId = order.id)
                    // Step 2: Pay the order (simulated with "online" method)
                    paymentRepository.payOrder(order.id, "online")
                        .onSuccess {
                            _uiState.value = _uiState.value.copy(isPaying = false, paySuccess = true)
                        }
                        .onFailure {
                            _uiState.value = _uiState.value.copy(
                                isPaying = false,
                                error = it.message ?: "Payment failed"
                            )
                        }
                }
                .onFailure {
                    _uiState.value = _uiState.value.copy(
                        isPaying = false,
                        error = it.message ?: "Failed to create order"
                    )
                }
        }
    }

    fun getConsultationFee(): Int = consultationFee
}

// ── Screen ───────────────────────────────────────────────────────────────────

@Composable
fun PaymentScreen(
    onBackClick: () -> Unit,
    onPaySuccess: (orderId: String, institutionName: String, doctorName: String, appointmentTime: String) -> Unit,
    onSelectCouponClick: () -> Unit,
    viewModel: PaymentViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(uiState.paySuccess) {
        if (uiState.paySuccess) {
            val institutionName = ""
            onPaySuccess(
                uiState.orderId ?: "",
                institutionName,
                viewModel.doctorName,
                viewModel.appointmentTime
            )
        }
    }

    Scaffold(
        topBar = {
            JoysongTopBar(
                title = stringResource(R.string.payment_title),
                onBackClick = onBackClick
            )
        }
    ) { innerPadding ->
        when {
            uiState.isLoading -> LoadingIndicator()
            uiState.error != null && uiState.project == null -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Spacer(modifier = Modifier.height(80.dp))
                    Text(
                        text = uiState.error ?: stringResource(R.string.payment_failed),
                        color = TextSecondary,
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { viewModel.confirmPayment() },
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryDark)
                    ) { Text(stringResource(R.string.retry), color = TextOnPrimary) }
                }
            }
            else -> {
                val project = uiState.project
                val fee = viewModel.getConsultationFee()

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .verticalScroll(rememberScrollState())
                        .background(Background)
                ) {
                    // Order info card
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Surface)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "",
                                fontSize = 13.sp,
                                color = TextSecondary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = project?.name ?: stringResource(R.string.project_placeholder),
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.payment_includes),
                                fontSize = 13.sp,
                                color = TextSecondary
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    text = stringResource(R.string.consultation_fee_amount, fee),
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = PrimaryDark
                                )
                            }
                        }
                    }

                    // Coupon selection row
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .clickable { onSelectCouponClick() },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Surface)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "优惠券",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = TextPrimary
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            if (uiState.selectedCouponName != null) {
                                Text(
                                    text = uiState.selectedCouponName ?: "",
                                    fontSize = 13.sp,
                                    color = PrimaryDark,
                                    maxLines = 1
                                )
                            } else {
                                Text(
                                    text = "请选择优惠券",
                                    fontSize = 13.sp,
                                    color = TextHint
                                )
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = TextHint,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // Price summary (only show when coupon is selected)
                    if (uiState.selectedCouponDiscount > 0) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = Surface)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "原价",
                                        fontSize = 13.sp,
                                        color = TextSecondary
                                    )
                                    Spacer(modifier = Modifier.weight(1f))
                                    Text(
                                        text = "¥$fee",
                                        fontSize = 13.sp,
                                        color = TextSecondary
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "优惠",
                                        fontSize = 13.sp,
                                        color = PrimaryDark
                                    )
                                    Spacer(modifier = Modifier.weight(1f))
                                    Text(
                                        text = "-¥${uiState.selectedCouponDiscount.toInt()}",
                                        fontSize = 13.sp,
                                        color = PrimaryDark
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "实付",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary
                                    )
                                    Spacer(modifier = Modifier.weight(1f))
                                    Text(
                                        text = "¥${(fee - uiState.selectedCouponDiscount).coerceAtLeast(0.0).toInt()}",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = PrimaryDark
                                    )
                                }
                            }
                        }
                    }

                    // Payment method section
                    Text(
                        text = stringResource(R.string.select_payment_method),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )

                    // Online payment option (simulated)
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .border(1.dp, Primary, RoundedCornerShape(12.dp)),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Surface)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CreditCard,
                                contentDescription = null,
                                tint = PrimaryDark,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.online_payment),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = TextPrimary
                                )
                                Text(
                                    text = stringResource(R.string.pay_alipay_desc),
                                    fontSize = 12.sp,
                                    color = TextSecondary
                                )
                            }
                            RadioButton(selected = true, onClick = null)
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Confirm payment button
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
                                modifier = Modifier.size(20.dp),
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
                                text = stringResource(R.string.confirm_pay),
                                color = TextOnPrimary,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}
