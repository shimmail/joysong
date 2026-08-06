package com.joysong.app.ui.detail

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.joysong.app.data.local.TokenManager
import com.joysong.app.domain.model.Comment
import com.joysong.app.domain.model.Diary
import com.joysong.app.domain.model.Doctor
import com.joysong.app.domain.model.DoctorInstitutionProjectInfo
import com.joysong.app.domain.model.ExpertArticle
import com.joysong.app.domain.model.FavoriteType
import com.joysong.app.domain.model.Institution
import com.joysong.app.domain.model.InstitutionProject
import com.joysong.app.domain.model.InstitutionProjectInfo
import com.joysong.app.domain.model.Project
import com.joysong.app.domain.model.Review
import com.joysong.app.data.repository.ConsultationFeeRepository
import com.joysong.app.domain.repository.CommentRepository
import com.joysong.app.domain.repository.DiscoverRepository
import com.joysong.app.domain.repository.InstitutionProjectDetailInfo
import com.joysong.app.domain.repository.ProjectDetail
import com.joysong.app.domain.repository.FavoriteRepository
import com.joysong.app.domain.repository.LikeRepository
import com.joysong.app.domain.repository.TranslationRepository
import com.joysong.app.ui.translation.TranslationUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class DetailUiState<out T> {
    data object Loading : DetailUiState<Nothing>()
    data class Success<T>(val data: T) : DetailUiState<T>()
    data class Error(val message: String) : DetailUiState<Nothing>()
}

