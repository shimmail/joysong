package com.joysong.app.ui.aiagent

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.joysong.app.R
import com.joysong.app.data.remote.dto.AgentPlanDto
import com.joysong.app.data.remote.dto.AgentPlanItemDto
import com.joysong.app.data.remote.dto.AgentSafetyScreeningRequestDto
import com.joysong.app.data.remote.dto.AgentCatalogItemDto
import com.joysong.app.data.remote.dto.AgentCatalogReportDto
import com.joysong.app.data.remote.dto.ChatSessionDto
import com.joysong.app.ui.components.MessageInputBar
import com.joysong.app.ui.theme.Background
import com.joysong.app.ui.theme.Error
import com.joysong.app.ui.theme.Primary
import com.joysong.app.ui.theme.PrimaryDark
import com.joysong.app.ui.theme.Surface
import com.joysong.app.ui.theme.SurfaceVariant
import com.joysong.app.ui.theme.TextHint
import com.joysong.app.ui.theme.TextPrimary
import com.joysong.app.ui.theme.TextSecondary
import com.joysong.app.ui.theme.Warning

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiAgentScreen(
    contextType: String = "",
    contextId: String = "",
    contextName: String = "",
    role: String = "",
    onOpenEntity: (AgentCatalogItemDto) -> Unit = {},
    onHumanChat: (AgentCatalogItemDto) -> Unit = {},
    viewModel: AiAgentViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var moreMenuExpanded by remember { mutableStateOf(false) }
    var showChatHistory by remember { mutableStateOf(false) }
    var showPlanHistory by remember { mutableStateOf(false) }
    var showClearConfirmation by remember { mutableStateOf(false) }
    var sessionToDelete by remember { mutableStateOf<ChatSessionDto?>(null) }
    var planToDelete by remember { mutableStateOf<AgentPlanDto?>(null) }
    var showClearHistoryConfirmation by remember { mutableStateOf(false) }
    var showClearPlansConfirmation by remember { mutableStateOf(false) }
    val localizedError = state.errorMessageRes?.let { stringResource(it) } ?: state.errorMessage

    LaunchedEffect(contextType, contextId, contextName) {
        viewModel.initContext(contextType, contextId, contextName)
    }
    LaunchedEffect(localizedError) {
        localizedError?.let {
            snackbar.showSnackbar(it)
            viewModel.clearError()
        }
    }

    Scaffold(
        containerColor = Background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (state.step == AgentStep.CHAT) {
                MessageInputBar(
                    placeholder = stringResource(R.string.agent_chat_hint),
                    sendContentDescription = stringResource(R.string.agent_send),
                    isSending = state.isSending,
                    onSend = viewModel::sendMessage
                )
            }
        },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.agent_title), fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.agent_subtitle), style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    }
                },
                actions = {
                    if (state.step == AgentStep.CHAT) {
                        TextButton(onClick = viewModel::startPlanning) { Text(stringResource(R.string.agent_create_plan), color = PrimaryDark) }
                    } else {
                        TextButton(onClick = viewModel::returnToChat) { Text(stringResource(R.string.agent_back_to_chat), color = PrimaryDark) }
                    }
                    if (state.step != AgentStep.PROFILE && state.step != AgentStep.CHAT) {
                        TextButton(onClick = viewModel::editProfile) { Text(stringResource(R.string.agent_edit_profile), color = PrimaryDark) }
                    }
                    Box {
                        IconButton(onClick = { moreMenuExpanded = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.agent_more))
                        }
                        DropdownMenu(expanded = moreMenuExpanded, onDismissRequest = { moreMenuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.agent_view_history)) },
                                onClick = {
                                    moreMenuExpanded = false
                                    viewModel.refreshChatHistory()
                                    showChatHistory = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.agent_view_plan_history)) },
                                onClick = { moreMenuExpanded = false; showPlanHistory = true }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.agent_new_chat)) },
                                onClick = { moreMenuExpanded = false; viewModel.newConversation() }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.agent_clear_chat), color = Error) },
                                onClick = { moreMenuExpanded = false; showClearConfirmation = true }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (state.step != AgentStep.CHAT) item { AgentProgress(state.step) }
                when (state.step) {
                    AgentStep.CHAT -> {
                        items(state.messages, key = { it.id }) { message ->
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                ChatBubble(message, onDelete = viewModel::deleteAgentMessage)
                                state.catalogItemsByMessageId[message.id]
                                    ?.takeIf { it.isNotEmpty() }
                                    ?.let { items ->
                                        CatalogReferenceCards(items, onOpenEntity)
                                    }
                                state.catalogReport?.takeIf {
                                    state.catalogReportMessageId == message.id &&
                                        it.items.isNotEmpty() && it.mode.uppercase() in setOf("COMPARISON", "SUMMARY")
                                }?.let { report ->
                                    CatalogReportCard(report, onOpenEntity, onHumanChat)
                                }
                            }
                        }
                        if (state.planningSuggested) {
                            item { PlanningSuggestion(state.suggestedAction, viewModel::startPlanning) }
                        }
                        if (state.isSending) {
                            item { Text(stringResource(R.string.agent_thinking), color = TextHint, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                    AgentStep.PROFILE -> item {
                        ProfileEditor(
                            draft = state.draft,
                            onDraftChange = viewModel::updateDraft,
                            onContinue = viewModel::saveAndConfirmProfile
                        )
                    }
                    AgentStep.SCREENING -> item {
                        SafetyScreening(
                            screening = state.screening,
                            confirmed = state.screeningConfirmed,
                            riskReasons = state.riskReasons,
                            onScreeningChange = viewModel::updateScreening,
                            onConfirmedChange = viewModel::setScreeningConfirmed,
                            onCreatePlan = viewModel::createPlan
                        )
                    }
                    AgentStep.PLAN -> {
                        val plan = state.plan
                        if (plan != null) {
                            item { PlanSummary(plan, viewModel::restartScreening, viewModel::returnToChat) }
                            items(plan.items, key = { it.id }) { PlanItemCard(it) }
                            item { MedicalBoundaryCard() }
                        }
                    }
                    AgentStep.SAFETY_BLOCK -> item {
                        SafetyBlock(state.riskReasons, viewModel::restartScreening, viewModel::returnToChat)
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
            if (state.isLoading) {
                Box(
                    Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.72f)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = PrimaryDark)
                        Spacer(Modifier.height(10.dp))
                        Text(stringResource(R.string.agent_loading), color = TextSecondary)
                    }
                }
            }
        }
    }

    if (showChatHistory) {
        AlertDialog(
            onDismissRequest = { showChatHistory = false },
            title = {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.agent_view_history), modifier = Modifier.weight(1f))
                    if (state.chatHistory.isNotEmpty()) {
                        TextButton(onClick = { showClearHistoryConfirmation = true }) {
                            Text(stringResource(R.string.agent_clear_all), color = Error)
                        }
                    }
                }
            },
            text = {
                if (state.chatHistory.isEmpty()) {
                    Text(stringResource(R.string.agent_no_chat_history), color = TextSecondary)
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                        items(state.chatHistory, key = { it.id }) { session ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                TextButton(
                                    onClick = { showChatHistory = false; viewModel.openConversation(session) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Column(Modifier.fillMaxWidth()) {
                                        Text(session.title.ifBlank { stringResource(R.string.agent_title) }, color = TextPrimary)
                                        Text(session.updatedAt, style = MaterialTheme.typography.labelSmall, color = TextHint)
                                    }
                                }
                                TextButton(onClick = { sessionToDelete = session }) {
                                    Text(stringResource(R.string.agent_delete), color = Error)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showChatHistory = false }) { Text(stringResource(R.string.agent_cancel)) } }
        )
    }
    if (showPlanHistory) {
        AlertDialog(
            onDismissRequest = { showPlanHistory = false },
            title = {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.agent_view_plan_history), modifier = Modifier.weight(1f))
                    if (state.planHistory.isNotEmpty()) {
                        TextButton(onClick = { showClearPlansConfirmation = true }) {
                            Text(stringResource(R.string.agent_clear_all), color = Error)
                        }
                    }
                }
            },
            text = {
                if (state.planHistory.isEmpty()) {
                    Text(stringResource(R.string.agent_no_plan_history), color = TextSecondary)
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 480.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(state.planHistory, key = { it.id }) { plan ->
                            HistoryPlanCard(
                                plan = plan,
                                onClick = { showPlanHistory = false; viewModel.selectPlan(plan) },
                                onDelete = { planToDelete = plan }
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showPlanHistory = false }) { Text(stringResource(R.string.agent_cancel)) } }
        )
    }
    if (showClearConfirmation) {
        AlertDialog(
            onDismissRequest = { showClearConfirmation = false },
            title = { Text(stringResource(R.string.agent_clear_chat)) },
            text = { Text(stringResource(R.string.agent_clear_chat_confirm)) },
            confirmButton = {
                TextButton(onClick = { showClearConfirmation = false; viewModel.clearConversation() }) {
                    Text(stringResource(R.string.agent_clear_chat), color = Error)
                }
            },
            dismissButton = { TextButton(onClick = { showClearConfirmation = false }) { Text(stringResource(R.string.agent_cancel)) } }
        )
    }
    sessionToDelete?.let { session ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.agent_delete_history),
            message = stringResource(R.string.agent_delete_history_confirm),
            onDismiss = { sessionToDelete = null },
            onConfirm = { sessionToDelete = null; viewModel.deleteConversation(session) }
        )
    }
    planToDelete?.let { plan ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.agent_delete_plan),
            message = stringResource(R.string.agent_delete_plan_confirm),
            onDismiss = { planToDelete = null },
            onConfirm = { planToDelete = null; viewModel.deletePlan(plan) }
        )
    }
    if (showClearHistoryConfirmation) {
        ConfirmDeleteDialog(
            title = stringResource(R.string.agent_clear_history),
            message = stringResource(R.string.agent_clear_history_confirm),
            onDismiss = { showClearHistoryConfirmation = false },
            onConfirm = { showClearHistoryConfirmation = false; showChatHistory = false; viewModel.clearConversationHistory() }
        )
    }
    if (showClearPlansConfirmation) {
        ConfirmDeleteDialog(
            title = stringResource(R.string.agent_clear_plans),
            message = stringResource(R.string.agent_clear_plans_confirm),
            onDismiss = { showClearPlansConfirmation = false },
            onConfirm = { showClearPlansConfirmation = false; showPlanHistory = false; viewModel.clearPlanHistory() }
        )
    }
}

