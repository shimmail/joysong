package com.joysong.app.ui.profile

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.Business
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.MedicalServices
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.SupportAgent
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.joysong.app.data.remote.dto.IdentityApplicationDto
import com.joysong.app.ui.components.JoysongTopBar
import com.joysong.app.ui.theme.Background
import com.joysong.app.ui.theme.Error
import com.joysong.app.ui.theme.PrimaryDark
import com.joysong.app.ui.theme.Success
import com.joysong.app.ui.theme.Surface
import com.joysong.app.ui.theme.SurfaceVariant
import com.joysong.app.ui.theme.TextHint
import com.joysong.app.ui.theme.TextPrimary
import com.joysong.app.ui.theme.TextSecondary
import com.joysong.app.ui.theme.Warning

private data class IdentityRoleConfig(
    val code: String,
    val title: String,
    val description: String,
    val icon: ImageVector,
    val accent: Color
)

private data class ApplicationField(
    val key: String,
    val label: String,
    val placeholder: String,
    val keyboardType: KeyboardType = KeyboardType.Text,
    val multiline: Boolean = false
)

private data class DocumentRequirement(
    val type: String,
    val title: String,
    val hint: String
)

private val roleConfigs = listOf(
    IdentityRoleConfig(
        "INSTITUTION_LEGAL_REPRESENTATIVE",
        "机构法人认证",
        "提交营业执照、法人实名信息与机构运营资料",
        Icons.Outlined.Business,
        Color(0xFF8B6BCE)
    ),
    IdentityRoleConfig(
        "DOCTOR",
        "医生认证",
        "提交医师资格证、执业证及本人身份核验资料",
        Icons.Outlined.MedicalServices,
        Color(0xFF4F8CC9)
    ),
    IdentityRoleConfig(
        "CONSULTANT",
        "咨询师认证",
        "提交实名资料、从业经历和专业能力证明",
        Icons.Outlined.SupportAgent,
        Color(0xFFC47A9A)
    )
)

private fun roleConfig(roleCode: String) = roleConfigs.firstOrNull { it.code == roleCode } ?: roleConfigs.last()

@Composable
fun OfficialVerificationScreen(
    onBackClick: () -> Unit,
    onApplyClick: (String) -> Unit,
    refreshKey: Long = 0,
    viewModel: IdentityVerificationViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(refreshKey) {
        if (refreshKey > 0) viewModel.loadOverview()
    }
    LaunchedEffect(state.errorMessage) {
        state.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessage()
        }
    }

    Scaffold(
        topBar = { JoysongTopBar(title = "官方认证", onBackClick = onBackClick) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Background)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF5F8))
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xFFFFE1EA)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Outlined.VerifiedUser, contentDescription = null, tint = PrimaryDark, modifier = Modifier.size(28.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text("申请专业身份", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                        Text("所有新注册账号均为普通用户，认证通过后可切换对应身份。", fontSize = 13.sp, color = TextSecondary, lineHeight = 19.sp)
                    }
                }
            }

            Text("选择认证类型", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            roleConfigs.forEach { config ->
                val active = state.overview.roles.any { it.roleCode == config.code && it.status == "ACTIVE" }
                val latestApplication = state.overview.applications.firstOrNull { it.roleCode == config.code }
                IdentityRoleCard(
                    config = config,
                    active = active,
                    application = latestApplication,
                    onClick = { if (!active && latestApplication?.status != "PENDING") onApplyClick(config.code) }
                )
            }

            Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Surface)
            ) {
                Row(modifier = Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Outlined.Security, contentDescription = null, tint = Success, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("隐私材料安全保护", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                        Text("身份证、营业执照和执业证将上传至私有存储，仅用于平台审核，不会作为公开图片展示。", fontSize = 12.sp, color = TextSecondary, lineHeight = 18.sp)
                    }
                }
            }

            if (state.isLoading) {
                Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = PrimaryDark)
                }
            } else {
                TextButton(onClick = viewModel::loadOverview, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("刷新认证状态")
                }
            }
        }
    }
}

