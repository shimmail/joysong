package com.joysong.app.ui.components

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.joysong.app.R
import com.joysong.app.ui.report.ReportViewModel
import com.joysong.app.ui.theme.*

@Composable
fun ReportDialog(
    reportViewModel: ReportViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val target by reportViewModel.reportTarget
    val hasReported by reportViewModel.hasReported
    val isSubmitting by reportViewModel.isSubmitting

    if (target == null) return

    val reportReasons = listOf(
        stringResource(R.string.report_reason_spam),
        stringResource(R.string.report_reason_porn),
        stringResource(R.string.report_reason_abuse),
        stringResource(R.string.report_reason_fake),
        stringResource(R.string.report_reason_political),
        stringResource(R.string.report_reason_copyright),
        stringResource(R.string.report_reason_other)
    )

    var selectedReason by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    val otherReason = stringResource(R.string.report_reason_other)
    val isOtherSelected = selectedReason == otherReason

    AlertDialog(
        onDismissRequest = {
            reportViewModel.hideReport()
            onDismiss()
        },
        title = {
            Text(
                text = stringResource(R.string.report),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = TextPrimary
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (hasReported) {
                    Text(
                        text = stringResource(R.string.report_already),
                        fontSize = 14.sp,
                        color = TextHint
                    )
                } else {
                    Text(
                        text = stringResource(R.string.report_reason),
                        fontSize = 14.sp,
                        color = TextPrimary
                    )
                    reportReasons.forEach { reason ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedReason = reason }
                                .padding(vertical = 4.dp)
                        ) {
                            RadioButton(
                                selected = selectedReason == reason,
                                onClick = { selectedReason = reason },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = PrimaryDark,
                                    unselectedColor = TextHint
                                )
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = reason,
                                fontSize = 14.sp,
                                color = TextPrimary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (isOtherSelected) {
                            stringResource(R.string.report_description) + " *"
                        } else {
                            stringResource(R.string.report_description)
                        },
                        fontSize = 14.sp,
                        color = TextPrimary
                    )
                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text(
                                stringResource(R.string.report_description_hint),
                                fontSize = 13.sp
                            )
                        },
                        minLines = 3,
                        maxLines = 5,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PrimaryDark,
                            unfocusedBorderColor = SurfaceVariant
                        )
                    )
                }
            }
        },
        confirmButton = {
            if (!hasReported) {
                Button(
                    onClick = {
                        if (selectedReason.isBlank()) {
                            Toast.makeText(context, context.getString(R.string.report_reason_required), Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        if (isOtherSelected && description.isBlank()) {
                            Toast.makeText(context, context.getString(R.string.report_description_required), Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        reportViewModel.submitReport(
                            reason = selectedReason,
                            description = description.ifBlank { null },
                            onSuccess = {
                                Toast.makeText(context, context.getString(R.string.report_success), Toast.LENGTH_SHORT).show()
                                reportViewModel.hideReport()
                                onDismiss()
                            },
                            onError = { msg ->
                                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            }
                        )
                    },
                    enabled = selectedReason.isNotBlank() && !isSubmitting && !(isOtherSelected && description.isBlank()),
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryDark)
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = TextOnPrimary
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.report_submit),
                            color = TextOnPrimary
                        )
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = {
                reportViewModel.hideReport()
                onDismiss()
            }) {
                Text(
                    text = stringResource(R.string.cancel),
                    color = TextSecondary
                )
            }
        },
        containerColor = Surface,
        shape = RoundedCornerShape(16.dp)
    )
}