@Composable
private fun ConfirmDeleteDialog(title: String, message: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.agent_delete), color = Error) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.agent_cancel)) } }
    )
}

@Composable
private fun AgentProgress(step: AgentStep) {
    val active = when (step) {
        AgentStep.CHAT -> 1
        AgentStep.PROFILE -> 1
        AgentStep.SCREENING, AgentStep.SAFETY_BLOCK -> 2
        AgentStep.PLAN -> 3
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        listOf(
            stringResource(R.string.agent_step_profile),
            stringResource(R.string.agent_step_safety),
            stringResource(R.string.agent_step_plan)
        ).forEachIndexed { index, label ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(30.dp).clip(CircleShape)
                        .background(if (index + 1 <= active) PrimaryDark else SurfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text("${index + 1}", color = if (index + 1 <= active) Color.White else TextHint)
                }
                Text(label, style = MaterialTheme.typography.labelSmall, color = if (index + 1 <= active) TextPrimary else TextHint)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileEditor(
    draft: AgentProfileDraft,
    onDraftChange: ((AgentProfileDraft) -> AgentProfileDraft) -> Unit,
    onContinue: () -> Unit
) {
    var painMenuExpanded by remember { mutableStateOf(false) }
    val painOptions = listOf(
        "" to stringResource(R.string.agent_pain_unspecified),
        "LOW" to stringResource(R.string.agent_pain_low),
        "MEDIUM" to stringResource(R.string.agent_pain_medium),
        "HIGH" to stringResource(R.string.agent_pain_high)
    )
    val selectedPainLabel = painOptions.firstOrNull { it.first == draft.painTolerance }?.second
        ?: stringResource(R.string.agent_pain_unspecified)

    AgentCard {
        Text(stringResource(R.string.agent_profile_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.agent_profile_desc), color = TextSecondary)
        OutlinedTextField(
            value = draft.goalsText,
            onValueChange = { value -> onDraftChange { it.copy(goalsText = value) } },
            label = { Text(stringResource(R.string.agent_goals)) },
            supportingText = { Text(stringResource(R.string.agent_goals_help)) },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = draft.city,
            onValueChange = { value -> onDraftChange { it.copy(city = value) } },
            label = { Text(stringResource(R.string.agent_city)) },
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = draft.budgetMaxText,
                onValueChange = { value -> onDraftChange { it.copy(budgetMaxText = value.filter(Char::isDigit)) } },
                label = { Text(stringResource(R.string.agent_budget_max)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = draft.downtimeDaysText,
                onValueChange = { value -> onDraftChange { it.copy(downtimeDaysText = value.filter(Char::isDigit)) } },
                label = { Text(stringResource(R.string.agent_downtime)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )
        }
        ExposedDropdownMenuBox(
            expanded = painMenuExpanded,
            onExpandedChange = { painMenuExpanded = !painMenuExpanded },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = selectedPainLabel,
                onValueChange = {},
                readOnly = true,
                singleLine = true,
                label = { Text(stringResource(R.string.agent_pain_tolerance)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = painMenuExpanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = painMenuExpanded,
                onDismissRequest = { painMenuExpanded = false }
            ) {
                painOptions.forEach { (value, label) ->
                    DropdownMenuItem(
                        text = { Text(label, maxLines = 1) },
                        onClick = {
                            onDraftChange { it.copy(painTolerance = value) }
                            painMenuExpanded = false
                        }
                    )
                }
            }
        }
        OutlinedTextField(
            value = draft.preferencesText,
            onValueChange = { value -> onDraftChange { it.copy(preferencesText = value) } },
            label = { Text(stringResource(R.string.agent_preferences)) },
            supportingText = { Text(stringResource(R.string.agent_preferences_help)) },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = draft.excludedProjectsText,
            onValueChange = { value -> onDraftChange { it.copy(excludedProjectsText = value) } },
            label = { Text(stringResource(R.string.agent_excluded)) },
            modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = draft.consentGranted,
                onCheckedChange = { checked -> onDraftChange { it.copy(consentGranted = checked) } }
            )
            Text(stringResource(R.string.agent_profile_consent), color = TextSecondary)
        }
        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = PrimaryDark)
        ) { Text(stringResource(R.string.agent_save_screen)) }
    }
}

@Composable
private fun SafetyScreening(
    screening: AgentSafetyScreeningRequestDto,
    confirmed: Boolean,
    riskReasons: List<String>,
    onScreeningChange: ((AgentSafetyScreeningRequestDto) -> AgentSafetyScreeningRequestDto) -> Unit,
    onConfirmedChange: (Boolean) -> Unit,
    onCreatePlan: () -> Unit
) {
    AgentCard {
        Text(stringResource(R.string.agent_safety_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.agent_safety_desc), color = TextSecondary)
        ScreeningRow(stringResource(R.string.agent_risk_pregnant), screening.pregnantOrNursing) { onScreeningChange { it.copy(pregnantOrNursing = !it.pregnantOrNursing) } }
        ScreeningRow(stringResource(R.string.agent_risk_skin), screening.activeSkinCondition) { onScreeningChange { it.copy(activeSkinCondition = !it.activeSkinCondition) } }
        ScreeningRow(stringResource(R.string.agent_risk_allergy), screening.severeAllergyHistory) { onScreeningChange { it.copy(severeAllergyHistory = !it.severeAllergyHistory) } }
        ScreeningRow(stringResource(R.string.agent_risk_medication), screening.takingRelevantMedication) { onScreeningChange { it.copy(takingRelevantMedication = !it.takingRelevantMedication) } }
        ScreeningRow(stringResource(R.string.agent_risk_recent), screening.recentProcedure) { onScreeningChange { it.copy(recentProcedure = !it.recentProcedure) } }
        ScreeningRow(stringResource(R.string.agent_risk_guarantee), screening.expectsGuaranteedResult) { onScreeningChange { it.copy(expectsGuaranteedResult = !it.expectsGuaranteedResult) } }
        ScreeningRow(stringResource(R.string.agent_risk_distress), screening.severeDistressAboutAppearance) { onScreeningChange { it.copy(severeDistressAboutAppearance = !it.severeDistressAboutAppearance) } }

        if (riskReasons.isNotEmpty()) {
            RiskNotice(riskReasons, Warning)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = confirmed, onCheckedChange = onConfirmedChange)
            Text(stringResource(R.string.agent_safety_confirm), color = TextPrimary)
        }
        Button(
            onClick = onCreatePlan,
            modifier = Modifier.fillMaxWidth(),
            enabled = confirmed,
            colors = ButtonDefaults.buttonColors(containerColor = PrimaryDark)
        ) { Text(stringResource(R.string.agent_generate_plan)) }
    }
}

@Composable
private fun ScreeningRow(label: String, checked: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Text(label, modifier = Modifier.weight(1f), color = TextPrimary)
    }
}

@Composable
private fun PlanSummary(plan: AgentPlanDto, onRestart: () -> Unit, onReturnToChat: () -> Unit) {
    AgentCard(container = Color(0xFFFFF5F8)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.agent_plan_version, plan.version), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(stringResource(if (plan.status == "READY") R.string.agent_plan_ready else R.string.agent_plan_review), color = PrimaryDark)
            }
            TextButton(onClick = onRestart) { Text(stringResource(R.string.agent_reassess)) }
        }
        Text(plan.summary, color = TextPrimary)
        if (plan.totalBudgetMax != null) {
            Text(stringResource(R.string.agent_budget_value, plan.totalBudgetMax.toInt()), fontWeight = FontWeight.SemiBold, color = TextPrimary)
        }
        if (plan.items.isEmpty()) {
            RiskNotice(listOf(stringResource(R.string.agent_no_guess)), Warning)
        }
        OutlinedButton(onClick = onReturnToChat, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.agent_continue_chat)) }
    }
}

