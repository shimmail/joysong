package com.joysong.app.ui.auth

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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import android.util.Log
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import androidx.hilt.navigation.compose.hiltViewModel
import com.joysong.app.BuildConfig
import com.joysong.app.R
import com.joysong.app.ui.components.CountryCodePicker
import com.joysong.app.ui.theme.Background
import com.joysong.app.ui.theme.PrimaryDark
import com.joysong.app.ui.theme.Surface
import com.joysong.app.ui.theme.SurfaceVariant
import com.joysong.app.ui.theme.TextHint
import com.joysong.app.ui.theme.TextOnPrimary
import com.joysong.app.ui.theme.TextPrimary
import com.joysong.app.ui.theme.TextSecondary
import com.joysong.app.util.LocaleHelper
import kotlinx.coroutines.delay

private enum class LoginPage { /* OneClick, */ Phone, Code, Register, ForgotPassword }
@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel()
) {
    val loginState by viewModel.loginState.collectAsState()

    var currentPage by remember { mutableStateOf(LoginPage.Phone) }
    var phone by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isCodeMode by remember { mutableStateOf(true) } // 默认验证码登录模式
    var showPassword by remember { mutableStateOf(false) }
    var agreed by remember { mutableStateOf(false) }
    var showAgreement by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(0) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var rememberPassword by remember { mutableStateOf(false) }
    var countryCode by remember { mutableStateOf("+86") }

    val registerState by viewModel.registerState.collectAsState()
    val resetPasswordState by viewModel.resetPasswordState.collectAsState()

    val context = LocalContext.current
    val currentLang = LocaleHelper.getLocale(context)

    // Google 登录逻辑（使用传统 GoogleSignInClient，兼容性更好）
    val googleSignInOptions = remember {
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(BuildConfig.GOOGLE_CLIENT_ID)
            .requestEmail()
            .build()
    }
    val googleSignInClient = remember {
        GoogleSignIn.getClient(context, googleSignInOptions)
    }
    val googleSignInLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(result.data).getResult(ApiException::class.java)
            val idToken = account.idToken
            if (idToken != null) {
                android.util.Log.d("GoogleLogin", "Legacy sign-in success, token length=${idToken.length}")
                viewModel.loginWithGoogle(idToken)
            } else {
                errorMessage = "Google 登录失败：未获取到 ID Token"
            }
        } catch (e: ApiException) {
            android.util.Log.e("GoogleLogin", "Google sign-in failed: code=${e.statusCode}, message=${e.message}", e)
            errorMessage = "Google 登录失败：${e.message}"
        }
    }
    val googleLogin: () -> Unit = {
        if (!agreed) {
            showAgreement = true
        } else {
            googleSignInLauncher.launch(googleSignInClient.signInIntent)
        }
    }

    LaunchedEffect(loginState) {
        when (loginState) {
            is AuthUiState.Success -> {
                // 密码登录成功时，根据记住密码选项保存/清除凭证
                if (!isCodeMode) {
                    viewModel.saveRememberedCredentials(phone, password, rememberPassword)
                }
                viewModel.resetStates()
                onLoginSuccess()
            }
            is AuthUiState.Error -> {
                errorMessage = (loginState as AuthUiState.Error).message
            }
            else -> errorMessage = null
        }
    }

    LaunchedEffect(registerState) {
        when (registerState) {
            is AuthUiState.Success -> {
                viewModel.resetStates()
                onLoginSuccess()
            }
            is AuthUiState.Error -> {
                errorMessage = (registerState as AuthUiState.Error).message
            }
            else -> {}
        }
    }

    LaunchedEffect(resetPasswordState) {
        when (resetPasswordState) {
            is AuthUiState.Success -> {
                viewModel.resetStates()
                errorMessage = null
                currentPage = LoginPage.Phone
                isCodeMode = false
            }
            is AuthUiState.Error -> {
                errorMessage = (resetPasswordState as AuthUiState.Error).message
            }
            else -> {}
        }
    }

    LaunchedEffect(countdown) {
        if (countdown > 0) {
            delay(1000)
            countdown--
        }
    }

    // 加载记住的账号密码
    LaunchedEffect(Unit) {
        viewModel.getRememberedCredentials()?.let { (savedPhone, savedPassword) ->
            phone = savedPhone
            password = savedPassword
            rememberPassword = true
            isCodeMode = false // 有记住的密码，切换到密码模式
        }
    }

    val phoneNotRegisteredMsg = stringResource(R.string.phone_not_registered)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Background)
            .statusBarsPadding()
    ) {
        when (currentPage) {
            // === 一键登录已禁用（存在安全漏洞：硬编码密码可登录任意手机号） ===
            // LoginPage.OneClick -> OneClickLoginPage(...)

            LoginPage.Phone -> PhoneLoginPage(
                phone = phone,
                password = password,
                agreed = agreed,
                isCodeMode = isCodeMode,
                showPassword = showPassword,
                countdown = countdown,
                rememberPassword = rememberPassword,
                countryCode = countryCode,
                onPhoneChange = { phone = it.filter { c -> c.isDigit() }.take(15) },
                onPasswordChange = { password = it },
                onAgreedChange = { agreed = it },
                onShowPasswordChange = { showPassword = it },
                onRememberChange = { rememberPassword = it },
                onCountryCodeChange = { countryCode = it },
                onToggleMode = {
                    isCodeMode = !isCodeMode
                    errorMessage = null
                },
                onPasswordLogin = {
                    if (phone.length >= 10 && password.isNotEmpty()) {
                        viewModel.login(countryCode + phone, password)
                    }
                },
                onSendCode = {
                    if (!agreed) {
                        showAgreement = true
                    } else if (phone.length >= 10) {
                        viewModel.sendCode(countryCode + phone)
                        countdown = 60
                        currentPage = LoginPage.Code
                    }
                },
                onBack = null,
                onAgreementClick = { showAgreement = true },
                onGoogleLogin = googleLogin,
                onRegisterClick = { currentPage = LoginPage.Register },
                onForgotPasswordClick = { currentPage = LoginPage.ForgotPassword }
            )

            LoginPage.Code -> CodeInputPage(
                phone = countryCode + phone,
                code = code,
                countdown = countdown,
                onCodeChange = { code = it.filter { c -> c.isDigit() }.take(6) },
                onResend = {
                    if (countdown == 0 && phone.length >= 10) {
                        viewModel.sendCode(countryCode + phone)
                        countdown = 60
                    }
                },
                onLogin = {
                    if (code.length == 6) {
                        viewModel.loginWithCode(countryCode + phone, code)
                    }
                },
                onBack = { currentPage = LoginPage.Phone }
            )

            LoginPage.Register -> RegisterPage(
                phone = phone,
                code = code,
                password = password,
                agreed = agreed,
                countdown = countdown,
                countryCode = countryCode,
                onPhoneChange = { phone = it.filter { c -> c.isDigit() }.take(15) },
                onCodeChange = { code = it.filter { c -> c.isDigit() }.take(6) },
                onPasswordChange = { password = it },
                onAgreedChange = { agreed = it },
                onCountryCodeChange = { countryCode = it },
                onSendCode = {
                    if (!agreed) {
                        showAgreement = true
                    } else if (phone.length >= 10) {
                        viewModel.sendCode(countryCode + phone)
                        countdown = 60
                    }
                },
                onRegister = {
                    if (phone.length >= 10 && code.length == 6 && password.isNotEmpty()) {
                        viewModel.register(countryCode + phone, code, password)
                    }
                },
                onBack = { currentPage = LoginPage.Phone },
                onAgreementClick = { showAgreement = true }
            )

            LoginPage.ForgotPassword -> ForgotPasswordPage(
                phone = phone,
                code = code,
                countdown = countdown,
                countryCode = countryCode,
                onPhoneChange = { phone = it.filter { c -> c.isDigit() }.take(15) },
                onCodeChange = { code = it.filter { c -> c.isDigit() }.take(6) },
                onCountryCodeChange = { countryCode = it },
                onSendCode = { onError ->
                    if (phone.length >= 10) {
                        viewModel.sendCodeForResetPassword(
                            phone = countryCode + phone,
                            onNotRegistered = { onError(phoneNotRegisteredMsg) },
                            onSent = { countdown = 60 }
                        )
                    }
                },
                onResetPassword = { newPwd ->
                    if (phone.length >= 10 && code.length == 6 && newPwd.isNotEmpty()) {
                        viewModel.resetPassword(countryCode + phone, code, newPwd)
                    }
                },
                onBack = { currentPage = LoginPage.Phone }
            )
        }

        LanguageSwitcher(
            currentLang = currentLang,
            onLanguageChange = { lang ->
                LocaleHelper.setLocale(context, lang)
                (context as? android.app.Activity)?.recreate()
            },
            modifier = Modifier.align(Alignment.TopEnd)
        )

        if (showAgreement) {
            AgreementDialog(
                onAgree = {
                    agreed = true
                    showAgreement = false
                },
                onDisagree = { showAgreement = false }
            )
        }

        errorMessage?.let { msg ->
            ErrorDialog(
                message = msg,
                onDismiss = { errorMessage = null }
            )
        }
    }
}

