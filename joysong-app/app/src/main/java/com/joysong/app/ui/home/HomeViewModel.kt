package com.joysong.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.joysong.app.data.local.MessageUnreadManager
import com.joysong.app.data.local.TokenManager
import com.joysong.app.domain.model.Banner
import com.joysong.app.domain.model.Diary
import com.joysong.app.domain.model.Doctor
import com.joysong.app.domain.model.ExpertArticle
import com.joysong.app.domain.model.FavoriteType
import com.joysong.app.domain.model.Institution
import com.joysong.app.domain.model.Project
import com.joysong.app.domain.model.RecommendedInstitutionProject
import com.joysong.app.domain.repository.HomeRepository
import com.joysong.app.domain.repository.FavoriteRepository
import com.joysong.app.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val banners: List<Banner> = emptyList(),
    val hotProjects: List<Project> = emptyList(),
    val recommendedInstitutionProjects: List<RecommendedInstitutionProject> = emptyList(),
    val expertArticles: List<ExpertArticle> = emptyList(),
    val userDiaries: List<Diary> = emptyList(),
    val institutions: List<Institution> = emptyList(),
    val doctors: List<Doctor> = emptyList(),
    val nickname: String = "",
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val homeRepository: HomeRepository,
    private val favoriteRepository: FavoriteRepository,
    private val userRepository: UserRepository,
    private val tokenManager: TokenManager,
    private val messageUnreadManager: MessageUnreadManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState

    // 直接暴露 MessageUnreadManager 的 StateFlow，供 HomeScreen 直接 collect
    val hasUnreadMessages: StateFlow<Boolean> = messageUnreadManager.hasUnread
    val totalUnreadCount: StateFlow<Int> = messageUnreadManager.totalUnreadCount

    // 文章收藏状态：独立追踪每篇文章，避免串状态
    private val _articleFavoriteStates = MutableStateFlow<Map<String, Boolean>>(emptyMap())

    init {
        loadHomeData()
        loadUserProfile()
        // 监听 token 变化，账号切换后自动刷新用户信息
        viewModelScope.launch {
            tokenManager.tokenFlow
                .drop(1)  // 跳过初始值，避免与 loadUserProfile() 重复
                .collect {
                    loadUserProfile()
                }
        }
        // 全局未读轮询已由 MessageUnreadManager 在 Application 作用域管理（15秒间隔）
        // 此处无需重复轮询
    }

    private fun loadUserProfile() {
        viewModelScope.launch {
            userRepository.getUserProfile().onSuccess { user ->
                _uiState.value = _uiState.value.copy(nickname = user.nickname)
            }
        }
    }

    fun loadHomeData() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val banners = async { homeRepository.getBanners() }
                val projects = async { homeRepository.getHotProjects() }
                val recommendedProjects = async { homeRepository.getRecommendedInstitutionProjects() }
                val articles = async { homeRepository.getExpertArticles() }
                val diaries = async { homeRepository.getUserDiaries() }
                val institutions = async { homeRepository.getInstitutions() }
                val doctors = async { homeRepository.getDoctors() }

                val bannerList = banners.await().getOrElse { emptyList() }
                val projectList = projects.await().getOrElse { emptyList() }
                val recommendedList = recommendedProjects.await().getOrElse { emptyList() }
                val articleList = articles.await().getOrElse { emptyList() }
                val diaryList = diaries.await().getOrElse { emptyList() }
                val institutionList = institutions.await().getOrElse { emptyList() }
                val doctorList = doctors.await().getOrElse { emptyList() }

                _uiState.value = _uiState.value.copy(
                    banners = bannerList,
                    hotProjects = projectList,
                    recommendedInstitutionProjects = recommendedList,
                    expertArticles = articleList,
                    userDiaries = diaryList,
                    institutions = institutionList,
                    doctors = doctorList,
                    isLoading = false
                )

                // 异步加载每篇文章的收藏状态（通过 API）
                articleList.forEach { article ->
                    viewModelScope.launch {
                        favoriteRepository.isFavorite(FavoriteType.ARTICLE, article.id)
                            .onSuccess { status ->
                                _articleFavoriteStates.value =
                                    _articleFavoriteStates.value + (article.id to status.favorited)
                                applyArticleFavorites()
                            }
                    }
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message)
            }
        }
    }

    /**
     * 将 _articleFavoriteStates 中的收藏状态映射到 uiState.expertArticles
     */
    private fun applyArticleFavorites() {
        val favMap = _articleFavoriteStates.value
        _uiState.value = _uiState.value.copy(
            expertArticles = _uiState.value.expertArticles.map { article ->
                article.copy(isFavorited = favMap[article.id] ?: false)
            }
        )
    }

    fun toggleFavorite(articleId: String) {
        val currentMap = _articleFavoriteStates.value
        val wasFavorited = currentMap[articleId] ?: false
        // 乐观更新
        _articleFavoriteStates.value = currentMap + (articleId to !wasFavorited)
        applyArticleFavorites()

        viewModelScope.launch {
            val article = _uiState.value.expertArticles.find { it.id == articleId }
            val result = if (wasFavorited) {
                favoriteRepository.removeFavorite(FavoriteType.ARTICLE, articleId)
            } else {
                favoriteRepository.addFavorite(
                    FavoriteType.ARTICLE,
                    articleId,
                    article?.title ?: "",
                    article?.coverImage ?: ""
                )
            }
            result.onFailure {
                // 回滚
                _articleFavoriteStates.value = currentMap
                applyArticleFavorites()
            }
        }
    }
}