@Composable
private fun PlanItemCard(item: AgentPlanItemDto) {
    AgentCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item.stage, color = PrimaryDark, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.weight(1f))
            ConfidenceLabel(item.confidence)
        }
        Text(item.projectName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        SectionText(stringResource(R.string.agent_reason), item.reason)
        SectionText(stringResource(R.string.agent_expected), item.expectedBenefit)
        SectionText(stringResource(R.string.agent_limits), item.limitations)
        BulletSection(stringResource(R.string.agent_risks), item.risks)
        BulletSection(stringResource(R.string.agent_confirm_in_person), item.requiredConfirmations)
    }
}

@Composable
private fun ConfidenceLabel(confidence: String) {
    val text = stringResource(when (confidence) { "HIGH" -> R.string.agent_confidence_high; "MEDIUM" -> R.string.agent_confidence_medium; else -> R.string.agent_confidence_low })
    Box(Modifier.clip(RoundedCornerShape(12.dp)).background(SurfaceVariant).padding(horizontal = 10.dp, vertical = 5.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
    }
}

@Composable
private fun MedicalBoundaryCard() {
    AgentCard(container = SurfaceVariant) {
        Text(stringResource(R.string.agent_next_step), fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.agent_next_step_body), color = TextSecondary)
    }
}

@Composable
private fun SafetyBlock(reasons: List<String>, onRestart: () -> Unit, onReturnToChat: () -> Unit) {
    AgentCard(container = Color(0xFFFFF2F2)) {
        Text(stringResource(R.string.agent_safety_block_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Error)
        Text(stringResource(R.string.agent_safety_block_body), color = TextPrimary)
        RiskNotice(reasons, Error)
        Text(stringResource(R.string.agent_urgent_help), color = TextSecondary)
        OutlinedButton(onClick = onRestart, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.agent_repeat_screen)) }
        TextButton(onClick = onReturnToChat, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.agent_back_to_chat)) }
    }
}

