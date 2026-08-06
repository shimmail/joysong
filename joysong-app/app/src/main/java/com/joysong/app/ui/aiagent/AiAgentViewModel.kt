package com.joysong.app.ui.aiagent

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.joysong.app.R
import com.joysong.app.data.remote.dto.AgentPlanDto
import com.joysong.app.data.remote.dto.AgentProfileDto
import com.joysong.app.data.remote.dto.AgentProfileRequestDto
import com.joysong.app.data.remote.dto.AgentSafetyScreeningRequestDto
import com.joysong.app.data.remote.dto.AgentCatalogItemDto
import com.joysong.app.data.remote.dto.ChatSessionDto
import com.joysong.app.data.remote.dto.AgentCatalogReportDto
import com.joysong.app.data.repository.AgentRepository
import com.joysong.app.data.repository.ChatRepository
import com.joysong.app.data.repository.MessageCenter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class AgentStep { CHAT, PROFILE, SCREENING, PLAN, SAFETY_BLOCK }

data class AgentChatMessage(
    val id: String,
    val content: String = "",
    val isUser: Boolean,
    @StringRes val contentRes: Int? = null,
    val formatArg: String? = null,
    val isPersisted: Boolean = false
)

data class AgentProfileDraft(
    val city: String = "",
    val goalsText: String = "",
    val budgetMaxText: String = "",
    val downtimeDaysText: String = "",
    val painTolerance: String = "",
    val preferencesText: String = "",
    val excludedProjectsText: String = "",
    val consentGranted: Boolean = false
)

data class AgentPlanningUiState(
    val step: AgentStep = AgentStep.CHAT,
    val messages: List<AgentChatMessage> = listOf(
        AgentChatMessage("welcome", isUser = false, contentRes = R.string.agent_welcome)
    ),
    val chatSessionId: String? = null,
    val planningSuggested: Boolean = false,
    val suggestedAction: String = "NONE",
    val profile: AgentProfileDto? = null,
    val draft: AgentProfileDraft = AgentProfileDraft(),
    val screening: AgentSafetyScreeningRequestDto = AgentSafetyScreeningRequestDto(),
    val screeningConfirmed: Boolean = false,
    val riskReasons: List<String> = emptyList(),
    val plan: AgentPlanDto? = null,
    val planHistory: List<AgentPlanDto> = emptyList(),
    val chatHistory: List<ChatSessionDto> = emptyList(),
    val catalogReport: AgentCatalogReportDto? = null,
    val catalogReportMessageId: String? = null,
    val catalogItemsByMessageId: Map<String, List<AgentCatalogItemDto>> = emptyMap(),
    val isLoading: Boolean = true,
    val isSending: Boolean = false,
    val errorMessage: String? = null,
    @StringRes val errorMessageRes: Int? = null
)

