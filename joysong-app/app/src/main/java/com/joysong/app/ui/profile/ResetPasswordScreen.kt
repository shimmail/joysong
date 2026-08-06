package com.joysong.app.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.joysong.app.R
import com.joysong.app.ui.auth.AuthUiState
import com.joysong.app.ui.auth.AuthViewModel
import com.joysong.app.ui.components.CountryCodePicker
import com.joysong.app.ui.components.JoysongTopBar
import com.joysong.app.ui.theme.Background
import com.joysong.app.ui.theme.PrimaryDark
import com.joysong.app.ui.theme.Surface
import com.joysong.app.ui.theme.SurfaceVariant
import com.joysong.app.ui.theme.TextHint
import com.joysong.app.ui.theme.TextOnPrimary
import com.joysong.app.ui.theme.TextPrimary
import com.joysong.app.ui.theme.TextSecondary

@Composable
fun ResetPasswordScreen(
    onBackClick: () -> Unit,
    onResetSuccess: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel()
) {
    val codeState by viewModel.codeState.collectAsState()
    val resetPasswordState by viewModel.resetPasswordState.collectAsState()

    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var showNewPassword by remember { mutableStateOf(false) }
    var showConfirmPassword by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(0) }
    var countryCode by remember { mutableStateOf("+86") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val passwordLengthError = stringResource(R.string.password_length_error)
    val passwordMismatchError = stringResource(R.string.password_mismatch_error)
    val phoneNotRegisteredError = stringResource(R.string.phone_not_registered)

    LaunchedEffect(codeState) {
        when (codeState) {
            is AuthUiState.Error -> {
                errorMessage = (codeState as AuthUiState.Error).message
            }
            else -> {}
        }
    }

    LaunchedEffect(resetPasswordState) {
        when (resetPasswordState) {
            is AuthUiState.Success -> {
                viewModel.resetStates()
                onResetSuccess()
            }
            is AuthUiState.Error -> {
                errorMessage = (resetPasswordState as AuthUiState.Error).message
            }
            else -> {}
        }
    }

    LaunchedEffect(countdown) {
        if (countdown > 0) {
            kotlinx.coroutines.delay(1000)
            countdown--
        }
    }

    Scaffold(
        topBar = { JoysongTopBar(title = stringResource(R.string.forgot_password_title), onBackClick = onBackClick) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Background)
                .padding(horizontal = 24.dp)
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.forgot_password_subtitle),
                fontSize = 14.sp,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 手机号输入
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Surface)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CountryCodePicker(
                    selectedCode = countryCode,
                    onCodeChange = { countryCode = it }
                )
                Spacer(modifier = Modifier.width(12.dp))
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(20.dp)
                        .background(SurfaceVariant)
                )
                Spacer(modifier = Modifier.width(12.dp))
                BasicTextField(
                    value = phone,
                    onValueChange = { phone = it.filter { c -> c.isDigit() }.take(15) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 16.sp,
                        color = TextPrimary
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    decorationBox = { innerTextField ->
                        Box {
                            if (phone.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.phone_hint),
                                    fontSize = 16.sp,
                                    color = TextHint
                                )
                            }
                            innerTextField()
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 验证码输入
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Surface)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = TextHint,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(20.dp)
                        .background(SurfaceVariant)
                )
                Spacer(modifier = Modifier.width(12.dp))
                BasicTextField(
                    value = code,
                    onValueChange = { code = it.filter { c -> c.isDigit() }.take(6) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 16.sp,
                        color = TextPrimary
                    ),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    decorationBox = { innerTextField ->
                        Box {
                            if (code.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.verification_code),
                                    fontSize = 16.sp,
                                    color = TextHint
                                )
                            }
                            innerTextField()
                        }
                    }
                )
                TextButton(
                    onClick = {
                        if (phone.length >= 10) {
                            viewModel.sendCodeForResetPassword(
                                phone = countryCode + phone,
                                onNotRegistered = { errorMessage = phoneNotRegisteredError },
                                onSent = { countdown = 60 }
                            )
                        }
                    },
                    enabled = countdown == 0 && phone.length >= 10
                ) {
                    Text(
                        text = if (countdown > 0) stringResource(R.string.resend_code, countdown)
                               else stringResource(R.string.get_code),
                        fontSize = 13.sp,
                        color = if (countdown > 0) TextHint else PrimaryDark
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 新密码输入
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Surface)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = TextHint,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(20.dp)
                        .background(SurfaceVariant)
                )
                Spacer(modifier = Modifier.width(12.dp))
                BasicTextField(
                    value = newPassword,
                    onValueChange = { newPassword = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 16.sp,
                        color = TextPrimary
                    ),
                    visualTransformation = if (showNewPassword) VisualTransformation.None
                        else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    decorationBox = { innerTextField ->
                        Box {
                            if (newPassword.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.new_password_hint),
                                    fontSize = 16.sp,
                                    color = TextHint
                                )
                            }
                            innerTextField()
                        }
                    }
                )
                Icon(
                    imageVector = if (showNewPassword) Icons.Outlined.VisibilityOff
                        else Icons.Outlined.Visibility,
                    contentDescription = null,
                    tint = TextHint,
                    modifier = Modifier
                        .size(20.dp)
                        .clickable { showNewPassword = !showNewPassword }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 确认新密码输入
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Surface)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = TextHint,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(20.dp)
                        .background(SurfaceVariant)
                )
                Spacer(modifier = Modifier.width(12.dp))
                BasicTextField(
                    value = confirmPassword,
                    onValueChange = { confirmPassword = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 16.sp,
                        color = TextPrimary
                    ),
                    visualTransformation = if (showConfirmPassword) VisualTransformation.None
                        else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    decorationBox = { innerTextField ->
                        Box {
                            if (confirmPassword.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.confirm_password_hint),
                                    fontSize = 16.sp,
                                    color = TextHint
                                )
                            }
                            innerTextField()
                        }
                    }
                )
                Icon(
                    imageVector = if (showConfirmPassword) Icons.Outlined.VisibilityOff
                        else Icons.Outlined.Visibility,
                    contentDescription = null,
                    tint = TextHint,
                    modifier = Modifier
                        .size(20.dp)
                        .clickable { showConfirmPassword = !showConfirmPassword }
                )
            }

            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = errorMessage!!,
                    fontSize = 13.sp,
                    color = androidx.compose.ui.graphics.Color(0xFFE53935)
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = {
                    errorMessage = null
                    if (newPassword.length < 6) {
                        errorMessage = passwordLengthError
                        return@Button
                    }
                    if (newPassword != confirmPassword) {
                        errorMessage = passwordMismatchError
                        return@Button
                    }
                    viewModel.resetPassword(countryCode + phone, code, newPassword)
                },
                enabled = phone.length >= 10 && code.length == 6 && newPassword.isNotEmpty() && confirmPassword.isNotEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp),
                shape = RoundedCornerShape(25.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PrimaryDark,
                    disabledContainerColor = SurfaceVariant
                )
            ) {
                Text(
                    text = stringResource(R.string.btn_reset_password),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (phone.length >= 10 && code.length == 6 && newPassword.isNotEmpty() && confirmPassword.isNotEmpty()) TextOnPrimary else TextHint
                )
            }
        }
    }
}