@Composable
private fun OneClickLoginPage(
    phone: String,
    agreed: Boolean,
    onAgreedChange: (Boolean) -> Unit,
    onOneClickLogin: (String) -> Unit,
    onGoogleLogin: () -> Unit,
    onPhoneLoginClick: () -> Unit,
    onAgreementClick: () -> Unit
) {
    var inputPhone by remember { mutableStateOf(phone) }
    var countryCode by remember { mutableStateOf("+86") }
    var showCountryPicker by remember { mutableStateOf(false) }
    
    // 常见国家/地区区号
    val countryCodes = listOf(
        "+86" to "中国大陆",
        "+852" to "中国香港",
        "+853" to "中国澳门",
        "+886" to "中国台湾",
        "+1" to "美国/加拿大",
        "+81" to "日本",
        "+82" to "韩国",
        "+65" to "新加坡",
        "+44" to "英国",
        "+33" to "法国",
        "+49" to "德国",
        "+61" to "澳大利亚"
    )
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(120.dp))

        Text(
            text = stringResource(R.string.app_name),
            fontSize = 48.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
        Text(
            text = "JOYSONG",
            fontSize = 14.sp,
            color = TextSecondary,
            letterSpacing = 6.sp
        )

        Spacer(modifier = Modifier.height(80.dp))

        // 手机号输入框
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Surface)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 区号选择器
            Text(
                text = "$countryCode ▼",
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = PrimaryDark,
                modifier = Modifier.clickable { showCountryPicker = true }
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
                value = inputPhone,
                onValueChange = { inputPhone = it.filter { c -> c.isDigit() }.take(15) },
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 16.sp,
                    color = TextPrimary
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                decorationBox = { innerTextField ->
                    Box {
                        if (inputPhone.isEmpty()) {
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
        
        // 区号选择弹窗
        if (showCountryPicker) {
            AlertDialog(
                onDismissRequest = { showCountryPicker = false },
                title = { Text("选择国家/地区") },
                text = {
                    Column {
                        countryCodes.forEach { (code, name) ->
                            TextButton(
                                onClick = {
                                    countryCode = code
                                    showCountryPicker = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "$name  $code",
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                },
                confirmButton = {}
            )
        }
        Text(
            text = stringResource(R.string.one_click_desc),
            fontSize = 13.sp,
            color = TextHint,
            modifier = Modifier.padding(top = 8.dp)
        )

        Spacer(modifier = Modifier.height(48.dp))

        Button(
            onClick = { onOneClickLogin(countryCode + inputPhone) },
            enabled = inputPhone.length >= 7,
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
                text = stringResource(R.string.one_click_login),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = if (inputPhone.length >= 7) TextOnPrimary else TextHint
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        AgreementRow(
            agreed = agreed,
            onAgreedChange = onAgreedChange,
            onAgreementClick = onAgreementClick
        )

        Spacer(modifier = Modifier.height(40.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HorizontalDivider(modifier = Modifier.weight(1f), color = SurfaceVariant)
            Text(
                text = stringResource(R.string.or),
                fontSize = 13.sp,
                color = TextHint,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            HorizontalDivider(modifier = Modifier.weight(1f), color = SurfaceVariant)
        }

        Spacer(modifier = Modifier.height(32.dp))

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlineLoginButton(
                icon = Icons.Outlined.PhoneAndroid,
                text = stringResource(R.string.phone_login),
                onClick = onPhoneLoginClick
            )
            OutlineLoginButton(
                icon = Icons.Outlined.Email,
                text = stringResource(R.string.google_login),
                onClick = onGoogleLogin
            )
        }
    }
}

@Composable
private fun PhoneLoginPage(
    phone: String,
    password: String,
    agreed: Boolean,
    isCodeMode: Boolean,
    showPassword: Boolean,
    countdown: Int,
    rememberPassword: Boolean = false,
    countryCode: String = "+86",
    onPhoneChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onAgreedChange: (Boolean) -> Unit,
    onShowPasswordChange: (Boolean) -> Unit,
    onRememberChange: (Boolean) -> Unit = {},
    onCountryCodeChange: (String) -> Unit = {},
    onToggleMode: () -> Unit,
    onPasswordLogin: () -> Unit,
    onSendCode: () -> Unit,
    onBack: (() -> Unit)? = null,
    onAgreementClick: () -> Unit,
    onGoogleLogin: (() -> Unit)? = null,
    onRegisterClick: (() -> Unit)? = null,
    onForgotPasswordClick: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
    ) {
        Spacer(modifier = Modifier.height(80.dp))

        if (onBack != null) {
            TextButton(onClick = onBack) {
                Text(
                    text = stringResource(R.string.back),
                    color = TextSecondary,
                    fontSize = 14.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 应用名称标题（居中）
        Text(
            text = stringResource(R.string.app_name),
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = PrimaryDark,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center
        )
        Text(
            text = "JOYSONG",
            fontSize = 14.sp,
            color = TextSecondary,
            letterSpacing = 6.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(40.dp))

        // Mode toggle tabs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Surface),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (!isCodeMode) PrimaryDark else Color.Transparent)
                    .clickable { if (isCodeMode) onToggleMode() }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.switch_to_password),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (!isCodeMode) TextOnPrimary else TextSecondary
                )
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isCodeMode) PrimaryDark else Color.Transparent)
                    .clickable { if (!isCodeMode) onToggleMode() }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.switch_to_code),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (isCodeMode) TextOnPrimary else TextSecondary
                )
            }
        }

        // 提示信息（模式切换下方）
        Text(
            text = if (isCodeMode) stringResource(R.string.phone_login_subtitle)
                   else stringResource(R.string.login_subtitle),
            fontSize = 13.sp,
            color = TextHint,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Phone input
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
                onCodeChange = onCountryCodeChange
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
                onValueChange = onPhoneChange,
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

        if (!isCodeMode) {
            // Password input
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
                    value = password,
                    onValueChange = onPasswordChange,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontSize = 16.sp,
                        color = TextPrimary
                    ),
                    visualTransformation = if (showPassword) VisualTransformation.None
                        else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    decorationBox = { innerTextField ->
                        Box {
                            if (password.isEmpty()) {
                                Text(
                                    text = stringResource(R.string.password_hint),
                                    fontSize = 16.sp,
                                    color = TextHint
                                )
                            }
                            innerTextField()
                        }
                    }
                )
                Icon(
                    imageVector = if (showPassword) Icons.Outlined.VisibilityOff
                        else Icons.Outlined.Visibility,
                    contentDescription = null,
                    tint = TextHint,
                    modifier = Modifier
                        .size(20.dp)
                        .clickable { onShowPasswordChange(!showPassword) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 记住密码复选框 + 忘记密码
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .border(1.dp, if (rememberPassword) PrimaryDark else SurfaceVariant, RoundedCornerShape(4.dp))
                            .background(if (rememberPassword) PrimaryDark else Color.Transparent)
                            .clickable { onRememberChange(!rememberPassword) },
                        contentAlignment = Alignment.Center
                    ) {
                        if (rememberPassword) {
                            Icon(
                                imageVector = Icons.Outlined.Check,
                                contentDescription = null,
                                tint = TextOnPrimary,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.remember_password),
                        fontSize = 13.sp,
                        color = TextSecondary
                    )
                }
                if (onForgotPasswordClick != null) {
                    TextButton(
                        onClick = onForgotPasswordClick,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.forgot_password),
                            fontSize = 13.sp,
                            color = PrimaryDark
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            AgreementRow(
                agreed = agreed,
                onAgreedChange = onAgreedChange,
                onAgreementClick = onAgreementClick
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = {
                    if (!agreed) {
                        onAgreementClick()
                    } else {
                        onPasswordLogin()
                    }
                },
                enabled = phone.length >= 10 && password.isNotEmpty(),
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
                    text = stringResource(R.string.btn_login),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (phone.length >= 10 && password.isNotEmpty()) TextOnPrimary else TextHint
                )
            }
        } else {
            // Code mode
            AgreementRow(
                agreed = agreed,
                onAgreedChange = onAgreedChange,
                onAgreementClick = onAgreementClick
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onSendCode,
                enabled = phone.length >= 10 && countdown == 0,
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
                    text = if (countdown > 0) stringResource(R.string.resend_code, countdown)
                           else stringResource(R.string.get_code),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (countdown > 0) TextHint else TextOnPrimary
                )
            }
        }

        // Google 登录 & 其他登录方式
        if (onGoogleLogin != null) {
            Spacer(modifier = Modifier.height(40.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HorizontalDivider(modifier = Modifier.weight(1f), color = SurfaceVariant)
                Text(
                    text = stringResource(R.string.or),
                    fontSize = 13.sp,
                    color = TextHint,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                HorizontalDivider(modifier = Modifier.weight(1f), color = SurfaceVariant)
            }

            Spacer(modifier = Modifier.height(24.dp))

            OutlineLoginButton(
                icon = Icons.Outlined.Email,
                text = stringResource(R.string.google_login),
                onClick = onGoogleLogin
            )
        }

        // 注册入口
        if (onRegisterClick != null) {
            Spacer(modifier = Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.no_account),
                    fontSize = 14.sp,
                    color = TextHint
                )
                TextButton(onClick = onRegisterClick) {
                    Text(
                        text = stringResource(R.string.go_register),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = PrimaryDark
                    )
                }
            }
        }
    }
}

@Composable
private fun CodeInputPage(
    phone: String,
    code: String,
    countdown: Int,
    onCodeChange: (String) -> Unit,
    onResend: () -> Unit,
    onLogin: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
    ) {
        Spacer(modifier = Modifier.height(80.dp))

        TextButton(onClick = onBack) {
            Text(
                text = stringResource(R.string.back),
                color = TextSecondary,
                fontSize = 14.sp
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.input_code_title),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
        Text(
            text = stringResource(R.string.code_sent_to, phone),
            fontSize = 14.sp,
            color = TextSecondary,
            modifier = Modifier.padding(top = 8.dp)
        )

        Spacer(modifier = Modifier.height(48.dp))

        CodeInputBoxes(
            code = code,
            onCodeChange = onCodeChange,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onLogin,
            enabled = code.length == 6,
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
                text = stringResource(R.string.btn_login),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = if (code.length == 6) TextOnPrimary else TextHint
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.no_code_received),
                fontSize = 14.sp,
                color = TextHint
            )
            TextButton(
                onClick = onResend,
                enabled = countdown == 0
            ) {
                Text(
                    text = if (countdown > 0) stringResource(R.string.resend_code, countdown) else stringResource(R.string.resend),
                    fontSize = 14.sp,
                    color = if (countdown > 0) TextHint else PrimaryDark
                )
            }
        }
    }
}