@HiltViewModel
class AiAgentViewModel @Inject constructor(
    private val repository: AgentRepository,
    private val chatRepository: ChatRepository,
    private val messageCenter: MessageCenter
) : ViewModel() {
    private val _uiState = MutableStateFlow(AgentPlanningUiState())
    val uiState: StateFlow<AgentPlanningUiState> = _uiState.asStateFlow()
    private var initializedContextKey: String? = null

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            repository.getProfile()
                .onSuccess { profile ->
                    _uiState.value = _uiState.value.copy(
                        profile = profile,
                        draft = profile.toDraft(),
                        isLoading = false
                    )
                }
                .onFailure { showError(it, R.string.agent_error_load_profile) }
            repository.getPlans().onSuccess { plans ->
                _uiState.value = _uiState.value.copy(planHistory = plans)
            }
            restoreLatestConversation()
        }
    }

    fun initContext(contextType: String, contextId: String, contextName: String) {
        if (contextName.isBlank()) return
        val contextKey = "$contextType:$contextId"
        if (initializedContextKey == contextKey) return
        initializedContextKey = contextKey
        val state = _uiState.value
        val contextMessageId = "context-$contextType-$contextId"
        if (state.messages.none { it.id == contextMessageId }) {
            _uiState.value = state.copy(
                messages = state.messages + AgentChatMessage(
                    contextMessageId,
                    isUser = false,
                    contentRes = R.string.agent_context_hint,
                    formatArg = contextName
                ),
                draft = if (state.draft.goalsText.isBlank()) state.draft.copy(goalsText = contextName) else state.draft
            )
        }
        viewModelScope.launch {
            val summaryRequest = if (java.util.Locale.getDefault().language.startsWith("zh")) {
                "请根据平台数据库信息，简要总结${currentEntityLabel(contextType)}“$contextName”的关键信息、适合关注的方面和必要风险。"
            } else {
                "Briefly summarize the key platform information, relevant considerations, and necessary risks for this ${contextType.replace('_', ' ')}: $contextName."
            }
            chatRepository.createSession(
                persona = "CONSULTANT",
                contextType = contextType.uppercase(),
                contextId = contextId,
                title = contextName
            ).onSuccess { session ->
                _uiState.value = _uiState.value.copy(
                    step = AgentStep.CHAT,
                    chatSessionId = session.id,
                    messages = listOf(
                        AgentChatMessage(contextMessageId, isUser = false, contentRes = R.string.agent_context_hint, formatArg = contextName),
                        AgentChatMessage("context-request-${System.nanoTime()}", summaryRequest, true)
                    ),
                    catalogReport = null,
                    catalogReportMessageId = null,
                    catalogItemsByMessageId = emptyMap(),
                    isSending = true
                )
                sendToSession(session.id, summaryRequest)
            }.onFailure { showChatError(it) }
        }
    }

    private fun currentEntityLabel(contextType: String): String = when (contextType.lowercase()) {
        "institution" -> "机构"
        "doctor" -> "医生"
        "institution_project" -> "机构项目"
        else -> "项目"
    }

    fun sendMessage(content: String) {
        val text = content.trim()
        if (text.isEmpty() || _uiState.value.isSending) return
        val userMessage = AgentChatMessage("local-${System.nanoTime()}", text, true)
        _uiState.value = _uiState.value.copy(
            messages = _uiState.value.messages + userMessage,
            isSending = true,
            planningSuggested = _uiState.value.planningSuggested || suggestsPlanning(text),
            errorMessage = null,
            errorMessageRes = null
        )
        viewModelScope.launch {
            val sessionId = _uiState.value.chatSessionId
            if (sessionId == null) {
                chatRepository.createSession("CONSULTANT", "GENERAL", "", text.take(20))
                    .onSuccess { session ->
                        _uiState.value = _uiState.value.copy(chatSessionId = session.id)
                        sendToSession(session.id, text)
                    }
                    .onFailure { showChatError(it) }
            } else {
                sendToSession(sessionId, text)
            }
        }
    }

    private suspend fun sendToSession(sessionId: String, text: String) {
        // FastAIToken SSE 未确认兼容，暂时使用非流式 JSON 响应。
        chatRepository.sendMessage(sessionId, text)
            .onSuccess { response ->
                val message = response.message
                val newReport = response.catalogReport.displayableOrNull()
                val newItems = response.catalogItems.filter { it.id.isNotBlank() && it.type.isNotBlank() }
                messageCenter.updateAiCard("AI_AGENT", message.content)
                _uiState.value = _uiState.value.copy(
                    messages = _uiState.value.messages + AgentChatMessage(message.id, message.content, false, isPersisted = true),
                    // 普通追问不会清掉当前报告；只有新的有效报告才会替换。
                    catalogReport = newReport ?: _uiState.value.catalogReport,
                    catalogReportMessageId = if (newReport != null) message.id else _uiState.value.catalogReportMessageId,
                    catalogItemsByMessageId = if (newItems.isNotEmpty()) {
                        _uiState.value.catalogItemsByMessageId + (message.id to newItems)
                    } else _uiState.value.catalogItemsByMessageId,
                    planningSuggested = response.nextAction in setOf("START_PLANNING", "COMPLETE_SAFETY_SCREENING"),
                    suggestedAction = response.nextAction,
                    isSending = false
                )
            }
            .onFailure { showChatError(it) }
    }

    private fun showChatError(error: Throwable) {
        _uiState.value = _uiState.value.copy(isSending = false, errorMessage = error.message, errorMessageRes = null)
    }

    fun startPlanning() {
        val nextStep = if (_uiState.value.profile?.confirmedAt != null) AgentStep.SCREENING else AgentStep.PROFILE
        _uiState.value = _uiState.value.copy(step = nextStep, planningSuggested = false, suggestedAction = "NONE")
    }

    fun returnToChat() {
        _uiState.value = _uiState.value.copy(step = AgentStep.CHAT, riskReasons = emptyList())
    }

    fun updateDraft(transform: (AgentProfileDraft) -> AgentProfileDraft) {
        _uiState.value = _uiState.value.copy(draft = transform(_uiState.value.draft), errorMessage = null)
    }

    fun saveAndConfirmProfile() {
        val draft = _uiState.value.draft
        val goals = splitValues(draft.goalsText)
        val budgetMax = draft.budgetMaxText.toDoubleOrNull()
        val downtime = draft.downtimeDaysText.toIntOrNull()
        val validationError = when {
            draft.budgetMaxText.isNotBlank() && (budgetMax == null || budgetMax <= 0) -> R.string.agent_error_budget
            draft.downtimeDaysText.isNotBlank() && (downtime == null || downtime !in 0..365) -> R.string.agent_error_downtime
            !draft.consentGranted -> R.string.agent_error_consent
            else -> null
        }
        if (validationError != null) {
            _uiState.value = _uiState.value.copy(errorMessage = null, errorMessageRes = validationError)
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            val request = AgentProfileRequestDto(
                city = draft.city.trim(),
                goals = goals,
                budgetMax = budgetMax,
                acceptableDowntimeDays = downtime,
                painTolerance = draft.painTolerance.trim(),
                preferences = splitValues(draft.preferencesText),
                excludedProjects = splitValues(draft.excludedProjectsText)
            )
            repository.updateProfile(request)
                .onSuccess {
                    repository.confirmProfile()
                        .onSuccess { confirmed ->
                            _uiState.value = _uiState.value.copy(
                                profile = confirmed,
                                draft = confirmed.toDraft(),
                                step = AgentStep.SCREENING,
                                isLoading = false
                            )
                        }
                        .onFailure { showError(it, R.string.agent_error_confirm_profile) }
                }
                .onFailure { showError(it, R.string.agent_error_save_profile) }
        }
    }

    fun updateScreening(transform: (AgentSafetyScreeningRequestDto) -> AgentSafetyScreeningRequestDto) {
        _uiState.value = _uiState.value.copy(
            screening = transform(_uiState.value.screening),
            screeningConfirmed = false,
            riskReasons = emptyList(),
            errorMessage = null
        )
    }

    fun setScreeningConfirmed(confirmed: Boolean) {
        _uiState.value = _uiState.value.copy(screeningConfirmed = confirmed, errorMessage = null)
    }

    fun createPlan() {
        if (!_uiState.value.screeningConfirmed) {
            _uiState.value = _uiState.value.copy(errorMessage = null, errorMessageRes = R.string.agent_error_screening_confirm)
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null, riskReasons = emptyList())
            repository.createAssessment(_uiState.value.screening)
                .onSuccess { assessment ->
                    when (assessment.nextAction) {
                        "CREATE_PLAN" -> repository.createPlan(assessment.id)
                            .onSuccess { plan ->
                                _uiState.value = _uiState.value.copy(
                                    step = AgentStep.PLAN,
                                    plan = plan,
                                    planHistory = listOf(plan) + _uiState.value.planHistory.filterNot { it.id == plan.id },
                                    isLoading = false
                                )
                            }
                            .onFailure { showError(it, R.string.agent_error_create_plan) }
                        "HUMAN_REVIEW" -> _uiState.value = _uiState.value.copy(
                            step = AgentStep.SAFETY_BLOCK,
                            riskReasons = assessment.riskReasons,
                            isLoading = false
                        )
                        else -> _uiState.value = _uiState.value.copy(
                            step = AgentStep.SCREENING,
                            riskReasons = assessment.riskReasons.ifEmpty {
                                assessment.missingFields
                            },
                            isLoading = false
                        )
                    }
                }
                .onFailure { showError(it, R.string.agent_error_assessment) }
        }
    }

    fun editProfile() {
        _uiState.value = _uiState.value.copy(step = AgentStep.PROFILE, plan = null, riskReasons = emptyList())
    }

    fun restartScreening() {
        _uiState.value = _uiState.value.copy(
            step = AgentStep.SCREENING,
            screening = AgentSafetyScreeningRequestDto(),
            screeningConfirmed = false,
            riskReasons = emptyList(),
            plan = null
        )
    }

    fun selectPlan(plan: AgentPlanDto) {
        _uiState.value = _uiState.value.copy(step = AgentStep.PLAN, plan = plan)
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null, errorMessageRes = null)
    }

    fun deleteAgentMessage(message: AgentChatMessage) {
        if (!message.isPersisted) {
            removeMessageFromState(message.id)
            return
        }
        viewModelScope.launch {
            chatRepository.deleteMessage(message.id)
                .onSuccess {
                    removeMessageFromState(message.id)
                }
                .onFailure { showError(it, R.string.agent_error_delete_message) }
        }
    }

    private fun removeMessageFromState(messageId: String) {
        val current = _uiState.value
        val removesReport = current.catalogReportMessageId == messageId
        _uiState.value = current.copy(
            messages = current.messages.filterNot { it.id == messageId },
            catalogItemsByMessageId = current.catalogItemsByMessageId - messageId,
            catalogReport = if (removesReport) null else current.catalogReport,
            catalogReportMessageId = if (removesReport) null else current.catalogReportMessageId
        )
    }

    fun refreshChatHistory() {
        viewModelScope.launch {
            chatRepository.getSessions("CONSULTANT").onSuccess { sessions ->
                _uiState.value = _uiState.value.copy(chatHistory = sessions.sortedByDescending { it.updatedAt })
            }
        }
    }

    /** Restore the most recently used AI session when the page is entered again. */
    private suspend fun restoreLatestConversation() {
        chatRepository.getSessions("CONSULTANT")
            .onSuccess { sessions ->
                val sorted = sessions.sortedByDescending { it.updatedAt }
                _uiState.value = _uiState.value.copy(chatHistory = sorted)
                sorted.firstOrNull()?.let { restoreConversation(it, showLoading = false) }
                    ?: run { _uiState.value = _uiState.value.copy(isLoading = false) }
            }
            .onFailure { showError(it, R.string.agent_error_load_history) }
    }

    fun openConversation(session: ChatSessionDto) {
        viewModelScope.launch {
            restoreConversation(session, showLoading = true)
        }
    }

    private suspend fun restoreConversation(session: ChatSessionDto, showLoading: Boolean) {
        if (showLoading) _uiState.value = _uiState.value.copy(isLoading = true)
        chatRepository.getMessages(session.id)
            .onSuccess { messages ->
                val restoredMessages = messages.map { message ->
                    AgentChatMessage(
                        id = message.id,
                        content = message.content,
                        isUser = message.role.equals("USER", true),
                        isPersisted = true
                    )
                }
                _uiState.value = _uiState.value.copy(
                    step = AgentStep.CHAT,
                    chatSessionId = session.id,
                    messages = restoredMessages.ifEmpty { welcomeMessages() },
                    catalogReport = null,
                    catalogReportMessageId = null,
                    catalogItemsByMessageId = emptyMap(),
                    isLoading = false,
                    planningSuggested = false
                )

                // The report is derived from live catalog data. Rebuild it from the
                // latest report-related user request so prices and availability stay current.
                val reportRequestIndex = messages.indexOfLast { message ->
                    message.role.equals("USER", true) && suggestsReport(message.content)
                }
                messages.getOrNull(reportRequestIndex)?.let { reportRequest ->
                    val reportMessageId = messages.drop(reportRequestIndex + 1)
                        .firstOrNull { it.role.equals("ASSISTANT", true) }?.id
                    repository.createCatalogReport(reportRequest.content).onSuccess { report ->
                        if (_uiState.value.chatSessionId == session.id) {
                            val displayable = report.displayableOrNull()
                            _uiState.value = _uiState.value.copy(
                                catalogReport = displayable,
                                catalogReportMessageId = displayable?.let { reportMessageId }
                            )
                        }
                    }
                }
            }
            .onFailure { showError(it, R.string.agent_error_load_history) }
    }

    fun newConversation() {
        _uiState.value = _uiState.value.copy(
            step = AgentStep.CHAT,
            chatSessionId = null,
            messages = welcomeMessages(),
            catalogReport = null,
            catalogReportMessageId = null,
            catalogItemsByMessageId = emptyMap(),
            planningSuggested = false,
            isSending = false
        )
    }

    fun deleteConversation(session: ChatSessionDto) {
        viewModelScope.launch {
            chatRepository.deleteSession(session.id)
                .onSuccess {
                    val wasCurrent = _uiState.value.chatSessionId == session.id
                    _uiState.value = _uiState.value.copy(
                        chatHistory = _uiState.value.chatHistory.filterNot { it.id == session.id }
                    )
                    if (wasCurrent) newConversation()
                }
                .onFailure { showError(it, R.string.agent_error_delete_history) }
        }
    }

    fun clearConversationHistory() {
        viewModelScope.launch {
            chatRepository.clearSessions("CONSULTANT")
                .onSuccess {
                    _uiState.value = _uiState.value.copy(chatHistory = emptyList())
                    newConversation()
                }
                .onFailure { showError(it, R.string.agent_error_clear_history) }
        }
    }

    fun deletePlan(plan: AgentPlanDto) {
        viewModelScope.launch {
            repository.deletePlan(plan.id)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        planHistory = _uiState.value.planHistory.filterNot { it.id == plan.id },
                        plan = if (_uiState.value.plan?.id == plan.id) null else _uiState.value.plan,
                        step = if (_uiState.value.plan?.id == plan.id) AgentStep.CHAT else _uiState.value.step
                    )
                }
                .onFailure { showError(it, R.string.agent_error_delete_plan) }
        }
    }

    fun clearPlanHistory() {
        viewModelScope.launch {
            repository.clearPlans()
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        planHistory = emptyList(), plan = null,
                        step = if (_uiState.value.step == AgentStep.PLAN) AgentStep.CHAT else _uiState.value.step
                    )
                }
                .onFailure { showError(it, R.string.agent_error_clear_plans) }
        }
    }

    fun clearConversation() {
        val sessionId = _uiState.value.chatSessionId
        if (sessionId == null) {
            _uiState.value = _uiState.value.copy(messages = welcomeMessages(), planningSuggested = false)
            return
        }
        viewModelScope.launch {
            chatRepository.clearMessages(sessionId)
                .onSuccess {
                    _uiState.value = _uiState.value.copy(
                        messages = welcomeMessages(), planningSuggested = false,
                        catalogReport = null, catalogReportMessageId = null,
                        catalogItemsByMessageId = emptyMap()
                    )
                    refreshChatHistory()
                }
                .onFailure { showError(it, R.string.agent_error_clear_chat) }
        }
    }

    private fun welcomeMessages() = listOf(
        AgentChatMessage("welcome-${System.nanoTime()}", isUser = false, contentRes = R.string.agent_welcome)
    )

    private fun showError(error: Throwable, @StringRes fallback: Int) {
        val message = error.message?.takeIf { it.isNotBlank() }
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            errorMessage = message,
            errorMessageRes = if (message == null) fallback else null
        )
    }

    private fun suggestsPlanning(text: String): Boolean {
        val keywords = listOf("规划", "方案", "推荐项目", "适合我", "做什么", "怎么选", "预算", "plan", "recommend", "suitable", "budget", "which treatment")
        return keywords.any { text.contains(it, ignoreCase = true) }
    }

    private fun suggestsReport(text: String): Boolean {
        val keywords = listOf(
            "对比", "比较", "区别", "哪个好", "哪家好", "两家", "三家", "四家",
            "compare", "comparison", "versus", " vs ", "which is better", "which clinic is better", "which doctor is better"
        )
        return keywords.any { text.contains(it, ignoreCase = true) }
    }

    private fun AgentCatalogReportDto?.displayableOrNull(): AgentCatalogReportDto? = this?.takeIf { report ->
        report.items.isNotEmpty() && report.mode.uppercase() in setOf("COMPARISON", "SUMMARY")
    }

    private fun AgentProfileDto.toDraft() = AgentProfileDraft(
        city = city,
        goalsText = goals.joinToString("、"),
        budgetMaxText = budgetMax?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() } ?: "",
        downtimeDaysText = acceptableDowntimeDays?.toString() ?: "",
        painTolerance = painTolerance,
        preferencesText = preferences.joinToString("、"),
        excludedProjectsText = excludedProjects.joinToString("、"),
        consentGranted = consentVersion.isNotBlank()
    )

    private fun splitValues(value: String): List<String> = value
        .split('、', ',', '，', ';', '；', '\n')
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()
}
