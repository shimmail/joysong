package com.joysong.app.ui.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.joysong.app.R
import com.joysong.app.ui.components.JoysongTopBar
import com.joysong.app.ui.theme.Background
import com.joysong.app.ui.theme.PrimaryDark
import com.joysong.app.ui.theme.Surface
import com.joysong.app.ui.theme.SurfaceVariant
import com.joysong.app.ui.theme.TextHint
import com.joysong.app.ui.theme.TextOnPrimary
import com.joysong.app.ui.theme.TextPrimary
import com.joysong.app.ui.theme.TextSecondary
import kotlinx.coroutines.delay

@Composable
fun BindEmailScreen(
    onBackClick: () -> Unit,
    onBindSuccess: () -> Unit = {},
    viewModel: ProfileViewModel = hiltViewModel()
) {
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var countdown by remember { mutableStateOf(0) }
    var isCodeSent by remember { mutableStateOf(false) }
    val bindState by viewModel.bindEmailState.collectAsState()

    LaunchedEffect(countdown) {
        if (countdown > 0) {
            delay(1000)
            countdown--
        }
    }

    LaunchedEffect(bindState) {
        if (bindState is ProfileActionState.Success) {
            viewModel.resetActionStates()
            onBindSuccess()
        }
    }

    Scaffold(
        topBar = { JoysongTopBar(title = stringResource(R.string.security_bind_email), onBackClick = onBackClick) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Background)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text(
                text = stringResource(R.string.bind_email_desc),
                fontSize = 14.sp,
                color = TextSecondary
            )

            OutlinedInputBox(
                value = email,
                onValueChange = { email = it },
                placeholder = stringResource(R.string.email_hint),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedInputBox(
                    value = code,
                    onValueChange = { if (it.length <= 6) code = it },
                    placeholder = stringResource(R.string.verification_code),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )

                TextButton(
                    onClick = {
                        if (countdown == 0 && email.isNotBlank()) {
                            isCodeSent = true
                            countdown = 60
                        }
                    },
                    enabled = countdown == 0 && email.isNotBlank()
                ) {
                    Text(
                        text = if (countdown > 0) stringResource(R.string.resend_code, countdown)
                        else if (isCodeSent) stringResource(R.string.resend)
                        else stringResource(R.string.get_code),
                        color = if (countdown == 0 && email.isNotBlank()) PrimaryDark else TextHint,
                        fontSize = 14.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            val isLoading = bindState is ProfileActionState.Loading
            Button(
                onClick = { viewModel.bindEmail(email, code) },
                enabled = email.isNotBlank() && code.isNotBlank() && !isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PrimaryDark,
                    contentColor = TextOnPrimary,
                    disabledContainerColor = SurfaceVariant,
                    disabledContentColor = TextHint
                )
            ) {
                Text(
                    text = if (isLoading) stringResource(R.string.binding) else stringResource(R.string.confirm_bind),
                    style = MaterialTheme.typography.labelLarge
                )
            }

            if (bindState is ProfileActionState.Error) {
                Text(
                    text = (bindState as ProfileActionState.Error).message,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 14.sp
                )
            }
        }
    }
}

@Composable
private fun OutlinedInputBox(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Surface)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = TextPrimary),
            keyboardOptions = keyboardOptions,
            singleLine = true,
            decorationBox = { innerTextField ->
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        color = TextHint,
                        fontSize = 15.sp
                    )
                }
                innerTextField()
            }
        )
    }
}
