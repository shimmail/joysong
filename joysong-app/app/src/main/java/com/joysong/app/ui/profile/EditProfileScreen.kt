package com.joysong.app.ui.profile

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.joysong.app.R
import com.joysong.app.ui.components.DefaultUserAvatar
import com.joysong.app.ui.components.JoysongTopBar
import com.joysong.app.ui.theme.Background
import com.joysong.app.ui.theme.PrimaryDark
import com.joysong.app.ui.theme.Surface
import com.joysong.app.ui.theme.SurfaceVariant
import com.joysong.app.ui.theme.TextHint
import com.joysong.app.ui.theme.TextPrimary
import com.joysong.app.ui.theme.TextOnPrimary
import com.joysong.app.ui.theme.TextSecondary
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// 允许的图片类型
private val ALLOWED_IMAGE_TYPES = setOf(
    "image/jpeg",
    "image/jpg",
    "image/png",
    "image/webp"
)

// 最大头像文件大小：5MB
private const val MAX_AVATAR_SIZE = 5 * 1024 * 1024L

/**
 * 验证图片是否符合要求
 * @return null 表示通过验证，否则返回错误信息资源ID
 */
private fun validateImage(context: Context, uri: Uri): Int? {
    // 检查文件类型
    val mimeType = context.contentResolver.getType(uri)
    if (mimeType == null || mimeType !in ALLOWED_IMAGE_TYPES) {
        return R.string.unsupported_image_format
    }

    // 检查文件大小
    val size = uri.let {
        context.contentResolver.openFileDescriptor(it, "r")?.use { fd ->
            fd.statSize
        } ?: 0L
    }
    if (size > MAX_AVATAR_SIZE) {
        return R.string.image_too_large
    }

    return null
}

