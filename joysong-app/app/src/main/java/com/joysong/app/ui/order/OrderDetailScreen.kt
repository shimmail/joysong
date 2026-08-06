package com.joysong.app.ui.order

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

import com.joysong.app.data.remote.dto.toEpochMilli
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Event
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import com.joysong.app.R
import com.joysong.app.domain.model.Order
import com.joysong.app.domain.model.OrderStatus
import com.joysong.app.domain.model.Review
import com.joysong.app.domain.model.Settlement
import com.joysong.app.ui.components.ErrorView
import com.joysong.app.ui.components.JoysongTopBar
import com.joysong.app.ui.components.LoadingIndicator
import com.joysong.app.ui.components.StarRatingBar
import com.joysong.app.ui.theme.Background
import com.joysong.app.ui.theme.Error
import com.joysong.app.ui.theme.JoysongTheme
import com.joysong.app.ui.theme.PrimaryDark
import com.joysong.app.ui.theme.Success
import com.joysong.app.ui.theme.Surface
import com.joysong.app.ui.theme.TextHint
import com.joysong.app.ui.theme.TextOnPrimary
import com.joysong.app.ui.theme.TextPrimary
import com.joysong.app.ui.theme.TextSecondary
import coil.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import com.joysong.app.ui.navigation.Routes
import com.joysong.app.ui.theme.Warning

// ── Status colors ─────────────────────────────────────────────────────────────

private val StatusPending = Color(0xFFFF9800)
private val StatusToUse = Color(0xFFFFC107)
private val StatusCompleted = Color(0xFF4CAF50)
private val StatusRefund = Color(0xFFF44336)
private val StatusCancelled = Color(0xFF9E9E9E)
private val StatusVerified = Color(0xFF2196F3)
private val StatusSettlement = Color(0xFF673AB7)

// ── Main composable ───────────────────────────────────────────────────────────

