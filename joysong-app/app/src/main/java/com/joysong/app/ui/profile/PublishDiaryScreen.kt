package com.joysong.app.ui.profile

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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import coil.compose.AsyncImage
import com.joysong.app.R
import com.joysong.app.ui.components.JoysongTopBar
import com.joysong.app.ui.components.StarRatingBar
import com.joysong.app.ui.navigation.Routes
import com.joysong.app.ui.theme.Background
import com.joysong.app.ui.theme.PrimaryDark
import com.joysong.app.ui.theme.Surface
import com.joysong.app.ui.theme.SurfaceVariant
import com.joysong.app.ui.theme.TextHint
import com.joysong.app.ui.theme.TextOnPrimary
import com.joysong.app.ui.theme.TextPrimary
import com.joysong.app.ui.theme.TextSecondary
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun PublishDiaryScreen(
    navController: NavHostController,
    onBackClick: () -> Unit,
    diaryId: String? = null,
    viewModel: MyDiariesViewModel = hiltViewModel(),
    backStackEntry: NavBackStackEntry? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isEditMode = !diaryId.isNullOrBlank()

    // Form state (use rememberSaveable to survive navigation to Select* screens)
    var title by rememberSaveable { mutableStateOf("") }
    var content by rememberSaveable { mutableStateOf("") }
    val selectedTags = rememberSaveable(
        saver = listSaver<SnapshotStateList<String>, String>(
            save = { it.toList() },
            restore = { mutableStateListOf<String>().apply { addAll(it) } }
        )
    ) { mutableStateListOf<String>() }
    val tags = listOf(
        stringResource(R.string.tag_pre_op),
        stringResource(R.string.tag_post_op),
        stringResource(R.string.tag_recovery),
        stringResource(R.string.tag_review)
    )
    var customTagInput by rememberSaveable { mutableStateOf("") }
    var rating by rememberSaveable { mutableIntStateOf(0) }
    var diaryStatus by rememberSaveable { mutableStateOf("published") }

    // Image lists (URLs after upload) - use rememberSaveable to survive navigation
    val beforeImageUrls = rememberSaveable(
        saver = listSaver<SnapshotStateList<String>, String>(
            save = { it.toList() },
            restore = { mutableStateListOf<String>().apply { addAll(it) } }
        )
    ) { mutableStateListOf<String>() }
    val afterImageUrls = rememberSaveable(
        saver = listSaver<SnapshotStateList<String>, String>(
            save = { it.toList() },
            restore = { mutableStateListOf<String>().apply { addAll(it) } }
        )
    ) { mutableStateListOf<String>() }

    // Upload state
    var isUploadingImage by rememberSaveable { mutableStateOf(false) }

    // Association state (use rememberSaveable to survive navigation)
    var selectedProjectId by rememberSaveable { mutableStateOf("") }
    var selectedProjectName by rememberSaveable { mutableStateOf("") }
    var selectedInstitutionName by rememberSaveable { mutableStateOf("") }
    var selectedOrderId by rememberSaveable { mutableStateOf("") }
    var selectedDoctorId by rememberSaveable { mutableStateOf("") }
    var selectedDoctorName by rememberSaveable { mutableStateOf("") }
    var selectedInstitutionId by rememberSaveable { mutableStateOf("") }
    var selectedInstitutionProjectId by rememberSaveable { mutableStateOf("") }

    val publishState by viewModel.publishState.collectAsState()
    val diariesUiState by viewModel.uiState.collectAsState()
    var hasLoadedDiaryData by rememberSaveable { mutableStateOf(false) }

    // Load diary data in edit mode
    LaunchedEffect(diaryId, diariesUiState.diaries) {
        if (isEditMode && !hasLoadedDiaryData) {
            val diary = diariesUiState.diaries.find { it.id == diaryId }
            if (diary != null) {
                title = diary.title
                content = diary.content
                rating = diary.rating
                selectedTags.clear()
                selectedTags.addAll(diary.tags)
                beforeImageUrls.clear()
                beforeImageUrls.addAll(
                    diary.beforeImages.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                )
                afterImageUrls.clear()
                afterImageUrls.addAll(
                    diary.afterImages.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                )
                selectedProjectId = diary.projectId
                selectedProjectName = diary.projectName
                selectedOrderId = diary.orderId
                selectedDoctorId = diary.doctorId
                selectedDoctorName = diary.doctorName
                selectedInstitutionId = diary.institutionId
                selectedInstitutionName = diary.institutionName
                selectedInstitutionProjectId = diary.institutionProjectId
                diaryStatus = diary.status
                hasLoadedDiaryData = true
            }
        }
    }

    // Monitor savedStateHandle for selection results
    // Use the stable backStackEntry passed from navigation (not currentBackStackEntry which changes during navigation)
    val savedStateHandle = backStackEntry?.savedStateHandle
        ?: navController.currentBackStackEntry?.savedStateHandle
    LaunchedEffect(savedStateHandle) {
        savedStateHandle?.let { handle ->
            launch {
                handle.getStateFlow<String?>("selectedProject", null).collect { json ->
                    if (json != null) {
                        try {
                            val obj = JSONObject(json)
                            selectedProjectId = obj.optString("projectId", "")
                            selectedProjectName = obj.optString("projectName", "")
                            selectedInstitutionName = obj.optString("institutionName", "")
                            selectedOrderId = obj.optString("orderId", "")
                            selectedInstitutionProjectId = obj.optString("institutionProjectId", "")
                            // 自动关联医生和机构
                            val doctorId = obj.optString("doctorId", "")
                            if (doctorId.isNotBlank()) {
                                selectedDoctorId = doctorId
                                selectedDoctorName = obj.optString("doctorName", "")
                            }
                            val institutionId = obj.optString("institutionId", "")
                            if (institutionId.isNotBlank()) {
                                selectedInstitutionId = institutionId
                            }
                            // Clear after reading
                            handle.set("selectedProject", null)
                        } catch (_: Exception) {}
                    }
                }
            }
            launch {
                handle.getStateFlow<String?>("selectedDoctor", null).collect { json ->
                    if (json != null) {
                        try {
                            val obj = JSONObject(json)
                            selectedDoctorId = obj.optString("id", "")
                            selectedDoctorName = obj.optString("name", "")
                            handle.set("selectedDoctor", null)
                        } catch (_: Exception) {}
                    }
                }
            }
            launch {
                handle.getStateFlow<String?>("selectedInstitution", null).collect { json ->
                    if (json != null) {
                        try {
                            val obj = JSONObject(json)
                            selectedInstitutionId = obj.optString("institutionId", "")
                            selectedInstitutionName = obj.optString("institutionName", "")
                            handle.set("selectedInstitution", null)
                        } catch (_: Exception) {}
                    }
                }
            }
        }
    }

    // Image pickers
    val beforeImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            scope.launch {
                isUploadingImage = true
                for (uri in uris) {
                    val url = viewModel.uploadImage(uri, context)
                    if (url != null) {
                        beforeImageUrls.add(url)
                    }
                }
                isUploadingImage = false
            }
        }
    }

    val afterImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            scope.launch {
                isUploadingImage = true
                for (uri in uris) {
                    val url = viewModel.uploadImage(uri, context)
                    if (url != null) {
                        afterImageUrls.add(url)
                    }
                }
                isUploadingImage = false
            }
        }
    }

    // Publish result
    LaunchedEffect(publishState) {
        when (publishState) {
            is PublishDiaryState.Success -> {
                viewModel.resetPublishState()
                onBackClick()
            }
            is PublishDiaryState.Error -> {
                Toast.makeText(context, (publishState as PublishDiaryState.Error).message, Toast.LENGTH_SHORT).show()
            }
            else -> {}
        }
    }

    Scaffold(
        topBar = {
            JoysongTopBar(
                title = stringResource(if (isEditMode) R.string.edit_diary else R.string.publish_diary),
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
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // === 关联信息区域（四项选填） ===
                // a) 关联项目
                SelectableInfoRow(
                    label = stringResource(R.string.related_project),
                    value = selectedProjectName,
                    placeholder = "${stringResource(R.string.please_select)}（${stringResource(R.string.optional_hint)}）",
                    onClick = { navController.navigate(Routes.SelectProject.route) },
                    onClear = {
                        selectedProjectId = ""
                        selectedProjectName = ""
                        selectedInstitutionName = ""
                        selectedOrderId = ""
                        selectedInstitutionProjectId = ""
                    }
                )

                // b) 关联医生
                SelectableInfoRow(
                    label = stringResource(R.string.related_doctor),
                    value = selectedDoctorName,
                    placeholder = "${stringResource(R.string.please_select)}（${stringResource(R.string.optional_hint)}）",
                    onClick = { navController.navigate(Routes.SelectDoctor.createRoute(selectedInstitutionProjectId)) },
                    onClear = {
                        selectedDoctorId = ""
                        selectedDoctorName = ""
                    }
                )

                // c) 关联机构
                SelectableInfoRow(
                    label = stringResource(R.string.related_institution),
                    value = selectedInstitutionName,
                    placeholder = "${stringResource(R.string.please_select)}（${stringResource(R.string.optional_hint)}）",
                    onClick = { navController.navigate(Routes.SelectInstitution.route) },
                    onClear = {
                        selectedInstitutionId = ""
                        selectedInstitutionName = ""
                    }
                )

                // d) 手术评分
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
                            text = stringResource(R.string.surgery_rating),
                            fontSize = 15.sp,
                            color = TextSecondary,
                            modifier = Modifier.width(80.dp)
                        )
                        StarRatingBar(
                            rating = rating,
                            onRatingChanged = { rating = it },
                            starSize = 28.dp,
                            modifier = Modifier.weight(1f)
                        )
                        if (rating > 0) {
                            Text(
                                text = stringResource(R.string.clear_selection),
                                fontSize = 13.sp,
                                color = PrimaryDark,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier
                                    .clickable { rating = 0 }
                                    .padding(horizontal = 4.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                // e) 标题
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.publish_diary_title)) },
                    placeholder = { Text(stringResource(R.string.publish_diary_title_hint)) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PrimaryDark,
                        focusedLabelColor = PrimaryDark,
                        unfocusedBorderColor = SurfaceVariant,
                        unfocusedContainerColor = Surface
                    )
                )

                // f) 正文
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp),
                    label = { Text(stringResource(R.string.publish_diary_content)) },
                    placeholder = { Text(stringResource(R.string.publish_diary_content_hint)) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PrimaryDark,
                        focusedLabelColor = PrimaryDark,
                        unfocusedBorderColor = SurfaceVariant,
                        unfocusedContainerColor = Surface
                    )
                )

                // g) 标签选择
                Text(
                    text = stringResource(R.string.publish_diary_tags),
                    fontSize = 14.sp,
                    color = TextPrimary
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    tags.forEach { tag ->
                        val selected = tag in selectedTags
                        TagChip(
                            text = tag,
                            selected = selected,
                            onClick = {
                                if (selected) selectedTags.remove(tag) else selectedTags.add(tag)
                            }
                        )
                    }
                }
                // 自定义标签
                val customTags = selectedTags.filter { it !in tags }
                if (customTags.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        customTags.forEach { tag ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(PrimaryDark)
                                    .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
                            ) {
                                Text(
                                    text = tag,
                                    fontSize = 13.sp,
                                    color = TextOnPrimary
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = null,
                                    tint = TextOnPrimary,
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clickable { selectedTags.remove(tag) }
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = customTagInput,
                        onValueChange = { customTagInput = it },
                        placeholder = { Text(stringResource(R.string.custom_tag_placeholder), fontSize = 13.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(20.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PrimaryDark,
                            unfocusedBorderColor = SurfaceVariant
                        ),
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
                    )
                    Button(
                        onClick = {
                            val tag = customTagInput.trim()
                            if (tag.isNotBlank() && tag !in selectedTags) {
                                selectedTags.add(tag)
                                customTagInput = ""
                            }
                        },
                        enabled = customTagInput.isNotBlank(),
                        shape = RoundedCornerShape(20.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryDark),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = TextOnPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                // h) 术前照
                Text(
                    text = stringResource(R.string.before_photos_label),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary
                )
                ImagePickerRow(
                    imageUrls = beforeImageUrls,
                    isUploading = isUploadingImage,
                    onAddClick = {
                        beforeImagePicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onRemoveClick = { url -> beforeImageUrls.remove(url) }
                )

                // i) 术后照
                Text(
                    text = stringResource(R.string.after_photos_label),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = TextPrimary
                )
                ImagePickerRow(
                    imageUrls = afterImageUrls,
                    isUploading = isUploadingImage,
                    onAddClick = {
                        afterImagePicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onRemoveClick = { url -> afterImageUrls.remove(url) }
                )

                // j) 公开/私密开关
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
                            text = stringResource(R.string.diary_visibility),
                            fontSize = 15.sp,
                            color = TextSecondary,
                            modifier = Modifier.weight(1f)
                        )
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(if (diaryStatus == "published") PrimaryDark.copy(alpha = 0.1f) else SurfaceVariant)
                                .clickable { diaryStatus = if (diaryStatus == "published") "private" else "published" }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (diaryStatus == "published") Icons.Outlined.Public else Icons.Outlined.Lock,
                                contentDescription = null,
                                tint = if (diaryStatus == "published") PrimaryDark else TextHint,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(if (diaryStatus == "published") R.string.diary_public else R.string.diary_private),
                                fontSize = 13.sp,
                                color = if (diaryStatus == "published") PrimaryDark else TextHint,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }

            // h) 底部固定发布按钮
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Background)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                val isLoading = publishState is PublishDiaryState.Loading
                Button(
                    onClick = {
                        val allImages = beforeImageUrls + afterImageUrls
                        scope.launch {
                            if (isEditMode) {
                                viewModel.updateDiary(
                                    diaryId = diaryId!!,
                                    title = title,
                                    content = content,
                                    images = allImages,
                                    tags = selectedTags.toList(),
                                    rating = rating,
                                    doctorId = selectedDoctorId,
                                    projectId = selectedProjectId,
                                    institutionId = selectedInstitutionId,
                                    institutionProjectId = selectedInstitutionProjectId,
                                    orderId = selectedOrderId,
                                    beforeImages = beforeImageUrls.toList(),
                                    afterImages = afterImageUrls.toList(),
                                    status = diaryStatus
                                )
                            } else {
                                viewModel.publishDiary(
                                    title = title,
                                    content = content,
                                    images = allImages,
                                    tags = selectedTags.toList(),
                                    rating = rating,
                                    doctorId = selectedDoctorId,
                                    projectId = selectedProjectId,
                                    institutionId = selectedInstitutionId,
                                    institutionProjectId = selectedInstitutionProjectId,
                                    orderId = selectedOrderId,
                                    beforeImages = beforeImageUrls.toList(),
                                    afterImages = afterImageUrls.toList(),
                                    status = diaryStatus
                                )
                            }
                        }
                    },
                    enabled = title.isNotBlank() && content.isNotBlank() && !isLoading && !isUploadingImage,
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
                        text = when {
                            isLoading -> stringResource(if (isEditMode) R.string.saving else R.string.publishing)
                            isUploadingImage -> stringResource(R.string.uploading_image)
                            else -> stringResource(if (isEditMode) R.string.save_changes else R.string.publish_diary_button)
                        },
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

@Composable
private fun ImagePickerRow(
    imageUrls: List<String>,
    isUploading: Boolean,
    onAddClick: () -> Unit,
    onRemoveClick: (String) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(end = 4.dp)
    ) {
        items(imageUrls) { url ->
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(SurfaceVariant)
            ) {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp)
                        .size(18.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                        .clickable { onRemoveClick(url) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        tint = TextOnPrimary,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
        item {
            Box(
                modifier = Modifier
                    .size(88.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, SurfaceVariant, RoundedCornerShape(10.dp))
                    .background(Surface)
                    .clickable(enabled = !isUploading, onClick = onAddClick),
                contentAlignment = Alignment.Center
            ) {
                if (isUploading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = PrimaryDark,
                        strokeWidth = 2.dp
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            tint = TextHint,
                            modifier = Modifier.size(24.dp)
                        )
                        Text(
                            text = stringResource(R.string.add_photo),
                            fontSize = 11.sp,
                            color = TextHint
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectableInfoRow(
    label: String,
    value: String,
    placeholder: String = "",
    onClick: () -> Unit,
    onClear: (() -> Unit)? = null
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
            if (value.isNotBlank() && onClear != null) {
                Text(
                    text = stringResource(R.string.clear_selection),
                    fontSize = 13.sp,
                    color = PrimaryDark,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clickable { onClear() }
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = TextHint,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun TagChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Text(
        text = text,
        fontSize = 13.sp,
        color = if (selected) TextOnPrimary else TextPrimary,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (selected) PrimaryDark else Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}
