package com.joysong.app.ui.order

import androidx.annotation.StringRes
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.joysong.app.R
import com.joysong.app.domain.model.Order
import com.joysong.app.domain.model.OrderStatus
import com.joysong.app.ui.components.EmptyView
import com.joysong.app.ui.components.ErrorView
import com.joysong.app.ui.components.JoysongTopBar
import com.joysong.app.ui.components.LoadingIndicator
import com.joysong.app.ui.theme.Background
import com.joysong.app.ui.theme.Error
import com.joysong.app.ui.theme.JoysongTheme
import com.joysong.app.ui.theme.PrimaryDark
import com.joysong.app.ui.theme.Secondary
import com.joysong.app.ui.theme.Success
import com.joysong.app.ui.theme.Surface
import com.joysong.app.ui.theme.TextHint
import com.joysong.app.ui.theme.TextOnPrimary
import com.joysong.app.ui.theme.TextPrimary
import com.joysong.app.ui.theme.TextSecondary
import com.joysong.app.ui.theme.Warning

// ── Tab definitions ───────────────────────────────────────────────────────────

private data class OrderTab(
    @StringRes val titleRes: Int,
    val filter: (OrderStatus, String) -> Boolean
)

private val orderTabs = listOf(
    OrderTab(R.string.order_tab_all) { _, _ -> true },
    OrderTab(R.string.order_tab_pending_payment) { status, refundStatus ->
        refundStatus !in setOf("PENDING", "REJECTED") && (
            status == OrderStatus.PENDING_PAYMENT || status == OrderStatus.CONSULTATION_PAID
                    || status == OrderStatus.VERIFIED || status == OrderStatus.BALANCE_PAID
                    || status == OrderStatus.PENDING_REMAINING
        )
    },
    OrderTab(R.string.order_tab_under_review) { _, refundStatus ->
        refundStatus == "PENDING"
    },
    OrderTab(R.string.order_tab_rejected) { _, refundStatus ->
        refundStatus == "REJECTED"
    },
    OrderTab(R.string.order_tab_to_use) { status, refundStatus ->
        refundStatus !in setOf("PENDING", "REJECTED") && (
            status == OrderStatus.CONSULTATION_PAID || status == OrderStatus.PENDING_COMPLETION
        )
    },
    OrderTab(R.string.order_tab_completed) { status, refundStatus ->
        refundStatus != "PENDING" && (
            status == OrderStatus.COMPLETED || status == OrderStatus.PENDING_SETTLEMENT || status == OrderStatus.SETTLED
        )
    },
    OrderTab(R.string.order_tab_cancelled) { status, refundStatus ->
        refundStatus != "REJECTED" && (
            status == OrderStatus.CANCELLED || status == OrderStatus.REFUNDED || status == OrderStatus.REFUNDING || status == OrderStatus.DISPUTE_MEDIATION
        )
    }
)