@Composable
fun OrderDetailScreen(
    orderId: String,
    navController: NavHostController,
    viewModel: OrderDetailViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val paymentSuccessToast = stringResource(R.string.payment_success_toast)
    val operationSuccessToast = stringResource(R.string.operation_success)
    val orderDeletedToast = stringResource(R.string.order_deleted)
    val orderCancelledToast = stringResource(R.string.order_cancelled_toast)
    val cancelRefundSuccessToast = stringResource(R.string.cancel_refund_success)

    LaunchedEffect(orderId) { viewModel.loadOrder(orderId) }

    val order by viewModel.order.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val payState by viewModel.payState.collectAsState()
    val actionState by viewModel.actionState.collectAsState()
    val settlement by viewModel.settlement.collectAsState()
    val deleteState by viewModel.deleteState.collectAsState()
    val cancelState by viewModel.cancelState.collectAsState()
    val cancelRefundState by viewModel.cancelRefundState.collectAsState()
    val review by viewModel.review.collectAsState()

    var showDeleteDialog by remember { mutableStateOf(false) }
    var showCancelDialog by remember { mutableStateOf(false) }
    var showCancelRefundDialog by remember { mutableStateOf(false) }
    var showDeleteReviewDialog by remember { mutableStateOf(false) }

    // Handle pay success / error toasts
    LaunchedEffect(payState) {
        when (val state = payState) {
            is OrderActionState.Success -> {
                Toast.makeText(context, paymentSuccessToast, Toast.LENGTH_SHORT).show()
                viewModel.resetPayState()
            }
            is OrderActionState.Error -> {
                Toast.makeText(context, state.message, Toast.LENGTH_SHORT).show()
                viewModel.resetPayState()
            }
            else -> {}
        }
    }

    // Handle action success / error
    LaunchedEffect(actionState) {
        when (val state = actionState) {
            is OrderActionState.Success -> {
                Toast.makeText(context, operationSuccessToast, Toast.LENGTH_SHORT).show()
                viewModel.resetActionState()
            }
            is OrderActionState.Error -> {
                Toast.makeText(context, state.message, Toast.LENGTH_SHORT).show()
                viewModel.resetActionState()
            }
            else -> {}
        }
    }

    // Handle delete success — navigate back
    LaunchedEffect(deleteState) {
        when (val state = deleteState) {
            is OrderActionState.Success -> {
                Toast.makeText(context, orderDeletedToast, Toast.LENGTH_SHORT).show()
                viewModel.resetDeleteState()
                navController.popBackStack()
            }
            is OrderActionState.Error -> {
                Toast.makeText(context, state.message, Toast.LENGTH_SHORT).show()
                viewModel.resetDeleteState()
            }
            else -> {}
        }
    }

    // Handle cancel success — navigate back
    LaunchedEffect(cancelState) {
        when (val state = cancelState) {
            is OrderActionState.Success -> {
                Toast.makeText(context, orderCancelledToast, Toast.LENGTH_SHORT).show()
                viewModel.resetCancelState()
                navController.popBackStack()
            }
            is OrderActionState.Error -> {
                Toast.makeText(context, state.message, Toast.LENGTH_SHORT).show()
                viewModel.resetCancelState()
            }
            else -> {}
        }
    }

    // Handle delete review message
    val deleteReviewMessage by viewModel.deleteReviewMessage.collectAsState()
    LaunchedEffect(deleteReviewMessage) {
        deleteReviewMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearDeleteReviewMessage()
        }
    }

    // Handle cancel refund success
    LaunchedEffect(cancelRefundState) {
        when (val result = cancelRefundState) {
            is Result<*> -> {
                if (result.isSuccess) {
                    Toast.makeText(context, cancelRefundSuccessToast, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, result.exceptionOrNull()?.message ?: "操作失败", Toast.LENGTH_SHORT).show()
                }
                viewModel.resetCancelRefundState()
            }
            else -> {}
        }
    }

    // Delete confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    order?.let { viewModel.deleteOrder(it.id) }
                }) {
                    Text(stringResource(android.R.string.ok), color = StatusRefund)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
            title = { Text(stringResource(R.string.delete_order)) },
            text = { Text(stringResource(R.string.delete_order_confirm)) }
        )
    }

    // Cancel confirmation dialog
    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    showCancelDialog = false
                    order?.let { viewModel.cancelOrder(it.id) }
                }) {
                    Text(stringResource(android.R.string.ok), color = StatusRefund)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
            title = { Text(stringResource(R.string.cancel_order)) },
            text = { Text(stringResource(R.string.cancel_order_confirm)) }
        )
    }

    // Cancel refund confirmation dialog
    if (showCancelRefundDialog) {
        AlertDialog(
            onDismissRequest = { showCancelRefundDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    showCancelRefundDialog = false
                    order?.let { viewModel.cancelRefund(it.id) }
                }) {
                    Text(stringResource(android.R.string.ok), color = StatusRefund)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCancelRefundDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
            title = { Text(stringResource(R.string.cancel_application)) },
            text = { Text(stringResource(R.string.cancel_application_confirm)) }
        )
    }

    // Delete review confirmation dialog
    if (showDeleteReviewDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteReviewDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteReviewDialog = false
                    review?.let { viewModel.deleteReview(it.id) }
                }) {
                    Text(stringResource(R.string.review_delete), color = Error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteReviewDialog = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
            title = { Text(stringResource(R.string.review_delete_confirm_title)) },
            text = { Text(stringResource(R.string.review_delete_confirm_msg)) }
        )
    }

    Scaffold(
        topBar = {
            JoysongTopBar(
                title = stringResource(R.string.order_detail),
                onBackClick = { navController.popBackStack() }
            )
        },
        bottomBar = {
            order?.let {
                OrderBottomBar(
                    order = it,
                    viewModel = viewModel,
                    navController = navController,
                    onDeleteClick = { showDeleteDialog = true },
                    onCancelClick = { showCancelDialog = true },
                    onRefundClick = { navController.navigate(Routes.RefundApply.createRoute(it.id)) },
                    onCancelRefundClick = { showCancelRefundDialog = true },
                    onContactSupportClick = {
                        if (it.institutionId.isNotBlank()) {
                            navController.navigate(Routes.DmChat.createRoute(it.institutionId, "institution"))
                        }
                    }
                )
            }
        },
        containerColor = Background
    ) { padding ->
        when {
            isLoading -> {
                Box(modifier = Modifier.padding(padding).fillMaxSize()) { LoadingIndicator() }
            }
            error != null -> {
                Box(modifier = Modifier.padding(padding).fillMaxSize()) {
                    ErrorView(message = error ?: stringResource(R.string.load_failed), onRetry = { viewModel.loadOrder(orderId) })
                }
            }
            order != null -> {
                Column(
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    StatusHeader(order!!)
                    Spacer(Modifier.height(12.dp))

                    // 预约时间醒目卡片（已完成/结算状态隐藏）
                    if (order!!.status != OrderStatus.PENDING_PAYMENT &&
                        order!!.status != OrderStatus.CANCELLED &&
                        order!!.status != OrderStatus.COMPLETED &&
                        order!!.status != OrderStatus.PENDING_SETTLEMENT &&
                        order!!.status != OrderStatus.SETTLED &&
                        order!!.appointmentTime.isNotBlank()
                    ) {
                        AppointmentTimeHighlightCard(order!!.appointmentTime)
                        Spacer(Modifier.height(12.dp))
                    }

                    // Status-specific sections
                    when (order!!.status) {
                        OrderStatus.CONSULTATION_PAID, OrderStatus.VERIFIED -> {
                            if (order!!.refundStatus != "PENDING") {
                                VerifyCodeSection(order!!)
                            }
                        }
                        OrderStatus.BALANCE_PAID -> {
                            // Show new verify code after balance payment (hide when under review)
                            if (order!!.refundStatus != "PENDING" &&
                                (order!!.verifyCode.isNotBlank() || order!!.qrCode.isNotBlank())
                            ) {
                                NewVerifyCodeSection(order!!)
                            }
                            WaitingServiceSection()
                        }
                        OrderStatus.PENDING_COMPLETION -> {} // Handled in bottom bar
                        // 用户端：结算状态不显示结算详情，视为已完成
                        OrderStatus.PENDING_SETTLEMENT, OrderStatus.SETTLED -> {}
                        // Legacy statuses
                        OrderStatus.PAID, OrderStatus.TO_USE -> QrCodeSection(order!!)
                        OrderStatus.REFUNDING -> RefundProgressSection(order!!)
                        OrderStatus.REFUNDED -> RefundAmountSection(order!!)
                        else -> {}
                    }

                    // 退款进度显示（基于refundStatus而非订单状态，REFUNDING状态已由when块处理）
                    val rs = order!!.refundStatus
                    if (rs != "NONE" && rs.isNotBlank() && order!!.status != OrderStatus.REFUNDING) {
                        // 退款状态标签（PENDING时不显示小标签，已通过顶部状态头展示“审核中”）
                        if (rs != "PENDING") {
                            val (tagText, tagColor) = when (rs) {
                                "APPROVED" -> stringResource(R.string.refund_status_approved) to Success
                                "REJECTED" -> stringResource(R.string.refund_status_rejected) to StatusRefund
                                else -> "" to TextSecondary
                            }
                            if (tagText.isNotEmpty()) {
                                RefundStatusTag(tagText, tagColor)
                                Spacer(Modifier.height(8.dp))
                            }
                        }
                        // 退款进度条（REJECTED不显示进度）
                        if (rs != "REJECTED") {
                            RefundProgressSection(order!!)
                        }
                        // 退款金额（APPROVED时显示）
                        if (rs == "APPROVED") {
                            RefundAmountSection(order!!)
                        }
                    }

                    FeeDetailCard(order!!)

                    // 评价信息卡片
                    val completedStatuses = setOf(OrderStatus.COMPLETED, OrderStatus.PENDING_SETTLEMENT, OrderStatus.SETTLED)
                    if (order!!.hasReview && review != null && order!!.status in completedStatuses) {
                        Spacer(Modifier.height(12.dp))
                        ReviewInfoCard(
                            review = review!!,
                            onEditClick = {
                                navController.navigate(Routes.ReviewOrder.createRoute(order!!.id, editMode = true))
                            },
                            onDeleteClick = {
                                showDeleteReviewDialog = true
                            }
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    OrderInfoCard(order!!, context)
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}

// ── Status header ─────────────────────────────────────────────────────────────

@Composable
private fun StatusHeader(order: Order) {
    val isRefundPending = order.refundStatus == "PENDING"
    val (label, color) = when {
        order.refundStatus == "APPROVED" -> stringResource(R.string.status_refunded) to StatusRefund
        order.refundStatus == "REJECTED" -> stringResource(R.string.status_refund_rejected) to StatusCancelled
        isRefundPending -> stringResource(R.string.status_under_review) to Warning
        else -> when (order.status) {
            OrderStatus.PENDING_PAYMENT -> stringResource(R.string.status_pending_deposit) to StatusPending
            OrderStatus.CONSULTATION_PAID -> stringResource(R.string.status_consultation_paid) to StatusToUse
            OrderStatus.VERIFIED -> stringResource(R.string.status_verified) to StatusVerified
            OrderStatus.BALANCE_PAID -> stringResource(R.string.status_balance_paid) to StatusVerified
            OrderStatus.PENDING_COMPLETION -> stringResource(R.string.status_pending_completion) to StatusPending
            OrderStatus.COMPLETED -> stringResource(R.string.status_completed) to StatusCompleted
            // 用户端：结算状态显示为“已完成”（结算仅管理后台/医生端可见）
            OrderStatus.PENDING_SETTLEMENT -> stringResource(R.string.status_completed) to StatusCompleted
            OrderStatus.SETTLED -> stringResource(R.string.status_completed) to StatusCompleted
            OrderStatus.DISPUTE_MEDIATION -> stringResource(R.string.status_dispute_mediation) to StatusRefund
            OrderStatus.CANCELLED -> stringResource(R.string.status_cancelled) to StatusCancelled
            OrderStatus.REFUNDED -> stringResource(R.string.status_refunded) to StatusRefund
            // Legacy
            OrderStatus.PENDING_REMAINING -> stringResource(R.string.status_pending_balance) to StatusPending
            OrderStatus.PAID, OrderStatus.TO_USE -> stringResource(R.string.status_to_use) to StatusToUse
            OrderStatus.REFUNDING -> stringResource(R.string.status_refunding) to StatusRefund
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 8.dp)
    ) {
        if (order.status == OrderStatus.COMPLETED || order.status == OrderStatus.PENDING_SETTLEMENT || order.status == OrderStatus.SETTLED) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(28.dp)
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = label,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

// ── Verify code section (CONSULTATION_PAID / VERIFIED) ────────────────────────

@Composable
private fun VerifyCodeSection(order: Order) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Surface)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(24.dp)
        ) {
            Text(stringResource(R.string.voucher_code), fontSize = 14.sp, color = TextSecondary)
            Spacer(Modifier.height(12.dp))
            Text(
                text = order.verifyCode.ifBlank { order.qrCode }.ifBlank { "\u2014" },
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = PrimaryDark,
                letterSpacing = 4.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.voucher_hint),
                fontSize = 12.sp,
                color = TextHint,
                textAlign = TextAlign.Center
            )
            if (order.verifiedAt.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.verified_time_format, order.verifiedAt),
                    fontSize = 12.sp,
                    color = TextHint
                )
            }
        }
    }
}

