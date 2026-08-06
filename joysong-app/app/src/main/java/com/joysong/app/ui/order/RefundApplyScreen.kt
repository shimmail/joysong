package com.joysong.app.ui.order

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.tooling.preview.Preview
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.joysong.app.R
import com.joysong.app.domain.model.Order
import com.joysong.app.domain.model.OrderStatus
import com.joysong.app.ui.components.ErrorView
import com.joysong.app.ui.components.JoysongTopBar
import com.joysong.app.ui.components.LoadingIndicator
import com.joysong.app.ui.theme.Background
import com.joysong.app.ui.theme.JoysongTheme
import com.joysong.app.ui.theme.PrimaryDark
import com.joysong.app.ui.theme.Surface
import com.joysong.app.ui.theme.TextHint
import com.joysong.app.ui.theme.TextOnPrimary
import com.joysong.app.ui.theme.TextPrimary
import com.joysong.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch

private val RefundReasonResIds = listOf(
    R.string.refund_reason_no_interest,
    R.string.refund_reason_wrong_time,
    R.string.refund_reason_wrong_institution,
    R.string.refund_reason_wrong_project,
    R.string.refund_reason_wrong_doctor,
    R.string.refund_reason_other_option
)

private val AmountRed = Color(0xFFF44336)

@Composable
fun RefundApplyScreen(
    orderId: String,
    navController: NavHostController,
    viewModel: RefundApplyViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Pre-resolve stringResource values for use in non-composable lambdas
    val successToast = stringResource(R.string.refund_submitted_success)
    val reasonRequiredToast = stringResource(R.string.select_refund_reason_required)
    val customReasonRequiredToast = stringResource(R.string.custom_reason_required)
    val maxEvidenceToast = stringResource(R.string.max_evidence_3)
    val otherOptionText = stringResource(R.string.refund_reason_other_option)

    LaunchedEffect(orderId) { viewModel.loadOrder(orderId) }

    val order by viewModel.order.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val submitState by viewModel.submitState.collectAsState()

    var selectedReason by remember { mutableStateOf("") }
    var customReason by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    val evidenceUrls = remember { mutableStateListOf<String>() }
    val evidenceUris = remember { mutableStateListOf<Uri>() }
    var showReasonDialog by remember { mutableStateOf(false) }
    var isUploading by remember { mutableStateOf(false) }

    // Image picker
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            scope.launch {
                isUploading = true
                for (uri in uris) {
                    if (evidenceUrls.size < 3) {
                        val url = viewModel.uploadImage(uri, context)
                        if (url != null) {
                            evidenceUrls.add(url)
                            evidenceUris.add(uri)
                        }
                    }
                }
                isUploading = false
            }
        }
    }

    // Handle submit result
    LaunchedEffect(submitState) {
        when (submitState) {
            is RefundSubmitState.Success -> {
                Toast.makeText(context, successToast, Toast.LENGTH_SHORT).show()
                navController.popBackStack()
            }
            is RefundSubmitState.Error -> {
                Toast.makeText(context, (submitState as RefundSubmitState.Error).message, Toast.LENGTH_SHORT).show()
                viewModel.resetSubmitState()
            }
            else -> {}
        }
    }

    // Reason selection dialog
    if (showReasonDialog) {
        AlertDialog(
            onDismissRequest = { showReasonDialog = false },
            title = { Text(stringResource(R.string.select_refund_reason)) },
            text = {
                Column {
                    RefundReasonResIds.forEach { reasonRes ->
                        val reasonText = stringResource(reasonRes)
                        TextButton(
                            onClick = {
                                selectedReason = reasonText
                                showReasonDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = reasonText,
                                color = if (reasonText == selectedReason) PrimaryDark else TextPrimary,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showReasonDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    Scaffold(
        topBar = {
            JoysongTopBar(
                title = stringResource(R.string.apply_refund),
                onBackClick = { navController.popBackStack() }
            )
        },
        bottomBar = {
            if (order != null) {
                val isSubmitting = submitState is RefundSubmitState.Loading
                Button(
                    onClick = {
                        if (selectedReason.isBlank()) {
                            Toast.makeText(context, reasonRequiredToast, Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        val finalReason = if (selectedReason == otherOptionText) {
                            if (customReason.isBlank()) {
                                Toast.makeText(context, customReasonRequiredToast, Toast.LENGTH_SHORT).show()
                                return@Button
                            }
                            customReason
                        } else {
                            selectedReason
                        }
                        viewModel.applyRefund(orderId, finalReason, description, evidenceUrls)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .height(52.dp),
                    shape = RoundedCornerShape(26.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryDark),
                    enabled = !isSubmitting
                ) {
                    Text(
                        text = if (isSubmitting) stringResource(R.string.submitting_short) else stringResource(R.string.submit_application),
                        color = TextOnPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
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
                val o = order!!
                // 退款金额 = 累计已付金额
                val refundAmount = o.paidAmount
                Column(
                    modifier = Modifier
                        .padding(padding)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // a) 关联订单信息卡片
                    RefundOrderInfoCard(o, refundAmount)

                    // b) 退款原因选择
                    ReasonSelectCard(selectedReason) {
                        showReasonDialog = true
                        // 重新选择时清空自定义原因
                        customReason = ""
                    }

                    // b2) 选择"其他"时显示自定义输入框
                    if (selectedReason == otherOptionText) {
                        CustomReasonInputCard(customReason) { customReason = it }
                    }

                    // c) 退款金额
                    RefundAmountCard(o, refundAmount)

                    // d) 退款说明
                    RefundDescriptionCard(description) { description = it }

                    // e) 上传凭证
                    RefundEvidenceCard(
                        evidenceUris = evidenceUris,
                        isUploading = isUploading,
                        onAddClick = {
                            val remaining = 3 - evidenceUrls.size
                            if (remaining > 0) {
                                imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            } else {
                                Toast.makeText(context, maxEvidenceToast, Toast.LENGTH_SHORT).show()
                            }
                        },
                        onRemoveClick = { index ->
                            if (index in evidenceUrls.indices) {
                                evidenceUrls.removeAt(index)
                                evidenceUris.removeAt(index)
                            }
                        }
                    )

                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

// ── Order Info Card ───────────────────────────────────────────────────────────

@Composable
private fun RefundOrderInfoCard(order: Order, refundAmount: Double) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.related_order), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = order.projectName,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = formatRefundAmount(refundAmount),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.order_no_format, order.orderNo.ifBlank { order.id }),
                fontSize = 12.sp,
                color = TextHint
            )
        }
    }
}

// ── Reason Select Card ────────────────────────────────────────────────────────

@Composable
private fun ReasonSelectCard(selectedReason: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.refund_reason_label), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
            Text(
                text = selectedReason.ifBlank { stringResource(R.string.please_select) },
                fontSize = 14.sp,
                color = if (selectedReason.isNotBlank()) PrimaryDark else TextHint
            )
        }
    }
}

// ── Custom Reason Input Card ────────────────────────────────────────────────

@Composable
private fun CustomReasonInputCard(customReason: String, onValueChange: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.refund_reason_label),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = TextPrimary
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = customReason,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4,
                placeholder = { Text(stringResource(R.string.custom_reason_hint), color = TextHint) },
                shape = RoundedCornerShape(8.dp)
            )
        }
    }
}

// ── Refund Amount Card ────────────────────────────────────────────────────────

@Composable
private fun RefundAmountCard(order: Order, refundAmount: Double) {
    val isConsultationRefund = order.status == OrderStatus.CONSULTATION_PAID || order.status == OrderStatus.VERIFIED
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.refund_amount_label), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
            Spacer(Modifier.height(8.dp))
            Text(
                text = formatRefundAmount(refundAmount),
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = AmountRed
            )
            // CONSULTATION_PAID 状态退款金额本身就是面诊金，无需再显示"含面诊金"提示
            if (!isConsultationRefund && order.consultationFee > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.refund_amount_include_fee, formatRefundAmount(order.consultationFee)),
                    fontSize = 12.sp,
                    color = TextHint
                )
            }
        }
    }
}