// ── Screen ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun OrderListScreen(
    onBackClick: () -> Unit,
    onOrderClick: (String) -> Unit,
    onPayClick: (String) -> Unit,
    onRefundClick: (String) -> Unit,
    onReviewClick: (String) -> Unit,
    onViewReviewClick: (String) -> Unit = {},
    onWriteDiaryClick: (String) -> Unit,
    viewModel: OrderListViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val pagerState = rememberPagerState { orderTabs.size }
    val coroutineScope = rememberCoroutineScope()
    val pullRefreshState = rememberPullToRefreshState()

    // Refresh orders when screen becomes visible (e.g. returning from detail page after payment)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.loadOrders()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Sync pager swipe -> ViewModel selectedTab
    LaunchedEffect(pagerState.currentPage) {
        viewModel.selectTab(pagerState.currentPage)
    }

    // Reload orders when pay/cancel/delete/confirmCompletion/cancelRefund action succeeds
    val payState by viewModel.payState.collectAsState()
    val cancelState by viewModel.cancelState.collectAsState()
    val deleteState by viewModel.deleteState.collectAsState()
    val confirmCompletionState by viewModel.confirmCompletionState.collectAsState()
    val cancelRefundState by viewModel.cancelRefundState.collectAsState()
    LaunchedEffect(payState, cancelState, deleteState, confirmCompletionState, cancelRefundState) {
        if (payState is OrderActionState.Success || cancelState is OrderActionState.Success
            || deleteState is OrderActionState.Success || confirmCompletionState is OrderActionState.Success
            || cancelRefundState is OrderActionState.Success) {
            viewModel.resetActionStates()
        }
    }

    Scaffold(
        topBar = {
            JoysongTopBar(
                title = stringResource(R.string.orders_title),
                onBackClick = onBackClick
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Background)
        ) {
            // Scrollable Tab Row driven by pagerState
            ScrollableTabRow(
                selectedTabIndex = pagerState.currentPage,
                containerColor = Surface,
                contentColor = PrimaryDark,
                edgePadding = 0.dp,
                indicator = { tabPositions ->
                    if (pagerState.currentPage < tabPositions.size) {
                        TabRowDefaults.Indicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[pagerState.currentPage]),
                            color = PrimaryDark
                        )
                    }
                }
            ) {
                orderTabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = {
                            coroutineScope.launch {
                                pagerState.animateScrollToPage(index)
                            }
                        },
                        text = {
                            Text(
                                text = stringResource(tab.titleRes),
                                fontSize = 14.sp,
                                fontWeight = if (pagerState.currentPage == index) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    )
                }
            }

            // Pull-to-refresh wrapping the pager content
            PullToRefreshBox(
                isRefreshing = uiState.isLoading,
                onRefresh = { viewModel.refreshOrders() },
                state = pullRefreshState,
                modifier = Modifier.fillMaxSize()
            ) {
                when {
                    uiState.error != null && uiState.orders.isEmpty() -> {
                        ErrorView(
                            message = uiState.error ?: stringResource(R.string.order_load_failed),
                            onRetry = { viewModel.loadOrders() }
                        )
                    }
                    else -> {
                        // HorizontalPager for swipeable tabs
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize()
                        ) { page ->
                            val filteredOrders = uiState.orders.filter {
                                orderTabs[page].filter(it.status, it.refundStatus)
                            }
                            if (filteredOrders.isEmpty()) {
                                EmptyView(
                                    message = stringResource(
                                        R.string.order_empty,
                                        stringResource(orderTabs[page].titleRes)
                                    )
                                )
                            } else {
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    items(filteredOrders, key = { it.id }) { order ->
                                        OrderCard(
                                            order = order,
                                            onOrderClick = { onOrderClick(order.id) },
                                            onPayClick = { onPayClick(order.id) },
                                            onCancelClick = { viewModel.cancelOrder(order.id) },
                                            onRefundClick = { onRefundClick(order.id) },
                                            onReviewClick = { onReviewClick(order.id) },
                                            onViewReviewClick = { onViewReviewClick(order.id) },
                                            onDeleteClick = { viewModel.deleteOrder(order.id) },
                                            onConfirmCompletionClick = { viewModel.confirmCompletion(order.id) },
                                            onCancelRefundClick = { viewModel.cancelRefund(order.id) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Order Card ────────────────────────────────────────────────────────────────

@Composable
private fun OrderCard(
    order: Order,
    onOrderClick: () -> Unit,
    onPayClick: () -> Unit,
    onCancelClick: () -> Unit,
    onRefundClick: () -> Unit,
    onReviewClick: () -> Unit,
    onViewReviewClick: (String) -> Unit = {},
    onDeleteClick: () -> Unit = {},
    onConfirmCompletionClick: () -> Unit = {},
    onCancelRefundClick: () -> Unit = {}
) {
    var showVoucherDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showCancelDialog by remember { mutableStateOf(false) }
    var showCancelRefundDialog by remember { mutableStateOf(false) }

    // Cancel confirmation dialog
    if (showCancelDialog) {
        AlertDialog(
            onDismissRequest = { showCancelDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    showCancelDialog = false
                    onCancelClick()
                }) {
                    Text(stringResource(android.R.string.ok), color = Error)
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

    // Voucher dialog
    if (showVoucherDialog) {
        AlertDialog(
            onDismissRequest = { showVoucherDialog = false },
            confirmButton = {
                TextButton(onClick = { showVoucherDialog = false }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            title = {
                Text(
                    text = stringResource(R.string.voucher_code),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = order.verifyCode.ifBlank { order.qrCode }.ifBlank { "\u2014" },
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        color = PrimaryDark,
                        letterSpacing = 4.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.voucher_hint),
                        fontSize = 13.sp,
                        color = TextHint,
                        textAlign = TextAlign.Center
                    )
                }
            }
        )
    }

    // Delete confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    onDeleteClick()
                }) {
                    Text(stringResource(android.R.string.ok), color = Error)
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

    // Cancel refund confirmation dialog
    if (showCancelRefundDialog) {
        AlertDialog(
            onDismissRequest = { showCancelRefundDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    showCancelRefundDialog = false
                    onCancelRefundClick()
                }) {
                    Text(stringResource(android.R.string.ok), color = Warning)
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

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOrderClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            // Row 1: Institution name + Status badge + Balance tag
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = order.institutionName,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary,
                    maxLines = 1,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 已核验状态不显示状态标签，仅显示“待付尾款”
                    if (order.status != OrderStatus.VERIFIED) {
                        OrderStatusBadge(status = order.status, refundStatus = order.refundStatus)
                    }
                    if (order.status == OrderStatus.VERIFIED && order.refundStatus != "PENDING") {
                        BalanceDueTag()
                    }
                    // 被驳回标签
                    if (order.refundStatus == "REJECTED") {
                        RefundRejectedTag()
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Row 2: Cover image + Project info + Price
            Row(modifier = Modifier.fillMaxWidth()) {
                AsyncImage(
                    model = order.coverImage.ifBlank { null },
                    contentDescription = order.projectName,
                    modifier = Modifier
                        .size(60.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = order.projectName,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = order.createdAt,
                        fontSize = 12.sp,
                        color = TextHint,
                        maxLines = 1
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                // 根据订单状态计算已付金额显示文本
                val paidDisplayText = when (order.status) {
                    OrderStatus.PENDING_PAYMENT -> {
                        "${stringResource(R.string.consultation_fee)} ¥${order.consultationFee.toInt()}"
                    }
                    OrderStatus.CONSULTATION_PAID, OrderStatus.VERIFIED -> {
                        "${stringResource(R.string.amount_paid)} ¥${order.consultationFee.toInt()}"
                    }
                    OrderStatus.BALANCE_PAID, OrderStatus.PENDING_COMPLETION,
                    OrderStatus.COMPLETED, OrderStatus.PENDING_SETTLEMENT, OrderStatus.SETTLED -> {
                        // 已付 = 面诊金 + 尾款 = consultationFee + (price - consultationFee) = price
                        val totalPaid = order.price
                        "${stringResource(R.string.amount_paid)} ¥${totalPaid.toInt()}"
                    }
                    else -> "" // CANCELLED, REFUNDED 等不显示
                }
                if (paidDisplayText.isNotEmpty()) {
                    Text(
                        text = paidDisplayText,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = PrimaryDark
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Row 3: Consultation fee / Remaining amount
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                if (order.consultationFee > 0) {
                    Text(
                        text = "${stringResource(R.string.consultation_fee)} ¥${order.consultationFee.toInt()}",
                        fontSize = 13.sp,
                        color = TextSecondary
                    )
                }
                if (order.remainingAmount > 0) {
                    Text(
                        text = "${stringResource(R.string.final_payment)} ¥${order.remainingAmount.toInt()}",
                        fontSize = 13.sp,
                        color = TextSecondary
                    )
                }
            }

            // Appointment time (hidden for completed/settled orders)
            if (order.appointmentTime.isNotBlank() &&
                order.status !in setOf(OrderStatus.COMPLETED, OrderStatus.PENDING_SETTLEMENT, OrderStatus.SETTLED)) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = null,
                        tint = TextHint,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.appointment_time, order.appointmentTime),
                        fontSize = 12.sp,
                        color = TextSecondary
                    )
                }

            }

            // Row 5: Action buttons (only for actionable statuses)
            val actions = orderActionButtons(
                order = order,
                status = order.status,
                refundStatus = order.refundStatus,
                onPayClick = onPayClick,
                onCancelClick = { showCancelDialog = true },
                onRefundClick = onRefundClick,
                onReviewClick = onReviewClick,
                onViewReviewClick = { onViewReviewClick(order.id) },
                onVoucherClick = { showVoucherDialog = true },
                onDeleteClick = { showDeleteDialog = true },
                onConfirmCompletionClick = onConfirmCompletionClick,
                onCancelRefundClick = { showCancelRefundDialog = true }
            )
            if (actions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    actions.forEachIndexed { index, action ->
                        if (index > 0) Spacer(modifier = Modifier.width(8.dp))
                        if (action.isPrimary) {
                            Button(
                                onClick = action.onClick,
                                shape = RoundedCornerShape(20.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = PrimaryDark),
                                contentPadding = ButtonDefaults.ContentPadding
                            ) {
                                Text(
                                    text = stringResource(action.labelRes),
                                    color = TextOnPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1
                                )
                            }
                        } else {
                            OutlinedButton(
                                onClick = action.onClick,
                                shape = RoundedCornerShape(20.dp),
                                contentPadding = ButtonDefaults.ContentPadding
                            ) {
                                Text(
                                    text = stringResource(action.labelRes),
                                    color = TextPrimary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Action button model ───────────────────────────────────────────────────────

private data class OrderAction(
    @StringRes val labelRes: Int,
    val isPrimary: Boolean,
    val onClick: () -> Unit
)

private fun orderActionButtons(
    order: Order,
    status: OrderStatus,
    refundStatus: String = "NONE",
    onPayClick: () -> Unit = {},
    onCancelClick: () -> Unit = {},
    onRefundClick: () -> Unit = {},
    onReviewClick: () -> Unit = {},
    onViewReviewClick: (String) -> Unit = {},
    onVoucherClick: () -> Unit = {},
    onDeleteClick: () -> Unit = {},
    onConfirmCompletionClick: () -> Unit = {},
    onCancelRefundClick: () -> Unit = {}
): List<OrderAction> {
    return when (status) {
        OrderStatus.PENDING_PAYMENT -> listOf(
            OrderAction(R.string.cancel_order, false, onCancelClick),
            OrderAction(R.string.pay_consultation_fee, true, onPayClick)
        )
        OrderStatus.CONSULTATION_PAID -> {
            if (refundStatus == "PENDING") {
                listOf(OrderAction(R.string.cancel_application, false, onCancelRefundClick))
            } else {
                listOf(
                    OrderAction(R.string.view_voucher, false, onVoucherClick),
                    OrderAction(R.string.apply_refund, false, onRefundClick)
                )
            }
        }
        OrderStatus.VERIFIED -> {
            when (refundStatus) {
                "PENDING" -> listOf(
                    OrderAction(R.string.cancel_application, false, onCancelRefundClick)
                )
                "APPROVED" -> listOf(
                    OrderAction(R.string.apply_refund, false, onRefundClick)
                )
                "REJECTED" -> listOf(
                    OrderAction(R.string.apply_refund, false, onRefundClick),
                    OrderAction(R.string.pay_remaining_btn, true, onPayClick)
                )
                else -> listOf(
                    OrderAction(R.string.apply_refund, false, onRefundClick),
                    OrderAction(R.string.pay_remaining_btn, true, onPayClick)
                )
            }
        }
        OrderStatus.BALANCE_PAID -> {
            if (refundStatus == "PENDING") {
                listOf(OrderAction(R.string.cancel_application, false, onCancelRefundClick))
            } else {
                listOf(
                    OrderAction(R.string.apply_refund, false, onRefundClick),
                    OrderAction(R.string.delete_order, false, onDeleteClick)
                )
            }
        }
        OrderStatus.PENDING_REMAINING -> listOf(
            OrderAction(R.string.delete_order, false, onDeleteClick)
        )
        OrderStatus.PENDING_COMPLETION -> {
            if (refundStatus == "PENDING") {
                listOf(OrderAction(R.string.cancel_application, false, onCancelRefundClick))
            } else {
                listOf(
                    OrderAction(R.string.apply_refund, false, onRefundClick),
                    OrderAction(R.string.order_completed_btn, true, onConfirmCompletionClick)
                )
            }
        }
        OrderStatus.COMPLETED -> if (refundStatus == "PENDING") {
            listOf(
                OrderAction(R.string.cancel_application, false, onCancelRefundClick)
            )
        } else {
            val actions = mutableListOf<OrderAction>()
            if (!order.hasReview) {
                actions.add(OrderAction(R.string.action_review, true, onReviewClick))
            } else {
                actions.add(OrderAction(R.string.view_review, false, { onViewReviewClick(order.id) }))
            }
            actions.add(OrderAction(R.string.apply_refund, false, onRefundClick))
            actions
        }
        OrderStatus.PENDING_SETTLEMENT, OrderStatus.SETTLED -> {
            // 用户端：结算状态显示为已完成操作栏（含评价/退款）
            if (refundStatus == "PENDING") {
                listOf(
                    OrderAction(R.string.cancel_application, false, onCancelRefundClick)
                )
            } else {
                val actions = mutableListOf<OrderAction>()
                if (!order.hasReview) {
                    actions.add(OrderAction(R.string.action_review, true, onReviewClick))
                } else {
                    actions.add(OrderAction(R.string.view_review, false, { onViewReviewClick(order.id) }))
                }
                actions.add(OrderAction(R.string.apply_refund, false, onRefundClick))
                actions
            }
        }
        OrderStatus.DISPUTE_MEDIATION -> listOf(
            OrderAction(R.string.refund_detail, false, onRefundClick)
        )
        OrderStatus.REFUNDING -> listOf(
            OrderAction(R.string.refund_detail, false, onRefundClick)
        )
        OrderStatus.REFUNDED, OrderStatus.CANCELLED -> listOf(
            OrderAction(R.string.delete_order, false, onDeleteClick)
        )
        // Legacy
        OrderStatus.PAID, OrderStatus.TO_USE -> listOf(
            OrderAction(R.string.view_voucher, false, onVoucherClick),
            OrderAction(R.string.apply_refund, false, onRefundClick),
            OrderAction(R.string.delete_order, false, onDeleteClick)
        )
    }
}

// ── Status Badge ──────────────────────────────────────────────────────────────

@Composable
private fun OrderStatusBadge(status: OrderStatus, refundStatus: String = "NONE") {
    val (text, color) = when {
        refundStatus == "PENDING" -> stringResource(R.string.status_under_review) to Warning
        else -> when (status) {
            OrderStatus.PENDING_PAYMENT -> stringResource(R.string.status_pending_payment) to Warning
            OrderStatus.CONSULTATION_PAID -> stringResource(R.string.status_consultation_paid) to Secondary
            OrderStatus.VERIFIED -> stringResource(R.string.status_verified) to Secondary
            OrderStatus.BALANCE_PAID -> stringResource(R.string.status_balance_paid) to Secondary
            OrderStatus.PENDING_COMPLETION -> stringResource(R.string.status_pending_completion) to Warning
            OrderStatus.COMPLETED -> stringResource(R.string.status_completed) to Success
            // 用户端：结算状态显示为“已完成”（结算仅管理后台/医生端可见）
            OrderStatus.PENDING_SETTLEMENT -> stringResource(R.string.status_completed) to Success
            OrderStatus.SETTLED -> stringResource(R.string.status_completed) to Success
            OrderStatus.DISPUTE_MEDIATION -> stringResource(R.string.status_dispute_mediation) to Error
            OrderStatus.CANCELLED -> stringResource(R.string.status_cancelled) to TextSecondary
            OrderStatus.REFUNDED -> stringResource(R.string.status_refunded) to TextSecondary
            // Legacy
            OrderStatus.PENDING_REMAINING -> stringResource(R.string.status_pending_remaining) to Warning
            OrderStatus.PAID -> stringResource(R.string.status_to_use) to Secondary
            OrderStatus.TO_USE -> stringResource(R.string.status_to_use) to Secondary
            OrderStatus.REFUNDING -> stringResource(R.string.status_refunding) to Error
        }
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            color = color,
            fontWeight = FontWeight.Medium
        )
    }
}

// ── Balance Due Tag ──────────────────────────────────────────────────────────

@Composable
private fun BalanceDueTag() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Warning.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = stringResource(R.string.pending_balance_tag),
            fontSize = 13.sp,
            color = Warning,
            fontWeight = FontWeight.Medium
        )
    }
}

// ── Refund Rejected Tag ──────────────────────────────────────────────────────

@Composable
private fun RefundRejectedTag() {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Error.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = stringResource(R.string.order_tab_rejected),
            fontSize = 13.sp,
            color = Error,
            fontWeight = FontWeight.Medium
        )
    }
}

// ── Previews ──────────────────────────────────────────────────────────────────

private fun mockOrder(
    id: String = "1",
    status: OrderStatus = OrderStatus.PENDING_PAYMENT,
    projectName: String = "热玛吉五代全脸抗衰",
    institutionName: String = "娇颜医疗美容医院",
    price: Double = 12800.0,
    consultationFee: Double = 500.0,
    remainingAmount: Double = 12300.0,
    coverImage: String = "",
    createdAt: String = "2026-07-20 14:30",
    appointmentTime: String = "2026-08-01 10:00",
    orderNo: String = "JS20260720001",
    verifyCode: String = "8826",
    doctorName: String = "李医生",
    refundStatus: String = "NONE",
    refundAmount: Double = 0.0
) = Order(
    id = id,
    projectName = projectName,
    institutionName = institutionName,
    coverImage = coverImage,
    price = price,
    paidAmount = 0.0,
    status = status,
    createdAt = createdAt,
    appointmentTime = appointmentTime,
    consultationFee = consultationFee,
    remainingAmount = remainingAmount,
    orderNo = orderNo,
    verifyCode = verifyCode,
    doctorName = doctorName,
    refundStatus = refundStatus,
    refundAmount = refundAmount
)

@Preview(showBackground = true, showSystemUi = true, locale = "zh", name = "订单卡片 - 待支付面诊金")
@Composable
private fun OrderCardPendingPaymentPreview() {
    JoysongTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            OrderCard(
                order = mockOrder(status = OrderStatus.PENDING_PAYMENT),
                onOrderClick = {},
                onPayClick = {},
                onCancelClick = {},
                onRefundClick = {},
                onReviewClick = {}
            )
        }
    }
}

@Preview(showBackground = true, showSystemUi = true, locale = "zh", name = "订单卡片 - 面诊金已付")
@Composable
private fun OrderCardConsultationPaidPreview() {
    JoysongTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            OrderCard(
                order = mockOrder(
                    id = "2",
                    status = OrderStatus.CONSULTATION_PAID,
                    verifyCode = "6632"
                ),
                onOrderClick = {},
                onPayClick = {},
                onCancelClick = {},
                onRefundClick = {},
                onReviewClick = {},
                onViewReviewClick = {},
                onCancelRefundClick = {}
            )
        }
    }
}

@Preview(showBackground = true, showSystemUi = true, locale = "zh", name = "订单卡片 - 已核验(待付尾款)")
@Composable
private fun OrderCardVerifiedPreview() {
    JoysongTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            OrderCard(
                order = mockOrder(
                    id = "3",
                    status = OrderStatus.VERIFIED,
                    verifyCode = "7741"
                ),
                onOrderClick = {},
                onPayClick = {},
                onCancelClick = {},
                onRefundClick = {},
                onReviewClick = {},
                onViewReviewClick = {},
                onCancelRefundClick = {}
            )
        }
    }
}

@Preview(showBackground = true, showSystemUi = true, locale = "zh", name = "订单卡片 - 待确认完成")
@Composable
private fun OrderCardPendingCompletionPreview() {
    JoysongTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            OrderCard(
                order = mockOrder(
                    id = "4",
                    status = OrderStatus.PENDING_COMPLETION,
                    price = 15800.0,
                    consultationFee = 800.0,
                    remainingAmount = 0.0
                ),
                onOrderClick = {},
                onPayClick = {},
                onCancelClick = {},
                onRefundClick = {},
                onReviewClick = {},
                onViewReviewClick = {},
                onConfirmCompletionClick = {},
                onCancelRefundClick = {}
            )
        }
    }
}

@Preview(showBackground = true, showSystemUi = true, locale = "zh", name = "订单卡片 - 已完成")
@Composable
private fun OrderCardCompletedPreview() {
    JoysongTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            OrderCard(
                order = mockOrder(
                    id = "5",
                    status = OrderStatus.COMPLETED,
                    price = 9800.0,
                    consultationFee = 300.0,
                    remainingAmount = 0.0
                ),
                onOrderClick = {},
                onPayClick = {},
                onCancelClick = {},
                onRefundClick = {},
                onReviewClick = {},
                onViewReviewClick = {},
                onDeleteClick = {},
                onCancelRefundClick = {}
            )
        }
    }
}

@Preview(showBackground = true, showSystemUi = true, locale = "zh", name = "订单卡片 - 已取消")
@Composable
private fun OrderCardCancelledPreview() {
    JoysongTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            OrderCard(
                order = mockOrder(
                    id = "6",
                    status = OrderStatus.CANCELLED
                ),
                onOrderClick = {},
                onPayClick = {},
                onCancelClick = {},
                onRefundClick = {},
                onReviewClick = {},
                onViewReviewClick = {},
                onDeleteClick = {}
            )
        }
    }
}