// ── New verify code section (BALANCE_PAID — after full payment) ─────────────

@Composable
private fun NewVerifyCodeSection(order: Order) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Surface)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(24.dp)
        ) {
            Text(stringResource(R.string.new_verification_code), fontSize = 14.sp, color = TextSecondary)
            Spacer(Modifier.height(12.dp))
            Text(
                text = order.verifyCode.ifBlank { order.qrCode },
                fontSize = 32.sp,
                fontWeight = FontWeight.Bold,
                color = PrimaryDark,
                letterSpacing = 4.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.voucher_hint),
                fontSize = 12.sp,
                color = TextHint,
                textAlign = TextAlign.Center
            )
        }
    }
}

// ── Waiting service section (BALANCE_PAID) ────────────────────────────────────

@Composable
private fun WaitingServiceSection() {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Surface)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = StatusVerified,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.waiting_service_full_paid),
                fontSize = 14.sp,
                color = TextPrimary
            )
        }
    }
}

// ── Settlement section ────────────────────────────────────────────────────────

@Composable
private fun SettlementSection(settlement: Settlement) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.settlement_info),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = TextPrimary
            )
            Spacer(Modifier.height(12.dp))
            SettlementRow(stringResource(R.string.settlement_total_amount), settlement.totalAmount)
            Spacer(Modifier.height(6.dp))
            SettlementRow(stringResource(R.string.settlement_platform_fee), settlement.platformAmount)
            Spacer(Modifier.height(6.dp))
            SettlementRow(stringResource(R.string.settlement_institution_share), settlement.institutionAmount)
            Spacer(Modifier.height(6.dp))
            SettlementRow(stringResource(R.string.settlement_consultant_commission), settlement.consultantAmount)
            Spacer(Modifier.height(6.dp))
            SettlementRow(stringResource(R.string.settlement_doctor_income), settlement.doctorAmount)
            if (settlement.settledAt != null && settlement.settledAt > 0) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = Color(0xFFF0F0F0), thickness = 1.dp)
                Spacer(Modifier.height(8.dp))
                InfoRow(stringResource(R.string.settlement_status), settlement.status)
                Spacer(Modifier.height(4.dp))
                InfoRow(stringResource(R.string.settlement_time), formatEpochMilli(settlement.settledAt))
            }
        }
    }
}

@Composable
private fun SettlementRow(label: String, amount: Double) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 13.sp, color = TextSecondary)
        Text(formatAmount(amount), fontSize = 13.sp, color = TextPrimary)
    }
}

// ── QR code section (legacy PAID / TO_USE) ────────────────────────────────────

@Composable
private fun QrCodeSection(order: Order) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Surface)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().padding(24.dp)
        ) {
            Text(stringResource(R.string.voucher_title), fontSize = 14.sp, color = TextSecondary)
            Spacer(Modifier.height(12.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(140.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFFF0F0F0))
            ) {
                if (order.qrCode.isNotBlank()) {
                    Text(
                        text = order.qrCode,
                        fontSize = 12.sp,
                        color = TextPrimary,
                        modifier = Modifier.padding(8.dp)
                    )
                } else {
                    Text(stringResource(R.string.voucher_generating), fontSize = 12.sp, color = TextHint)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.voucher_show_prompt),
                fontSize = 12.sp,
                color = TextHint
            )
        }
    }
}

// ── Refund status tag ───────────────────────────────────────────────────────────

