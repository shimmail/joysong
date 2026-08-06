package com.joysong.app.ui.booking

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.util.Calendar
import java.util.TimeZone
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.joysong.app.R
import com.joysong.app.domain.model.Doctor
import com.joysong.app.ui.components.JoysongTopBar
import com.joysong.app.ui.components.LoadingIndicator
import com.joysong.app.ui.components.PlaceholderImage
import com.joysong.app.ui.components.ScrollingTimePicker
import com.joysong.app.ui.theme.Background
import com.joysong.app.ui.theme.Error
import com.joysong.app.ui.theme.Primary
import com.joysong.app.ui.theme.PrimaryDark
import com.joysong.app.ui.theme.Surface
import com.joysong.app.ui.theme.TextHint
import com.joysong.app.ui.theme.TextOnPrimary
import com.joysong.app.ui.theme.TextPrimary
import com.joysong.app.ui.theme.TextSecondary

// Design colors
private val BookingPageBg = Color(0xFFF5F5F5)
private val BookingCardBg = Color.White
private val BookingPriceColor = Color(0xFFE53935)
private val BookingAccentDark = Color(0xFF1A1A1A)
private val BookingBodyColor = Color(0xFF666666)
private val BookingHintColor = Color(0xFF999999)
private val BookingTagBg = Color(0xFFF0F0F0)
private val BookingDivider = Color(0xFFEEEEEE)