@Preview(showBackground = true, locale = "en", name = "多状态订单卡片列表")
@Composable
private fun OrderCardMultiStatusPreview() {
    JoysongTheme {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            listOf(
                OrderStatus.PENDING_PAYMENT,
                OrderStatus.CONSULTATION_PAID,
                OrderStatus.VERIFIED,
                OrderStatus.PENDING_COMPLETION,
                OrderStatus.COMPLETED,
                OrderStatus.CANCELLED
            ).forEachIndexed { index, status ->
                OrderCard(
                    order = mockOrder(
                        id = "${index + 1}",
                        status = status,
                        verifyCode = if (status == OrderStatus.CONSULTATION_PAID || status == OrderStatus.VERIFIED) "88${index}6" else ""
                    ),
                    onOrderClick = {},
                    onPayClick = {},
                    onCancelClick = {},
                    onRefundClick = {},
                    onReviewClick = {},
                                    onViewReviewClick = {},
                    onDeleteClick = {},
                    onConfirmCompletionClick = {},
                    onCancelRefundClick = {}
                )
            }
        }
    }
}

@Preview(showBackground = true, showSystemUi = true, locale = "zh", name = "订单卡片 - 全款已付")
@Composable
private fun OrderCardBalancePaidPreview() {
    JoysongTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            OrderCard(
                order = mockOrder(
                    id = "7",
                    status = OrderStatus.BALANCE_PAID,
                    verifyCode = "9901",
                    remainingAmount = 0.0
                ),
                onOrderClick = {},
                onPayClick = {},
                onCancelClick = {},
                onRefundClick = {},
                onReviewClick = {},
                onViewReviewClick = {},
                onDeleteClick = {},
                onCancelRefundClick = {}
            )
        }
    }
}