@Composable
private fun RefundStatusTag(text: String, color: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

// ── Refund progress (REFUNDING) ───────────────────────────────────────────────

@Composable
private fun RefundProgressSection(order: Order) {
    val isRejected = order.refundStatus == "REJECTED"
    val currentStage = when {
        order.refundStatus == "APPROVED" && order.status == OrderStatus.REFUNDED -> 3  // 退款成功（全部完成）
        order.refundStatus == "APPROVED" -> 3  // 审核通过（全部完成）
        order.refundStatus == "PENDING" -> 1   // 已提交申请，等待审核
        order.refundStatus == "REJECTED" -> -1  // 已拒绝（特殊处理）
        else -> 0
    }

    val stages = listOf(
        stringResource(R.string.refund_stage_submit),
        stringResource(R.string.refund_stage_approved),
        stringResource(R.string.refund_stage_success)
    )

    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.refund_progress_title), fontSize = 14.sp, color = TextSecondary)
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                stages.forEachIndexed { index, label ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(
                                    when {
                                        isRejected -> Color(0xFFE0E0E0)
                                        index < currentStage -> Success
                                        index == currentStage -> PrimaryDark
                                        else -> Color(0xFFE0E0E0)
                                    }
                                )
                        ) {
                            if (!isRejected && index < currentStage) {
                                Icon(
                                    Icons.Default.Check,
                                    contentDescription = null,
                                    tint = TextOnPrimary,
                                    modifier = Modifier.size(16.dp)
                                )
                            } else {
                                Text(
                                    text = "${index + 1}",
                                    fontSize = 12.sp,
                                    color = if (!isRejected && index == currentStage) TextOnPrimary else TextHint
                                )
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = label,
                            fontSize = 11.sp,
                            color = if (!isRejected && index < currentStage) TextPrimary else TextHint
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFFE0E0E0))
            ) {
                if (!isRejected) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction = (currentStage.toFloat() / stages.size).coerceIn(0f, 1f))
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(PrimaryDark)
                    )
                }
            }
            // 退款已拒绝提示
            if (isRejected) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.refund_status_rejected),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = StatusRefund
                )
            }
        }
    }

    // RefundAmountSection 在主布局中单独显示，避免重复渲染
}

// ── Refund amount (REFUNDING / REFUNDED) ───────────────────────────────────────

@Composable
private fun RefundAmountSection(order: Order) {
    if (order.refundAmount > 0) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Surface)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.refund_amount_label), fontSize = 14.sp, color = TextSecondary)
                Text(
                    formatAmount(order.refundAmount),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = StatusRefund
                )
            }
        }
    }
}

// ── Appointment time highlight card ──────────────────────────────────────────

@Composable
private fun AppointmentTimeHighlightCard(appointmentTime: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Event,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = stringResource(R.string.appointment_time_label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = appointmentTime,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

// ── Fee detail card ────────────────────────────────────────────────────────────

@Composable
private fun FeeDetailCard(order: Order) {
    val consultationFee = order.consultationFee
    val balance = order.price - order.consultationFee  // 尾款 = 项目实付价 - 已付面诊金
    val totalAmount = consultationFee + balance  // 总金额

    // 已付金额：根据订单状态计算
    val paidAmount = when (order.status) {
        OrderStatus.PENDING_PAYMENT, OrderStatus.CANCELLED -> 0.0
        OrderStatus.CONSULTATION_PAID, OrderStatus.VERIFIED -> consultationFee
        OrderStatus.REFUNDED -> 0.0  // 退款完成后，已付金额清零
        else -> totalAmount  // BALANCE_PAID 及之后 = 全部
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.fee_detail_title),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = TextPrimary
            )
            Spacer(Modifier.height(12.dp))

            // 面诊金
            FeeRow(stringResource(R.string.consultation_fee), consultationFee)

            // 尾款
            Spacer(Modifier.height(8.dp))
            FeeRow(stringResource(R.string.balance_payment), balance)

            // 优惠减免（仅 > 0 时显示，红色带负号）
            if (order.couponDiscount > 0) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        stringResource(R.string.discount_amount),
                        fontSize = 13.sp,
                        color = StatusRefund
                    )
                    Text(
                        "-${formatAmount(order.couponDiscount)}",
                        fontSize = 13.sp,
                        color = StatusRefund
                    )
                }
            }

            // 分隔线
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = Color(0xFFF0F0F0), thickness = 1.dp)
            Spacer(Modifier.height(12.dp))

            // 总金额（加粗加大）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    stringResource(R.string.total_amount),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary
                )
                Text(
                    formatAmount(totalAmount),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }

            // 已付金额（仅 > 0 时显示）
            if (paidAmount > 0) {
                Spacer(Modifier.height(8.dp))
                FeeRow(stringResource(R.string.amount_paid), paidAmount)
            }
        }
    }
}

@Composable
private fun FeeRow(label: String, amount: Double) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 13.sp, color = TextSecondary)
        Text(formatAmount(amount), fontSize = 13.sp, color = TextPrimary)
    }
}

// ── Order info card ────────────────────────────────────────────────────────────

@Composable
private fun OrderInfoCard(order: Order, context: Context) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.order_info_title), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
            Spacer(Modifier.height(12.dp))

            // 订单编号 + 复制按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.order_no_label), fontSize = 13.sp, color = TextSecondary, modifier = Modifier.width(70.dp))
                Text(
                    order.orderNo.ifBlank { order.id },
                    fontSize = 13.sp,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                val orderNoLabel = stringResource(R.string.order_no_label)
                val copiedText = stringResource(R.string.copied)
                IconButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText(orderNoLabel, order.orderNo.ifBlank { order.id })
                        )
                        Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.copy),
                        tint = TextHint,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            if (order.transactionMethod.isNotBlank()) {
                InfoRow(stringResource(R.string.transaction_method), order.transactionMethod)
                Spacer(Modifier.height(8.dp))
            }

            if (order.userPhone.isNotBlank()) {
                InfoRow(stringResource(R.string.phone_number_label), maskPhone(order.userPhone))
                Spacer(Modifier.height(8.dp))
            }

            InfoRow(stringResource(R.string.order_time_info), order.createdAt)

            if (order.appointmentTime.isNotBlank() &&
                order.status != OrderStatus.COMPLETED &&
                order.status != OrderStatus.PENDING_SETTLEMENT &&
                order.status != OrderStatus.SETTLED) {
                Spacer(Modifier.height(8.dp))
                InfoRow(stringResource(R.string.appointment_time_label), order.appointmentTime)

            }

            if (order.paymentTime.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                InfoRow(stringResource(R.string.payment_time), order.paymentTime)
            }

            if (order.verifiedAt.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                InfoRow("核验时间", order.verifiedAt)
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, fontSize = 13.sp, color = TextSecondary, modifier = Modifier.width(70.dp))
        Text(value, fontSize = 13.sp, color = TextPrimary, modifier = Modifier.weight(1f))
    }
}