@Composable
fun EditProfileScreen(
    onBackClick: () -> Unit,
    viewModel: ProfileViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val user by viewModel.user.collectAsState()
    val updateState by viewModel.updateState.collectAsState()
    val uploadState by viewModel.avatarUploadState.collectAsState()

    var nickname by remember { mutableStateOf(user?.nickname ?: "") }
    var bio by remember { mutableStateOf(user?.bio ?: "") }
    var city by remember { mutableStateOf(user?.city ?: "") }
    var avatarUrl by remember { mutableStateOf(user?.avatar ?: "") }
    var gender by remember { mutableStateOf(user?.gender ?: "") }
    var birthday by remember { mutableStateOf(user?.birthday ?: "") }

    // 裁剪流程状态
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var showCropScreen by remember { mutableStateOf(false) }

    // 对话框状态
    var showGenderDialog by remember { mutableStateOf(false) }
    var showBirthdayDialog by remember { mutableStateOf(false) }

    // 图片选择器 - 只允许选择图片
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let {
            // 验证图片大小和类型
            val errorResId = validateImage(context, it)
            if (errorResId != null) {
                Toast.makeText(context, context.getString(errorResId), Toast.LENGTH_SHORT).show()
            } else {
                // 验证通过，进入裁剪界面
                selectedImageUri = it
                showCropScreen = true
            }
        }
    }

    LaunchedEffect(updateState) {
        if (updateState is ProfileActionState.Success) {
            viewModel.resetActionStates()
            onBackClick()
        }
    }

    LaunchedEffect(user) {
        user?.let {
            nickname = it.nickname
            bio = it.bio
            city = it.city
            avatarUrl = it.avatar
            gender = it.gender
            birthday = it.birthday
        }
    }

    // 监听上传结果
    LaunchedEffect(uploadState) {
        when (val state = uploadState) {
            is AvatarUploadState.Success -> {
                avatarUrl = state.url
                viewModel.resetAvatarUploadState()
            }
            is AvatarUploadState.Error -> {
                Toast.makeText(context, state.message, Toast.LENGTH_SHORT).show()
                viewModel.resetAvatarUploadState()
            }
            else -> {}
        }
    }

    // 如果处于裁剪界面，显示裁剪屏幕
    if (showCropScreen && selectedImageUri != null) {
        AvatarCropScreen(
            imageUri = selectedImageUri!!,
            onCropComplete = { croppedFile ->
                showCropScreen = false
                selectedImageUri = null
                // 上传裁剪后的文件
                viewModel.uploadAvatar(croppedFile)
            },
            onBackClick = {
                showCropScreen = false
                selectedImageUri = null
            }
        )
        return
    }

    Scaffold(
        topBar = { JoysongTopBar(title = stringResource(R.string.edit_profile), onBackClick = onBackClick) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .background(Background)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 头像区域 - 使用 Box 包裹，相机图标在右下角
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .clickable {
                        imagePickerLauncher.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                // 头像图片或占位符
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .clip(CircleShape)
                        .background(SurfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (uploadState is AvatarUploadState.Uploading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            color = PrimaryDark,
                            strokeWidth = 3.dp
                        )
                    } else if (avatarUrl.isNotBlank()) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(avatarUrl)
                                .memoryCachePolicy(CachePolicy.DISABLED)
                                .diskCachePolicy(CachePolicy.DISABLED)
                                .build(),
                            contentDescription = "头像",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        DefaultUserAvatar(
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                // 相机图标 - 在右下角，完全显示
                if (uploadState !is AvatarUploadState.Uploading) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .offset(x = 4.dp, y = 4.dp)
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(PrimaryDark)
                            .padding(6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CameraAlt,
                            contentDescription = "更换头像",
                            tint = androidx.compose.ui.graphics.Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            ProfileEditField(
                label = stringResource(R.string.nickname),
                value = nickname,
                onValueChange = { nickname = it }
            )
            Spacer(modifier = Modifier.height(16.dp))
            ProfileEditField(
                label = stringResource(R.string.bio),
                value = bio,
                onValueChange = { bio = it },
                singleLine = false
            )
            Spacer(modifier = Modifier.height(16.dp))
            ProfileEditField(
                label = stringResource(R.string.city),
                value = city,
                onValueChange = { city = it }
            )
            Spacer(modifier = Modifier.height(16.dp))
            InfoRow(label = stringResource(R.string.phone_number), value = user?.phone ?: "")

            // 性别选择行
            Spacer(modifier = Modifier.height(16.dp))
            SelectableInfoRow(
                label = stringResource(R.string.gender),
                value = genderDisplayText(gender),
                onClick = { showGenderDialog = true }
            )

            // 生日选择行
            Spacer(modifier = Modifier.height(16.dp))
            SelectableInfoRow(
                label = stringResource(R.string.birthday),
                value = birthday,
                placeholder = stringResource(R.string.select_birthday),
                onClick = { showBirthdayDialog = true }
            )

            Spacer(modifier = Modifier.height(24.dp))

            val isLoading = updateState is ProfileActionState.Loading
            Button(
                onClick = {
                    viewModel.updateProfile(
                        nickname = nickname,
                        avatar = avatarUrl,
                        city = city,
                        bio = bio,
                        gender = gender,
                        birthday = birthday
                    )
                },
                enabled = !isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryDark)
            ) {
                Text(
                    text = if (isLoading) stringResource(R.string.saving) else stringResource(R.string.save),
                    color = TextOnPrimary
                )
            }

            if (updateState is ProfileActionState.Error) {
                Text(
                    text = (updateState as ProfileActionState.Error).message,
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        // 性别选择对话框
        if (showGenderDialog) {
            GenderPickerDialog(
                currentGender = gender,
                onDismiss = { showGenderDialog = false },
                onSelected = { selected ->
                    gender = selected
                    showGenderDialog = false
                }
            )
        }

        // 生日选择对话框
        if (showBirthdayDialog) {
            BirthdayPickerDialog(
                currentBirthday = birthday,
                onDismiss = { showBirthdayDialog = false },
                onSelected = { selected ->
                    birthday = selected
                    showBirthdayDialog = false
                }
            )
        }
    }
}

@Composable
private fun ProfileEditField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    singleLine: Boolean = true
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = singleLine,
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = PrimaryDark,
            focusedLabelColor = PrimaryDark
        )
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
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
                text = label,
                fontSize = 15.sp,
                color = TextSecondary,
                modifier = Modifier.width(80.dp)
            )
            Text(
                text = value,
                fontSize = 15.sp,
                color = TextPrimary,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = TextHint,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * 可点击的选择行（用于性别、生日等选择器）
 */
@Composable
private fun SelectableInfoRow(
    label: String,
    value: String,
    placeholder: String = "",
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
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
                text = label,
                fontSize = 15.sp,
                color = TextSecondary,
                modifier = Modifier.width(80.dp)
            )
            Text(
                text = value.ifBlank { placeholder },
                fontSize = 15.sp,
                color = if (value.isBlank()) TextHint else TextPrimary,
                modifier = Modifier.weight(1f)
            )
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = TextHint,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * 性别代码转显示文本
 */
@Composable
private fun genderDisplayText(gender: String): String {
    return when (gender) {
        "MALE" -> stringResource(R.string.gender_male)
        "FEMALE" -> stringResource(R.string.gender_female)
        "OTHER" -> stringResource(R.string.gender_other)
        else -> stringResource(R.string.gender_private)
    }
}

/**
 * 性别选择对话框
 */
@Composable
private fun GenderPickerDialog(
    currentGender: String,
    onDismiss: () -> Unit,
    onSelected: (String) -> Unit
) {
    val options = listOf(
        "MALE" to stringResource(R.string.gender_male),
        "FEMALE" to stringResource(R.string.gender_female),
        "OTHER" to stringResource(R.string.gender_other),
        "" to stringResource(R.string.gender_private)
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.select_gender)) },
        text = {
            Column(Modifier.selectableGroup()) {
                options.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = currentGender == value,
                                onClick = { onSelected(value) },
                                role = Role.RadioButton
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentGender == value,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(selectedColor = PrimaryDark)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(text = label, fontSize = 15.sp, color = TextPrimary)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = TextSecondary)
            }
        }
    )
}

/**
 * 生日选择对话框（使用 Material3 DatePicker）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BirthdayPickerDialog(
    currentBirthday: String,
    onDismiss: () -> Unit,
    onSelected: (String) -> Unit
) {
    val formatter = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd") }

    // 解析当前生日，用于初始化 DatePicker
    val initialMillis = remember(currentBirthday) {
        try {
            if (currentBirthday.isNotBlank()) {
                LocalDate.parse(currentBirthday, formatter)
                    .atStartOfDay(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            } else null
        } catch (e: Exception) {
            null
        }
    }

    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = initialMillis
    )

    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                datePickerState.selectedDateMillis?.let { millis ->
                    val date = Instant.ofEpochMilli(millis)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate()
                    onSelected(date.format(formatter))
                }
            }) {
                Text(stringResource(R.string.confirm), color = PrimaryDark)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = TextSecondary)
            }
        }
    ) {
        DatePicker(state = datePickerState)
    }
}