@Preview(showBackground = true, showSystemUi = true, locale = "zh", name = "订单卡片 - 已核验(退款待审)")
@Composable
private fun OrderCardVerifiedRefundPendingPreview() {
    JoysongTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            OrderCard(
                order = mockOrder(
                    id = "8",
                    status = OrderStatus.VERIFIED,
                    verifyCode = "7741",
                    refundStatus = "PENDING",
                    refundAmount = 500.0
                ),
                onOrderClick = {},
                onPayClick = {},
                onCancelClick = {},
                onRefundClick = {},
                onReviewClick = {},
                onViewReviewClick = {},
                onDeleteClick = {},
                onCancelRefundClick = {}
            )
        }
    }
}

// ── 高优先级 Preview（基线状态缺失）──────────────────────────────────────────

/** 待结算状态：订单已完成服务，等待平台结算 */
@Preview(showBackground = true, showSystemUi = true, locale = "zh", name = "订单卡片 - 待结算")
@Composable
private fun OrderCardPendingSettlementPreview() {
    JoysongTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            OrderCard(
                order = mockOrder(
                    id = "10",
                    status = OrderStatus.PENDING_SETTLEMENT,
                    remainingAmount = 0.0
                ),
                onOrderClick = {},
                onPayClick = {},
                onCancelClick = {},
                onRefundClick = {},
                onReviewClick = {},
                onViewReviewClick = {},
                onDeleteClick = {}
            )
        }
    }
}