// ── Review info card ──────────────────────────────────────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReviewInfoCard(
    review: Review,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 标题
            Text(
                text = stringResource(R.string.review_info_title),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary
            )
            Spacer(Modifier.height(12.dp))

            // 星级
            StarRatingBar(
                rating = review.rating,
                onRatingChanged = {},
                readOnly = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))

            // 评价内容
            if (review.content.isNotBlank()) {
                Text(
                    text = review.content,
                    fontSize = 14.sp,
                    color = TextPrimary,
                    lineHeight = 22.sp
                )
                Spacer(Modifier.height(8.dp))
            }

            // 标签
            if (review.tags.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    review.tags.forEach { tag ->
                        SuggestionChip(
                            onClick = {},
                            label = { Text(tag, fontSize = 12.sp) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // 评价图片
            if (review.images.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    review.images.forEach { imageUrl ->
                        AsyncImage(
                            model = imageUrl,
                            contentDescription = null,
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // 评价时间
            Text(
                text = review.createdAt,
                fontSize = 12.sp,
                color = TextHint
            )

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))

            // 操作按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onEditClick,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.review_edit))
                }
                OutlinedButton(
                    onClick = onDeleteClick,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Error)
                ) {
                    Text(stringResource(R.string.review_delete))
                }
            }
        }
    }
}

// ── Bottom action bar ──────────────────────────────────────────────────────────