@Composable
fun BookingConfirmScreen(
    onBackClick: () -> Unit,
    onOrderCreated: (orderId: String) -> Unit,
    onSelectCouponClick: (originalPrice: Double) -> Unit,
    viewModel: BookingConfirmViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(uiState.orderCreated) {
        if (uiState.orderCreated) {
            val orderId = uiState.createdOrder?.id ?: ""
            viewModel.consumeOrderCreated()
            onOrderCreated(orderId)
        }
    }

    Scaffold(
        topBar = {
            JoysongTopBar(
                title = stringResource(R.string.booking_confirm_title),
                onBackClick = onBackClick
            )
        },
        bottomBar = {
            if (uiState.detail != null && !uiState.isLoading) {
                Button(
                    onClick = { viewModel.confirmBooking() },
                    enabled = !uiState.isCreating && uiState.selectedDoctor != null && uiState.appointmentTime.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .height(50.dp),
                    shape = RoundedCornerShape(25.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BookingAccentDark)
                ) {
                    if (uiState.isCreating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = TextOnPrimary,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.booking_creating_order),
                            color = TextOnPrimary,
                            fontWeight = FontWeight.SemiBold
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.confirm_booking),
                            color = TextOnPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        when {
            uiState.isLoading -> LoadingIndicator()
            uiState.detail != null -> {
                val detail = uiState.detail!!
                val project = detail.project
                val institution = detail.institution
                val ip = detail.institutionProject
                val consultationFee = viewModel.getConsultationFee()
                val projectPrice = ip.price
                val remainingAmount = (projectPrice - consultationFee).coerceAtLeast(0.0)

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .verticalScroll(rememberScrollState())
                        .background(BookingPageBg)
                ) {
                    Spacer(modifier = Modifier.height(12.dp))

                    // 1. Project info card
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = BookingCardBg)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            // Cover image
                            if (ip.coverImage.isNotBlank()) {
                                AsyncImage(
                                    model = ip.coverImage,
                                    contentDescription = project.name,
                                    modifier = Modifier
                                        .size(80.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop,
                                    onError = { /* fallback */ }
                                )
                            } else {
                                PlaceholderImage(
                                    modifier = Modifier
                                        .size(80.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    text = project.name.take(1)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = institution.name,
                                    fontSize = 13.sp,
                                    color = BookingBodyColor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = project.name,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = BookingAccentDark,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text(
                                        text = "¥${projectPrice.toInt()}",
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = BookingPriceColor
                                    )
                                    ip.originalPrice?.let { op ->
                                        if (op > projectPrice) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "¥${op.toInt()}",
                                                fontSize = 12.sp,
                                                color = BookingHintColor,
                                                textDecoration = TextDecoration.LineThrough,
                                                modifier = Modifier.padding(bottom = 2.dp)
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = stringResource(
                                        R.string.booking_fee_breakdown,
                                        consultationFee.toInt(),
                                        remainingAmount.toInt()
                                    ),
                                    fontSize = 12.sp,
                                    color = BookingHintColor
                                )
                            }
                            Text(
                                text = "×1",
                                fontSize = 13.sp,
                                color = BookingHintColor
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 2. Select doctor section
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = BookingCardBg)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.booking_select_doctor),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = BookingAccentDark
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            if (uiState.doctors.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.no_bookable_doctors),
                                    fontSize = 14.sp,
                                    color = BookingHintColor
                                )
                            } else {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    uiState.doctors.forEach { doctor ->
                                        DoctorSelectItem(
                                            doctor = doctor,
                                            isSelected = uiState.selectedDoctor?.id == doctor.id,
                                            onClick = { viewModel.selectDoctor(doctor) }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 2.5 Appointment time selector
                    AppointmentTimeCard(
                        selectedTime = uiState.appointmentTime,
                        onTimeSelected = { viewModel.onAppointmentTimeSelected(it) }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // 3. Remark
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = BookingCardBg)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.booking_remark_label),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = BookingAccentDark
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedTextField(
                                value = uiState.remark,
                                onValueChange = { viewModel.updateRemark(it) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(80.dp),
                                placeholder = {
                                    Text(
                                        stringResource(R.string.booking_remark_placeholder),
                                        fontSize = 13.sp,
                                        color = BookingHintColor
                                    )
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = BookingAccentDark,
                                    unfocusedBorderColor = BookingDivider,
                                    cursorColor = BookingAccentDark
                                ),
                                textStyle = TextStyle(fontSize = 13.sp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 4. Coupon entry
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .clickable { onSelectCouponClick(consultationFee) },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = BookingCardBg)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.coupon_label),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = BookingAccentDark
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
                                    text = stringResource(R.string.coupon_select_placeholder),
                                    fontSize = 13.sp,
                                    color = BookingHintColor
                                )
                            }
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = BookingHintColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 5. Price summary
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = BookingCardBg)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.booking_price_summary_title),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                color = BookingAccentDark
                            )
                            Spacer(modifier = Modifier.height(12.dp))

                            // 项目价格（主项，加粗）
                            PriceRow(
                                label = stringResource(R.string.booking_project_price),
                                value = "¥${projectPrice.toInt()}",
                                valueColor = BookingAccentDark,
                                labelWeight = FontWeight.Bold,
                                valueSize = 15.sp
                            )
                            Spacer(modifier = Modifier.height(4.dp))

                            // 面诊金（子项，缩进，浅色）
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.booking_consultation_fee_label),
                                    fontSize = 13.sp,
                                    color = TextSecondary
                                )
                                Text(
                                    text = "¥${consultationFee.toInt()}",
                                    fontSize = 13.sp,
                                    color = TextSecondary
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))

                            // 尾款（子项，缩进，浅色）
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.booking_remaining_label),
                                    fontSize = 13.sp,
                                    color = TextSecondary
                                )
                                Text(
                                    text = "¥${remainingAmount.toInt()}",
                                    fontSize = 13.sp,
                                    color = TextSecondary
                                )
                            }

                            if (uiState.selectedCouponDiscount > 0) {
                                Spacer(modifier = Modifier.height(6.dp))
                                PriceRow(
                                    label = stringResource(R.string.booking_coupon_discount),
                                    value = "-¥${uiState.selectedCouponDiscount.toInt()}",
                                    valueColor = PrimaryDark
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))
                            HorizontalDivider(
                                color = BookingDivider,
                                thickness = 1.dp
                            )
                            Spacer(modifier = Modifier.height(12.dp))

                            // 合计（= 项目价格）
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.booking_total_label),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = BookingAccentDark
                                )
                                Text(
                                    text = "¥${projectPrice.toInt()}",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = BookingPriceColor
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // 当前应付（面诊金）
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.booking_actual_payment_label),
                                    fontSize = 13.sp,
                                    color = BookingBodyColor
                                )
                                Text(
                                    text = "¥${consultationFee.toInt()}",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = BookingPriceColor
                                )
                            }
                        }
                    }

                    // Error message
                    if (uiState.error != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = uiState.error ?: "",
                            color = Error,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
            uiState.error != null -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = uiState.error ?: stringResource(R.string.load_failed),
                        color = TextSecondary,
                        fontSize = 15.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun DoctorSelectItem(
    doctor: Doctor,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) Color(0xFFF0F7FF) else BookingTagBg)
            .border(
                width = if (isSelected) 1.dp else 0.dp,
                color = if (isSelected) Primary else Color.Transparent,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar
        if (doctor.avatar.isNotBlank()) {
            AsyncImage(
                model = doctor.avatar,
                contentDescription = doctor.name,
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop,
                onError = { /* fallback */ }
            )
        } else {
            PlaceholderImage(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape),
                text = doctor.name.take(1)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${doctor.name} ${doctor.title}",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = BookingAccentDark,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (doctor.certificationTags.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = doctor.certificationTags.first(),
                    fontSize = 12.sp,
                    color = BookingBodyColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (isSelected) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = Primary,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun PriceRow(
    label: String,
    value: String,
    valueColor: Color = BookingAccentDark,
    subtitle: String? = null,
    labelWeight: FontWeight = FontWeight.Normal,
    valueSize: androidx.compose.ui.unit.TextUnit = 13.sp
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = label,
                fontSize = 13.sp,
                color = BookingBodyColor,
                fontWeight = labelWeight
            )
            if (subtitle != null) {
                Text(
                    text = "($subtitle)",
                    fontSize = 11.sp,
                    color = BookingHintColor
                )
            }
        }
        Text(
            text = value,
            fontSize = valueSize,
            color = valueColor,
            fontWeight = FontWeight.Medium
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppointmentTimeCard(
    selectedTime: String,
    onTimeSelected: (String) -> Unit
) {
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }

    val today = remember { Calendar.getInstance() }
    val todayText = remember {
        String.format(
            "%04d-%02d-%02d",
            today.get(Calendar.YEAR),
            today.get(Calendar.MONTH) + 1,
            today.get(Calendar.DAY_OF_MONTH)
        )
    }
    // DatePicker 内部使用 UTC 毫秒，需按本地日期构造当天 UTC 零点，避免时区偏移选中前一天
    val todayUtcMillis = remember {
        Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(
                today.get(Calendar.YEAR),
                today.get(Calendar.MONTH),
                today.get(Calendar.DAY_OF_MONTH)
            )
        }.timeInMillis
    }
    // 默认选中今天，避免用户未选日期就确定
    var selectedDate by remember { mutableStateOf(todayText) }

    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = todayUtcMillis)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = BookingCardBg)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.appointment_time_label),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = BookingAccentDark
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showDatePicker = true }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.DateRange,
                    contentDescription = null,
                    tint = if (selectedTime.isNotBlank()) BookingAccentDark else BookingHintColor,
                    modifier = Modifier.size(22.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (selectedTime.isNotBlank()) selectedTime
                    else stringResource(R.string.appointment_time_hint),
                    fontSize = 14.sp,
                    color = if (selectedTime.isNotBlank()) BookingAccentDark else BookingHintColor
                )
            }
        }
    }

    // DatePicker Dialog
    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { millis ->
                        val calendar = Calendar.getInstance()
                        calendar.timeInMillis = millis
                        selectedDate = String.format(
                            "%04d-%02d-%02d",
                            calendar.get(Calendar.YEAR),
                            calendar.get(Calendar.MONTH) + 1,
                            calendar.get(Calendar.DAY_OF_MONTH)
                        )
                    }
                    showDatePicker = false
                    showTimePicker = true
                }) { Text(stringResource(R.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.cancel)) }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    // TimePicker Dialog（自定义 Dialog + Card 包裹，确保时间选择器宽度足够）
    // confirmedHour/confirmedMinute 仅在点击“确定”时更新，作为下次打开选择器的初始值
    var confirmedHour by remember { mutableIntStateOf(9) }
    var confirmedMinute by remember { mutableIntStateOf(0) }
    // 选择器滚动中的实时值：普通数组不参与重组，避免滚动时持续重组弹窗子树而卡顿
    val livePick = remember { intArrayOf(9, 0) }

    LaunchedEffect(showTimePicker) {
        if (showTimePicker) {
            livePick[0] = confirmedHour
            livePick[1] = confirmedMinute
        }
    }

    if (showTimePicker) {
        Dialog(
            onDismissRequest = { showTimePicker = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.select_time),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    ScrollingTimePicker(
                        initialHour24 = confirmedHour,
                        initialMinute = confirmedMinute,
                        onTimeChanged = { hour24, minute ->
                            livePick[0] = hour24
                            livePick[1] = minute
                        }
                    )
                    Spacer(modifier = Modifier.height(20.dp))
                    HorizontalDivider(color = BookingDivider)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(onClick = { showTimePicker = false }) {
                            Text(stringResource(R.string.cancel))
                        }
                        TextButton(onClick = {
                            val hour = livePick[0]
                            val min = livePick[1]
                            confirmedHour = hour
                            confirmedMinute = min
                            onTimeSelected(
                                "${selectedDate}T" + String.format("%02d:%02d:00", hour, min)
                            )
                            showTimePicker = false
                        }) {
                            Text(stringResource(R.string.confirm))
                        }
                    }
                }
            }
        }
    }
}