/** 已结算状态：平台已完成结算 */
@Preview(showBackground = true, showSystemUi = true, locale = "en", name = "订单卡片 - 已结算")
@Composable
private fun OrderCardSettledPreview() {
    JoysongTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            OrderCard(
                order = mockOrder(
                    id = "11",
                    status = OrderStatus.SETTLED,
                    remainingAmount = 0.0
                ),
                onOrderClick = {},
                onPayClick = {},
                onCancelClick = {},
                onRefundClick = {},
                onReviewClick = {},
                onViewReviewClick = {},
                onDeleteClick = {}
            )
        }
    }
}

/** 争议调解中状态：退款产生争议，进入平台调解 */
@Preview(showBackground = true, showSystemUi = true, locale = "zh", name = "订单卡片 - 争议调解中")
@Composable
private fun OrderCardDisputeMediationPreview() {
    JoysongTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            OrderCard(
                order = mockOrder(
                    id = "12",
                    status = OrderStatus.DISPUTE_MEDIATION,
                    remainingAmount = 0.0
                ),
                onOrderClick = {},
                onPayClick = {},
                onCancelClick = {},
                onRefundClick = {},
                onReviewClick = {},
                onViewReviewClick = {},
                onDeleteClick = {}
            )
        }
    }
}

/** 已退款状态：退款已完成 */
@Preview(showBackground = true, showSystemUi = true, locale = "zh", name = "订单卡片 - 已退款")
@Composable
private fun OrderCardRefundedPreview() {
    JoysongTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            OrderCard(
                order = mockOrder(
                    id = "13",
                    status = OrderStatus.REFUNDED,
                    refundAmount = 500.0
                ),
                onOrderClick = {},
                onPayClick = {},
                onCancelClick = {},
                onRefundClick = {},
                onReviewClick = {},
                onViewReviewClick = {},
                onDeleteClick = {}
            )
        }
    }
}