// ── Description Card ──────────────────────────────────────────────────────────

@Composable
private fun RefundDescriptionCard(description: String, onValueChange: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.refund_description_label), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = description,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = 4,
                maxLines = 8,
                placeholder = { Text(stringResource(R.string.refund_description_hint), color = TextHint) },
                shape = RoundedCornerShape(8.dp)
            )
        }
    }
}

// ── Evidence Card ─────────────────────────────────────────────────────────────

@Composable
private fun RefundEvidenceCard(
    evidenceUris: List<Uri>,
    isUploading: Boolean,
    onAddClick: () -> Unit,
    onRemoveClick: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.upload_evidence), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.evidence_optional_hint), fontSize = 12.sp, color = TextHint)
            }
            Spacer(Modifier.height(12.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items(evidenceUris.size) { index ->
                    Box {
                        AsyncImage(
                            model = evidenceUris[index],
                            contentDescription = stringResource(R.string.evidence_image_desc),
                            modifier = Modifier
                                .size(80.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                        IconButton(
                            onClick = { onRemoveClick(index) },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .size(20.dp)
                                .offset(4.dp, (-4).dp)
                                .background(Color(0xFF666666), RoundedCornerShape(10.dp))
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(R.string.delete),
                                tint = Color.White,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }
                }
                if (evidenceUris.size < 3) {
                    item {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(80.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .border(1.dp, TextHint, RoundedCornerShape(8.dp))
                                .clickable(enabled = !isUploading) { onAddClick() }
                        ) {
                            if (isUploading) {
                                Text(stringResource(R.string.uploading_short), fontSize = 10.sp, color = TextHint)
                            } else {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = stringResource(R.string.add_image_desc),
                                    tint = TextHint,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Helpers ────────────────────────────────────────────────────────────────────

private fun formatRefundAmount(amount: Double): String = String.format("¥%.2f", amount)

// ── Previews ──────────────────────────────────────────────────────────────────

private fun mockRefundOrder(
    id: String = "ORD-R001",
    projectName: String = "热玛吉五代全脸抗衰",
    institutionName: String = "娇颜医疗美容医院",
    price: Double = 12800.0,
    paidAmount: Double = 500.0,
    consultationFee: Double = 500.0,
    orderNo: String = "JS20260728001"
): Order = Order(
    id = id,
    projectName = projectName,
    institutionName = institutionName,
    coverImage = "",
    price = price,
    paidAmount = paidAmount,
    status = OrderStatus.CONSULTATION_PAID,
    createdAt = "2026-07-28 10:00",
    consultationFee = consultationFee,
    orderNo = orderNo
)

@Preview(showBackground = true, showSystemUi = true, locale = "zh", name = "退款申请 - 默认状态(未选择理由)")
@Composable
private fun RefundApplyDefaultPreview() {
    val order = mockRefundOrder()
    JoysongTheme {
        val refundAmount = order.paidAmount
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            RefundOrderInfoCard(order, refundAmount)
            ReasonSelectCard(selectedReason = "") {}
            RefundAmountCard(order, refundAmount)
            RefundDescriptionCard(description = "") {}
            RefundEvidenceCard(
                evidenceUris = emptyList(),
                isUploading = false,
                onAddClick = {},
                onRemoveClick = {}
            )
        }
    }
}

@Preview(showBackground = true, showSystemUi = true, locale = "zh", name = "退款申请 - 已选择理由")
@Composable
private fun RefundApplyReasonSelectedPreview() {
    val order = mockRefundOrder()
    val refundAmount = order.paidAmount
    JoysongTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            RefundOrderInfoCard(order, refundAmount)
            ReasonSelectCard(selectedReason = "不想做了") {}
            RefundAmountCard(order, refundAmount)
            RefundDescriptionCard(description = "临时有事无法前往，希望全额退款。") {}
            RefundEvidenceCard(
                evidenceUris = emptyList(),
                isUploading = false,
                onAddClick = {},
                onRemoveClick = {}
            )
        }
    }
}

@Preview(showBackground = true, showSystemUi = true, locale = "zh", name = "退款申请 - 选择其他理由(含自定义输入)")
@Composable
private fun RefundApplyOtherReasonPreview() {
    val order = mockRefundOrder(
        price = 9800.0,
        paidAmount = 500.0,
        consultationFee = 300.0
    )
    val refundAmount = order.paidAmount
    JoysongTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            RefundOrderInfoCard(order, refundAmount)
            ReasonSelectCard(selectedReason = "其他") {}
            CustomReasonInputCard(customReason = "因个人原因需要取消，请协助办理退款。") {}
            RefundAmountCard(order, refundAmount)
            RefundDescriptionCard(description = "因个人原因需要取消，请协助办理退款。") {}
            RefundEvidenceCard(
                evidenceUris = emptyList(),
                isUploading = false,
                onAddClick = {},
                onRemoveClick = {}
            )
        }
    }
}

@Preview(showBackground = true, showSystemUi = true, locale = "zh", name = "退款卡片 - 订单信息")
@Composable
private fun RefundOrderInfoCardPreview() {
    val order = mockRefundOrder()
    val refundAmount = order.paidAmount
    JoysongTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            RefundOrderInfoCard(order, refundAmount)
        }
    }
}

@Preview(showBackground = true, showSystemUi = true, locale = "zh", name = "退款卡片 - 理由选择(未选)")
@Composable
private fun ReasonSelectCardEmptyPreview() {
    JoysongTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            ReasonSelectCard(selectedReason = "") {}
        }
    }
}