@Composable
private fun RegisterPage(
    phone: String,
    code: String,
    password: String,
    agreed: Boolean,
    countdown: Int,
    countryCode: String = "+86",
    onPhoneChange: (String) -> Unit,
    onCodeChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onAgreedChange: (Boolean) -> Unit,
    onCountryCodeChange: (String) -> Unit = {},
    onSendCode: () -> Unit,
    onRegister: () -> Unit,
    onBack: () -> Unit,
    onAgreementClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
    ) {
        Spacer(modifier = Modifier.height(80.dp))

        TextButton(onClick = onBack) {
            Text(
                text = stringResource(R.string.back),
                color = TextSecondary,
                fontSize = 14.sp
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.register_title),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
        Text(
            text = stringResource(R.string.register_subtitle),
            fontSize = 14.sp,
            color = TextSecondary,
            modifier = Modifier.padding(top = 8.dp)
        )

        Spacer(modifier = Modifier.height(40.dp))

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
                onCodeChange = onCountryCodeChange
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
                onValueChange = onPhoneChange,
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
                onValueChange = onCodeChange,
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
                onClick = onSendCode,
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

        // 密码输入
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
                value = password,
                onValueChange = onPasswordChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 16.sp,
                    color = TextPrimary
                ),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                decorationBox = { innerTextField ->
                    Box {
                        if (password.isEmpty()) {
                            Text(
                                text = stringResource(R.string.password_hint),
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

        AgreementRow(
            agreed = agreed,
            onAgreedChange = onAgreedChange,
            onAgreementClick = onAgreementClick
        )

        Spacer(modifier = Modifier.height(32.dp))

        Button(
            onClick = onRegister,
            enabled = phone.length >= 10 && code.length == 6 && password.isNotEmpty(),
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
                text = stringResource(R.string.btn_register),
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                color = if (phone.length >= 10 && code.length == 6 && password.isNotEmpty()) TextOnPrimary else TextHint
            )
        }
    }
}

@Composable
private fun ForgotPasswordPage(
    phone: String,
    code: String,
    countdown: Int,
    countryCode: String,
    onPhoneChange: (String) -> Unit,
    onCodeChange: (String) -> Unit,
    onCountryCodeChange: (String) -> Unit,
    onSendCode: ((String) -> Unit) -> Unit,
    onResetPassword: (String) -> Unit,
    onBack: () -> Unit
) {
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var showNewPassword by remember { mutableStateOf(false) }
    var showConfirmPassword by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
    ) {
        Spacer(modifier = Modifier.height(80.dp))

        TextButton(onClick = onBack) {
            Text(
                text = stringResource(R.string.back),
                color = TextSecondary,
                fontSize = 14.sp
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.forgot_password_title),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )
        Text(
            text = stringResource(R.string.forgot_password_subtitle),
            fontSize = 14.sp,
            color = TextSecondary,
            modifier = Modifier.padding(top = 8.dp)
        )

        Spacer(modifier = Modifier.height(40.dp))

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
                onCodeChange = onCountryCodeChange
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
                onValueChange = onPhoneChange,
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
                onValueChange = onCodeChange,
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
                onClick = { onSendCode { error -> localError = error } },
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

        // 确认密码输入
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

        if (localError != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = localError!!,
                fontSize = 13.sp,
                color = androidx.compose.ui.graphics.Color(0xFFE53935)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        val passwordLengthError = stringResource(R.string.password_length_error)
        val passwordMismatchError = stringResource(R.string.password_mismatch_error)

        Button(
            onClick = {
                localError = null
                if (newPassword.length < 6) {
                    localError = passwordLengthError
                    return@Button
                }
                if (newPassword != confirmPassword) {
                    localError = passwordMismatchError
                    return@Button
                }
                onResetPassword(newPassword)
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

@Composable
private fun CodeInputBoxes(
    code: String,
    onCodeChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    BasicTextField(
        value = code,
        onValueChange = onCodeChange,
        modifier = modifier,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        decorationBox = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                repeat(6) { index ->
                    val digit = code.getOrNull(index)?.toString() ?: ""
                    val isFocused = code.length == index
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(
                                width = 1.dp,
                                color = if (isFocused) PrimaryDark else SurfaceVariant,
                                shape = RoundedCornerShape(8.dp)
                            )
                            .background(Surface),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = digit,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }
                }
            }
        }
    )
}

@Composable
private fun AgreementRow(
    agreed: Boolean,
    onAgreedChange: (Boolean) -> Unit,
    onAgreementClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .clip(RoundedCornerShape(4.dp))
                .border(1.dp, if (agreed) PrimaryDark else SurfaceVariant, RoundedCornerShape(4.dp))
                .background(if (agreed) PrimaryDark else Color.Transparent)
                .clickable { onAgreedChange(!agreed) },
            contentAlignment = Alignment.Center
        ) {
            if (agreed) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    tint = TextOnPrimary,
                    modifier = Modifier.size(14.dp)
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        val termsTag = "terms"
        val privacyTag = "privacy"
        val annotatedText = buildAnnotatedString {
            append(stringResource(R.string.i_have_read))
            pushStringAnnotation(tag = termsTag, annotation = termsTag)
            withStyle(style = SpanStyle(color = PrimaryDark, fontWeight = FontWeight.Medium)) {
                append(stringResource(R.string.terms_of_service))
            }
            pop()
            append(stringResource(R.string.and))
            pushStringAnnotation(tag = privacyTag, annotation = privacyTag)
            withStyle(style = SpanStyle(color = PrimaryDark, fontWeight = FontWeight.Medium)) {
                append(stringResource(R.string.privacy_policy))
            }
            pop()
        }
        ClickableText(
            text = annotatedText,
            onClick = { offset ->
                annotatedText.getStringAnnotations(termsTag, offset, offset)
                    .firstOrNull()
                    ?.let { onAgreementClick() }
                annotatedText.getStringAnnotations(privacyTag, offset, offset)
                    .firstOrNull()
                    ?.let { onAgreementClick() }
            },
            style = androidx.compose.ui.text.TextStyle(
                fontSize = 13.sp,
                color = TextSecondary
            )
        )
    }
}

@Composable
private fun AgreementDialog(
    onAgree: () -> Unit,
    onDisagree: () -> Unit
) {
    Dialog(onDismissRequest = onDisagree) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Surface)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.agreement_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.agreement_content),
                    fontSize = 14.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    TextButton(onClick = onDisagree) {
                        Text(
                            text = stringResource(R.string.disagree),
                            fontSize = 15.sp,
                            color = TextSecondary
                        )
                    }
                    Button(
                        onClick = onAgree,
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryDark)
                    ) {
                        Text(
                            text = stringResource(R.string.agree),
                            fontSize = 15.sp,
                            color = TextOnPrimary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OutlineLoginButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, SurfaceVariant, RoundedCornerShape(12.dp))
            .background(Surface)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = TextPrimary,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            fontSize = 15.sp,
            color = TextPrimary,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun LanguageSwitcher(
    currentLang: String,
    onLanguageChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(top = 16.dp, end = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        val isEn = currentLang == LocaleHelper.LANG_ENGLISH
        Text(
            text = "EN",
            fontSize = 13.sp,
            fontWeight = if (isEn) FontWeight.Bold else FontWeight.Normal,
            color = if (isEn) TextPrimary else TextHint,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(if (isEn) Surface else Color.Transparent)
                .padding(horizontal = 10.dp, vertical = 4.dp)
                .clickable { onLanguageChange(LocaleHelper.LANG_ENGLISH) }
        )
        Text(
            text = "|",
            fontSize = 13.sp,
            color = SurfaceVariant
        )
        val isZh = currentLang == LocaleHelper.LANG_CHINESE
        Text(
            text = "\u4E2D\u6587",
            fontSize = 13.sp,
            fontWeight = if (isZh) FontWeight.Bold else FontWeight.Normal,
            color = if (isZh) TextPrimary else TextHint,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(if (isZh) Surface else Color.Transparent)
                .padding(horizontal = 10.dp, vertical = 4.dp)
                .clickable { onLanguageChange(LocaleHelper.LANG_CHINESE) }
        )
    }
}

@Composable
private fun ErrorDialog(
    message: String,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Surface)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.error_title),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = message,
                    fontSize = 14.sp,
                    color = TextSecondary,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryDark),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.btn_confirm),
                        fontSize = 15.sp,
                        color = TextOnPrimary
                    )
                }
            }
        }
    }
}