// ── 中优先级 Preview（退款组合场景）──────────────────────────────────────────

/** 全款已付 + 退款待审核：已付全款后申请退款 */
@Preview(showBackground = true, showSystemUi = true, locale = "zh", name = "订单卡片 - 全款已付(退款待审)")
@Composable
private fun OrderCardBalancePaidRefundPendingPreview() {
    JoysongTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            OrderCard(
                order = mockOrder(
                    id = "14",
                    status = OrderStatus.BALANCE_PAID,
                    refundStatus = "PENDING",
                    refundAmount = 12800.0,
                    remainingAmount = 0.0
                ),
                onOrderClick = {},
                onPayClick = {},
                onCancelClick = {},
                onRefundClick = {},
                onReviewClick = {},
                onViewReviewClick = {},
                onDeleteClick = {},
                onCancelRefundClick = {}
            )
        }
    }
}

/** 已核验 + 退款已批准：退款申请已通过审批 */
@Preview(showBackground = true, showSystemUi = true, locale = "zh", name = "订单卡片 - 已核验(退款已批准)")
@Composable
private fun OrderCardRefundApprovedPreview() {
    JoysongTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            OrderCard(
                order = mockOrder(
                    id = "15",
                    status = OrderStatus.VERIFIED,
                    refundStatus = "APPROVED",
                    refundAmount = 500.0
                ),
                onOrderClick = {},
                onPayClick = {},
                onCancelClick = {},
                onRefundClick = {},
                onReviewClick = {},
                onViewReviewClick = {},
                onDeleteClick = {}
            )
        }
    }
}