@Composable
private fun OrderBottomBar(
    order: Order,
    viewModel: OrderDetailViewModel,
    navController: NavHostController,
    onDeleteClick: () -> Unit = {},
    onCancelClick: () -> Unit = {},
    onRefundClick: () -> Unit = {},
    onCancelRefundClick: () -> Unit = {},
    onContactSupportClick: () -> Unit = {}
) {
    val hasRefundPending = order.refundStatus == "PENDING"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Surface)
    ) {
        // 联系机构客服按钮 - 始终显示在顶部
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onContactSupportClick() }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Chat,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = PrimaryDark
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.contact_institution_support),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = PrimaryDark
            )
        }
        HorizontalDivider(thickness = 0.5.dp, color = Color(0xFFEEEEEE))

        // 状态相关操作按钮
        when (order.status) {
        OrderStatus.PENDING_PAYMENT -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onCancelClick,
                    modifier = Modifier.weight(0.35f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusRefund)
                ) {
                    Text(stringResource(R.string.cancel_order), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Button(
                    onClick = { navController.navigate("order_payment/${order.id}/consultation") },
                    modifier = Modifier.weight(0.65f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryDark)
                ) {
                    Text("支付面诊金 ${formatAmount(order.consultationFee)}", color = TextOnPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        OrderStatus.CONSULTATION_PAID -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Button(
                    onClick = { navController.navigate(Routes.RefundApply.createRoute(order.id)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Warning)
                ) {
                    Text(stringResource(R.string.apply_refund), color = TextOnPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        OrderStatus.VERIFIED -> {
            if (hasRefundPending) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Surface)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    OutlinedButton(
                        onClick = onCancelRefundClick,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Warning)
                    ) {
                        Text(stringResource(R.string.cancel_application), fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Surface)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onRefundClick,
                        modifier = Modifier.weight(0.40f).heightIn(min = 48.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                    ) {
                        Text(stringResource(R.string.apply_refund), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Button(
                        onClick = { navController.navigate("order_payment/${order.id}/balance") },
                        modifier = Modifier.weight(0.60f).heightIn(min = 48.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryDark)
                    ) {
                        Text(stringResource(R.string.pay_remaining_btn), color = TextOnPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
        OrderStatus.BALANCE_PAID -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                if (hasRefundPending) {
                    OutlinedButton(
                        onClick = onCancelRefundClick,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Warning)
                    ) {
                        Text(stringResource(R.string.cancel_application), fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    OutlinedButton(
                        onClick = onRefundClick,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                    ) {
                        Text(stringResource(R.string.apply_refund), fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        OrderStatus.PENDING_COMPLETION -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (hasRefundPending) {
                    OutlinedButton(
                        onClick = onCancelRefundClick,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Warning)
                    ) {
                        Text(stringResource(R.string.cancel_application), fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    OutlinedButton(
                        onClick = { navController.navigate(Routes.RefundApply.createRoute(order.id)) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = PrimaryDark)
                    ) {
                        Text(stringResource(R.string.apply_after_sale), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Button(
                        onClick = { viewModel.confirmCompletion(order.id) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryDark)
                    ) {
                        Text(stringResource(R.string.order_completed_btn), color = TextOnPrimary)
                    }
                }
            }
        }
        OrderStatus.COMPLETED -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (hasRefundPending) {
                    OutlinedButton(
                        onClick = onCancelRefundClick,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Warning)
                    ) {
                        Text(stringResource(R.string.cancel_application), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    OutlinedButton(
                        onClick = onRefundClick,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusRefund)
                    ) {
                        Text(stringResource(R.string.apply_refund), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (!order.hasReview) {
                    Button(
                        onClick = { navController.navigate(Routes.ReviewOrder.createRoute(order.id)) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryDark)
                    ) {
                        Text(stringResource(R.string.action_review), color = TextOnPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        // 用户端：结算状态显示与 COMPLETED 相同的操作栏
        OrderStatus.PENDING_SETTLEMENT, OrderStatus.SETTLED -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (hasRefundPending) {
                    OutlinedButton(
                        onClick = onCancelRefundClick,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Warning)
                    ) {
                        Text(stringResource(R.string.cancel_application), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                } else {
                    OutlinedButton(
                        onClick = onRefundClick,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusRefund)
                    ) {
                        Text(stringResource(R.string.apply_refund), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (!order.hasReview) {
                    Button(
                        onClick = { navController.navigate(Routes.ReviewOrder.createRoute(order.id)) },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        shape = RoundedCornerShape(24.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryDark)
                    ) {
                        Text(stringResource(R.string.action_review), color = TextOnPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        // Legacy
        OrderStatus.PENDING_REMAINING -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDeleteClick,
                    modifier = Modifier.weight(0.35f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusRefund)
                ) {
                    Text(stringResource(R.string.delete_order), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Button(
                    onClick = { navController.navigate("order_payment/${order.id}/balance") },
                    modifier = Modifier.weight(0.65f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryDark)
                ) {
                    Text(stringResource(R.string.go_pay_format, formatAmount(order.price)), color = TextOnPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        OrderStatus.PAID, OrderStatus.TO_USE -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Surface)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onDeleteClick,
                    modifier = Modifier.weight(0.35f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusRefund)
                ) {
                    Text(stringResource(R.string.delete_order), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(
                    onClick = { /* 退款页 */ },
                    modifier = Modifier.weight(0.65f).heightIn(min = 48.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                ) {
                    Text(stringResource(R.string.apply_refund), fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        OrderStatus.REFUNDING -> {}
        OrderStatus.CANCELLED, OrderStatus.REFUNDED, OrderStatus.DISPUTE_MEDIATION -> DeleteBottomButton(onDeleteClick)
        }
    }
}

@Composable
private fun PrimaryBottomButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .heightIn(min = 48.dp),
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(containerColor = PrimaryDark)
    ) {
        Text(text, color = TextOnPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun OutlinedBottomButton(text: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .heightIn(min = 48.dp),
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
    ) {
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun DeleteBottomButton(onDeleteClick: () -> Unit) {
    OutlinedButton(
        onClick = onDeleteClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .heightIn(min = 48.dp),
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = StatusRefund)
    ) {
        Icon(
            imageVector = Icons.Default.Delete,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = StatusRefund
        )
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.delete_order), fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// ── Helpers ────────────────────────────────────────────────────────────────────

private fun formatAmount(amount: Double): String = String.format("¥%.2f", amount)

private fun formatEpochMilli(millis: Long?): String {
    if (millis == null || millis <= 0) return ""
    return Instant.ofEpochMilli(millis)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
}

private fun maskPhone(phone: String): String {
    return if (phone.length >= 7) {
        "${phone.take(3)}****${phone.takeLast(4)}"
    } else phone
}

// ── Previews ──────────────────────────────────────────────────────────────────

private fun mockDetailOrder(
    id: String = "ORD-001",
    status: OrderStatus = OrderStatus.PENDING_PAYMENT,
    projectName: String = "热玛吉五代全脸抗衰",
    institutionName: String = "娇颜医疗美容医院",
    price: Double = 12800.0,
    paidAmount: Double = 0.0,
    consultationFee: Double = 500.0,
    remainingAmount: Double = 12300.0,
    coverImage: String = "",
    createdAt: String = "2026-07-20 14:30",
    appointmentTime: String = "2026-08-01 10:00",
    orderNo: String = "JS20260720001",
    verifyCode: String = "8826",
    doctorName: String = "李医生",
    userPhone: String = "13812345678",
    paymentTime: String = "",
    refundAmount: Double = 0.0,
    refundStatus: String = "NONE",
    transactionMethod: String = "线上支付",
    couponDiscount: Double = 0.0,
    verifiedAt: String = "",
    completedAt: String = ""
) = Order(
    id = id,
    projectName = projectName,
    institutionName = institutionName,
    coverImage = coverImage,
    price = price,
    paidAmount = paidAmount,
    status = status,
    createdAt = createdAt,
    appointmentTime = appointmentTime,
    consultationFee = consultationFee,
    remainingAmount = remainingAmount,
    orderNo = orderNo,
    verifyCode = verifyCode,
    doctorName = doctorName,
    userPhone = userPhone,
    paymentTime = paymentTime,
    refundAmount = refundAmount,
    refundStatus = refundStatus,
    transactionMethod = transactionMethod,
    couponDiscount = couponDiscount,
    verifiedAt = verifiedAt,
    completedAt = completedAt
)

@Preview(showBackground = true, showSystemUi = true, locale = "zh", name = "订单详情 - 待支付面诊金")
@Composable
private fun OrderDetailPendingPaymentPreview() {
    val context = LocalContext.current
    val order = mockDetailOrder(status = OrderStatus.PENDING_PAYMENT)
    JoysongTheme {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            StatusHeader(order)
            Spacer(Modifier.height(12.dp))
            FeeDetailCard(order)
            Spacer(Modifier.height(12.dp))
            OrderInfoCard(order, context)
        }
    }
}

@Preview(showBackground = true, showSystemUi = true, locale = "en", name = "订单详情 - 面诊金已付(凭证码)")
@Composable
private fun OrderDetailConsultationPaidPreview() {
    val context = LocalContext.current
    val order = mockDetailOrder(
        status = OrderStatus.CONSULTATION_PAID,
        verifyCode = "6632",
        paymentTime = "2026-07-20 15:02"
    )
    JoysongTheme {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            StatusHeader(order)
            Spacer(Modifier.height(12.dp))
            AppointmentTimeHighlightCard(order.appointmentTime)
            Spacer(Modifier.height(12.dp))
            VerifyCodeSection(order)
            FeeDetailCard(order)
            Spacer(Modifier.height(12.dp))
            OrderInfoCard(order, context)
        }
    }
}

@Preview(showBackground = true, showSystemUi = true, locale = "zh", name = "订单详情 - 已核验(待付尾款)")
@Composable
private fun OrderDetailVerifiedPreview() {
    val context = LocalContext.current
    val order = mockDetailOrder(
        status = OrderStatus.VERIFIED,
        verifyCode = "7741",
        paymentTime = "2026-07-20 15:02",
        verifiedAt = "2026-07-28 09:45"
    )
    JoysongTheme {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            StatusHeader(order)
            Spacer(Modifier.height(12.dp))
            AppointmentTimeHighlightCard(order.appointmentTime)
            Spacer(Modifier.height(12.dp))
            VerifyCodeSection(order)
            FeeDetailCard(order)
            Spacer(Modifier.height(12.dp))
            OrderInfoCard(order, context)
        }
    }
}

@Preview(showBackground = true, showSystemUi = true, locale = "zh", name = "订单详情 - 全款已付")
@Composable
private fun OrderDetailBalancePaidPreview() {
    val context = LocalContext.current
    val order = mockDetailOrder(
        status = OrderStatus.BALANCE_PAID,
        verifyCode = "9901",
        paymentTime = "2026-07-21 10:30",
        verifiedAt = "2026-07-28 09:45",
        remainingAmount = 0.0
    )
    JoysongTheme {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            StatusHeader(order)
            Spacer(Modifier.height(12.dp))
            AppointmentTimeHighlightCard(order.appointmentTime)
            Spacer(Modifier.height(12.dp))
            NewVerifyCodeSection(order)
            WaitingServiceSection()
            FeeDetailCard(order)
            Spacer(Modifier.height(12.dp))
            OrderInfoCard(order, context)
        }
    }
}

@Preview(showBackground = true, showSystemUi = true, locale = "zh", name = "订单详情 - 待确认完成")
@Composable
private fun OrderDetailPendingCompletionPreview() {
    val context = LocalContext.current
    val order = mockDetailOrder(
        status = OrderStatus.PENDING_COMPLETION,
        price = 15800.0,
        consultationFee = 800.0,
        remainingAmount = 0.0,
        paymentTime = "2026-07-21 10:30"
    )
    JoysongTheme {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            StatusHeader(order)
            Spacer(Modifier.height(12.dp))
            AppointmentTimeHighlightCard(order.appointmentTime)
            Spacer(Modifier.height(12.dp))
            FeeDetailCard(order)
            Spacer(Modifier.height(12.dp))
            OrderInfoCard(order, context)
        }
    }
}

@Preview(showBackground = true, showSystemUi = true, locale = "zh", name = "订单详情 - 已完成")
@Composable
private fun OrderDetailCompletedPreview() {
    val context = LocalContext.current
    val order = mockDetailOrder(
        status = OrderStatus.COMPLETED,
        price = 9800.0,
        consultationFee = 300.0,
        remainingAmount = 0.0,
        paymentTime = "2026-07-10 11:00",
        completedAt = "2026-07-25 14:30"
    )
    JoysongTheme {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            StatusHeader(order)
            Spacer(Modifier.height(12.dp))
            FeeDetailCard(order)
            Spacer(Modifier.height(12.dp))
            OrderInfoCard(order, context)
        }
    }
}

@Preview(showBackground = true, showSystemUi = true, locale = "en", name = "Order Detail - Completed (EN)")
@Composable
private fun OrderDetailCompletedEnPreview() {
    val context = LocalContext.current
    val order = mockDetailOrder(
        status = OrderStatus.COMPLETED,
        price = 9800.0,
        consultationFee = 300.0,
        remainingAmount = 0.0,
        paymentTime = "2026-07-10 11:00",
        completedAt = "2026-07-25 14:30"
    )
    JoysongTheme {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            StatusHeader(order)
            Spacer(Modifier.height(12.dp))
            FeeDetailCard(order)
            Spacer(Modifier.height(12.dp))
            OrderInfoCard(order, context)
        }
    }
}

@Preview(showBackground = true, showSystemUi = true, locale = "zh", name = "订单详情 - 已结算(含结算信息)")
@Composable
private fun OrderDetailSettledPreview() {
    val context = LocalContext.current
    val order = mockDetailOrder(
        status = OrderStatus.SETTLED,
        price = 12800.0,
        consultationFee = 500.0,
        remainingAmount = 0.0,
        paymentTime = "2026-07-10 11:00",
        completedAt = "2026-07-25 14:30"
    )
    val settlement = Settlement(
        id = 1L,
        orderId = order.id,
        totalAmount = 12800.0,
        platformAmount = 1280.0,
        institutionAmount = 7680.0,
        consultantAmount = 1280.0,
        doctorAmount = 2560.0,
        status = "已结算",
        settledAt = "2026-07-25T16:00:00".toEpochMilli()
    )
    JoysongTheme {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            StatusHeader(order)
            Spacer(Modifier.height(12.dp))
            SettlementSection(settlement)
            FeeDetailCard(order)
            Spacer(Modifier.height(12.dp))
            OrderInfoCard(order, context)
        }
    }
}

@Preview(showBackground = true, showSystemUi = true, locale = "zh", name = "订单详情 - 含优惠券")
@Composable
private fun OrderDetailWithCouponPreview() {
    val context = LocalContext.current
    val order = mockDetailOrder(
        status = OrderStatus.BALANCE_PAID,
        price = 12800.0,
        consultationFee = 500.0,
        couponDiscount = 600.0,
        remainingAmount = 0.0,
        verifyCode = "9901",
        paymentTime = "2026-07-21 10:30",
        verifiedAt = "2026-07-28 09:45"
    )
    JoysongTheme {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            StatusHeader(order)
            Spacer(Modifier.height(12.dp))
            AppointmentTimeHighlightCard(order.appointmentTime)
            Spacer(Modifier.height(12.dp))
            NewVerifyCodeSection(order)
            WaitingServiceSection()
            FeeDetailCard(order)
            Spacer(Modifier.height(12.dp))
            OrderInfoCard(order, context)
        }
    }
}

@Preview(showBackground = true, showSystemUi = true, locale = "zh", name = "订单详情 - 已核验(退款待审)")
@Composable
private fun OrderDetailVerifiedRefundPendingPreview() {
    val context = LocalContext.current
    val order = mockDetailOrder(
        status = OrderStatus.VERIFIED,
        verifyCode = "7741",
        paymentTime = "2026-07-20 15:02",
        verifiedAt = "2026-07-28 09:45",
        refundStatus = "PENDING",
        refundAmount = 500.0
    )
    JoysongTheme {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            StatusHeader(order)
            Spacer(Modifier.height(12.dp))
            AppointmentTimeHighlightCard(order.appointmentTime)
            Spacer(Modifier.height(12.dp))
            RefundStatusTag(stringResource(R.string.refund_status_pending), Warning)
            Spacer(Modifier.height(8.dp))
            RefundProgressSection(order)
            FeeDetailCard(order)
            Spacer(Modifier.height(12.dp))
            OrderInfoCard(order, context)
        }
    }
}

@Preview(showBackground = true, showSystemUi = true, locale = "zh", name = "订单详情 - 退款已批准")
@Composable
private fun OrderDetailRefundApprovedPreview() {
    val context = LocalContext.current
    val order = mockDetailOrder(
        status = OrderStatus.VERIFIED,
        verifyCode = "7741",
        paymentTime = "2026-07-20 15:02",
        verifiedAt = "2026-07-28 09:45",
        refundStatus = "APPROVED",
        refundAmount = 500.0
    )
    JoysongTheme {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            StatusHeader(order)
            Spacer(Modifier.height(12.dp))
            AppointmentTimeHighlightCard(order.appointmentTime)
            Spacer(Modifier.height(12.dp))
            RefundStatusTag(stringResource(R.string.refund_status_approved), Success)
            Spacer(Modifier.height(8.dp))
            RefundProgressSection(order)
            RefundAmountSection(order)
            FeeDetailCard(order)
            Spacer(Modifier.height(12.dp))
            OrderInfoCard(order, context)
        }
    }
}

@Preview(showBackground = true, showSystemUi = true, locale = "zh", name = "订单详情 - 退款已拒绝")
@Composable
private fun OrderDetailRefundRejectedPreview() {
    val context = LocalContext.current
    val order = mockDetailOrder(
        status = OrderStatus.VERIFIED,
        verifyCode = "7741",
        paymentTime = "2026-07-20 15:02",
        verifiedAt = "2026-07-28 09:45",
        refundStatus = "REJECTED",
        refundAmount = 0.0
    )
    JoysongTheme {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            StatusHeader(order)
            Spacer(Modifier.height(12.dp))
            AppointmentTimeHighlightCard(order.appointmentTime)
            Spacer(Modifier.height(12.dp))
            VerifyCodeSection(order)
            RefundStatusTag(stringResource(R.string.refund_status_rejected), StatusRefund)
            Spacer(Modifier.height(8.dp))
            FeeDetailCard(order)
            Spacer(Modifier.height(12.dp))
            OrderInfoCard(order, context)
        }
    }
}

// ── 新增 Preview：高优先级（基线状态缺失） ──────────────────────────────────────

/** 待结算：全款已付，等待平台结算（结算时间在未来30天） */
@Preview(showBackground = true, showSystemUi = true, locale = "zh", name = "订单详情 - 待结算")
@Composable
private fun OrderDetailPendingSettlementPreview() {
    val context = LocalContext.current
    val order = mockDetailOrder(
        status = OrderStatus.PENDING_SETTLEMENT,
        price = 12800.0,
        consultationFee = 500.0,
        remainingAmount = 0.0,
        paymentTime = "2026-07-15 10:30",
        verifiedAt = "2026-07-20 09:00",
        completedAt = "2026-07-28 16:00"
    )
    val settlement = Settlement(
        id = 2L,
        orderId = order.id,
        totalAmount = 12800.0,
        platformAmount = 1280.0,
        institutionAmount = 7680.0,
        consultantAmount = 1280.0,
        doctorAmount = 2560.0,
        status = "待结算",
        settledAt = "2026-08-31T16:00:00".toEpochMilli()
    )
    JoysongTheme {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            StatusHeader(order)
            Spacer(Modifier.height(12.dp))
            SettlementSection(settlement)
            FeeDetailCard(order)
            Spacer(Modifier.height(12.dp))
            OrderInfoCard(order, context)
        }
    }
}

/** 纠纷调解中：用户对订单发起纠纷，进入平台调解阶段 */
@Preview(showBackground = true, showSystemUi = true, locale = "zh", name = "订单详情 - 纠纷调解中")
@Composable
private fun OrderDetailDisputeMediationPreview() {
    val context = LocalContext.current
    val order = mockDetailOrder(
        status = OrderStatus.DISPUTE_MEDIATION,
        price = 12800.0,
        consultationFee = 500.0,
        remainingAmount = 0.0,
        paymentTime = "2026-07-15 10:30",
        verifiedAt = "2026-07-20 09:00"
    )
    JoysongTheme {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            StatusHeader(order)
            Spacer(Modifier.height(12.dp))
            AppointmentTimeHighlightCard(order.appointmentTime)
            Spacer(Modifier.height(12.dp))
            FeeDetailCard(order)
            Spacer(Modifier.height(12.dp))
            OrderInfoCard(order, context)
        }
    }
}

/** 已退款：退款完成，退款金额等于面诊金（仅付面诊金后退款） */
@Preview(showBackground = true, showSystemUi = true, locale = "zh", name = "订单详情 - 已退款")
@Composable
private fun OrderDetailRefundedPreview() {
    val context = LocalContext.current
    val order = mockDetailOrder(
        status = OrderStatus.REFUNDED,
        price = 12800.0,
        consultationFee = 500.0,
        remainingAmount = 0.0,
        paymentTime = "2026-07-15 10:30",
        refundAmount = 500.0
    )
    JoysongTheme {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            StatusHeader(order)
            Spacer(Modifier.height(12.dp))
            AppointmentTimeHighlightCard(order.appointmentTime)
            Spacer(Modifier.height(12.dp))
            RefundAmountSection(order)
            FeeDetailCard(order)
            Spacer(Modifier.height(12.dp))
            OrderInfoCard(order, context)
        }
    }
}

/** 已取消：用户主动取消订单（未付款前取消） */
@Preview(showBackground = true, showSystemUi = true, locale = "zh", name = "订单详情 - 已取消")
@Composable
private fun OrderDetailCancelledPreview() {
    val context = LocalContext.current
    val order = mockDetailOrder(status = OrderStatus.CANCELLED)
    JoysongTheme {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            StatusHeader(order)
            Spacer(Modifier.height(12.dp))
            FeeDetailCard(order)
            Spacer(Modifier.height(12.dp))
            OrderInfoCard(order, context)
        }
    }
}

/** 已完成 + 退款审核中：订单已完成（含评价），用户申请全额退款，等待审核 */
@Preview(showBackground = true, showSystemUi = true, locale = "zh", name = "订单详情 - 已完成(退款审核中)")
@Composable
private fun OrderDetailCompletedRefundPendingPreview() {
    val context = LocalContext.current
    val order = mockDetailOrder(
        status = OrderStatus.COMPLETED,
        price = 12800.0,
        consultationFee = 500.0,
        remainingAmount = 0.0,
        paymentTime = "2026-07-10 11:00",
        verifiedAt = "2026-07-15 09:00",
        completedAt = "2026-07-25 14:30",
        refundStatus = "PENDING",
        refundAmount = 12800.0
    )
    JoysongTheme {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            StatusHeader(order)
            Spacer(Modifier.height(12.dp))
            RefundStatusTag(stringResource(R.string.refund_status_pending), Warning)
            Spacer(Modifier.height(8.dp))
            RefundProgressSection(order)
            FeeDetailCard(order)
            Spacer(Modifier.height(12.dp))
            OrderInfoCard(order, context)
        }
    }
}

/** 已完成 + 退款被拒绝：订单已完成（含评价），退款申请被驳回 */
@Preview(showBackground = true, showSystemUi = true, locale = "zh", name = "订单详情 - 已完成(退款被拒绝)")
@Composable
private fun OrderDetailCompletedRefundRejectedPreview() {
    val context = LocalContext.current
    val order = mockDetailOrder(
        status = OrderStatus.COMPLETED,
        price = 12800.0,
        consultationFee = 500.0,
        remainingAmount = 0.0,
        paymentTime = "2026-07-10 11:00",
        verifiedAt = "2026-07-15 09:00",
        completedAt = "2026-07-25 14:30",
        refundStatus = "REJECTED",
        refundAmount = 0.0
    )
    JoysongTheme {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            StatusHeader(order)
            Spacer(Modifier.height(12.dp))
            RefundStatusTag(stringResource(R.string.refund_status_rejected), StatusRefund)
            Spacer(Modifier.height(8.dp))
            FeeDetailCard(order)
            Spacer(Modifier.height(12.dp))
            OrderInfoCard(order, context)
        }
    }
}