@Composable
private fun HistoryPlanCard(plan: AgentPlanDto, onClick: () -> Unit, onDelete: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = Surface),
        shape = RoundedCornerShape(14.dp)
    ) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.agent_plan_version, plan.version), fontWeight = FontWeight.SemiBold)
                Text(plan.createdAt.take(16).replace('T', ' '), style = MaterialTheme.typography.labelSmall, color = TextHint)
            }
            Text(stringResource(R.string.agent_candidate_count, plan.items.size), color = PrimaryDark)
            TextButton(onClick = onDelete) { Text(stringResource(R.string.agent_delete), color = Error) }
        }
    }
}

@Composable
private fun SectionText(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        Text(body, color = TextSecondary)
    }
}

@Composable
private fun BulletSection(title: String, values: List<String>) {
    if (values.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        values.forEach { Text("• $it", color = TextSecondary) }
    }
}

@Composable
private fun RiskNotice(reasons: List<String>, color: Color) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(color.copy(alpha = 0.10f)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        reasons.forEach { Text("• $it", color = color) }
    }
}

@Composable
private fun AgentCard(container: Color = Surface, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = container),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatBubble(message: AgentChatMessage, onDelete: (AgentChatMessage) -> Unit) {
    var menuExpanded by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val content = message.contentRes?.let { res ->
        message.formatArg?.let { stringResource(res, it) } ?: stringResource(res)
    } ?: message.content

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isUser) Arrangement.End else Arrangement.Start
    ) {
        Box {
            Card(
                colors = CardDefaults.cardColors(containerColor = if (message.isUser) PrimaryDark else Surface),
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = if (message.isUser) 16.dp else 4.dp,
                    bottomEnd = if (message.isUser) 4.dp else 16.dp
                ),
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { menuExpanded = true }
                    )
            ) {
                Text(
                    content,
                    color = if (message.isUser) Color.White else TextPrimary,
                    modifier = Modifier.padding(13.dp)
                )
            }
            DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.agent_copy_reply)) },
                        onClick = {
                            menuExpanded = false
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("AI reply", content))
                            Toast.makeText(context, context.getString(R.string.agent_reply_copied), Toast.LENGTH_SHORT).show()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.agent_delete_reply), color = Error) },
                        onClick = {
                            menuExpanded = false
                            onDelete(message)
                        }
                    )
            }
        }
    }
}