/** 已核验 + 退款被驳回：退款申请被拒绝 */
@Preview(showBackground = true, showSystemUi = true, locale = "en", name = "订单卡片 - 已核验(退款被驳回)")
@Composable
private fun OrderCardRefundRejectedPreview() {
    JoysongTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            OrderCard(
                order = mockOrder(
                    id = "16",
                    status = OrderStatus.VERIFIED,
                    refundStatus = "REJECTED",
                    refundAmount = 0.0
                ),
                onOrderClick = {},
                onPayClick = {},
                onCancelClick = {},
                onRefundClick = {},
                onReviewClick = {},
                onViewReviewClick = {},
                onDeleteClick = {}
            )
        }
    }
}

/** 待确认完成 + 退款待审核：服务完成后申请退款 */
@Preview(showBackground = true, showSystemUi = true, locale = "zh", name = "订单卡片 - 待确认完成(退款待审)")
@Composable
private fun OrderCardPendingCompletionRefundPendingPreview() {
    JoysongTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            OrderCard(
                order = mockOrder(
                    id = "17",
                    status = OrderStatus.PENDING_COMPLETION,
                    refundStatus = "PENDING",
                    refundAmount = 12800.0,
                    remainingAmount = 0.0
                ),
                onOrderClick = {},
                onPayClick = {},
                onCancelClick = {},
                onRefundClick = {},
                onReviewClick = {},
                onViewReviewClick = {},
                onDeleteClick = {},
                onConfirmCompletionClick = {},
                onCancelRefundClick = {}
            )
        }
    }
}

/** 已完成 + 退款待审核：完成后申请退款 */
@Preview(showBackground = true, showSystemUi = true, locale = "zh", name = "订单卡片 - 已完成(退款待审)")
@Composable
private fun OrderCardCompletedRefundPendingPreview() {
    JoysongTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            OrderCard(
                order = mockOrder(
                    id = "18",
                    status = OrderStatus.COMPLETED,
                    refundStatus = "PENDING",
                    refundAmount = 12800.0,
                    remainingAmount = 0.0
                ),
                onOrderClick = {},
                onPayClick = {},
                onCancelClick = {},
                onRefundClick = {},
                onReviewClick = {},
                onViewReviewClick = {},
                onDeleteClick = {},
                onCancelRefundClick = {}
            )
        }
    }
}

/** 面诊金已付 + 退款待审核：仅付面诊金后申请退款 */
@Preview(showBackground = true, showSystemUi = true, locale = "zh", name = "订单卡片 - 面诊金已付(退款待审)")
@Composable
private fun OrderCardConsultationPaidRefundPendingPreview() {
    JoysongTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            OrderCard(
                order = mockOrder(
                    id = "19",
                    status = OrderStatus.CONSULTATION_PAID,
                    refundStatus = "PENDING",
                    refundAmount = 500.0
                ),
                onOrderClick = {},
                onPayClick = {},
                onCancelClick = {},
                onRefundClick = {},
                onReviewClick = {},
                onViewReviewClick = {},
                onCancelRefundClick = {}
            )
        }
    }
}