@HiltViewModel
class DetailViewModel @Inject constructor(
    private val discoverRepository: DiscoverRepository,
    private val likeRepository: LikeRepository,
    private val commentRepository: CommentRepository,
    private val favoriteRepository: FavoriteRepository,
    private val tokenManager: TokenManager,
    private val consultationFeeRepository: ConsultationFeeRepository,
    private val translationRepository: TranslationRepository
) : ViewModel() {

    // 点赞状态
    val isLiked = mutableStateOf(false)
    val likeCount = mutableIntStateOf(0)

    // 收藏状态
    val isFavorited = mutableStateOf(false)
    val favoriteCount = mutableIntStateOf(0)

    // 评论
    val comments = mutableStateOf<List<Comment>>(emptyList())
    val commentCount = mutableIntStateOf(0)
    // 回复列表：parentId -> replies
    val repliesMap = mutableStateOf<Map<String, List<Comment>>>(emptyMap())

    // 按内容 ID 保存翻译结果及当前显示原文/译文的状态。
    val translationStates = mutableStateOf<Map<String, TranslationUiState>>(emptyMap())

    fun translateContent(key: String, text: String, contentType: String) {
        val current = translationStates.value[key]
        if (text.isBlank() || current is TranslationUiState.Loading) return
        if (current is TranslationUiState.Success) {
            translationStates.value = translationStates.value +
                (key to current.copy(showingTranslation = true))
            return
        }
        if (!translationRepository.shouldTranslate(text)) return
        translationStates.value = translationStates.value + (key to TranslationUiState.Loading)
        viewModelScope.launch {
            translationRepository.translate(
                text = text,
                contentType = contentType
            ).onSuccess { translation ->
                translationStates.value = translationStates.value +
                    (key to TranslationUiState.Success(translation.translatedText))
            }.onFailure { error ->
                translationStates.value = translationStates.value +
                    (key to TranslationUiState.Error(error.message ?: "AI翻译暂时不可用"))
            }
        }
    }

    fun showOriginal(key: String) {
        val current = translationStates.value[key] as? TranslationUiState.Success ?: return
        translationStates.value = translationStates.value +
            (key to current.copy(showingTranslation = false))
    }

    fun showTranslation(key: String) {
        val current = translationStates.value[key] as? TranslationUiState.Success ?: return
        translationStates.value = translationStates.value +
            (key to current.copy(showingTranslation = true))
    }

    // 折叠的评论 ID 集合（持久化）
    private val _collapsedComments = MutableStateFlow<Set<String>>(emptySet())
    val collapsedComments: StateFlow<Set<String>> = _collapsedComments.asStateFlow()

    // 重新计算评论总数（父评论 + 所有子回复）
    private fun recalcCommentCount() {
        commentCount.intValue = comments.value.size + repliesMap.value.values.sumOf { it.size }
    }

    /** 加载持久化的折叠评论 ID */
    fun loadCollapsedComments() {
        viewModelScope.launch {
            _collapsedComments.value = tokenManager.getCollapsedComments()
        }
    }

    /** 切换评论折叠状态并持久化 */
    fun toggleCommentCollapse(commentId: String) {
        val current = _collapsedComments.value.toMutableSet()
        if (current.contains(commentId)) {
            current.remove(commentId)
        } else {
            current.add(commentId)
        }
        _collapsedComments.value = current
        viewModelScope.launch {
            tokenManager.saveCollapsedComments(current)
        }
    }

    // 操作状态
    val isLikeLoading = mutableStateOf(false)
    val isCommentLoading = mutableStateOf(false)
    private val _isFavoriteLoading = mutableStateOf(false)

    // 当前用户ID
    val currentUserId = mutableStateOf("")

    init {
        viewModelScope.launch {
            currentUserId.value = tokenManager.getUserId() ?: ""
        }
    }

    fun checkLikeStatus(diaryId: String) {
        viewModelScope.launch {
            likeRepository.isLiked("diary", diaryId)
                .onSuccess { response ->
                    isLiked.value = response.liked
                    likeCount.intValue = response.count.toInt()
                }
        }
    }

    fun checkFavoriteStatus(diaryId: String) {
        viewModelScope.launch {
            favoriteRepository.isFavorite(FavoriteType.DIARY, diaryId)
                .onSuccess { result ->
                    isFavorited.value = result.favorited
                    favoriteCount.intValue = result.count.toInt()
                }
        }
    }

    // --- 通用收藏状态（用于机构/项目/医生详情页） ---
    private val _genericFavorited = mutableStateOf(false)
    val genericFavorited: androidx.compose.runtime.State<Boolean> get() = _genericFavorited

    private val _genericFavoriteLoading = mutableStateOf(false)
    val genericFavoriteLoading: androidx.compose.runtime.State<Boolean> get() = _genericFavoriteLoading

    // --- 机构内项目独立收藏状态（不影响主页面的 genericFavorited） ---
    private val _projectFavoriteStates = mutableStateOf<Map<String, Boolean>>(emptyMap())
    val projectFavoriteStates: androidx.compose.runtime.State<Map<String, Boolean>> get() = _projectFavoriteStates

    fun checkProjectFavorite(projectId: String) {
        viewModelScope.launch {
            favoriteRepository.isFavorite(FavoriteType.PROJECT, projectId)
                .onSuccess { result ->
                    _projectFavoriteStates.value = _projectFavoriteStates.value + (projectId to result.favorited)
                }
        }
    }

    fun toggleProjectFavorite(projectId: String, projectName: String, projectImage: String) {
        val currentMap = _projectFavoriteStates.value
        val wasFavorited = currentMap[projectId] ?: false
        _projectFavoriteStates.value = currentMap + (projectId to !wasFavorited)
        viewModelScope.launch {
            val result = if (wasFavorited) {
                favoriteRepository.removeFavorite(FavoriteType.PROJECT, projectId)
            } else {
                favoriteRepository.addFavorite(FavoriteType.PROJECT, projectId, projectName, projectImage)
            }
            result.onFailure { _projectFavoriteStates.value = currentMap }
        }
    }

    fun checkGenericFavoriteStatus(type: FavoriteType, targetId: String) {
        viewModelScope.launch {
            favoriteRepository.isFavorite(type, targetId)
                .onSuccess { result -> _genericFavorited.value = result.favorited }
        }
    }

    fun toggleGenericFavorite(type: FavoriteType, targetId: String, targetName: String = "", targetImage: String = "") {
        if (_genericFavoriteLoading.value) return
        val wasFavorited = _genericFavorited.value
        _genericFavorited.value = !wasFavorited
        _genericFavoriteLoading.value = true
        viewModelScope.launch {
            try {
                val result = if (wasFavorited) {
                    favoriteRepository.removeFavorite(type, targetId)
                } else {
                    favoriteRepository.addFavorite(type, targetId, targetName, targetImage)
                }
                result.onFailure { _genericFavorited.value = wasFavorited }
            } finally {
                _genericFavoriteLoading.value = false
            }
        }
    }

    fun loadComments(diaryId: String) {
        viewModelScope.launch {
            commentRepository.getComments(diaryId)
                .onSuccess { list ->
                    comments.value = list
                    // 自动加载所有父评论的子回复
                    val newReplies = mutableMapOf<String, List<Comment>>()
                    list.forEach { comment ->
                        commentRepository.getReplies(comment.id)
                            .onSuccess { replies ->
                                if (replies.isNotEmpty()) {
                                    newReplies[comment.id] = replies
                                }
                            }
                    }
                    if (newReplies.isNotEmpty()) {
                        repliesMap.value = repliesMap.value + newReplies
                    }
                    recalcCommentCount()
                }
        }
    }

    fun loadReplies(parentId: String) {
        viewModelScope.launch {
            commentRepository.getReplies(parentId)
                .onSuccess { replies ->
                    repliesMap.value = repliesMap.value + (parentId to replies)
                    recalcCommentCount()
                }
        }
    }

    fun toggleLike(diaryId: String) {
        if (isLikeLoading.value) return
        val wasLiked = isLiked.value
        val oldCount = likeCount.intValue
        // 乐观更新
        isLiked.value = !wasLiked
        likeCount.intValue = if (wasLiked) oldCount - 1 else oldCount + 1
        isLikeLoading.value = true

        viewModelScope.launch {
            try {
                val result = if (wasLiked) {
                    likeRepository.unlike("diary", diaryId)
                } else {
                    likeRepository.like("diary", diaryId)
                }
                result.onFailure {
                    // 回滚
                    isLiked.value = wasLiked
                    likeCount.intValue = oldCount
                }
            } finally {
                isLikeLoading.value = false
            }
        }
    }

    fun toggleFavorite(diaryId: String, targetName: String = "", targetImage: String = "") {
        if (_isFavoriteLoading.value) return
        val wasFavorited = isFavorited.value
        val oldCount = favoriteCount.intValue
        // 乐观更新
        isFavorited.value = !wasFavorited
        favoriteCount.intValue = if (wasFavorited) oldCount - 1 else oldCount + 1
        _isFavoriteLoading.value = true

        viewModelScope.launch {
            try {
                val result = if (wasFavorited) {
                    favoriteRepository.removeFavorite(FavoriteType.DIARY, diaryId)
                } else {
                    favoriteRepository.addFavorite(FavoriteType.DIARY, diaryId, targetName, targetImage)
                }
                result.onFailure {
                    // 回滚
                    isFavorited.value = wasFavorited
                    favoriteCount.intValue = oldCount
                }
            } finally {
                _isFavoriteLoading.value = false
            }
        }
    }

    fun addComment(diaryId: String, content: String, parentId: String? = null, replyToUserId: String? = null) {
        if (isCommentLoading.value || content.isBlank()) return
        isCommentLoading.value = true
        viewModelScope.launch {
            commentRepository.addComment(diaryId, content, parentId, replyToUserId)
                .onSuccess {
                    isCommentLoading.value = false
                    if (parentId != null) {
                        // 回复评论：刷新该父评论的回复列表
                        loadReplies(parentId)
                    } else {
                        // 顶级评论：刷新评论列表
                        loadComments(diaryId)
                    }
                }
                .onFailure {
                    isCommentLoading.value = false
                }
        }
    }

    fun deleteComment(commentId: String, diaryId: String, parentId: String? = null) {
        viewModelScope.launch {
            commentRepository.deleteComment(commentId)
                .onSuccess {
                    // 先刷新顶级评论列表，等待完成后再刷新回复列表
                    commentRepository.getComments(diaryId)
                        .onSuccess { list ->
                            comments.value = list
                        }
                    if (parentId != null) {
                        // 删除的是回复，顺序刷新父评论的回复列表
                        commentRepository.getReplies(parentId)
                            .onSuccess { replies ->
                                repliesMap.value = repliesMap.value + (parentId to replies)
                            }
                    }
                    recalcCommentCount()
                }
        }
    }

    /**
     * 点赞/取消点赞评论
     */
    fun likeComment(commentId: String) {
        viewModelScope.launch {
            likeRepository.isLiked("comment", commentId)
                .onSuccess { response ->
                    if (response.liked) {
                        likeRepository.unlike("comment", commentId)
                            .onSuccess { updateCommentLikeState(commentId, liked = false, delta = -1) }
                    } else {
                        likeRepository.like("comment", commentId)
                            .onSuccess { updateCommentLikeState(commentId, liked = true, delta = 1) }
                    }
                }
        }
    }

    // 更新评论的点赞状态（本地乐观更新）
    private fun updateCommentLikeState(commentId: String, liked: Boolean, delta: Int) {
        comments.value = comments.value.map { c ->
            if (c.id == commentId) c.copy(isLiked = liked, likeCount = (c.likeCount + delta).coerceAtLeast(0)) else c
        }
        val newRepliesMap = repliesMap.value.toMutableMap()
        for ((parentId, replies) in newRepliesMap) {
            newRepliesMap[parentId] = replies.map { r ->
                if (r.id == commentId) r.copy(isLiked = liked, likeCount = (r.likeCount + delta).coerceAtLeast(0)) else r
            }
        }
        repliesMap.value = newRepliesMap
    }

    private val _project = MutableStateFlow<DetailUiState<Project>>(DetailUiState.Loading)
    val project: StateFlow<DetailUiState<Project>> = _project

    private val _institution = MutableStateFlow<DetailUiState<Institution>>(DetailUiState.Loading)
    val institution: StateFlow<DetailUiState<Institution>> = _institution

    private val _doctor = MutableStateFlow<DetailUiState<Doctor>>(DetailUiState.Loading)
    val doctor: StateFlow<DetailUiState<Doctor>> = _doctor

    private val _article = MutableStateFlow<DetailUiState<ExpertArticle>>(DetailUiState.Loading)
    val article: StateFlow<DetailUiState<ExpertArticle>> = _article

    private val _diary = MutableStateFlow<DetailUiState<Diary>>(DetailUiState.Loading)
    val diary: StateFlow<DetailUiState<Diary>> = _diary

    // Doctor related data
    private val _doctorProjects = MutableStateFlow<List<DoctorInstitutionProjectInfo>>(emptyList())
    val doctorProjects: StateFlow<List<DoctorInstitutionProjectInfo>> = _doctorProjects

    private val _doctorDiaries = MutableStateFlow<List<Diary>>(emptyList())
    val doctorDiaries: StateFlow<List<Diary>> = _doctorDiaries

    private val _doctorInstitution = MutableStateFlow<Institution?>(null)
    val doctorInstitution: StateFlow<Institution?> = _doctorInstitution

    private val _doctorInstitutions = MutableStateFlow<List<Institution>>(emptyList())
    val doctorInstitutions: StateFlow<List<Institution>> = _doctorInstitutions

    // Project related data
    private val _projectInstitutions = MutableStateFlow<List<Pair<InstitutionProject, Institution>>>(emptyList())
    val projectInstitutions: StateFlow<List<Pair<InstitutionProject, Institution>>> = _projectInstitutions

    private val _projectDiaries = MutableStateFlow<List<Diary>>(emptyList())
    val projectDiaries: StateFlow<List<Diary>> = _projectDiaries

    // Institution related data（机构项目现为 InstitutionProjectInfo，含机构特定价格）
    private val _institutionProjects = MutableStateFlow<List<InstitutionProjectInfo>>(emptyList())
    val institutionProjects: StateFlow<List<InstitutionProjectInfo>> = _institutionProjects

    private val _institutionDoctors = MutableStateFlow<List<Doctor>>(emptyList())
    val institutionDoctors: StateFlow<List<Doctor>> = _institutionDoctors

    private val _institutionDiaries = MutableStateFlow<List<Diary>>(emptyList())
    val institutionDiaries: StateFlow<List<Diary>> = _institutionDiaries

    private val _institutionReviews = MutableStateFlow<List<Review>>(emptyList())
    val institutionReviews: StateFlow<List<Review>> = _institutionReviews

    // Institution Project Detail
    private val _institutionProjectDetail = MutableStateFlow<DetailUiState<InstitutionProjectDetailInfo>>(DetailUiState.Loading)
    val institutionProjectDetail: StateFlow<DetailUiState<InstitutionProjectDetailInfo>> = _institutionProjectDetail

    // Institution Project Doctors (可预约医生)
    private val _ipDoctors = MutableStateFlow<List<Doctor>>(emptyList())
    val ipDoctors: StateFlow<List<Doctor>> = _ipDoctors

    // Consultation fee for institution project detail
    private val _consultationFee = MutableStateFlow(0.0)
    val consultationFee: StateFlow<Double> = _consultationFee

    // Refresh state
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    fun loadProject(id: String) {
        viewModelScope.launch {
            _project.value = DetailUiState.Loading
            discoverRepository.getProjectById(id)
                .onSuccess { detail ->
                    _project.value = DetailUiState.Success(detail.project)
                    _projectInstitutions.value = detail.institutionProjects
                    _projectDiaries.value = detail.diaries
                }
                .onFailure { _project.value = DetailUiState.Error(it.message ?: "加载失败") }
        }
    }

    fun loadInstitution(id: String) {
        viewModelScope.launch {
            _institution.value = DetailUiState.Loading
            discoverRepository.getInstitutionById(id)
                .onSuccess { detail ->
                    _institution.value = DetailUiState.Success(detail.institution)
                    _institutionProjects.value = detail.projects
                    _institutionDoctors.value = detail.doctors
                    _institutionDiaries.value = detail.diaries
                    _institutionReviews.value = detail.reviews
                }
                .onFailure { _institution.value = DetailUiState.Error(it.message ?: "加载失败") }
        }
    }

    fun loadDoctor(id: String) {
        viewModelScope.launch {
            _doctor.value = DetailUiState.Loading
            discoverRepository.getDoctorById(id)
                .onSuccess { detail ->
                    _doctor.value = DetailUiState.Success(detail.doctor)
                    _doctorProjects.value = detail.institutionProjects
                    _doctorDiaries.value = detail.diaries
                    _doctorInstitution.value = detail.institution
                    _doctorInstitutions.value = detail.institutions.ifEmpty { listOfNotNull(detail.institution) }
                }
                .onFailure { _doctor.value = DetailUiState.Error(it.message ?: "加载失败") }
        }
    }

    fun loadArticle(id: String) {
        viewModelScope.launch {
            _article.value = DetailUiState.Loading
            discoverRepository.getArticleById(id)
                .onSuccess { _article.value = DetailUiState.Success(it) }
                .onFailure { _article.value = DetailUiState.Error(it.message ?: "加载失败") }
        }
    }

    fun loadDiary(id: String) {
        viewModelScope.launch {
            _diary.value = DetailUiState.Loading
            discoverRepository.getDiaryById(id)
                .onSuccess { _diary.value = DetailUiState.Success(it) }
                .onFailure { _diary.value = DetailUiState.Error(it.message ?: "加载失败") }
        }
    }

    /**
     * 下拉刷新日记详情（刷新日记内容 + 评论列表）
     */
    fun refreshDiary(id: String) {
        _isRefreshing.value = true
        viewModelScope.launch {
            try {
                // 刷新日记内容（不设置 Loading，避免闪烁）
                discoverRepository.getDiaryById(id)
                    .onSuccess { _diary.value = DetailUiState.Success(it) }
                // 刷新评论
                loadComments(id)
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun refreshProject(id: String) {
        _isRefreshing.value = true
        viewModelScope.launch {
            try {
                discoverRepository.getProjectById(id)
                    .onSuccess { detail ->
                        _project.value = DetailUiState.Success(detail.project)
                        _projectInstitutions.value = detail.institutionProjects
                        _projectDiaries.value = detail.diaries
                    }
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun refreshInstitution(id: String) {
        _isRefreshing.value = true
        viewModelScope.launch {
            try {
                discoverRepository.getInstitutionById(id)
                    .onSuccess { detail ->
                        _institution.value = DetailUiState.Success(detail.institution)
                        _institutionProjects.value = detail.projects
                        _institutionDoctors.value = detail.doctors
                        _institutionDiaries.value = detail.diaries
                        _institutionReviews.value = detail.reviews
                    }
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun refreshDoctor(id: String) {
        _isRefreshing.value = true
        viewModelScope.launch {
            try {
                discoverRepository.getDoctorById(id)
                    .onSuccess { detail ->
                        _doctor.value = DetailUiState.Success(detail.doctor)
                        _doctorProjects.value = detail.institutionProjects
                        _doctorDiaries.value = detail.diaries
                        _doctorInstitution.value = detail.institution
                        _doctorInstitutions.value = detail.institutions.ifEmpty { listOfNotNull(detail.institution) }
                    }
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    fun loadInstitutionProjectDetail(institutionId: String, projectId: String) {
        viewModelScope.launch {
            _institutionProjectDetail.value = DetailUiState.Loading
            discoverRepository.getInstitutionProjectDetail(institutionId, projectId)
                .onSuccess { detail ->
                    _institutionProjectDetail.value = DetailUiState.Success(detail)
                    // 加载可预约医生列表
                    discoverRepository.getInstitutionProjectDoctors(detail.institutionProject.id)
                        .onSuccess { doctors -> _ipDoctors.value = doctors }
                        .onFailure { _ipDoctors.value = emptyList() }
                    // 异步加载面诊费
                    fetchConsultationFeeForDetail(detail.institutionProject.id)
                }
                .onFailure { _institutionProjectDetail.value = DetailUiState.Error(it.message ?: "加载失败") }
        }
    }

    private fun fetchConsultationFeeForDetail(institutionProjectId: String) {
        viewModelScope.launch {
            try {
                val ipDoctors = _ipDoctors.value
                val doctorId = ipDoctors.firstOrNull()?.id ?: ""
                if (doctorId.isNotBlank()) {
                    consultationFeeRepository.getConsultationFee(doctorId, institutionProjectId)
                        .onSuccess { fee -> _consultationFee.value = fee }
                        .onFailure { _consultationFee.value = 0.0 }
                }
            } catch (_: Exception) {
                _consultationFee.value = 0.0
            }
        }
    }

    // --- 文章收藏状态 ---
    fun checkArticleFavoriteStatus(articleId: String) {
        viewModelScope.launch {
            favoriteRepository.isFavorite(FavoriteType.ARTICLE, articleId)
                .onSuccess { result ->
                    isFavorited.value = result.favorited
                    favoriteCount.intValue = result.count.toInt()
                }
        }
    }

    fun toggleArticleFavorite(articleId: String, targetName: String = "", targetImage: String = "") {
        if (_isFavoriteLoading.value) return
        val wasFavorited = isFavorited.value
        val oldCount = favoriteCount.intValue
        // 乐观更新
        isFavorited.value = !wasFavorited
        favoriteCount.intValue = if (wasFavorited) oldCount - 1 else oldCount + 1
        _isFavoriteLoading.value = true

        viewModelScope.launch {
            try {
                val result = if (wasFavorited) {
                    favoriteRepository.removeFavorite(FavoriteType.ARTICLE, articleId)
                } else {
                    favoriteRepository.addFavorite(FavoriteType.ARTICLE, articleId, targetName, targetImage)
                }
                result.onFailure {
                    // 回滚
                    isFavorited.value = wasFavorited
                    favoriteCount.intValue = oldCount
                }
            } finally {
                _isFavoriteLoading.value = false
            }
        }
    }
}

@HiltViewModel
class ProjectAllDiariesViewModel @Inject constructor(
    private val discoverRepository: DiscoverRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow<DetailUiState<List<Diary>>>(DetailUiState.Loading)
    val uiState: StateFlow<DetailUiState<List<Diary>>> = _uiState

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    fun load(projectId: String) {
        viewModelScope.launch {
            _uiState.value = DetailUiState.Loading
            discoverRepository.getProjectById(projectId)
                .onSuccess { detail ->
                    _uiState.value = DetailUiState.Success(detail.diaries)
                }
                .onFailure { _uiState.value = DetailUiState.Error(it.message ?: "加载失败") }
        }
    }

    fun refresh(projectId: String) {
        _isRefreshing.value = true
        viewModelScope.launch {
            try {
                discoverRepository.getProjectById(projectId)
                    .onSuccess { detail ->
                        _uiState.value = DetailUiState.Success(detail.diaries)
                    }
            } finally {
                _isRefreshing.value = false
            }
        }
    }
}

@HiltViewModel
class ProjectAllInstitutionsViewModel @Inject constructor(
    private val discoverRepository: DiscoverRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow<DetailUiState<List<Pair<InstitutionProject, Institution>>>>(DetailUiState.Loading)
    val uiState: StateFlow<DetailUiState<List<Pair<InstitutionProject, Institution>>>> = _uiState

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    fun load(projectId: String) {
        viewModelScope.launch {
            _uiState.value = DetailUiState.Loading
            discoverRepository.getProjectById(projectId)
                .onSuccess { detail ->
                    _uiState.value = DetailUiState.Success(detail.institutionProjects)
                }
                .onFailure { _uiState.value = DetailUiState.Error(it.message ?: "加载失败") }
        }
    }

    fun refresh(projectId: String) {
        _isRefreshing.value = true
        viewModelScope.launch {
            try {
                discoverRepository.getProjectById(projectId)
                    .onSuccess { detail ->
                        _uiState.value = DetailUiState.Success(detail.institutionProjects)
                    }
            } finally {
                _isRefreshing.value = false
            }
        }
    }
}

@HiltViewModel
class DoctorAllDiariesViewModel @Inject constructor(
    private val discoverRepository: DiscoverRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow<DetailUiState<List<Diary>>>(DetailUiState.Loading)
    val uiState: StateFlow<DetailUiState<List<Diary>>> = _uiState
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    fun load(doctorId: String) {
        viewModelScope.launch {
            _uiState.value = DetailUiState.Loading
            discoverRepository.getDoctorById(doctorId)
                .onSuccess { detail -> _uiState.value = DetailUiState.Success(detail.diaries) }
                .onFailure { _uiState.value = DetailUiState.Error(it.message ?: "加载失败") }
        }
    }

    fun refresh(doctorId: String) {
        _isRefreshing.value = true
        viewModelScope.launch {
            try {
                discoverRepository.getDoctorById(doctorId)
                    .onSuccess { detail -> _uiState.value = DetailUiState.Success(detail.diaries) }
            } finally { _isRefreshing.value = false }
        }
    }
}

@HiltViewModel
class DoctorAllProjectsViewModel @Inject constructor(
    private val discoverRepository: DiscoverRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow<DetailUiState<List<DoctorInstitutionProjectInfo>>>(DetailUiState.Loading)
    val uiState: StateFlow<DetailUiState<List<DoctorInstitutionProjectInfo>>> = _uiState
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    fun load(doctorId: String) {
        viewModelScope.launch {
            _uiState.value = DetailUiState.Loading
            discoverRepository.getDoctorById(doctorId)
                .onSuccess { detail -> _uiState.value = DetailUiState.Success(detail.institutionProjects) }
                .onFailure { _uiState.value = DetailUiState.Error(it.message ?: "加载失败") }
        }
    }

    fun refresh(doctorId: String) {
        _isRefreshing.value = true
        viewModelScope.launch {
            try {
                discoverRepository.getDoctorById(doctorId)
                    .onSuccess { detail -> _uiState.value = DetailUiState.Success(detail.institutionProjects) }
            } finally { _isRefreshing.value = false }
        }
    }
}

@HiltViewModel
class InstitutionAllDiariesViewModel @Inject constructor(
    private val discoverRepository: DiscoverRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow<DetailUiState<List<Diary>>>(DetailUiState.Loading)
    val uiState: StateFlow<DetailUiState<List<Diary>>> = _uiState
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    fun load(institutionId: String) {
        viewModelScope.launch {
            _uiState.value = DetailUiState.Loading
            discoverRepository.getInstitutionById(institutionId)
                .onSuccess { detail -> _uiState.value = DetailUiState.Success(detail.diaries) }
                .onFailure { _uiState.value = DetailUiState.Error(it.message ?: "加载失败") }
        }
    }

    fun refresh(institutionId: String) {
        _isRefreshing.value = true
        viewModelScope.launch {
            try {
                discoverRepository.getInstitutionById(institutionId)
                    .onSuccess { detail -> _uiState.value = DetailUiState.Success(detail.diaries) }
            } finally { _isRefreshing.value = false }
        }
    }
}

@HiltViewModel
class InstitutionAllProjectsViewModel @Inject constructor(
    private val discoverRepository: DiscoverRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow<DetailUiState<List<InstitutionProjectInfo>>>(DetailUiState.Loading)
    val uiState: StateFlow<DetailUiState<List<InstitutionProjectInfo>>> = _uiState
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    fun load(institutionId: String) {
        viewModelScope.launch {
            _uiState.value = DetailUiState.Loading
            discoverRepository.getInstitutionById(institutionId)
                .onSuccess { detail -> _uiState.value = DetailUiState.Success(detail.projects) }
                .onFailure { _uiState.value = DetailUiState.Error(it.message ?: "加载失败") }
        }
    }

    fun refresh(institutionId: String) {
        _isRefreshing.value = true
        viewModelScope.launch {
            try {
                discoverRepository.getInstitutionById(institutionId)
                    .onSuccess { detail -> _uiState.value = DetailUiState.Success(detail.projects) }
            } finally { _isRefreshing.value = false }
        }
    }
}

@HiltViewModel
class InstitutionAllDoctorsViewModel @Inject constructor(
    private val discoverRepository: DiscoverRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow<DetailUiState<List<Doctor>>>(DetailUiState.Loading)
    val uiState: StateFlow<DetailUiState<List<Doctor>>> = _uiState
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    fun load(institutionId: String) {
        viewModelScope.launch {
            _uiState.value = DetailUiState.Loading
            discoverRepository.getInstitutionById(institutionId)
                .onSuccess { detail -> _uiState.value = DetailUiState.Success(detail.doctors) }
                .onFailure { _uiState.value = DetailUiState.Error(it.message ?: "加载失败") }
        }
    }

    fun refresh(institutionId: String) {
        _isRefreshing.value = true
        viewModelScope.launch {
            try {
                discoverRepository.getInstitutionById(institutionId)
                    .onSuccess { detail -> _uiState.value = DetailUiState.Success(detail.doctors) }
            } finally { _isRefreshing.value = false }
        }
    }
}

@HiltViewModel
class InstitutionAllReviewsViewModel @Inject constructor(
    private val discoverRepository: DiscoverRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow<DetailUiState<List<Review>>>(DetailUiState.Loading)
    val uiState: StateFlow<DetailUiState<List<Review>>> = _uiState
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing = _isRefreshing.asStateFlow()

    fun load(institutionId: String) {
        viewModelScope.launch {
            _uiState.value = DetailUiState.Loading
            discoverRepository.getInstitutionById(institutionId)
                .onSuccess { detail -> _uiState.value = DetailUiState.Success(detail.reviews) }
                .onFailure { _uiState.value = DetailUiState.Error(it.message ?: "加载失败") }
        }
    }

    fun refresh(institutionId: String) {
        _isRefreshing.value = true
        viewModelScope.launch {
            try {
                discoverRepository.getInstitutionById(institutionId)
                    .onSuccess { detail -> _uiState.value = DetailUiState.Success(detail.reviews) }
            } finally { _isRefreshing.value = false }
        }
    }
}