@Composable
private fun PlanningSuggestion(action: String, onStart: () -> Unit) {
    val safety = action == "COMPLETE_SAFETY_SCREENING"
    AgentCard(container = Color(0xFFFFF5F8)) {
        Text(stringResource(if (safety) R.string.agent_safety_prompt_title else R.string.agent_plan_prompt_title), fontWeight = FontWeight.SemiBold)
        Text(stringResource(if (safety) R.string.agent_safety_prompt_body else R.string.agent_plan_prompt_body), color = TextSecondary)
        Button(
            onClick = onStart,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = PrimaryDark)
        ) { Text(stringResource(if (safety) R.string.agent_start_screening else R.string.agent_start_plan)) }
    }
}

@Composable
private fun CatalogReferenceCards(
    items: List<AgentCatalogItemDto>,
    onOpenEntity: (AgentCatalogItemDto) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item ->
            Card(
                onClick = { onOpenEntity(item) },
                colors = CardDefaults.cardColors(containerColor = Surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(item.name, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    if (item.subtitle.isNotBlank()) {
                        Text(item.subtitle, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    }
                    val attributePreview = item.attributes.entries.take(2)
                        .joinToString(stringResource(R.string.agent_list_separator)) { (label, value) -> "$label $value" }
                    if (attributePreview.isNotBlank()) {
                        Text(attributePreview, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                    Text(
                        stringResource(R.string.agent_view_details),
                        style = MaterialTheme.typography.labelLarge,
                        color = PrimaryDark
                    )
                }
            }
        }
    }
}

@Composable
private fun CatalogReportCard(
    report: AgentCatalogReportDto,
    onOpenEntity: (AgentCatalogItemDto) -> Unit,
    onHumanChat: (AgentCatalogItemDto) -> Unit
) {
    AgentCard(container = Color(0xFFF7F9FC)) {
        Text(report.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(report.summary, color = TextSecondary)
        if (report.comparisonDimensions.isNotEmpty()) {
            Text(
                stringResource(R.string.agent_report_dimensions, report.comparisonDimensions.joinToString(stringResource(R.string.agent_list_separator))),
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
        }
        report.items.forEach { item ->
            Card(
                colors = CardDefaults.cardColors(containerColor = Surface),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(item.name, fontWeight = FontWeight.SemiBold, color = TextPrimary)
                    if (item.subtitle.isNotBlank()) Text(item.subtitle, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    item.attributes.forEach { (label, value) ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(label, style = MaterialTheme.typography.bodySmall, color = TextSecondary, modifier = Modifier.weight(0.42f))
                            Text(value, style = MaterialTheme.typography.bodySmall, color = TextPrimary, modifier = Modifier.weight(0.58f))
                        }
                    }
                    if (item.summary.isNotBlank()) Text(item.summary, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { onOpenEntity(item) }) { Text(stringResource(R.string.agent_view_details)) }
                        if (item.canChatWithHuman) {
                            TextButton(onClick = { onHumanChat(item) }) { Text(stringResource(R.string.agent_human_consult)) }
                        }
                    }
                }
            }
        }
        report.warnings.forEach { warning ->
            Text("⚠ $warning", style = MaterialTheme.typography.bodySmall, color = Warning)
        }
    }
}
