package com.joysong.app.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.joysong.app.R
import com.joysong.app.domain.model.Project
import com.joysong.app.ui.components.JoysongTopBar
import com.joysong.app.ui.components.LoadingIndicator
import com.joysong.app.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppointmentScreen(
    projectId: String,
    institutionId: String,
    project: Project?,
    onBackClick: () -> Unit,
    onGoToPay: (String, String) -> Unit // (doctorName, appointmentTime)
) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var remark by remember { mutableStateOf("") }
    val today = remember { java.util.Calendar.getInstance() }
    // 默认选中今天，避免用户未选日期就提交
    var selectedDate by remember {
        mutableStateOf(
            String.format(
                "%04d-%02d-%02d",
                today.get(java.util.Calendar.YEAR),
                today.get(java.util.Calendar.MONTH) + 1,
                today.get(java.util.Calendar.DAY_OF_MONTH)
            )
        )
    }
    // DatePicker 内部使用 UTC 毫秒，需按本地日期构造当天 UTC 零点，避免时区偏移选中前一天
    val todayUtcMillis = remember {
        java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(
                today.get(java.util.Calendar.YEAR),
                today.get(java.util.Calendar.MONTH),
                today.get(java.util.Calendar.DAY_OF_MONTH)
            )
        }.timeInMillis
    }
    var selectedTime by remember { mutableStateOf("12:00") }
    var showDatePicker by remember { mutableStateOf(false) }

    val consultationFee = 0
    val finalPayment = ((project?.referencePrice?.toInt() ?: 2000) - consultationFee)

    Scaffold(
        topBar = { JoysongTopBar(title = stringResource(R.string.appointment_title), onBackClick = onBackClick) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .background(Background)
        ) {
            // Service info card
            Card(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "—",
                        fontSize = 14.sp, color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = project?.name ?: stringResource(R.string.project_placeholder),
                        fontSize = 17.sp, fontWeight = FontWeight.Bold, color = TextPrimary
                    )
                    Row(modifier = Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = "¥${project?.referencePrice?.toInt() ?: 2000}",
                            fontSize = 20.sp, fontWeight = FontWeight.Bold, color = PrimaryDark
                        )

                        Spacer(modifier = Modifier.weight(1f))
                        Text(text = "×1", fontSize = 15.sp, color = TextSecondary)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Row {
                        Text(stringResource(R.string.consultation_fee), fontSize = 14.sp, color = TextSecondary)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("¥$consultationFee", fontSize = 14.sp, color = TextPrimary)
                        Spacer(modifier = Modifier.weight(1f))
                    }
                    Row {
                        Text(stringResource(R.string.final_payment), fontSize = 14.sp, color = TextSecondary)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("¥$finalPayment", fontSize = 14.sp, color = TextPrimary)
                    }
                }
            }

            // Appointment info
            Text(
                text = stringResource(R.string.appointment_title),
                fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // Time picker
            InfoRowField(
                label = stringResource(R.string.appointment_time_label),
                value = "$selectedDate $selectedTime",
                onClick = { showDatePicker = true }
            )
            if (showDatePicker) {
                val datePickerState = rememberDatePickerState(initialSelectedDateMillis = todayUtcMillis)
                DatePickerDialog(
                    onDismissRequest = { showDatePicker = false },
                    confirmButton = {
                        TextButton(onClick = {
                            datePickerState.selectedDateMillis?.let { millis ->
                                val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                                selectedDate = sdf.format(java.util.Date(millis))
                            }
                            showDatePicker = false
                        }) { Text(stringResource(R.string.confirm)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDatePicker = false }) { Text(stringResource(R.string.cancel)) }
                    }
                ) { DatePicker(state = datePickerState) }
            }

            // Name
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text(stringResource(R.string.appointment_name_label)) },
                placeholder = { Text(stringResource(R.string.appointment_name_hint), color = TextHint) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                singleLine = true, shape = RoundedCornerShape(10.dp)
            )

            // Phone
            OutlinedTextField(
                value = phone, onValueChange = { phone = it },
                label = { Text(stringResource(R.string.appointment_phone_label)) },
                placeholder = { Text(stringResource(R.string.appointment_phone_hint), color = TextHint) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                singleLine = true, shape = RoundedCornerShape(10.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
            )
            Text(
                text = stringResource(R.string.appointment_phone_note),
                fontSize = 12.sp, color = TextHint,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
            )

            // Remark
            OutlinedTextField(
                value = remark, onValueChange = { remark = it },
                label = { Text(stringResource(R.string.appointment_remark_label)) },
                placeholder = { Text(stringResource(R.string.appointment_remark_hint), color = TextHint) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).height(100.dp),
                shape = RoundedCornerShape(10.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Bottom bar
            Row(
                modifier = Modifier.fillMaxWidth().background(Surface).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.actual_pay_label, consultationFee),
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextPrimary,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = { onGoToPay("", "$selectedDate $selectedTime") },
                    shape = RoundedCornerShape(24.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = TextPrimary),
                    modifier = Modifier.height(44.dp).padding(start = 12.dp)
                ) { Text(stringResource(R.string.go_to_pay), color = TextOnPrimary) }
            }
        }
    }
}

@Composable
private fun InfoRowField(label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, fontSize = 15.sp, color = TextPrimary, modifier = Modifier.widthIn(min = 80.dp))
        Spacer(modifier = Modifier.weight(1f))
        Text(text = value, fontSize = 15.sp, color = TextSecondary)
        Icon(
            imageVector = Icons.Default.KeyboardArrowDown,
            contentDescription = null, tint = TextHint, modifier = Modifier.size(20.dp)
        )
    }
}