@Composable
private fun IdentityRoleCard(
    config: IdentityRoleConfig,
    active: Boolean,
    application: IdentityApplicationDto?,
    onClick: () -> Unit
) {
    val status = when {
        active -> "ACTIVE"
        else -> application?.status
    }
    val statusText = when (status) {
        "ACTIVE" -> "已认证"
        "PENDING" -> "审核中"
        "REJECTED" -> "未通过，可重新申请"
        "REVOKED" -> "已撤销，可重新申请"
        else -> "去认证"
    }
    val statusColor = when (status) {
        "ACTIVE" -> Success
        "PENDING" -> Warning
        "REJECTED" -> Error
        else -> PrimaryDark
    }

    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Surface)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(config.accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(config.icon, contentDescription = null, tint = config.accent, modifier = Modifier.size(27.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(config.title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = TextPrimary)
                Text(config.description, fontSize = 12.sp, color = TextSecondary, lineHeight = 17.sp)
                if (application?.status == "REJECTED" && application.reviewNote.isNotBlank()) {
                    Text("原因：${application.reviewNote}", fontSize = 12.sp, color = Error, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(statusText, fontSize = 12.sp, color = statusColor, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
fun IdentityApplicationScreen(
    roleCode: String,
    onBackClick: () -> Unit,
    onSubmitted: () -> Unit,
    viewModel: IdentityVerificationViewModel = hiltViewModel()
) {
    val config = roleConfig(roleCode)
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var values by remember(roleCode) { mutableStateOf<Map<String, String>>(emptyMap()) }
    var selectedUris by remember(roleCode) { mutableStateOf<Map<String, Uri>>(emptyMap()) }
    var agreedTruth by remember { mutableStateOf(false) }
    var agreedPrivacy by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    val fields = remember(roleCode) { fieldsFor(roleCode) }
    val documents = remember(roleCode) { documentsFor(roleCode) }

    LaunchedEffect(state.errorMessage, localError) {
        val message = localError ?: state.errorMessage
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            localError = null
            viewModel.clearMessage()
        }
    }
    LaunchedEffect(state.submitSuccess) {
        if (state.submitSuccess) onSubmitted()
    }

    Scaffold(
        topBar = { JoysongTopBar(title = config.title, onBackClick = onBackClick) },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Background)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            FormSection(title = when (roleCode) {
                "INSTITUTION_LEGAL_REPRESENTATIVE" -> "营业执照"
                "DOCTOR" -> "认证说明"
                else -> "申请说明"
            }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(config.icon, contentDescription = null, tint = config.accent, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(config.title, fontWeight = FontWeight.Medium, color = TextPrimary)
                        Text(config.description, fontSize = 12.sp, color = TextSecondary)
                    }
                }
            }

            if (roleCode == "INSTITUTION_LEGAL_REPRESENTATIVE") {
                FormSection(title = "机构资质", subtitle = "请填写营业执照上的信息，提交后进入平台审核") {
                    IdentityTextField(
                        field = ApplicationField("institutionName", "机构名称", "请输入营业执照上的机构全称"),
                        value = values["institutionName"].orEmpty(),
                        onValueChange = { values = values + ("institutionName" to it) }
                    )
                    IdentityTextField(
                        field = ApplicationField("businessLicenseNo", "统一社会信用代码", "请输入营业执照编号"),
                        value = values["businessLicenseNo"].orEmpty(),
                        onValueChange = { values = values + ("businessLicenseNo" to it) }
                    )
                    DocumentUploadBox(
                        requirement = DocumentRequirement("BUSINESS_LICENSE", "上传营业执照", "证件完整清晰、四角无遮挡"),
                        selectedUri = selectedUris["BUSINESS_LICENSE"],
                        uploaded = state.uploadedFiles.containsKey("BUSINESS_LICENSE"),
                        uploading = "BUSINESS_LICENSE" in state.uploadingTypes,
                        onSelected = { uri -> selectedUris = selectedUris + ("BUSINESS_LICENSE" to uri); viewModel.uploadDocument("BUSINESS_LICENSE", uri) },
                        onRemove = { selectedUris = selectedUris - "BUSINESS_LICENSE"; viewModel.removeDocument("BUSINESS_LICENSE") }
                    )
                }
            }

            FormSection(title = "实名信息", subtitle = "身份信息仅用于认证审核与安全核验") {
                fields.filter { it.key in setOf("realName", "idNumber", "phone") }.forEach { field ->
                    IdentityTextField(field, values[field.key].orEmpty()) { values = values + (field.key to it) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    documents.filter { it.type == "ID_CARD_FRONT" || it.type == "ID_CARD_BACK" }.forEach { requirement ->
                        key(requirement.type) {
                            Box(modifier = Modifier.weight(1f)) {
                                DocumentUploadBox(
                                    requirement = requirement,
                                    selectedUri = selectedUris[requirement.type],
                                    uploaded = state.uploadedFiles.containsKey(requirement.type),
                                    uploading = requirement.type in state.uploadingTypes,
                                    onSelected = { uri -> selectedUris = selectedUris + (requirement.type to uri); viewModel.uploadDocument(requirement.type, uri) },
                                    onRemove = { selectedUris = selectedUris - requirement.type; viewModel.removeDocument(requirement.type) }
                                )
                            }
                        }
                    }
                }
                documents.firstOrNull { it.type == "ID_CARD_HANDHELD" }?.let { requirement ->
                    DocumentUploadBox(
                        requirement = requirement,
                        selectedUri = selectedUris[requirement.type],
                        uploaded = state.uploadedFiles.containsKey(requirement.type),
                        uploading = requirement.type in state.uploadingTypes,
                        onSelected = { uri -> selectedUris = selectedUris + (requirement.type to uri); viewModel.uploadDocument(requirement.type, uri) },
                        onRemove = { selectedUris = selectedUris - requirement.type; viewModel.removeDocument(requirement.type) }
                    )
                }
            }

            val detailFields = fields.filterNot { it.key in setOf("realName", "idNumber", "phone", "institutionName", "businessLicenseNo") }
            if (detailFields.isNotEmpty()) {
                FormSection(
                    title = when (roleCode) {
                        "DOCTOR" -> "执业信息"
                        "CONSULTANT" -> "从业资料"
                        else -> "运营人信息"
                    }
                ) {
                    detailFields.forEach { field ->
                        IdentityTextField(field, values[field.key].orEmpty()) { values = values + (field.key to it) }
                    }
                }
            }

            val professionalDocuments = documents.filterNot {
                it.type in setOf("BUSINESS_LICENSE", "ID_CARD_FRONT", "ID_CARD_BACK", "ID_CARD_HANDHELD")
            }
            if (professionalDocuments.isNotEmpty()) {
                FormSection(title = "专业证明", subtitle = "请上传真实、清晰且在有效期内的证明材料") {
                    professionalDocuments.forEach { requirement ->
                        key(requirement.type) {
                            DocumentUploadBox(
                                requirement = requirement,
                                selectedUri = selectedUris[requirement.type],
                                uploaded = state.uploadedFiles.containsKey(requirement.type),
                                uploading = requirement.type in state.uploadingTypes,
                                onSelected = { uri -> selectedUris = selectedUris + (requirement.type to uri); viewModel.uploadDocument(requirement.type, uri) },
                                onRemove = { selectedUris = selectedUris - requirement.type; viewModel.removeDocument(requirement.type) }
                            )
                        }
                    }
                }
            }

            AgreementRow(checked = agreedTruth, text = "我承诺所填写信息和上传材料真实、有效，并同意平台进行必要核验") { agreedTruth = it }
            AgreementRow(checked = agreedPrivacy, text = "我已阅读并同意《官方认证服务协议》和《隐私保护说明》") { agreedPrivacy = it }

            Button(
                onClick = {
                    val missingField = fields.firstOrNull { values[it.key].isNullOrBlank() }
                    val missingDocument = documents.firstOrNull { !state.uploadedFiles.containsKey(it.type) }
                    when {
                        missingField != null -> localError = "请填写${missingField.label}"
                        missingDocument != null -> localError = "请上传${missingDocument.title}"
                        !agreedTruth || !agreedPrivacy -> localError = "请阅读并勾选认证协议"
                        state.uploadingTypes.isNotEmpty() -> localError = "材料正在上传，请稍候"
                        else -> viewModel.submit(roleCode, values)
                    }
                },
                enabled = !state.isSubmitting,
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(25.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PrimaryDark)
            ) {
                if (state.isSubmitting) {
                    CircularProgressIndicator(color = Color.White, modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    Text("提交审核", color = Color.White, fontSize = 16.sp)
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun fieldsFor(roleCode: String): List<ApplicationField> {
    val common = mutableListOf(
        ApplicationField("realName", "真实姓名", "请输入证件上的姓名"),
        ApplicationField("idNumber", "证件号码", "请输入本人证件号码")
    )
    return when (roleCode) {
        "INSTITUTION_LEGAL_REPRESENTATIVE" -> common + listOf(
            ApplicationField("institutionName", "机构名称", "请输入营业执照上的机构全称"),
            ApplicationField("businessLicenseNo", "统一社会信用代码", "请输入营业执照编号"),
            ApplicationField("phone", "联系电话", "请输入法人或运营人手机号", KeyboardType.Phone),
            ApplicationField("region", "所在地区", "请输入省/市/区"),
            ApplicationField("address", "详细地址", "请输入街道、门牌号等详细地址", multiline = true)
        )
        "DOCTOR" -> common + listOf(
            ApplicationField("hospitalName", "执业机构", "请输入当前执业医院或机构"),
            ApplicationField("department", "科室", "请输入所在科室"),
            ApplicationField("title", "职称", "请输入当前职称"),
            ApplicationField("qualificationNo", "医师资格证编号", "请输入资格证书编号"),
            ApplicationField("practiceNo", "医师执业证编号", "请输入执业证书编号"),
            ApplicationField("reason", "申请理由", "请说明认证用途和专业方向", multiline = true)
        )
        else -> common + listOf(
            ApplicationField("phone", "联系电话", "请输入本人手机号", KeyboardType.Phone),
            ApplicationField("experience", "从业经历", "请填写从业年限、服务机构和擅长领域", multiline = true),
            ApplicationField("proofDescription", "证明材料说明", "请说明培训、资格或任职证明", multiline = true),
            ApplicationField("reason", "申请理由", "请说明申请咨询师身份的原因", multiline = true)
        )
    }
}

private fun documentsFor(roleCode: String): List<DocumentRequirement> = when (roleCode) {
    "INSTITUTION_LEGAL_REPRESENTATIVE" -> listOf(
        DocumentRequirement("BUSINESS_LICENSE", "营业执照", "上传完整营业执照"),
        DocumentRequirement("ID_CARD_FRONT", "身份证人像面", "证件完整清晰"),
        DocumentRequirement("ID_CARD_BACK", "身份证国徽面", "证件完整清晰")
    )
    "DOCTOR" -> listOf(
        DocumentRequirement("ID_CARD_FRONT", "身份证人像面", "证件完整清晰"),
        DocumentRequirement("ID_CARD_BACK", "身份证国徽面", "证件完整清晰"),
        DocumentRequirement("ID_CARD_HANDHELD", "手持身份证照片", "本人手持证件正面拍摄"),
        DocumentRequirement("DOCTOR_QUALIFICATION", "医师资格证", "上传资格证信息页"),
        DocumentRequirement("DOCTOR_PRACTICE_CERTIFICATE", "医师执业证", "上传执业证信息页")
    )
    else -> listOf(
        DocumentRequirement("ID_CARD_FRONT", "身份证人像面", "证件完整清晰"),
        DocumentRequirement("ID_CARD_BACK", "身份证国徽面", "证件完整清晰"),
        DocumentRequirement("CONSULTANT_PROOF", "相关专业证明", "培训证书、任职证明或其他能力材料")
    )
}

@Composable
private fun FormSection(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Surface)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
            subtitle?.let { Text(it, fontSize = 12.sp, color = TextHint, lineHeight = 18.sp) }
            Divider(color = SurfaceVariant)
            content()
        }
    }
}

@Composable
private fun IdentityTextField(field: ApplicationField, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { if (it.length <= if (field.multiline) 1000 else 200) onValueChange(it) },
        label = { Text(field.label) },
        placeholder = { Text(field.placeholder, fontSize = 13.sp) },
        modifier = Modifier.fillMaxWidth(),
        minLines = if (field.multiline) 3 else 1,
        maxLines = if (field.multiline) 6 else 1,
        keyboardOptions = KeyboardOptions(keyboardType = field.keyboardType),
        shape = RoundedCornerShape(10.dp)
    )
}

@Composable
private fun DocumentUploadBox(
    requirement: DocumentRequirement,
    selectedUri: Uri?,
    uploaded: Boolean,
    uploading: Boolean,
    onSelected: (Uri) -> Unit,
    onRemove: () -> Unit
) {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(onSelected)
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(enabled = !uploading) { launcher.launch("image/*") },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceVariant)
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(132.dp)) {
            if (selectedUri != null) {
                AsyncImage(
                    model = selectedUri,
                    contentDescription = requirement.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.18f)))
            }
            Column(
                modifier = Modifier.fillMaxSize().padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                when {
                    uploading -> CircularProgressIndicator(color = PrimaryDark, modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                    uploaded -> Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Success, modifier = Modifier.size(30.dp))
                    else -> Icon(
                        if (requirement.type.contains("ID_CARD") || requirement.type == "BUSINESS_LICENSE") Icons.Outlined.Badge else Icons.Outlined.Description,
                        contentDescription = null,
                        tint = if (selectedUri == null) TextHint else Color.White,
                        modifier = Modifier.size(30.dp)
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(requirement.title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (selectedUri == null) TextPrimary else Color.White)
                Text(
                    when {
                        uploading -> "正在安全上传…"
                        uploaded -> "已安全上传，点击可更换"
                        else -> requirement.hint
                    },
                    fontSize = 11.sp,
                    color = if (selectedUri == null) TextHint else Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (selectedUri != null && !uploading) {
                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.align(Alignment.TopEnd).size(34.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "移除", tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun AgreementRow(checked: Boolean, text: String, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.Top
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange, modifier = Modifier.size(36.dp))
        Text(text, fontSize = 12.sp, color = TextSecondary, lineHeight = 18.sp, modifier = Modifier.padding(top = 8.dp))
    }
}