// ── 低优先级 Preview（边缘场景补充）──────────────────────────────────────────

/** 退款中状态：退款正在处理 */
@Preview(showBackground = true, showSystemUi = true, locale = "zh", name = "订单卡片 - 退款中")
@Composable
private fun OrderCardRefundingPreview() {
    JoysongTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            OrderCard(
                order = mockOrder(
                    id = "20",
                    status = OrderStatus.REFUNDING,
                    refundAmount = 12800.0,
                    remainingAmount = 0.0
                ),
                onOrderClick = {},
                onPayClick = {},
                onCancelClick = {},
                onRefundClick = {},
                onReviewClick = {},
                onViewReviewClick = {},
                onDeleteClick = {}
            )
        }
    }
}

/** 全款已付 + 退款被驳回：全款支付后退款申请被拒绝 */
@Preview(showBackground = true, showSystemUi = true, locale = "zh", name = "订单卡片 - 全款已付(退款被驳回)")
@Composable
private fun OrderCardBalancePaidRefundRejectedPreview() {
    JoysongTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            OrderCard(
                order = mockOrder(
                    id = "21",
                    status = OrderStatus.BALANCE_PAID,
                    refundStatus = "REJECTED",
                    refundAmount = 0.0,
                    remainingAmount = 0.0
                ),
                onOrderClick = {},
                onPayClick = {},
                onCancelClick = {},
                onRefundClick = {},
                onReviewClick = {},
                onViewReviewClick = {},
                onDeleteClick = {},
                onCancelRefundClick = {}
            )
        }
    }
}

/** 已完成 + 退款被驳回：完成后退款申请被拒绝 */
@Preview(showBackground = true, showSystemUi = true, locale = "en", name = "订单卡片 - 已完成(退款被驳回)")
@Composable
private fun OrderCardCompletedRefundRejectedPreview() {
    JoysongTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            OrderCard(
                order = mockOrder(
                    id = "22",
                    status = OrderStatus.COMPLETED,
                    refundStatus = "REJECTED",
                    refundAmount = 0.0,
                    remainingAmount = 0.0
                ),
                onOrderClick = {},
                onPayClick = {},
                onCancelClick = {},
                onRefundClick = {},
                onReviewClick = {},
                onViewReviewClick = {},
                onDeleteClick = {},
                onCancelRefundClick = {}
            )
        }
    }
}

/** 待确认完成 + 退款被驳回：待完成状态下退款申请被拒绝 */
@Preview(showBackground = true, showSystemUi = true, locale = "zh", name = "订单卡片 - 待确认完成(退款被驳回)")
@Composable
private fun OrderCardPendingCompletionRefundRejectedPreview() {
    JoysongTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            OrderCard(
                order = mockOrder(
                    id = "23",
                    status = OrderStatus.PENDING_COMPLETION,
                    refundStatus = "REJECTED",
                    refundAmount = 0.0,
                    remainingAmount = 0.0
                ),
                onOrderClick = {},
                onPayClick = {},
                onCancelClick = {},
                onRefundClick = {},
                onReviewClick = {},
                onViewReviewClick = {},
                onDeleteClick = {},
                onConfirmCompletionClick = {},
                onCancelRefundClick = {}
            )
        }
    }
}

/** 已结算 + 退款待审核：结算后申请退款 */
@Preview(showBackground = true, showSystemUi = true, locale = "zh", name = "订单卡片 - 已结算(退款待审)")
@Composable
private fun OrderCardSettledRefundPendingPreview() {
    JoysongTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            OrderCard(
                order = mockOrder(
                    id = "24",
                    status = OrderStatus.SETTLED,
                    refundStatus = "PENDING",
                    refundAmount = 12800.0,
                    remainingAmount = 0.0
                ),
                onOrderClick = {},
                onPayClick = {},
                onCancelClick = {},
                onRefundClick = {},
                onReviewClick = {},
                onViewReviewClick = {},
                onDeleteClick = {},
                onCancelRefundClick = {}
            )
        }
    }
}

/** 待结算 + 退款待审核：待结算状态下申请退款 */
@Preview(showBackground = true, showSystemUi = true, locale = "en", name = "订单卡片 - 待结算(退款待审)")
@Composable
private fun OrderCardPendingSettlementRefundPendingPreview() {
    JoysongTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            OrderCard(
                order = mockOrder(
                    id = "25",
                    status = OrderStatus.PENDING_SETTLEMENT,
                    refundStatus = "PENDING",
                    refundAmount = 12800.0,
                    remainingAmount = 0.0
                ),
                onOrderClick = {},
                onPayClick = {},
                onCancelClick = {},
                onRefundClick = {},
                onReviewClick = {},
                onViewReviewClick = {},
                onDeleteClick = {},
                onCancelRefundClick = {}
            )
        }
    }
}