@Preview(showBackground = true, showSystemUi = true, locale = "zh", name = "退款卡片 - 理由选择(已选)")
@Composable
private fun ReasonSelectCardSelectedPreview() {
    JoysongTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            ReasonSelectCard(selectedReason = "不想做了") {}
        }
    }
}

@Preview(showBackground = true, showSystemUi = true, locale = "zh", name = "退款卡片 - 退款金额")
@Composable
private fun RefundAmountCardPreview() {
    val order = mockRefundOrder()
    val refundAmount = order.paidAmount
    JoysongTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            RefundAmountCard(order, refundAmount)
        }
    }
}

@Preview(showBackground = true, showSystemUi = true, locale = "zh", name = "退款卡片 - 退款说明")
@Composable
private fun RefundDescriptionCardPreview() {
    JoysongTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            RefundDescriptionCard(description = "临时有事无法前往") {}
        }
    }
}

@Preview(showBackground = true, showSystemUi = true, locale = "zh", name = "退款卡片 - 上传凭证(空)")
@Composable
private fun RefundEvidenceCardEmptyPreview() {
    JoysongTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            RefundEvidenceCard(
                evidenceUris = emptyList(),
                isUploading = false,
                onAddClick = {},
                onRemoveClick = {}
            )
        }
    }
}

@Preview(showBackground = true, showSystemUi = true, locale = "zh", name = "退款卡片 - 上传凭证(上传中)")
@Composable
private fun RefundEvidenceCardUploadingPreview() {
    JoysongTheme {
        Column(modifier = Modifier.padding(16.dp)) {
            RefundEvidenceCard(
                evidenceUris = emptyList(),
                isUploading = true,
                onAddClick = {},
                onRemoveClick = {}
            )
        }
    }
}
